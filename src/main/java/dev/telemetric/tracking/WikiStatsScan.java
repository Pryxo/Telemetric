package dev.telemetric.tracking;

import dev.telemetric.Telemetric;
import dev.telemetric.data.TelosData;
import dev.telemetric.data.TelosData.Bag;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.ServerGate;
import dev.telemetric.game.WikiMenuAdapter;
import dev.telemetric.game.WikiMenuAdapter.Page;
import dev.telemetric.game.WikiMenuAdapter.Slot;
import dev.telemetric.ui.notification.Toasts;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lifetime Stats → <i>Update from Wiki</i>: walks through Telos' {@code /wiki} and copies the server's own all-time
 * numbers into the {@link TrackingStore} (the account values, the "acc" half of "(char/acc)").
 * <ol>
 *   <li>{@code /wiki} → slot 21 (Dungeons &amp; Bosses). Its slot 49 ("All Statistics") lists the bags you got, e.g.
 *       {@code <glyph> Royal (char/acc): 4 / 275} gives {@code bag:royal} = 275.</li>
 *   <li>Each category on the left page (slots 9-12, 18-21, 27-30, 36-39: Lowland Dungeons, Realm Bosses…), then each
 *       dungeon or boss on its left page, then its slot 49 ({@code Completions (char/acc): 0 / 45}). The right page is
 *       ignored, and back is slot 45. All the completions added up give Runs.</li>
 * </ol>
 * Pages are told apart by their slot 49 (the title glyph is the same everywhere), so it works whether "back" returns
 * to the category or to the Dungeons &amp; Bosses page. Opening any other screen or pressing Esc stops it.
 */
public final class WikiStatsScan {

    private static final WikiStatsScan INSTANCE = new WikiStatsScan();

    private static final int DUNGEONS_SLOT = 21, INFO_SLOT = 49, BACK_SLOT = 45;
    private static final int[] LEFT_PAGE = {9, 10, 11, 12, 18, 19, 20, 21, 27, 28, 29, 30, 36, 37, 38, 39};
    private static final String MAIN_INFO = "Wiki Information", ROOT_INFO = "All Statistics";
    private static final long OPEN_DELAY_MS = 250, SETTLE_MS = 150, CLICK_GAP_MS = 200, PAGE_TIMEOUT_MS = 6_000,
            CLOSED_GRACE_MS = 1_000;
    /** {@code <glyph> Royal (char/acc): 4 / 275} (the glyph is optional). */
    private static final Pattern COUNT = Pattern.compile(
            "([A-Za-z][A-Za-z' ]*?)\\s*\\(char/acc\\)\\s*:\\s*([\\d,]+)\\s*/\\s*([\\d,]+)");

    private enum Step { IDLE, SEND, WAIT_PAGE }

    private Step step = Step.IDLE;
    private long stepAt, pageSeenAt, lastClickAt, closedSince;
    private int clickedContainer = -1;

    private final Set<Integer> categoriesDone = new HashSet<>();
    private final Set<Integer> dungeonsDone = new HashSet<>();
    private int category = -1, dungeon = -1;
    private String categoryName = "";
    private final Map<String, Integer> totals = new LinkedHashMap<>();
    private final Map<String, Integer> completions = new LinkedHashMap<>();

    private WikiStatsScan() {}

    public static WikiStatsScan get() {
        return INSTANCE;
    }

    public boolean running() {
        return step != Step.IDLE;
    }

    /** Starts a scan (closing the ClickGUI first). Does nothing off Telos or while a scan is running. */
    public void start() {
        if (running()) {
            Toasts.info("Wiki update already running");
            return;
        }
        if (!ServerGate.onTelos()) {
            Toasts.error("Join Telos to update from the wiki");
            return;
        }
        categoriesDone.clear();
        dungeonsDone.clear();
        totals.clear();
        completions.clear();
        category = dungeon = clickedContainer = -1;
        categoryName = "";
        WikiMenuAdapter.closeScreen();
        step = Step.SEND;
        stepAt = System.currentTimeMillis() + OPEN_DELAY_MS;
        Toasts.info("Reading /wiki…");
    }

    public void tick() {
        if (step == Step.IDLE) return;
        long now = System.currentTimeMillis();
        if (!ServerGate.onTelos()) {
            stop("Wiki update stopped (left Telos)", false);
            return;
        }
        switch (step) {
            case SEND -> {
                if (now < stepAt) return;
                if (WikiMenuAdapter.screenOpen()) WikiMenuAdapter.closeScreen();
                PlayerAdapter.sendCommand("wiki");
                awaitPage(now, -1);
            }
            case WAIT_PAGE -> waitPage(now);
            default -> {}
        }
    }

