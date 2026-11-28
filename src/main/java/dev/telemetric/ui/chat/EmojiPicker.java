package dev.telemetric.ui.chat;

import dev.telemetric.Telemetric;
import dev.telemetric.data.EmojiTable;
import dev.telemetric.data.EmojiTable.Emoji;
import dev.telemetric.data.TelosData;
import dev.telemetric.game.ChatHudAdapter;
import dev.telemetric.input.InputUtil;
import dev.telemetric.module.impl.social.ChatModule;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.framework.Hit;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Icons;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Chat Emoji Picker (Social → Chat → Emoji Button), a Discord-style window over the chat box.
 * <ul>
 *   <li>The emoji icon sits at right of the chat box at its bottom; the window opens to the right; click it to open / close the window.
 *       Hold right-click on it to drag it anywhere (offset saved in the Chat module).</li>
 *   <li>Search bar on top (typing goes there while the window is open; Enter picks the first hit, Esc closes).</li>
 *   <li>Sections: Favorites first, then the wiki categories from {@code emojis.json}. Click a section header
 *       to collapse / expand it; the left rail jumps to a section.</li>
 *   <li>Left-click an emoji: types its code into the chat box (Shift keeps the window open). Right-click: favorite /
 *       unfavorite. Favorites and collapsed sections are saved in the Chat module.</li>
 * </ul>
 * The server turns the typed code into the emoji, so nothing but the code is ever sent.
 */
final class EmojiPicker {

    // 2/4/6/8 spacing, emoji ≈ ⅔ of its cell
    private static final int COLS = 9, CELL = 16, PAD = 6, RAIL = 20, RAIL_ITEM = 16, RAIL_ICON = 10, RAIL_STEP = 18, SEARCH_H = 14,
            HEADER_H = 12, FOOTER_H = 20, SECTION_GAP = 4, SCROLLBAR = 4, CLEAR_W = 12;
    private static final int W = RAIL + PAD + COLS * CELL + PAD + SCROLLBAR;
    private static final int MAX_H = 200, RADIUS = 4, INNER_RADIUS = 3, SHADOW = 6;
    /** The icon in the chat input (12 px high, like the input bar). */
    private static final int BTN = 12, TAB_BAR_OFFSET = 32, LINE_H = 9; // one chat line up: sits on the bottom chat line
    /** Grid / footer / rail emoji scales (snapped to whole screen pixels when drawn). */
    private static final float GLYPH_SCALE = 1.25f, PREVIEW_SCALE = 1.25f, RAIL_SCALE = 1f;
    private static final int MAX_QUERY = 32, GLFW_MOD_CONTROL = 0x0002;
    /** Emojis are coloured textures: drawn untinted, this isn't a UI colour. */
    private static final int GLYPH_TINT = 0xFFFFFFFF;
    private static final String FAV = "\u0000fav", SEARCH = "\u0000search", ICON_CODE = ":)", ICON_FALLBACK = "☺";

    private static final Render2D R = new Render2D();
    private static final Animation OPEN = new Animation(Durations.DROPDOWN, Easing.OUT_CUBIC, 0);
    private static final Animation BTN_HOVER = new Animation(Durations.HOVER, Easing.OUT_CUBIC, 0);
    private static final Animation SCROLL = new Animation(Durations.STANDARD, Easing.OUT_CUBIC, 0);
    private static final Map<String, Animation> EXPAND = new HashMap<>();
    private static final Map<String, Animation> HOVER = new HashMap<>();

    private static boolean open;
    private static String query = "";
    private static double scrollTarget;
    private static boolean dirty;
    private static int screenW, screenH, contentH;
    private static List<String> favorites = new ArrayList<>();
    private static Set<String> collapsed = new LinkedHashSet<>();
    /** Icon offset from its default spot (saved in the Chat module); right-drag state. */
    private static int offX, offY, grabX, grabY;
    private static boolean offLoaded, dragging;

    private EmojiPicker() {}

