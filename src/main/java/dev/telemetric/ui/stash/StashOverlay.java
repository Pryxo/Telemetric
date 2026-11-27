package dev.telemetric.ui.stash;

import dev.telemetric.Telemetric;
import dev.telemetric.game.GameItem;
import dev.telemetric.game.StashAdapter;
import dev.telemetric.module.impl.visual.StashOverlayModule;
import dev.telemetric.stash.SlotAction;
import dev.telemetric.stash.StashBook;
import dev.telemetric.stash.StashMode;
import dev.telemetric.stash.StashPage;
import dev.telemetric.stash.StashSession;
import dev.telemetric.ui.framework.Hit;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

/**
 * The stash overlay. It replaces the Telos stash screen with a header (title, page status, Scan, Normal |
 * Seasonal toggle), a grid of page cards (each one a 9x5 stash page with real item icons, the open page outlined in
 * the accent colour) and the player's inventory centred below.
 * <ul>
 *   <li>Items work like in a normal container: left/right click, shift-click, double-click, hotbar keys 1–9, F
 *       (offhand) and Q (drop) on the hovered slot. Clicking an item on another page walks the stash there first and
 *       then sends the click ({@link StashSession#clickPageSlot}).</li>
 *   <li>Click a page name or an empty spot on a card to go there. Right-click the name to rename it (Enter saves, Esc
 *       cancels, an empty name goes back to "Page N"). ←/→ go to the previous/next page.</li>
 *   <li>The open page shows the live container, and the other pages show their saved copy.</li>
 * </ul>
 * It's laid out in natural units, then scaled down to fit the screen ({@link Render2D#pushTransform}). Mouse handling
 * reuses the last frame's layout.
 */
public final class StashOverlay {

    private static final int PAD = 4;
    private static final int GAP = 4;
    private static final int SLOT = 18;          // a 16px item plus a 2px gap
    private static final int GRID_W = 9 * SLOT;
    private static final int GRID_H = 5 * SLOT;
    private static final int LABEL_H = 11;
    private static final int CARD_W = GRID_W + 4;
    private static final int CARD_H = LABEL_H + GRID_H + 3;
    private static final int HEADER_H = 16;
    private static final int SEARCH_H = 12;
    /** The top of the first card row, below the header and the search box row. */
    private static final int CARDS_TOP = PAD + HEADER_H + SEARCH_H + GAP;
    private static final int MAX_SEARCH = 64;
    private static final int SCROLLBAR_W = 4;
    private static final int MARGIN = 6;
    private static final int INV_W = PAD * 2 + GRID_W;
    private static final int HOTBAR_GAP = 4;
    private static final int INV_H = PAD + LABEL_H + 3 * SLOT + HOTBAR_GAP + SLOT + PAD;
    private static final int MAX_NAME = 32;
    /** The first menu slot of the player's inventory in the 6-row stash menu (54–80 inventory, 81–89 hotbar). */
    private static final int INV_SLOT0 = 54;
    private static final long DOUBLE_CLICK_MS = 250;

    // last frame's layout (screen units)
    private static float ox, oy, scale = 1f;
    private static int width, height, totalH, cols, visRows, totalRows, pageCount, invX, invY;
    private static boolean laidOut;
    /** The mouse position in natural units on the last frame (keys don't carry a position). */
    private static int lastNx = Integer.MIN_VALUE, lastNy = Integer.MIN_VALUE;
    private static int scrollRow;
    private static int followedPage;
    // header hit boxes (natural units)
    private static int scanX, normalX, seasonalX, pillW, pillY, pillH, scanW, convertX, convertW;
    private static boolean convertShown;
    private static String convertLabel = "";

    // rename
    public enum RenameKey { CONFIRM, CANCEL, BACKSPACE }
    private static int renamePage;
    private static StashMode renameMode;
    private static String renameText = "";

    // search (see StashSearch for the # & ^ $ filters)
    public enum SearchKey { CONFIRM, CANCEL, BACKSPACE, CLEAR }
    private static String searchText = "";
    private static boolean searchFocused;
    private static int searchX, searchY, searchW;
    private static int legendX, legendW;
    /** Search legend rows: prefix, what it filters, examples. */
    private static final String[][] LEGEND = {
            {"", "name", "calamity  sage"},
            {"#", "tooltip", "#tier points  #ability"},
            {"&", "tag / id", "&sceptre  &stick"},
            {"^", "rarity", "^bloodshot  ^tier 8  ^12"},
            {"$", "type", "$mount  $pet  $flying"},
    };
    private static final int LEGEND_LABEL_W = 44;