    private void awaitPage(long now, int fromContainer) {
        clickedContainer = fromContainer;
        pageSeenAt = 0;
        closedSince = 0;
        stepAt = now + PAGE_TIMEOUT_MS;
        step = Step.WAIT_PAGE;
    }

    private void waitPage(long now) {
        Page page = WikiMenuAdapter.current();
        if (page == null) {
            if (WikiMenuAdapter.screenOpen()) stop("Wiki update stopped (another screen opened)", false);
            else if (clickedContainer == -1) {
                if (now > stepAt) stop("Wiki update failed: /wiki did not open", false);
            } else {
                if (closedSince == 0) closedSince = now; // a page switch may close the menu before it opens the next one
                if (now - closedSince > CLOSED_GRACE_MS) stop("Wiki update stopped (menu closed)", false);
            }
            return;
        }
        closedSince = 0;
        Slot info = page.at(INFO_SLOT);
        if (page.containerId() == clickedContainer || info == null) {
            if (now > stepAt) stop("Wiki update failed: the wiki did not answer", false);
            return;
        }
        if (pageSeenAt == 0) pageSeenAt = now;
        if (now - pageSeenAt < SETTLE_MS || now - lastClickAt < CLICK_GAP_MS) return; // give the items time to arrive
        try {
            act(page, info, now);
        } catch (RuntimeException e) {
            Telemetric.LOGGER.error("Wiki update failed", e);
            stop("Wiki update failed", false);
        }
    }

    /** Works out from slot 49 which wiki page this is, and clicks on. */
    private void act(Page page, Slot info, long now) {
        if (info.name().equals(MAIN_INFO)) {
            click(page, DUNGEONS_SLOT, now);
        } else if (info.name().equals(ROOT_INFO)) {
            if (totals.isEmpty()) for (String line : info.lore()) parse(line, totals);
            int next = nextSlot(page, categoriesDone);
            if (next < 0) {
                finish();
                return;
            }
            if (next != category) {
                category = next;
                dungeonsDone.clear();
            }
            categoryName = page.at(next).name();
            click(page, next, now);
        } else if (category >= 0 && info.name().equals(categoryName)) {
            int next = nextSlot(page, dungeonsDone);
            if (next < 0) {
                categoriesDone.add(category);
                click(page, BACK_SLOT, now);
            } else {
                dungeon = next;
                click(page, next, now);
            }
        } else if (dungeon >= 0 && isDungeonInfo(info)) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String line : info.lore()) parse(line, counts);
            completions.put(info.name(), counts.getOrDefault("completions", counts.getOrDefault("kills", 0)));
            dungeonsDone.add(dungeon);
            dungeon = -1;
            click(page, BACK_SLOT, now);
        } else {
            stop("Wiki update failed: unknown page '" + info.name() + "'", false);
        }
    }

    private static boolean isDungeonInfo(Slot info) {
        for (String line : info.lore()) if (line.contains("(char/acc)")) return true;
        return false;
    }

    /** The first left-page slot with an item that isn't done yet, or -1. */
    private static int nextSlot(Page page, Set<Integer> done) {
        for (int s : LEFT_PAGE) if (page.at(s) != null && !done.contains(s)) return s;
        return -1;
    }

    private void click(Page page, int slot, long now) {
        WikiMenuAdapter.click(slot);
        lastClickAt = now;
        awaitPage(now, page.containerId());
    }

    /** Turns {@code <glyph> Royal (char/acc): 4 / 275} into {@code royal = 275} (the account value). */
    private static void parse(String line, Map<String, Integer> into) {
        Matcher m = COUNT.matcher(line);
        if (!m.find()) return;
        into.put(m.group(1).strip().toLowerCase(Locale.ROOT), Integer.parseInt(m.group(3).replace(",", "")));
    }

    private void finish() {
        TrackingStore store = LootTracker.get().store();
        TelosData.Bags bags = TelosData.BAGS.get();
        int bagRows = 0;
        for (Map.Entry<String, Integer> e : totals.entrySet()) {
            Bag bag = bags.byLabel(e.getKey());
            if (bag == null || !bag.group().equals(bag.key())) continue; // shared groups (Event) aren't on the wiki
            store.setStat(LootTracker.BAG_PREFIX + bag.group(), e.getValue());
            bagRows++;
        }
        int runs = 0;
        for (int n : completions.values()) runs += n;
        if (!completions.isEmpty()) store.setStat(LootTracker.STAT_RUNS, runs);
        stop("Lifetime Stats updated: " + bagRows + " bags, " + completions.size() + " dungeons / bosses", true);
    }

    private void stop(String message, boolean success) {
        step = Step.IDLE;
        category = dungeon = clickedContainer = -1;
        if (WikiMenuAdapter.current() != null) WikiMenuAdapter.closeScreen();
        if (success) Toasts.success(message);
        else Toasts.error(message);
    }
}