    /** One block of the grid: header + rows of emojis, {@code top} in content pixels. */
    private record Section(String key, String title, String icon, List<Emoji> emojis, boolean collapsible, int top, int height) {
        int gridTop() { return top + HEADER_H; }
    }

    static boolean isOpen() {
        return open;
    }

    private static void toggle(ChatModule chat) {
        if (open) close(chat);
        else openPicker(chat);
    }

    private static void openPicker(ChatModule chat) {
        open = true;
        favorites = new ArrayList<>(chat.emojiFavorites());
        collapsed = new LinkedHashSet<>(chat.emojiCollapsed());
        EXPAND.clear();
        query = "";
        scrollTarget = 0;
        SCROLL.snapTo(0);
        OPEN.animateTo(1);
    }

    private static void close(ChatModule chat) {
        open = false;
        OPEN.animateTo(0);
        if (dirty && chat != null) {
            chat.setEmojiFavorites(favorites);
            chat.setEmojiCollapsed(collapsed);
            Telemetric.get().config().onGuiClosed(); // saves the profile (auto save)
        }
        dirty = false;
    }

    /** The chat screen went away: close without the fade. */
    static void onChatClosed(ChatModule chat) {
        if (dragging) endDrag(chat);
        offLoaded = false;
        if (open) close(chat);
        OPEN.snapTo(0);
        BTN_HOVER.snapTo(0);
    }

    private static Animation expand(String key) {
        return EXPAND.computeIfAbsent(key, k -> new Animation(Durations.ACCORDION, Easing.IN_OUT_CUBIC, collapsed.contains(k) ? 0 : 1));
    }

    private static boolean isExpanded(String key) {
        return expand(key).getTarget() > 0.5;
    }