    // double-click
    private static long lastClickTime;
    private static int lastClickPage, lastClickSlot = -1;

    // shift + hold drag quick-moves every slot the mouse passes over (keys are page * 100 + slot, page 0 is the inventory)
    private static boolean shiftDrag;
    private static int shiftDragButton;
    private static int dragNx, dragNy;
    private static final java.util.Set<Integer> dragVisited = new java.util.HashSet<>();

    /** What the mouse points at: a stash slot on a page, an inventory slot, a card label, or a card body. */
    private record Target(Kind kind, int page, int slot) {}
    private enum Kind { STASH_SLOT, INV_SLOT, LABEL, CARD }

    private StashOverlay() {}

    private static StashOverlayModule module() {
        return Telemetric.get().modules().get(StashOverlayModule.class);
    }

    public static void render(Render2D r, int mouseX, int mouseY, int screenW, int screenH) {
        StashOverlayModule m = module();
        StashSession session = StashSession.get();
        StashBook book = session.book();
        Theme p = Telemetric.get().theme().palette();
        int radius = Telemetric.get().theme().cornerRadius();
        if (renamePage > 0 && renameMode != session.mode()) cancelRename();

        cols = Math.max(1, m.columns());
        pageCount = Math.max(book.displayCount(), session.page());
        totalRows = Math.max(1, (pageCount + cols - 1) / cols);
        visRows = Math.max(1, Math.min(m.rows(), totalRows));
        scrollRow = clamp(scrollRow, 0, totalRows - visRows);
        followCurrentPage(session.page());

        boolean scroll = totalRows > visRows;
        width = Math.max(INV_W, PAD * 2 + cols * CARD_W + (cols - 1) * GAP + (scroll ? SCROLLBAR_W + GAP : 0));
        height = CARDS_TOP + visRows * CARD_H + (visRows - 1) * GAP + PAD;
        totalH = height + GAP * 2 + INV_H;
        invX = (width - INV_W) / 2;
        invY = height + GAP * 2;
        // the search legend sits left of the inventory, and if it sticks out past the main panel, both sides widen equally
        legendW = legendWidth(r);
        legendX = invX - GAP - legendW;
        int extra = Math.max(0, -legendX);
        int layoutW = width + 2 * extra;

        scale = Math.min(m.maxScale(), Math.min((float) (screenW - 2 * MARGIN) / layoutW,
                (float) (screenH - 2 * MARGIN) / totalH));
        scale = Render2D.pixelScale(Math.max(0.2f, scale)); // whole window pixels per unit, so items look exactly like vanilla
        ox = Render2D.snapToPixel((screenW - width * scale) / 2f);
        oy = Render2D.snapToPixel(Math.max(MARGIN, (screenH - totalH * scale) / 2f));
        laidOut = true;

        int nx = (int) Math.floor((mouseX - ox) / scale);
        int ny = (int) Math.floor((mouseY - oy) / scale);
        lastNx = nx;
        lastNy = ny;
        Target hover = target(nx, ny);
        GameItem carried = StashAdapter.carried();
        GameItem hovered = null;

        // our own backdrop instead of the hidden stash texture
        r.gradientV(0, 0, screenW, screenH, p.backdrop(), ColorUtil.shade(p.backdrop(), -0.4));
        r.nextLayer();
        r.pushTransform(ox, oy, scale);
        try {
            boolean shadows = Telemetric.get().theme().shadows();
            if (shadows) r.shadow(0, 0, width, height, radius, p.shadow(), 6);
            r.roundedRect(0, 0, width, height, radius, p.panel());
            drawHeader(r, p, session, book, radius, nx, ny);
            drawSearch(r, p, radius, nx, ny);

            if (pageCount == 0) {
                int cy = CARDS_TOP + (height - PAD - CARDS_TOP) / 2 - r.fontHeight();
                r.textCentered("No pages saved yet", width / 2, cy, p.textMuted());
                r.textCentered("Flip through the stash or press Scan", width / 2, cy + r.fontHeight() + 2, p.textDisabled());
            } else {
                hovered = drawCards(r, p, session, book, radius, hover);
            }
            if (scroll) drawScrollbar(r, p);

            if (shadows) r.shadow(invX, invY, INV_W, INV_H, radius, p.shadow(), 6);
            r.roundedRect(invX, invY, INV_W, INV_H, radius, p.panel());
            if (shadows) r.shadow(legendX, invY, legendW, INV_H, radius, p.shadow(), 6);
            r.roundedRect(legendX, invY, legendW, INV_H, radius, p.panel());
            drawLegend(r, p);
            GameItem invHovered = drawInventory(r, p, hover);
            if (invHovered != null) hovered = invHovered;
        } finally {
            r.popTransform();
        }
        if (carried != null) {
            r.nextLayer();
            r.item(carried, mouseX - 8, mouseY - 8);
        } else if (hovered != null && m.tooltips()) {
            r.itemTooltip(hovered, mouseX, mouseY);
            r.flushTooltip(); // afterExtract runs after the screen's deferred tooltip pass
        }
    }

