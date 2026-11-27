package dev.telemetric.stash;

import dev.telemetric.config.ModPaths;
import dev.telemetric.Telemetric;
import dev.telemetric.game.StashAdapter;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Tracks the open Telos stash: which page and mode are showing. It saves each page as it's seen, and walks to a
 * requested page by clicking the stash's own arrows.
 * <p>
 * Telos doesn't show a page number anywhere (the title is a fixed glyph and the arrows are invisible items), so the
 * page is tracked from the arrow clicks. Both ours and the player's are caught in
 * {@code MultiPlayerGameMode#handleContainerInput}:
 * <ul>
 *   <li>slot 47 (yellow, far left) goes to page 1</li>
 *   <li>slot 48 / 50 go one page back / forward (Telos doesn't wrap)</li>
 *   <li>slot 51 (yellow, far right) goes to the last page (needs the page count)</li>
 *   <li>slot 53 switches between normal and seasonal pages (the page is unknown afterwards)</li>
 * </ul>
 * The page count is learned with a scan: first page, last page, then back one at a time until the content matches
 * page 1 again. After every click we wait for the server's container packets to settle before saving or clicking
 * again.
 */
public final class StashSession {

    public static final int SLOT_FIRST = 47, SLOT_PREV = 48, SLOT_CONVERT = 49, SLOT_NEXT = 50, SLOT_LAST = 51,
            SLOT_MODE = 53;

    /** After an item move, wait this long without container packets before reading the page again. */
    private static final long SETTLE_MS = 120;
    /**
     * After a page click, the server has to be quiet for this long after its last container packet before the page
     * counts as loaded. Measured on Telos, the whole answer arrives about 25-30 ms after the click.
     */
    private static final long PAGE_QUIET_MS = 50;
    /** Bounds of the Page Delay slider (ms between two page clicks). */
    public static final long MIN_PAGE_DELAY_MS = 50, MAX_PAGE_DELAY_MS = 500;
    /** Give up on a click that hasn't been answered after this long. */
    private static final long TIMEOUT_MS = 2500;
    private static final int MAX_SCAN_PAGES = 120;
    /** After a mode-switch click, a screen that isn't the stash showing up this soon is taken to be Telos' confirm pop-up. */
    private static final long POPUP_WINDOW_MS = 4000;
    private static final long POPUP_RETRY_MS = 400;
    private static final long NOTICE_MS = 5000;

    private static StashSession instance;

    private final StashStore store;

    private boolean open;
    private int containerId = -1;
    private boolean firstSettle;
    private StashMode mode;
    private StashMode lastMode = StashMode.NORMAL;
    /** The page shown right now (starting at 1), or 0 if unknown. */
    private int page;

    private boolean awaiting;
    private long awaitStart;
    private boolean dirty;
    private long lastPacket;
    private final Deque<Integer> queue = new ArrayDeque<>();

    private enum Scan { NONE, FIRST, LAST, BACK }
    private Scan scan = Scan.NONE;
    private StashPage scanFirst;
    private final List<StashPage> scanBack = new ArrayList<>();
    /** The page to walk to once a mode switch or scan finishes, or 0 for none. */
    private int pendingTarget;
    private StashMode pendingMode;
    private boolean justSwitched;
    /** When the last mode-switch click happened (kept while the stash is closed for the confirm pop-up). */
    private long modeSwitchAt = -POPUP_WINDOW_MS;
    private long lastPopupTry;
    /** The mode that was showing when slot 53 was clicked, and whether Telos' warning pop-up followed (both kept across the reopen). */
    private StashMode switchFrom;
    private boolean popupSeen;
    /** A short message for the overlay header (e.g. why a mode switch did nothing). */
    private String notice;
    private long noticeUntil;
    /** The last ← / → click (slot, page before it, content before it) for the page-count math. 0 for none. */
    private int arrowSlot, arrowFrom;
    private StashPage arrowBefore;
    /** Item click to send once the stash shows {@link PendingClick#page}. */
    private record PendingClick(int page, int slot, SlotAction action, int button) {}
    private PendingClick pendingClick;
    private boolean syncOnOpen = true;
    private boolean autoScan = true;
    private long pageDelay = 100;
    /** When the last page or mode click happened, and the first container packet after it (for pacing and the benchmark). */
    private long lastClickAt = Long.MIN_VALUE / 2;
    private long firstPacket;
    /** True while a mode switch is in progress (from the slot-53 click until the new stash has settled). */
    private boolean switching;
    private boolean popupAnswered;
    /** Benchmark: a scan at the fastest pace that times every page click. */
    private boolean benchmark;
    private long benchStart, benchAnswerSum, benchAnswerMax, benchLoadSum;
    private int benchTurns;

    private StashSession(Path file) {
        this.store = new StashStore(file);
    }

    public static StashSession get() {
        if (instance == null) instance = new StashSession(ModPaths.STASH);
        return instance;
    }


    public boolean isOpen() { return open; }

    public int page() { return page; }

    /** The mode shown in the stash, or the last one seen while it's still being read. */
    public StashMode mode() { return mode != null ? mode : lastMode; }

    public boolean isBusy() { return awaiting || !queue.isEmpty(); }

    public boolean isScanning() { return scan != Scan.NONE; }

    public boolean isBenchmarking() { return benchmark && isScanning(); }

    /** True from a mode-switch click until the new mode's stash has loaded (or the switch timed out). */
    public boolean isSwitching() { return switching && now() - modeSwitchAt < POPUP_WINDOW_MS; }

    public boolean isPopupAnswered() { return popupAnswered; }

    /** The player closed the "switching" screen, so stop waiting for the new stash. */
    public void cancelSwitch() { switching = false; }

    public StashBook book() { return book(mode()); }

    public StashBook book(StashMode m) { return store.book(StashAdapter.playerKey(), m); }


    /** Any container packet (contents or one slot) for {@code id}. */
    public void onContainerPacket(int id) {
        if (!open || id != containerId) return;
        lastPacket = now();
        if (awaiting && !dirty) firstPacket = lastPacket;
        dirty = true;
    }

    /** A slot click is about to be sent for container {@code id} (by the player or by us). */
    public void onSlotClick(int id, int slot) {
        if (!open || id != containerId) return;
        int count = book().pageCount();
        arrowSlot = 0;
        switch (slot) {
            case SLOT_FIRST -> page = 1;
            case SLOT_PREV, SLOT_NEXT -> {
                // remember the page before the arrow: if nothing changes, we're at an edge (see settleArrow)
                if (page > 0 && mode != null && scan == Scan.NONE) {
                    arrowSlot = slot;
                    arrowFrom = page;
                    arrowBefore = new StashPage(StashAdapter.readPage(), 0L);
                }
                if (page > 0) page = slot == SLOT_PREV ? Math.max(1, page - 1) : page + 1;
            }
            case SLOT_LAST -> page = count > 0 ? count : 0;
            case SLOT_MODE -> {
                switchFrom = mode;
                // show the target mode's saved pages right away, instead of the old ones while it loads
                if (mode != null) lastMode = mode.other();
                switching = true;
                popupAnswered = false;
                popupSeen = false;
                page = 0;
                mode = null;
                justSwitched = true;
                modeSwitchAt = now();
            }
            case SLOT_CONVERT -> {
                // Telos ignores the button on a page that isn't empty
                if (page < 1 || mode == null || !StashAdapter.isPageEmpty()) return;
                book(mode).removePage(page);
                book(mode.other()).appendEmptyPage(System.currentTimeMillis());
                store.markDirty();
                page = 1;    // Telos shows page 1 afterwards (of the other mode, if this one has no pages left)
                mode = null; // read it again once it has settled
            }
            default -> {
                // An item move. Telos sends nothing back when the client's prediction was right, so we read the page again once the
                // click has been applied, instead of waiting for packets.
                dirty = true;
                lastPacket = now();
                return;
            }
        }
        awaiting = true;
        awaitStart = now();
        lastClickAt = awaitStart;
        dirty = false;
    }


    /** Called every client tick while the module is on. */
    public void tick(boolean syncOnOpen, boolean autoScan, long pageDelayMs) {
        this.syncOnOpen = syncOnOpen;
        this.autoScan = autoScan;
        this.pageDelay = Math.max(MIN_PAGE_DELAY_MS, Math.min(MAX_PAGE_DELAY_MS, pageDelayMs));
        boolean stashOpen = StashAdapter.isStashOpen();
        if (!stashOpen) {
            if (open) close();
            long t = now();
            // Telos asks you to confirm the normal/seasonal switch in a pop-up, so answer it for the player. (Normally that
            // already happened the moment it opened, see answerPopup; this is the fallback.)
            if (!popupAnswered && t - modeSwitchAt < POPUP_WINDOW_MS && t - lastPopupTry >= POPUP_RETRY_MS) {
                lastPopupTry = t;
                answerPopup();
            }
            if (isSwitching()) StashAdapter.holdOverlay();
            return;
        }
        int id = StashAdapter.containerId();
        if (!open || id != containerId) {
            reset();
            open = true;
            containerId = id;
            firstSettle = true;
            dirty = true;
            lastPacket = now();
        }

        long t = now();
        if (awaiting) {
            if (dirty && t - lastPacket >= PAGE_QUIET_MS) {
                awaiting = false;
                if (benchmark) {
                    benchTurns++;
                    benchAnswerSum += firstPacket - awaitStart;
                    benchAnswerMax = Math.max(benchAnswerMax, lastPacket - awaitStart);
                    benchLoadSum += t - awaitStart;
                }
                settle();
            } else if (t - awaitStart >= TIMEOUT_MS) {
                Telemetric.LOGGER.warn("Stash: no answer to a page click, page tracking lost");
                awaiting = false;
                page = 0;
                queue.clear();
                scan = Scan.NONE;
                benchmark = false;
                pendingTarget = 0;
            }
            return;
        }
        if (dirty && t - lastPacket >= SETTLE_MS) {
            settle();
        }
        long delay = benchmark ? MIN_PAGE_DELAY_MS : pageDelay;
        if (!awaiting && !queue.isEmpty() && t - lastClickAt >= delay) {
            StashAdapter.click(queue.poll()); // onSlotClick sets awaiting
        }
    }

    /**
     * Presses Ok on Telos' mode-switch pop-up, then keeps the overlay up until the stash reopens. It's called the moment
     * a non-container screen opens during a switch (before it's drawn), and from tick as a fallback.
     */
    public void answerPopup() {
        if (popupAnswered || now() - modeSwitchAt >= POPUP_WINDOW_MS) return;
        if (StashAdapter.confirmPopup()) {
            popupAnswered = true;
            popupSeen = true;
            if (isSwitching()) StashAdapter.holdOverlay();
        }
    }

    private void settle() {
        dirty = false;
        StashMode read = StashAdapter.readMode();
        if (read != null) {
            mode = read;
            lastMode = read;
        }
        StashPage live = new StashPage(StashAdapter.readPage(), System.currentTimeMillis());

        if (scan != Scan.NONE) {
            arrowSlot = 0;
            scanStep(live);
            return;
        }
        // Telos only shows a mode that has pages
        if (mode != null && book().isKnownEmpty()) book().markNotEmpty();
        settleArrow(live);
        if (page > 0 && mode != null) {
            StashPage old = book().page(page);
            if (!live.sameContent(old)) {
                book().put(page, live);
                store.markDirty();
            }
        }

        if (firstSettle) {
            firstSettle = false;
            // when the other mode has no pages, Telos shows its warning and then reopens the stash unchanged
            if (switchFrom != null && popupSeen && mode == switchFrom) {
                StashBook other = book(switchFrom.other());
                noticeUntil = now() + NOTICE_MS;
                if (other.pageCount() == 0 && other.pages().isEmpty()) {
                    notice = "No " + switchFrom.other().label() + " pages yet";
                    other.markEmpty();
                    store.markDirty();
                } else {
                    // the saved pages say otherwise, so keep them; the switch just didn't go through
                    notice = "Couldn't switch to " + switchFrom.other().label() + " — try again";
                }
            }
            switchFrom = null;
            popupSeen = false;
            switching = false;
            afterModeKnown();
            return;
        }
        if (pendingMode != null && mode != null) {
            StashMode want = pendingMode;
            pendingMode = null;
            if (want != mode) {
                queue.add(SLOT_MODE);
                return;
            }
        }
        if (justSwitched && mode != null) {
            justSwitched = false;
            switching = false;
            afterModeKnown();
            return;
        }
        if (pendingTarget > 0 && queue.isEmpty()) {
            int target = pendingTarget;
            pendingTarget = 0;
            planRoute(target);
        }
        if (pendingClick != null && queue.isEmpty() && pendingTarget == 0) {
            PendingClick c = pendingClick;
            pendingClick = null;
            if (page == c.page() && mode != null) StashAdapter.slotInput(c.slot(), c.action(), c.button());
        }
    }

    /**
     * Page-count math from the last ← / → click, so a scan is rarely needed. Telos doesn't wrap, so an arrow that
     * leaves a non-empty page unchanged means we're on the first or last page. A → from the known last page that shows
     * new content means pages were added.
     */
    private void settleArrow(StashPage live) {
        if (arrowSlot == 0 || mode == null) return;
        int slot = arrowSlot;
        arrowSlot = 0;
        StashBook b = book();
        boolean same = live.sameContent(arrowBefore);
        boolean unchanged = same && !arrowBefore.isEmpty(); // two empty pages in a row look the same
        int count = b.pageCount();
        if (slot == SLOT_NEXT) {
            if (unchanged) {
                page = arrowFrom;
                if (count != arrowFrom) {
                    b.setPageCount(arrowFrom);
                    store.markDirty();
                }
            } else if (count > 0 && page > count) {
                if (same) {
                    page = count; // empty last page, so assume → did nothing
                } else {
                    b.setPageCount(page);
                    store.markDirty();
                }
            }
        } else if (unchanged) {
            page = 1;
        }
    }

    /** The stash just opened or switched mode: learn the page count, or at least find page 1. */
    private void afterModeKnown() {
        if (mode == null) return;
        if (autoScan && book().pageCount() == 0 && !book().isKnownEmpty()) startScan();
        else if (syncOnOpen && page == 0) queue.add(SLOT_FIRST);
    }

    /** Writes unsaved pages to disk. */
    public void flush() {
        store.saveIfDirty();
    }

    private void close() {
        store.saveIfDirty();
        reset();
    }

    private void reset() {
        open = false;
        containerId = -1;
        mode = null;
        page = 0;
        awaiting = false;
        dirty = false;
        queue.clear();
        scan = Scan.NONE;
        benchmark = false;
        scanBack.clear();
        scanFirst = null;
        pendingTarget = 0;
        pendingMode = null;
        justSwitched = false;
        pendingClick = null;
    }


    /** Walks to {@code target} (starting at 1) with the fewest arrow clicks. */
    public void goTo(int target) {
        if (!open || target < 1 || isScanning()) return;
        pendingClick = null;
        queue.clear();
        pendingTarget = 0;
        if (awaiting) {
            pendingTarget = target;
            return;
        }
        planRoute(target);
    }

    /** One page back / forward from the current page. */
    public void step(int delta) {
        if (page > 0) goTo(Math.max(1, page + delta));
    }

    /**
     * An item click on stash slot {@code slot} (0–44) of {@code target}. It's sent right away if that page is showing,
     * otherwise after walking there.
     */
    public void clickPageSlot(int target, int slot, SlotAction action, int button) {
        if (!open || isScanning() || mode == null) return;
        if (target == page && !isBusy()) {
            StashAdapter.slotInput(slot, action, button);
            return;
        }
        goTo(target);
        pendingClick = new PendingClick(target, slot, action, button);
    }

    /** Renames a page of the shown mode (blank goes back to "Page N") and saves right away. */
    public void renamePage(int index, String name) {
        book().setName(index, name);
        store.markDirty();
        store.saveIfDirty();
    }

    /** Switches the stash to {@code m} pages (clicks slot 53 if needed). */
    public void switchMode(StashMode m) {
        if (!open || isScanning() || m == mode()) return;
        // always ask Telos, even for a mode marked empty, so a stale "empty" can never lock the player out
        queue.clear();
        pendingTarget = 0;
        if (mode == null || awaiting) pendingMode = m; // decided once the current page has settled
        else queue.add(SLOT_MODE);
    }

    /** True if the open page is empty and settled, so Telos' convert button (slot 49) would work. */
    public boolean canConvert() {
        return open && mode != null && page > 0 && !isBusy() && !isScanning() && StashAdapter.isPageEmpty();
    }

    /** Moves the open (empty) page to the other mode through slot 49. */
    public void convertPage() {
        if (canConvert()) queue.add(SLOT_CONVERT);
    }

    /** The header message to show right now, or {@code null}. */
    public String notice() {
        return notice != null && now() < noticeUntil ? notice : null;
    }

    /** Visits every page once, saves it, and learns the page count. */
    public void startScan() {
        startScan(false);
    }

    /**
     * A scan that also times every page click at the fastest pace (ignoring Page Delay) and shows the result in the
     * header and the log.
     */
    public void startBenchmark() {
        startScan(true);
    }

    private void startScan(boolean bench) {
        if (!open || mode == null || isScanning()) return;
        benchmark = bench;
        benchStart = now();
        benchTurns = 0;
        benchAnswerSum = benchAnswerMax = benchLoadSum = 0;
        queue.clear();
        pendingTarget = 0;
        scanBack.clear();
        scanFirst = null;
        scan = Scan.FIRST;
        queue.add(SLOT_FIRST);
    }

    private void planRoute(int target) {
        int count = book().pageCount();
        if (count > 0) target = Math.min(target, count);
        if (target == page) return;

        // Clicks needed: from here with ±1 steps, or one jump to page 1 / the last page (if the count is known) and steps.
        int fromHere = page > 0 ? Math.abs(target - page) : Integer.MAX_VALUE;
        int fromFirst = target;
        int fromLast = count > 0 ? count - target + 1 : Integer.MAX_VALUE;
        int from = page;
        if (fromLast < Math.min(fromHere, fromFirst)) {
            queue.add(SLOT_LAST);
            from = count;
        } else if (fromFirst < fromHere) {
            queue.add(SLOT_FIRST);
            from = 1;
        }
        for (int p = from; p < target; p++) queue.add(SLOT_NEXT);
        for (int p = from; p > target; p--) queue.add(SLOT_PREV);
    }

    private void scanStep(StashPage live) {
        switch (scan) {
            case FIRST -> {
                scanFirst = live;
                book().put(1, live);
                store.markDirty();
                scan = Scan.LAST;
                queue.add(SLOT_LAST);
            }
            case LAST, BACK -> {
                scanBack.add(live);
                if (live.sameContent(scanFirst)) {
                    finishScan(scanBack.size());
                } else if (scanBack.size() >= MAX_SCAN_PAGES) {
                    Telemetric.LOGGER.warn("Stash scan: stopped after {} pages", MAX_SCAN_PAGES);
                    scan = Scan.NONE;
                    benchmark = false;
                    page = 0;
                } else {
                    scan = Scan.BACK;
                    queue.add(SLOT_PREV);
                }
            }
            default -> { }
        }
    }

    /** scanBack holds the pages count, count-1, ..., 1. */
    private void finishScan(int count) {
        StashBook book = book();
        book.setPageCount(count);
        for (int i = 0; i < scanBack.size(); i++) book.put(count - i, scanBack.get(i));
        store.markDirty();
        store.saveIfDirty();
        scan = Scan.NONE;
        scanBack.clear();
        scanFirst = null;
        page = 1;
        if (benchmark) reportBenchmark(count);
        if (pendingTarget > 0) {
            int target = pendingTarget;
            pendingTarget = 0;
            planRoute(target);
        }
    }

    private void reportBenchmark(int pages) {
        benchmark = false;
        if (benchTurns == 0) return;
        long total = now() - benchStart;
        long perPage = total / benchTurns;
        long answer = benchAnswerSum / benchTurns;
        long load = benchLoadSum / benchTurns;
        notice = "Benchmark: " + perPage + " ms/page (answer " + answer + " ms)";
        noticeUntil = now() + NOTICE_MS * 2;
        Telemetric.LOGGER.info("Stash benchmark: {} pages, {} clicks in {} ms = {} ms/page; Telos answers in {} ms avg, "
                        + "last packet {} ms max; page loaded {} ms after its click (quiet {} ms, min Page Delay {} ms)",
                pages, benchTurns, total, perPage, answer, benchAnswerMax, load, PAGE_QUIET_MS, MIN_PAGE_DELAY_MS);
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }
}