    /** Right of the icon (beside the chat box, not over it); flipped to its left if it doesn't fit, then clamped. */
    private static int px() {
        int right = bx() + BTN + 4;
        if (right + W <= screenW - 2) return right;
        int left = bx() - 4 - W;
        return left >= 2 ? left : Math.max(2, screenW - 2 - W);
    }
    /** As tall as the room above the icon allows (or below it, if the icon was dragged near the top). */
    private static int ph() { return Math.max(80, Math.min(MAX_H, Math.max(by() + BTN, screenH - by()) - 6)); }
    /** Bottom edge level with the icon's; if that doesn't fit, top edge level with it. */
    private static int py() {
        int above = by() + BTN - ph();
        return above >= 2 ? above : Math.max(2, Math.min(by(), screenH - 2 - ph()));
    }
    /** Default spot: just right of the chat box, level with its bottom (the last chat line, above the tab bar). */
    private static int baseX() { return ChatHudAdapter.chatWidth() + 12; }
    private static int baseY() { return screenH - TAB_BAR_OFFSET - BTN - 1 - LINE_H; }
    /** Default spot + the right-drag offset, kept on screen. */
    private static int bx() { return Math.clamp(baseX() + offX, 2, Math.max(2, screenW - 2 - BTN)); }
    private static int by() { return Math.clamp(baseY() + offY, 2, Math.max(2, screenH - 2 - BTN)); }
    private static int bodyX() { return px() + RAIL; }
    private static int bodyY() { return py() + PAD + SEARCH_H + 4; }
    private static int bodyH() { return py() + ph() - FOOTER_H - bodyY(); }
    private static int gridX() { return bodyX() + PAD; }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return Hit.inside(mx, my, x, y, w, h);
    }

    private static boolean inButton(double mx, double my) { return in(mx, my, bx(), by(), BTN, BTN); }
    private static boolean inPanel(double mx, double my) { return in(mx, my, px(), py(), W, ph()); }
    private static boolean inBody(double mx, double my) { return in(mx, my, bodyX(), bodyY(), W - RAIL, bodyH()); }

    private static int maxScroll() {
        return Math.max(0, contentH - bodyH());
    }

    private static void scrollTo(double target) {
        scrollTarget = Math.clamp(target, 0, maxScroll());
        SCROLL.animateTo(scrollTarget);
    }

    private static List<Section> layout() {
        EmojiTable table = TelosData.allEmojis();
        List<Section> out = new ArrayList<>();
        int y = 0;
        if (!query.isEmpty()) {
            y = add(out, SEARCH, "Search Results", "", search(table), false, y);
        } else {
            List<Emoji> favs = new ArrayList<>();
            for (String code : favorites) {
                Emoji e = table.byCode(code);
                if (e != null) favs.add(e);
            }
            y = add(out, FAV, "Favorites", "", favs, true, y);
            for (EmojiTable.Category c : table.categories()) y = add(out, c.name(), c.name(), c.icon(), c.emojis(), true, y);
        }
        contentH = y;
        return out;
    }

    private static int add(List<Section> out, String key, String title, String icon, List<Emoji> emojis, boolean collapsible, int y) {
        int rows = Math.max(1, (emojis.size() + COLS - 1) / COLS);
        double ex = collapsible ? expand(key).get() : 1;
        int h = (int) Math.round(rows * CELL * ex);
        out.add(new Section(key, title, icon, emojis, collapsible, y, h));
        return y + HEADER_H + h + SECTION_GAP;
    }

    private static List<Emoji> search(EmojiTable table) {
        String q = plain(query);
        List<Emoji> starts = new ArrayList<>(), contains = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (EmojiTable.Category c : table.categories()) {
            for (Emoji e : c.emojis()) {
                String code = plain(e.code());
                if (!seen.add(e.code())) continue;
                if (code.startsWith(q)) starts.add(e);
                else if (code.contains(q)) contains.add(e);
            }
        }
        starts.addAll(contains);
        return starts;
    }

    /** Lower case, colons dropped (":valorcry:" matches "valor"). */
    private static String plain(String s) {
        String t = s.toLowerCase(Locale.ROOT);
        return t.length() > 2 && t.startsWith(":") && t.endsWith(":") ? t.substring(1, t.length() - 1) : t;
    }

    /** The emoji cell under the mouse, or null. */
    private static Emoji emojiAt(List<Section> secs, double mx, double my) {
        if (!inBody(mx, my)) return null;
        int scroll = (int) Math.round(SCROLL.get());
        for (Section s : secs) {
            int gridTop = bodyY() + s.gridTop() - scroll;
            if (my < gridTop || my >= gridTop + s.height()) continue;
            int col = (int) Math.floor((mx - gridX()) / CELL), row = (int) Math.floor((my - gridTop) / CELL);
            if (col < 0 || col >= COLS || row < 0) return null;
            int i = row * COLS + col;
            return i < s.emojis().size() ? s.emojis().get(i) : null;
        }
        return null;
    }

    private static Section sectionOf(List<Section> secs, Emoji e, double my) {
        int scroll = (int) Math.round(SCROLL.get());
        for (Section s : secs) {
            int gridTop = bodyY() + s.gridTop() - scroll;
            if (my >= gridTop && my < gridTop + s.height() && s.emojis().contains(e)) return s;
        }
        return null;
    }

    private static Section headerAt(List<Section> secs, double mx, double my) {
        if (!inBody(mx, my)) return null;
        int scroll = (int) Math.round(SCROLL.get());
        for (Section s : secs) {
            int top = bodyY() + s.top() - scroll;
            if (s.collapsible() && in(mx, my, gridX(), top, COLS * CELL, HEADER_H)) return s;
        }
        return null;
    }

    /** Index into {@code secs} of the rail icon under the mouse, or -1. */
    private static int railAt(List<Section> secs, double mx, double my) {
        if (!query.isEmpty() || !in(mx, my, px(), bodyY(), RAIL, bodyH())) return -1;
        int i = (int) Math.floor((my - bodyY() - 2) / RAIL_STEP);
        return i >= 0 && i < secs.size() ? i : -1;
    }

    /** The section the top of the view is in (for the rail highlight). */
    private static int currentSection(List<Section> secs) {
        double scroll = SCROLL.get() + 1;
        int current = 0;
        for (int i = 0; i < secs.size(); i++) if (secs.get(i).top() <= scroll) current = i;
        if (scrollTarget >= maxScroll() - 1 && maxScroll() > 0) {
            // at the bottom: the last section whose header is in view wins
            for (int i = 0; i < secs.size(); i++) if (secs.get(i).top() - SCROLL.get() < bodyH() / 2.0) current = Math.max(current, i);
        }
        return current;
    }

    private static String iconGlyph() {
        Emoji e = TelosData.EMOJIS.get().byCode(ICON_CODE);
        return e != null ? e.glyph() : ICON_FALLBACK;
    }

    static void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, ChatModule chat, int width, int height) {
        screenW = width;
        screenH = height;
        if (!chat.showsEmojiButton()) {
            if (dragging) endDrag(chat);
            if (open) close(chat);
            return;
        }
        if (!offLoaded) {
            int[] o = chat.emojiButtonOffset();
            offX = o[0];
            offY = o[1];
            offLoaded = true;
        }
        if (dragging) {
            if (InputUtil.isKeyDown(InputUtil.mouseCode(1))) moveTo(mouseX - grabX, mouseY - grabY);
            else endDrag(chat);
        }
        Theme p = Telemetric.get().theme().palette();
        R.begin(graphics, false);
        try {
            drawButton(p, mouseX, mouseY);
            double t = OPEN.get();
            if (t > 0.001) {
                R.nextLayer();
                drawPicker(p, mouseX, mouseY, t);
            }
        } finally {
            R.end();
        }
    }

    private static void drawButton(Theme p, int mx, int my) {
        BTN_HOVER.animateTo(inButton(mx, my) || open || dragging ? 1 : 0);
        double t = BTN_HOVER.get();
        int x = bx(), y = by();
        R.pushAlpha(0.55 + 0.45 * t); // one hover cue: it brightens, no growing
        glyph(iconGlyph(), x + BTN / 2f, y + BTN / 2f, 1f);
        R.popAlpha();
    }

    /** An emoji centred on (cx, cy), its scale snapped so the texels stay square. */
    private static void glyph(String g, float cx, float cy, float scale) {
        scale = Render2D.nearestPixelScale(scale);
        int w = R.textWidth(g);
        R.pushTransform(cx - w * scale / 2f, cy - 4f * scale, scale);
        R.text(g, 0, 0, GLYPH_TINT, Render2D.TextLook.NONE);
        R.popTransform();
    }

    /** Text y that centres the pixel font in a row of height {@code h}. */
    private static int textY(int y, int h) {
        return y + (h - 8) / 2;
    }

    private static void drawPicker(Theme p, int mx, int my, double t) {
        List<Section> secs = layout();
        if (scrollTarget > maxScroll()) scrollTo(maxScroll());
        boolean live = open && t > 0.98; // no hover effects while it fades
        int x = px(), y = py(), h = ph();
        int slide = (int) Math.round((1 - t) * 4);
        R.pushAlpha(t);
        R.pushTransform(0, slide, 1);
        try {
            R.shadow(x, y, W, h, RADIUS, p.shadow(), SHADOW);
            R.roundedRect(x, y, W, h, RADIUS, p.border());
            R.roundedRect(x + 1, y + 1, W - 2, h - 2, RADIUS - 1, p.panel());

            drawSearch(p, mx, my, x + PAD, y + PAD, W - PAD * 2);
            drawRail(p, secs, live ? mx : -1, my, x, bodyY());
            Emoji hovered = drawGrid(p, secs, live ? mx : -1, my, live && inPanel(mx, my));
            if (hovered == null && !query.isEmpty() && !secs.isEmpty() && !secs.getFirst().emojis().isEmpty()) {
                hovered = secs.getFirst().emojis().getFirst(); // what Enter would pick
            }
            drawFooter(p, hovered, x, y + h - FOOTER_H);
        } finally {
            R.popTransform();
            R.popAlpha();
        }
    }

    private static void drawSearch(Theme p, int mx, int my, int x, int y, int w) {
        R.roundedRect(x, y, w, SEARCH_H, INNER_RADIUS, p.control());
        int ty = textY(y, SEARCH_H);
        int iconColor = query.isEmpty() ? p.textMuted() : p.accent();
        Icons.magnifier(R, x + 7, y + SEARCH_H / 2, iconColor, p.control());
        int tx = x + 15;
        if (query.isEmpty()) {
            R.text("Search emoji", tx, ty, ColorUtil.fade(p.textMuted(), 0.8));
            return;
        }
        String shown = query;
        while (shown.length() > 1 && R.textWidth(shown) > w - 15 - CLEAR_W - 2) shown = shown.substring(1);
        R.text(shown, tx, ty, p.text());
        boolean caret = (System.currentTimeMillis() / Durations.CARET_BLINK) % 2 == 0;
        if (caret) R.rect(tx + R.textWidth(shown) + 1, ty - 1, 1, 10, p.accent());
        boolean hov = in(mx, my, x + w - CLEAR_W, y, CLEAR_W, SEARCH_H);
        Icons.close(R, x + w - CLEAR_W / 2, y + SEARCH_H / 2, hov ? p.text() : p.textMuted());
    }

    private static void drawRail(Theme p, List<Section> secs, int mx, int my, int x, int top) {
        int bh = bodyH();
        R.rect(x + RAIL, top, 1, bh, ColorUtil.fade(p.border(), 0.6)); // hairline instead of a filled strip
        if (!query.isEmpty()) return;
        int current = currentSection(secs);
        int hovered = railAt(secs, mx, my);
        R.pushClip(x + 1, top, RAIL - 1, bh);
        int ix = x + (RAIL - RAIL_ITEM) / 2 + 1;
        for (int i = 0; i < secs.size(); i++) {
            int iy = top + 2 + i * RAIL_STEP;
            boolean cur = i == current, hov = i == hovered;
            Animation a = HOVER.computeIfAbsent("rail/" + secs.get(i).key(), k -> new Animation(Durations.HOVER, Easing.OUT_CUBIC, 0));
            a.animateTo(cur || hov ? 1 : 0);
            if (a.get() > 0.01) {
                R.roundedRect(ix, iy, RAIL_ITEM, RAIL_ITEM, INNER_RADIUS, ColorUtil.fade(cur ? p.surfaceActive() : p.surfaceHover(), a.get()));
            }
            if (cur) R.rect(x + 1, iy + 4, 2, RAIL_ITEM - 8, p.accent());
            Section s = secs.get(i);
            int cx = ix + RAIL_ITEM / 2, cy = iy + RAIL_ITEM / 2;
            if (s.key().equals(FAV)) {
                Icons.star(R, cx, cy, cur, cur || hov ? p.accent() : p.textMuted());
            } else if (!s.icon().isEmpty()) {
                R.pushAlpha(0.55 + 0.45 * a.get());
                R.texture(s.icon(), cx - RAIL_ICON / 2, cy - RAIL_ICON / 2, RAIL_ICON, RAIL_ICON, RAIL_ICON, RAIL_ICON);
                R.popAlpha();
            } else if (!s.emojis().isEmpty()) {
                R.pushAlpha(0.55 + 0.45 * a.get());
                glyph(s.emojis().getFirst().glyph(), cx, cy, RAIL_SCALE);
                R.popAlpha();
            }
        }
        R.popClip();
    }

    private static Emoji drawGrid(Theme p, List<Section> secs, int mx, int my, boolean overPanel) {
        int bx = bodyX(), by = bodyY(), bw = W - RAIL, bh = bodyH(), gx = gridX(), gw = COLS * CELL;
        int scroll = (int) Math.round(SCROLL.get());
        Emoji hovered = emojiAt(secs, mx, my);
        Section hoveredSec = hovered == null ? null : sectionOf(secs, hovered, my);
        Section hoveredHeader = headerAt(secs, mx, my);
        R.pushClip(bx, by, bw, bh);
        for (Section s : secs) {
            int top = by + s.top() - scroll;
            if (top > by + bh || top + HEADER_H + s.height() < by) continue;
            // header: chevron, Title Case name (muted), count (quieter still)
            boolean hh = s == hoveredHeader;
            int titleColor = hh ? p.text() : p.textMuted();
            int ty = textY(top, HEADER_H);
            int tx = gx;
            if (s.collapsible()) {
                Icons.chevron(R, gx + 3, top + HEADER_H / 2, expand(s.key()).get(), titleColor);
                tx += 9;
            }
            R.text(s.title(), tx, ty, titleColor);
            R.textRight(String.valueOf(s.emojis().size()), gx + gw - 1, ty, ColorUtil.fade(p.textMuted(), 0.55));
            if (s.height() <= 0) continue;
            int gridTop = top + HEADER_H;
            R.pushClip(gx, gridTop, gw, s.height());
            if (s.emojis().isEmpty()) {
                String hint = s.key().equals(FAV) ? "Right-click to add" : "No matches";
                R.text(hint, gx + 2, textY(gridTop, CELL), ColorUtil.fade(p.textMuted(), 0.7));
            }
            for (int i = 0; i < s.emojis().size(); i++) {
                int cx = gx + (i % COLS) * CELL, cy = gridTop + (i / COLS) * CELL;
                if (cy + CELL < by || cy > by + bh) continue;
                Emoji e = s.emojis().get(i);
                boolean hov = s == hoveredSec && e == hovered;
                Animation a = HOVER.computeIfAbsent(s.key() + "/" + e.code(), k -> new Animation(Durations.HOVER, Easing.OUT_CUBIC, 0));
                a.animateTo(hov ? 1 : 0);
                double ht = a.get();
                if (ht > 0.01) R.roundedRect(cx, cy, CELL, CELL, INNER_RADIUS, ColorUtil.fade(p.surfaceHover(), ht));
                glyph(e.glyph(), cx + CELL / 2f, cy + CELL / 2f, GLYPH_SCALE);
            }
            R.popClip();
        }
        R.popClip();
        // scrollbar: a thin thumb in its own gutter, quieter unless the mouse is over the picker
        int max = maxScroll();
        if (max > 0) {
            int th = Math.max(12, bh * bh / Math.max(1, contentH));
            int ty = by + (int) Math.round((bh - th) * Math.clamp(SCROLL.get() / max, 0, 1));
            R.roundedRect(px() + W - SCROLLBAR - 1, ty, 2, th, 1, ColorUtil.fade(p.textMuted(), overPanel ? 0.5 : 0.25));
        }
        return hovered;
    }

    /** One line: the hovered emoji and its code, a star if it's a favourite; otherwise a short hint. */
    private static void drawFooter(Theme p, Emoji e, int x, int y) {
        R.rect(x + 1, y, W - 2, 1, ColorUtil.fade(p.border(), 0.6));
        int ty = textY(y, FOOTER_H) + 1;
        if (e == null) {
            R.text("Right-click to favorite", x + PAD, ty, ColorUtil.fade(p.textMuted(), 0.7));
            return;
        }
        int cy = y + FOOTER_H / 2 + 1;
        glyph(e.glyph(), x + PAD + 6, cy, PREVIEW_SCALE);
        int tx = x + PAD + 16, starX = x + W - PAD - 4;
        R.text(R.trim(e.code(), starX - 8 - tx), tx, ty, p.text());
        boolean fav = favorites.contains(e.code());
        Icons.star(R, starX, cy, fav, fav ? p.accent() : ColorUtil.fade(p.textMuted(), 0.6));
    }

    static boolean mouseClicked(ChatModule chat, double mx, double my, int button) {
        if (!chat.showsEmojiButton() || screenW == 0) return false;
        if (inButton(mx, my)) {
            if (button == 0) toggle(chat);
            else if (button == 1) startDrag(mx, my);
            return true;
        }
        if (!open) return false;
        if (!inPanel(mx, my)) {
            close(chat);
            return false; // the click still reaches the chat
        }
        int sx = px() + PAD, sw = W - PAD * 2;
        if (!query.isEmpty() && in(mx, my, sx + sw - CLEAR_W, py() + PAD, CLEAR_W, SEARCH_H)) {
            setQuery("");
            return true;
        }
        List<Section> secs = layout();
        int rail = railAt(secs, mx, my);
        if (rail >= 0) {
            Section s = secs.get(rail);
            if (s.collapsible() && !isExpanded(s.key())) setCollapsed(s.key(), false);
            scrollTo(s.top());
            return true;
        }
        Section header = headerAt(secs, mx, my);
        if (header != null) {
            setCollapsed(header.key(), isExpanded(header.key()));
            return true;
        }
        Emoji e = emojiAt(secs, mx, my);
        if (e == null) return true;
        if (button == 1) {
            if (!favorites.remove(e.code())) favorites.add(e.code());
            dirty = true;
        } else if (button == 0) {
            pick(chat, e, InputUtil.isShiftDown());
        }
        return true;
    }

    /** Right-press on the icon: it follows the mouse while the button is held (polled in {@link #render}). */
    private static void startDrag(double mx, double my) {
        dragging = true;
        grabX = (int) mx - bx();
        grabY = (int) my - by();
    }

    private static void moveTo(int x, int y) {
        offX = Math.clamp(x, 2, Math.max(2, screenW - 2 - BTN)) - baseX();
        offY = Math.clamp(y, 2, Math.max(2, screenH - 2 - BTN)) - baseY();
    }

    private static void endDrag(ChatModule chat) {
        dragging = false;
        int[] saved = chat.emojiButtonOffset();
        if (saved[0] == offX && saved[1] == offY) return;
        chat.setEmojiButtonOffset(offX, offY);
        Telemetric.get().config().onGuiClosed(); // saves the profile (auto save)
    }

    private static void pick(ChatModule chat, Emoji e, boolean keepOpen) {
        ChatHudAdapter.insertWord(e.code());
        if (!keepOpen) close(chat);
    }

    private static void setCollapsed(String key, boolean value) {
        expand(key).animateTo(value ? 0 : 1);
        if (value) collapsed.add(key);
        else collapsed.remove(key);
        dirty = true;
    }

    static boolean mouseScrolled(double mx, double my, double scrollY) {
        if (!open || !inPanel(mx, my)) return false;
        scrollTo(scrollTarget - scrollY * CELL * 2);
        return true;
    }

    static boolean keyPressed(ChatModule chat, int key, int modifiers) {
        if (!open) return false;
        boolean ctrl = (modifiers & GLFW_MOD_CONTROL) != 0;
        if (key == InputUtil.KEY_ESCAPE) {
            close(chat);
            return true;
        }
        if (key == InputUtil.KEY_ENTER) {
            if (query.isEmpty()) {
                close(chat);
                return false; // Enter sends the chat line as usual
            }
            List<Section> secs = layout();
            if (!secs.isEmpty() && !secs.getFirst().emojis().isEmpty()) pick(chat, secs.getFirst().emojis().getFirst(), InputUtil.isShiftDown());
            return true;
        }
        if (key == InputUtil.KEY_BACKSPACE && !query.isEmpty()) {
            setQuery(ctrl ? "" : query.substring(0, query.offsetByCodePoints(query.length(), -1)));
        } else if (ctrl && key == InputUtil.KEY_V) {
            StringBuilder b = new StringBuilder(query);
            InputUtil.getClipboard().codePoints().filter(c -> c > 32 && c != 127).forEach(b::appendCodePoint);
            setQuery(b.toString());
        }
        return true; // the chat box doesn't get keys while the picker is open
    }

    static boolean charTyped(int codepoint) {
        if (!open) return false;
        if (codepoint >= 32 && codepoint != 127 && query.length() < MAX_QUERY) setQuery(query + Character.toString(codepoint));
        return true;
    }

    private static void setQuery(String q) {
        query = q.length() > MAX_QUERY ? q.substring(0, MAX_QUERY) : q;
        scrollTarget = 0;
        SCROLL.snapTo(0);
    }
}