    private static void drawHeader(Render2D r, Theme p, StashSession session, StashBook book, int radius, int nx, int ny) {
        int textY = PAD + (HEADER_H - r.fontHeight()) / 2;
        r.text("Stash", PAD + 2, textY, p.text());

        String status;
        if (session.isSwitching()) status = "Switching to " + session.mode().label() + "…";
        else if (session.isBenchmarking()) status = "Benchmarking…";
        else if (session.isScanning()) status = "Scanning…";
        else if (session.page() > 0) status = "Page " + session.page() + "/" + (book.pageCount() > 0 ? book.pageCount() : "?");
        else status = "Page ?";
        if (session.isBusy() && !session.isScanning() && !session.isSwitching()) status += " …";
        String notice = session.notice();
        r.text(notice != null ? notice : status, PAD + 2 + r.textWidth("Stash") + 6, textY,
                notice != null ? p.accent() : p.textMuted());

        pillH = 12;
        pillY = PAD + (HEADER_H - pillH) / 2 - 1;
        String normalLabel = modeLabel(session, StashMode.NORMAL);
        String seasonalLabel = modeLabel(session, StashMode.SEASONAL);
        pillW = Math.max(r.textWidth(normalLabel), r.textWidth(seasonalLabel)) + 8;
        seasonalX = width - PAD - pillW;
        normalX = seasonalX - pillW;
        scanW = r.textWidth("Scan") + 8;
        scanX = normalX - GAP - scanW;

        StashMode mode = session.mode();
        // mode toggle: one track, with the active half filled in the accent colour
        r.roundedRect(normalX, pillY, pillW * 2, pillH, Math.min(radius, pillH / 2), p.control());
        int activeX = mode == StashMode.NORMAL ? normalX : seasonalX;
        r.roundedRect(activeX, pillY, pillW, pillH, Math.min(radius, pillH / 2), p.accent());
        pill(r, p, normalLabel, normalX, mode == StashMode.NORMAL, Hit.inside(nx, ny, normalX, pillY, pillW, pillH));
        pill(r, p, seasonalLabel, seasonalX, mode == StashMode.SEASONAL, Hit.inside(nx, ny, seasonalX, pillY, pillW, pillH));

        boolean scanHover = Hit.inside(nx, ny, scanX, pillY, scanW, pillH);
        r.roundedRect(scanX, pillY, scanW, pillH, Math.min(radius, pillH / 2),
                session.isScanning() ? p.accentAlt() : scanHover ? p.surfaceHover() : p.surface());
        r.textCentered("Scan", scanX + scanW / 2, pillY + (pillH - r.fontHeight()) / 2 + 1, p.text());

        // Telos' convert button: only an empty page can move to the other mode
        convertLabel = "→ " + mode.other().label();
        convertW = r.textWidth(convertLabel) + 8;
        convertX = scanX - GAP - convertW;
        convertShown = session.canConvert();
        if (convertShown) {
            boolean convertHover = Hit.inside(nx, ny, convertX, pillY, convertW, pillH);
            r.roundedRect(convertX, pillY, convertW, pillH, Math.min(radius, pillH / 2),
                    convertHover ? p.surfaceHover() : p.surface());
            r.textCentered(convertLabel, convertX + convertW / 2, pillY + (pillH - r.fontHeight()) / 2 + 1,
                    convertHover ? p.text() : p.textMuted());
        }
    }

    /** The search box row under the header. A filter prefix (# & ^ $) at the start is drawn in the accent colour. */
    private static void drawSearch(Render2D r, Theme p, int radius, int nx, int ny) {
        searchX = PAD;
        searchY = PAD + HEADER_H;
        searchW = width - PAD * 2;
        boolean hover = Hit.inside(nx, ny, searchX, searchY, searchW, SEARCH_H);
        r.roundedRect(searchX, searchY, searchW, SEARCH_H, Math.min(radius, SEARCH_H / 2),
                searchFocused || hover ? p.surfaceHover() : p.control());
        if (searchFocused) r.outline(searchX, searchY, searchW, SEARCH_H, p.accent());

        int ty = searchY + (SEARCH_H - r.fontHeight()) / 2 + 1;
        int tx = searchX + 4;
        int maxW = searchW - 8;
        if (searchText.isEmpty()) {
            if (!searchFocused) {
                r.text(r.trim("Search…   #tooltip  &tag  ^rarity  $type", maxW), tx, ty, p.textDisabled());
                return;
            }
        } else if (!searchFocused && StashSearch.active(searchText)) {
            String clear = "×";
            r.textRight(clear, searchX + searchW - 4, ty, p.textMuted());
            maxW -= r.textWidth(clear) + 4;
        }
        String shown = searchText;
        while (shown.length() > 1 && r.textWidth(shown) > maxW - 2) shown = shown.substring(1); // keep the caret end visible
        int x = tx;
        if (shown.length() == searchText.length() && !shown.isEmpty() && StashSearch.PREFIXES.indexOf(shown.charAt(0)) >= 0) {
            String prefix = shown.substring(0, 1);
            r.text(prefix, x, ty, p.accent());
            x += r.textWidth(prefix);
            shown = shown.substring(1);
        }
        r.text(shown, x, ty, p.text());
        if (searchFocused) r.rect(x + r.textWidth(shown), ty, 1, r.fontHeight() - 1, p.accent());
    }

    /** "(18) Normal": the page count of that mode. (0) means Telos has none (you can't switch there), (?) means not known yet. */
    private static String modeLabel(StashSession session, StashMode m) {
        return "(" + session.book(m).countLabel() + ") " + m.label();
    }

    private static void pill(Render2D r, Theme p, String label, int x, boolean active, boolean hover) {
        int color = active ? p.text() : hover ? p.text() : p.textMuted();
        r.textCentered(label, x + pillW / 2, pillY + (pillH - r.fontHeight()) / 2 + 1, color);
    }

    /** True if the card should show the live container (it's the open page and no page change is pending). */
    private static boolean isLive(StashSession session, int index) {
        return index == session.page() && !session.isBusy();
    }

    private static GameItem drawCards(Render2D r, Theme p, StashSession session, StashBook book, int radius, Target hover) {
        GameItem hovered = null;
        int top = CARDS_TOP;
        boolean searching = StashSearch.active(searchText);
        for (int row = 0; row < visRows; row++) {
            for (int col = 0; col < cols; col++) {
                int index = (scrollRow + row) * cols + col + 1;
                if (index > pageCount) continue;
                int x = cardX(col), y = top + row * (CARD_H + GAP);
                boolean current = index == session.page();
                boolean live = isLive(session, index);
                boolean cardHover = hover != null && hover.page() == index && hover.kind() != Kind.INV_SLOT;
                StashPage page = book.page(index);

                r.roundedRect(x, y, CARD_W, CARD_H, radius, cardHover && !current ? p.surfaceHover() : p.surface());
                if (current) r.outline(x, y, CARD_W, CARD_H, p.accent());
                int matches = -1;
                if (searching && (page != null || live)) {
                    matches = 0;
                    for (int i = 0; i < StashPage.SLOTS; i++) {
                        GameItem item = live ? StashAdapter.liveItem(i) : page.item(i);
                        if (item != null && StashSearch.matches(item, searchText)) matches++;
                    }
                }
                drawLabel(r, p, book, index, x, y, current, cardHover && hover.kind() == Kind.LABEL, matches);

                int gx = x + 2, gy = y + LABEL_H;
                for (int i = 0; i < StashPage.SLOTS; i++) {
                    r.rect(gx + (i % 9) * SLOT + 1, gy + (i / 9) * SLOT + 1, 16, 16, p.control());
                }
                if (page == null && !live) {
                    r.textCentered("Not saved", x + CARD_W / 2, gy + GRID_H / 2 - r.fontHeight() / 2, p.textDisabled());
                    continue;
                }
                for (int i = 0; i < StashPage.SLOTS; i++) {
                    GameItem item = live ? StashAdapter.liveItem(i) : page.item(i);
                    // vanilla slot: an 18 px cell with the 16x16 item at +1 (Telos skin frames fill the cell edge to edge)
                    int sx = gx + (i % 9) * SLOT + 1, sy = gy + (i / 9) * SLOT + 1;
                    if (item != null) r.item(item, sx, sy);
                    if (searching) searchMark(r, p, item, sx, sy);
                    if (hover != null && hover.kind() == Kind.STASH_SLOT && hover.page() == index && hover.slot() == i) {
                        r.rect(sx, sy, 16, 16, ColorUtil.withAlpha(p.text(), 0x40)); // like vanilla, highlight over the item
                        hovered = item;
                    }
                }
            }
        }
        return hovered;
    }

    /** A search result on a slot: matches get an accent frame, and everything else (empty slots too) is darkened. */
    private static void searchMark(Render2D r, Theme p, GameItem item, int sx, int sy) {
        if (item != null && StashSearch.matches(item, searchText)) {
            r.outline(sx - 1, sy - 1, 18, 18, p.accent());
        } else {
            r.rect(sx, sy, 16, 16, ColorUtil.withAlpha(ColorUtil.shade(p.panel(), -0.6), 0xB0));
        }
    }

    /** @param matches search hits on this page, or -1 when no search is active or the page is unknown */
    private static void drawLabel(Render2D r, Theme p, StashBook book, int index, int x, int y, boolean current, boolean hover,
                                  int matches) {
        if (renamePage == index) {
            r.rect(x + 1, y + 1, CARD_W - 2, LABEL_H - 1, p.control());
            String shown = r.trim(renameText, CARD_W - 10);
            if (r.textWidth(renameText) > CARD_W - 10) {
                // keep the end (where the caret is) visible
                shown = renameText;
                while (shown.length() > 1 && r.textWidth(shown) > CARD_W - 10) shown = shown.substring(1);
            }
            r.text(shown, x + 3, y + 2, p.text());
            r.rect(x + 3 + r.textWidth(shown), y + 2, 1, r.fontHeight() - 1, p.accent());
            return;
        }
        String name = book.name(index);
        String label = name == null ? "Page " + index : index + " · " + name;
        int color = current ? p.accent() : hover ? p.text() : p.textMuted();
        int countW = 0;
        if (matches >= 0) {
            String count = String.valueOf(matches);
            countW = r.textWidth(count) + 4;
            r.textRight(count, x + CARD_W - 3, y + 2, matches > 0 ? p.accent() : p.textDisabled());
            if (matches == 0 && !current) color = p.textDisabled();
        }
        r.text(r.trim(label, CARD_W - 6 - countW), x + 3, y + 2, color);
    }

    private static int legendWidth(Render2D r) {
        int w = r.textWidth("Search filters");
        for (String[] row : LEGEND) w = Math.max(w, 8 + LEGEND_LABEL_W + r.textWidth(row[2]));
        return PAD * 2 + 2 + w;
    }

    /** The search filter legend: the prefix (accent), what it filters, and 1–3 examples (muted). */
    private static void drawLegend(Render2D r, Theme p) {
        int x = legendX + PAD + 1;
        r.text("Search filters", x, invY + PAD + 1, p.textMuted());
        int lineH = Math.max(r.fontHeight() + 3, (INV_H - PAD * 2 - LABEL_H) / LEGEND.length);
        int y = invY + PAD + LABEL_H + 2;
        for (String[] row : LEGEND) {
            r.text(row[0], x, y, p.accent());
            r.text(row[1], x + 8, y, p.text());
            r.text(row[2], x + 8 + LEGEND_LABEL_W, y, p.textMuted());
            y += lineH;
        }
    }

    private static GameItem drawInventory(Render2D r, Theme p, Target hover) {
        r.text("Inventory", invX + PAD + 1, invY + PAD + 1, p.textMuted());
        GameItem hovered = null;
        for (int j = 0; j < 36; j++) {
            int sx = invSlotX(j) + 1, sy = invSlotY(j) + 1;
            r.rect(sx, sy, 16, 16, p.control());
            GameItem item = StashAdapter.liveItem(INV_SLOT0 + j);
            if (item != null) r.item(item, sx, sy);
            if (StashSearch.active(searchText)) searchMark(r, p, item, sx, sy);
            if (hover != null && hover.kind() == Kind.INV_SLOT && hover.slot() == j) {
                r.rect(sx, sy, 16, 16, ColorUtil.withAlpha(p.text(), 0x40));
                hovered = item;
            }
        }
        return hovered;
    }

    private static void drawScrollbar(Render2D r, Theme p) {
        int trackX = width - PAD - SCROLLBAR_W;
        int trackY = CARDS_TOP;
        int trackH = height - trackY - PAD;
        r.roundedRect(trackX, trackY, SCROLLBAR_W, trackH, SCROLLBAR_W / 2, p.control());
        int thumbH = Math.max(8, trackH * visRows / totalRows);
        int range = totalRows - visRows;
        int thumbY = trackY + (range == 0 ? 0 : (trackH - thumbH) * scrollRow / range);
        r.roundedRect(trackX, thumbY, SCROLLBAR_W, thumbH, SCROLLBAR_W / 2, p.accent());
    }

    /** Scrolls so the current page stays visible whenever it changes. */
    private static void followCurrentPage(int page) {
        if (page == followedPage || page <= 0) {
            followedPage = page;
            return;
        }
        followedPage = page;
        int row = (page - 1) / cols;
        if (row < scrollRow) scrollRow = row;
        else if (row >= scrollRow + visRows) scrollRow = row - visRows + 1;
        scrollRow = clamp(scrollRow, 0, totalRows - visRows);
    }

    private static int cardX(int col) {
        return PAD + col * (CARD_W + GAP);
    }

    /** Inventory slot j: 0–26 are the main inventory (rows top to bottom), 27–35 the hotbar. */
    private static int invSlotX(int j) {
        return invX + PAD + (j % 9) * SLOT;
    }

    private static int invSlotY(int j) {
        int row = j / 9;
        return invY + PAD + LABEL_H + row * SLOT + (row == 3 ? HOTBAR_GAP : 0);
    }

    /** What's under the natural point (nx, ny), or {@code null}. */
    private static Target target(int nx, int ny) {
        if (!laidOut) return null;
        for (int j = 0; j < 36; j++) {
            if (Hit.inside(nx, ny, invSlotX(j), invSlotY(j), SLOT - 1, SLOT - 1)) return new Target(Kind.INV_SLOT, 0, j);
        }
        int top = CARDS_TOP;
        for (int row = 0; row < visRows; row++) {
            for (int col = 0; col < cols; col++) {
                int index = (scrollRow + row) * cols + col + 1;
                if (index > pageCount) continue;
                int x = cardX(col), y = top + row * (CARD_H + GAP);
                if (!Hit.inside(nx, ny, x, y, CARD_W, CARD_H)) continue;
                if (ny < y + LABEL_H) return new Target(Kind.LABEL, index, -1);
                int gx = x + 2, gy = y + LABEL_H;
                for (int i = 0; i < StashPage.SLOTS; i++) {
                    if (Hit.inside(nx, ny, gx + (i % 9) * SLOT, gy + (i / 9) * SLOT, SLOT - 1, SLOT - 1)) {
                        return new Target(Kind.STASH_SLOT, index, i);
                    }
                }
                return new Target(Kind.CARD, index, -1);
            }
        }
        return null;
    }

    /** The overlay takes every click while it's replacing the stash. */
    public static boolean mouseClicked(double mouseX, double mouseY, int button, boolean shift) {
        if (!laidOut) return true;
        if (renamePage > 0) {
            renameKey(RenameKey.CONFIRM);
            return true;
        }
        int nx = (int) Math.floor((mouseX - ox) / scale);
        int ny = (int) Math.floor((mouseY - oy) / scale);
        if (Hit.inside(nx, ny, searchX, searchY, searchW, SEARCH_H)) {
            // left-click types here, right-click or the × clears
            boolean onClear = !searchFocused && !searchText.isEmpty() && nx >= searchX + searchW - 12;
            if (button == 1 || onClear) searchText = "";
            searchFocused = button == 0 && !onClear || button == 1;
            return true;
        }
        searchFocused = false;
        StashSession session = StashSession.get();
        boolean inMain = Hit.inside(nx, ny, 0, 0, width, height);
        boolean inInv = Hit.inside(nx, ny, invX, invY, INV_W, INV_H);

        if (Hit.inside(nx, ny, legendX, invY, legendW, INV_H)) return true; // clicks on the legend don't drop anything
        if (!inMain && !inInv) {
            // outside the window: drop the carried item, like vanilla
            if (StashAdapter.carried() != null && (button == 0 || button == 1)) StashAdapter.slotInput(-999, SlotAction.PICKUP, button);
            return true;
        }
        if (button == 0 && inMain) {
            if (Hit.inside(nx, ny, normalX, pillY, pillW, pillH)) {
                session.switchMode(StashMode.NORMAL);
                return true;
            }
            if (Hit.inside(nx, ny, seasonalX, pillY, pillW, pillH)) {
                session.switchMode(StashMode.SEASONAL);
                return true;
            }
            if (Hit.inside(nx, ny, scanX, pillY, scanW, pillH)) {
                if (shift) session.startBenchmark(); // times every page click and shows the result in the header and the log
                else session.startScan();
                return true;
            }
            if (convertShown && Hit.inside(nx, ny, convertX, pillY, convertW, pillH)) {
                session.convertPage();
                return true;
            }
        }
        Target t = target(nx, ny);
        if (t == null || (button != 0 && button != 1)) return true;
        switch (t.kind()) {
            case LABEL -> {
                if (button == 1) startRename(t.page());
                else session.goTo(t.page());
            }
            case CARD -> session.goTo(t.page());
            case STASH_SLOT, INV_SLOT -> {
                SlotAction action = shift ? SlotAction.QUICK_MOVE : SlotAction.PICKUP;
                long now = System.currentTimeMillis();
                int clickPage = t.kind() == Kind.INV_SLOT ? 0 : t.page();
                boolean dbl = button == 0 && !shift && now - lastClickTime < DOUBLE_CLICK_MS
                        && lastClickPage == clickPage && lastClickSlot == t.slot() && StashAdapter.carried() != null;
                lastClickTime = dbl ? 0 : now;
                lastClickPage = clickPage;
                lastClickSlot = t.slot();
                send(t, dbl ? SlotAction.PICKUP_ALL : action, button);
                shiftDrag = shift;
                if (shift) {
                    shiftDragButton = button;
                    dragNx = nx;
                    dragNy = ny;
                    dragVisited.clear();
                    dragVisited.add(dragKey(t));
                }
            }
        }
        return true;
    }

    /**
     * The mouse moved with a button held. A drag that started with a shift-click quick-moves every further slot it
     * passes over while Shift stays held (inventory to the shown stash page, or the shown stash page to the inventory).
     */
    public static boolean mouseDragged(double mouseX, double mouseY, int button, boolean shift) {
        if (!laidOut || !shiftDrag || button != shiftDragButton) return true;
        int nx = (int) Math.floor((mouseX - ox) / scale);
        int ny = (int) Math.floor((mouseY - oy) / scale);
        if (shift) {
            // walk the path since the last event, so a fast swipe doesn't skip slots
            int steps = Math.max(1, Math.max(Math.abs(nx - dragNx), Math.abs(ny - dragNy)) / 4);
            for (int i = 1; i <= steps; i++) {
                dragQuickMove(target(dragNx + (nx - dragNx) * i / steps, dragNy + (ny - dragNy) * i / steps));
            }
        }
        dragNx = nx;
        dragNy = ny;
        return true;
    }

    public static void mouseReleased(int button) {
        if (button == shiftDragButton) {
            shiftDrag = false;
            dragVisited.clear();
        }
    }

    private static void dragQuickMove(Target t) {
        if (t == null || (t.kind() != Kind.STASH_SLOT && t.kind() != Kind.INV_SLOT)) return;
        StashSession session = StashSession.get();
        if (session.isBusy()) return; // mid page switch, the live container isn't the page being shown
        if (t.kind() == Kind.STASH_SLOT && t.page() != session.page()) return; // only the page being shown is live
        if (!dragVisited.add(dragKey(t))) return;
        int menuSlot = t.kind() == Kind.INV_SLOT ? INV_SLOT0 + t.slot() : t.slot();
        if (StashAdapter.liveItem(menuSlot) == null) return;
        send(t, SlotAction.QUICK_MOVE, shiftDragButton);
    }

    private static int dragKey(Target t) {
        return (t.kind() == Kind.INV_SLOT ? 0 : t.page()) * 100 + t.slot();
    }

    /** A slot key (1–9, F, Q) on the hovered slot. @return true if a slot was hovered. */
    public static boolean hoverAction(SlotAction action, int button) {
        if (!laidOut || renamePage > 0) return false;
        Target t = target(lastNx, lastNy);
        if (t == null || (t.kind() != Kind.STASH_SLOT && t.kind() != Kind.INV_SLOT)) return false;
        send(t, action, button);
        return true;
    }

    private static void send(Target t, SlotAction action, int button) {
        if (t.kind() == Kind.INV_SLOT) StashAdapter.slotInput(INV_SLOT0 + t.slot(), action, button);
        else StashSession.get().clickPageSlot(t.page(), t.slot(), action, button);
    }

    /** @return true if the scroll was over the overlay */
    public static boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!laidOut) return false;
        if (amount != 0 && totalRows > visRows) scrollRow = clamp(scrollRow - (int) Math.signum(amount), 0, totalRows - visRows);
        return true;
    }

    public static boolean isRenaming() {
        return renamePage > 0;
    }

    private static void startRename(int page) {
        StashSession session = StashSession.get();
        renamePage = page;
        renameMode = session.mode();
        String name = session.book().name(page);
        renameText = name == null ? "" : name;
    }

    private static void cancelRename() {
        renamePage = 0;
        renameText = "";
    }

    public static void renameKey(RenameKey key) {
        if (renamePage <= 0) return;
        switch (key) {
            case CONFIRM -> {
                StashSession session = StashSession.get();
                if (renameMode == session.mode()) session.renamePage(renamePage, renameText);
                cancelRename();
            }
            case CANCEL -> cancelRename();
            case BACKSPACE -> {
                if (!renameText.isEmpty()) renameText = renameText.substring(0, renameText.offsetByCodePoints(renameText.length(), -1));
            }
        }
    }

    public static boolean isSearchFocused() {
        return searchFocused && renamePage <= 0;
    }

    /** Ctrl+F focuses the search box. */
    public static void focusSearch() {
        if (renamePage <= 0) searchFocused = true;
    }

    public static void searchKey(SearchKey key) {
        switch (key) {
            case CONFIRM -> searchFocused = false;  // keep the filter and give the keys back to the slots
            case CANCEL -> {
                if (!searchText.isEmpty()) searchText = "";
                else searchFocused = false;
            }
            case BACKSPACE -> {
                if (!searchText.isEmpty()) searchText = searchText.substring(0, searchText.offsetByCodePoints(searchText.length(), -1));
            }
            case CLEAR -> searchText = "";
        }
    }

    /** @return true if the character went into the page name being edited or into the search box */
    public static boolean charTyped(String s) {
        if (s == null || s.isEmpty()) return false;
        if (renamePage <= 0 && searchFocused) {
            if (s.codePointAt(0) >= 32 && searchText.codePointCount(0, searchText.length()) < MAX_SEARCH) searchText += s;
            return true;
        }
        if (renamePage <= 0) return false;
        if (s.codePointAt(0) >= 32 && renameText.codePointCount(0, renameText.length()) < MAX_NAME) renameText += s;
        return true;
    }

    /** The stash closed, so drop any half-typed name. */
    public static void onClose() {
        cancelRename();
        searchFocused = false; // the search text stays for the next time the stash opens
        shiftDrag = false;
        dragVisited.clear();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
