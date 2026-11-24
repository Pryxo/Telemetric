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
import dev.telemetric.ui.render.ColorUtil;
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
 * Chat <b>Emoji Picker</b> (Social → Chat → Emoji Button), a Discord-style window over the chat box.
 * <ul>
 *   <li>The emoji icon sits at right of the chat box at its bottom; the window opens to the right; click it to open / close the window.</li>
 *   <li>Search bar on top (typing goes there while the window is open; Enter picks the first hit, Esc closes).</li>
 *   <li>Sections: <b>Favorites</b> first, then the wiki categories from {@code emojis.json}. Click a section header
 *       to collapse / expand it; the left rail jumps to a section.</li>
 *   <li>Left-click an emoji: types its code into the chat box (Shift keeps the window open). Right-click: favorite /
 *       unfavorite. Favorites and collapsed sections are saved in the Chat module.</li>
 * </ul>
 * The server turns the typed code into the emoji, so nothing but the code is ever sent.
 */
final class EmojiPicker {

    private static final int COLS = 8, CELL = 20, PAD = 6, RAIL = 22, SEARCH_H = 16, HEADER_H = 14, FOOTER_H = 26,
            SECTION_GAP = 4, SCROLLBAR = 3;
    private static final int W = RAIL + PAD + COLS * CELL + PAD + SCROLLBAR;
    private static final int MAX_H = 236, RADIUS = 6;
    /** The icon in the chat input (12 px high, like the input bar). */
    private static final int BTN = 12, TAB_BAR_OFFSET = 32, LINE_H = 9; // one chat line up: sits on the bottom chat line
    private static final float GLYPH_SCALE = 1.75f;
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

    private EmojiPicker() {}

    /** One block of the grid: header + rows of emojis, {@code top} in content pixels. */
    private record Section(String key, String title, List<Emoji> emojis, boolean collapsible, int top, int height) {
        int gridTop() { return top + HEADER_H; }
    }

    // ------------------------------------------------------------------ state

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

    // ------------------------------------------------------------------ geometry

    /** Right of the icon (beside the chat box, not over it); pushed left only if the screen is too narrow. */
    private static int px() { return Math.max(2, Math.min(bx() + BTN + 4, screenW - 2 - W)); }
    private static int ph() { return Math.max(80, Math.min(MAX_H, by() + BTN - 6)); }
    /** Bottom edge level with the icon's. */
    private static int py() { return by() + BTN - ph(); }
    /** Just right of the chat box, level with its bottom (the last chat line, above the tab bar). */
    private static int bx() { return Math.min(screenW - 2 - BTN, ChatHudAdapter.chatWidth() + 12); }
    private static int by() { return screenH - TAB_BAR_OFFSET - BTN - 1 - LINE_H; }
    private static int bodyX() { return px() + RAIL; }
    private static int bodyY() { return py() + PAD + SEARCH_H + 4; }
    private static int bodyH() { return py() + ph() - FOOTER_H - bodyY(); }
    private static int gridX() { return bodyX() + PAD; }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
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

    // ------------------------------------------------------------------ layout

    private static List<Section> layout() {
        EmojiTable table = TelosData.EMOJIS.get();
        List<Section> out = new ArrayList<>();
        int y = 0;
        if (!query.isEmpty()) {
            y = add(out, SEARCH, "Search Results", search(table), false, y);
        } else {
            List<Emoji> favs = new ArrayList<>();
            for (String code : favorites) {
                Emoji e = table.byCode(code);
                if (e != null) favs.add(e);
            }
            y = add(out, FAV, "Favorites", favs, true, y);
            for (EmojiTable.Category c : table.categories()) y = add(out, c.name(), c.name(), c.emojis(), true, y);
        }
        contentH = y;
        return out;
    }

    private static int add(List<Section> out, String key, String title, List<Emoji> emojis, boolean collapsible, int y) {
        int rows = Math.max(1, (emojis.size() + COLS - 1) / COLS);
        double ex = collapsible ? expand(key).get() : 1;
        int h = (int) Math.round(rows * CELL * ex);
        out.add(new Section(key, title, emojis, collapsible, y, h));
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
        int i = (int) Math.floor((my - bodyY() - 2) / (RAIL - 2));
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

    // ------------------------------------------------------------------ render

    static void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, ChatModule chat, int width, int height) {
        screenW = width;
        screenH = height;
        if (!chat.showsEmojiButton()) {
            if (open) close(chat);
            return;
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
        BTN_HOVER.animateTo(inButton(mx, my) || open ? 1 : 0);
        double t = BTN_HOVER.get();
        int x = bx(), y = by();
        R.pushAlpha(0.55 + 0.45 * t);
        glyph(iconGlyph(), x + BTN / 2f, y + BTN / 2f, (float) (1 + 0.15 * t));
        R.popAlpha();
    }

    /** An emoji centred on (cx, cy). */
    private static void glyph(String g, float cx, float cy, float scale) {
        int w = R.textWidth(g);
        R.pushTransform(cx - w * scale / 2f, cy - 4f * scale, scale);
        R.text(g, 0, 0, GLYPH_TINT, Render2D.TextLook.NONE);
        R.popTransform();
    }

    private static void drawPicker(Theme p, int mx, int my, double t) {
        List<Section> secs = layout();
        if (scrollTarget > maxScroll()) scrollTo(maxScroll());
        boolean live = open && t > 0.98; // no hover effects while it fades
        int x = px(), y = py(), h = ph();
        int slide = (int) Math.round((1 - t) * 8);
        R.pushAlpha(t);
        R.pushTransform(0, slide, 1);
        try {
            R.shadow(x, y, W, h, RADIUS, p.shadow(), 8);
            R.roundedRect(x, y, W, h, RADIUS, p.border());
            R.roundedRect(x + 1, y + 1, W - 2, h - 2, RADIUS - 1, p.panel());

            drawSearch(p, mx, my, x + PAD, y + PAD, W - PAD * 2);
            drawRail(p, secs, live ? mx : -1, my, x, bodyY());
            Emoji hovered = drawGrid(p, secs, live ? mx : -1, my);
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
        R.roundedRect(x, y, w, SEARCH_H, 3, p.control());
        int ty = y + (SEARCH_H - 8) / 2;
        if (query.isEmpty()) {
            R.text("Find the perfect emoji", x + 5, ty, p.textMuted());
        } else {
            String shown = query;
            while (shown.length() > 1 && R.textWidth(shown) > w - 22) shown = shown.substring(1);
            R.text(shown, x + 5, ty, p.text());
            boolean caret = (System.currentTimeMillis() / Durations.CARET_BLINK) % 2 == 0;
            if (caret) R.rect(x + 5 + R.textWidth(shown) + 1, ty - 1, 1, 10, p.accent());
            boolean hov = in(mx, my, x + w - 13, y, 13, SEARCH_H);
            R.text("✕", x + w - 10, ty, hov ? p.text() : p.textMuted());
        }
    }

    private static void drawRail(Theme p, List<Section> secs, int mx, int my, int x, int top) {
        int bh = bodyH();
        R.rect(x + 1, top, RAIL - 1, bh, ColorUtil.fade(p.header(), 0.5));
        if (!query.isEmpty()) return;
        int current = currentSection(secs);
        int hovered = railAt(secs, mx, my);
        R.pushClip(x + 1, top, RAIL - 1, bh);
        for (int i = 0; i < secs.size(); i++) {
            int iy = top + 2 + i * (RAIL - 2);
            int ix = x + 2;
            int size = RAIL - 4;
            Animation a = HOVER.computeIfAbsent("rail/" + secs.get(i).key(), k -> new Animation(Durations.HOVER, Easing.OUT_CUBIC, 0));
            a.animateTo(i == hovered || i == current ? 1 : 0);
            if (a.get() > 0.01) {
                int bg = i == current ? p.surfaceActive() : p.surfaceHover();
                R.roundedRect(ix, iy, size, size, 4, ColorUtil.fade(bg, a.get()));
            }
            if (i == current) R.rect(x + 1, iy + 3, 2, size - 6, p.accent());
            Section s = secs.get(i);
            float cx = ix + size / 2f, cy = iy + size / 2f;
            if (s.key().equals(FAV)) {
                int c = i == current || i == hovered ? p.accent() : p.textMuted();
                R.text("★", Math.round(cx) - R.textWidth("★") / 2, Math.round(cy) - 4, c);
            } else if (!s.emojis().isEmpty()) {
                R.pushAlpha(0.6 + 0.4 * a.get());
                glyph(s.emojis().getFirst().glyph(), cx, cy, 1.25f);
                R.popAlpha();
            }
        }
        R.popClip();
    }

    private static Emoji drawGrid(Theme p, List<Section> secs, int mx, int my) {
        int bx = bodyX(), by = bodyY(), bw = W - RAIL, bh = bodyH(), gx = gridX();
        int scroll = (int) Math.round(SCROLL.get());
        Emoji hovered = emojiAt(secs, mx, my);
        Section hoveredSec = hovered == null ? null : sectionOf(secs, hovered, my);
        Section hoveredHeader = headerAt(secs, mx, my);
        R.pushClip(bx, by, bw, bh);
        for (Section s : secs) {
            int top = by + s.top() - scroll;
            if (top > by + bh || top + HEADER_H + s.height() < by) continue;
            // header: chevron, name, count
            boolean hh = s == hoveredHeader;
            String chevron = s.collapsible() ? (isExpanded(s.key()) ? "▾ " : "▸ ") : "";
            String name = (s.key().equals(FAV) ? "★ " : "") + s.title().toUpperCase(Locale.ROOT);
            R.text(chevron + name, gx, top + 3, hh ? p.text() : p.textMuted());
            R.textRight(String.valueOf(s.emojis().size()), gx + COLS * CELL - 2, top + 3, ColorUtil.fade(p.textMuted(), 0.7));
            if (s.height() <= 0) continue;
            int gridTop = top + HEADER_H;
            R.pushClip(gx - 1, gridTop, COLS * CELL + 2, s.height());
            if (s.emojis().isEmpty()) {
                String hint = s.key().equals(FAV) ? "Right-click an emoji to add it here" : "No emoji found";
                R.text(hint, gx + 2, gridTop + (CELL - 8) / 2, ColorUtil.fade(p.textMuted(), 0.8));
            }
            for (int i = 0; i < s.emojis().size(); i++) {
                int cx = gx + (i % COLS) * CELL, cy = gridTop + (i / COLS) * CELL;
                if (cy + CELL < by || cy > by + bh) continue;
                Emoji e = s.emojis().get(i);
                boolean hov = s == hoveredSec && e == hovered;
                Animation a = HOVER.computeIfAbsent(s.key() + "/" + e.code(), k -> new Animation(Durations.HOVER, Easing.OUT_CUBIC, 0));
                a.animateTo(hov ? 1 : 0);
                double ht = a.get();
                if (ht > 0.01) R.roundedRect(cx + 1, cy + 1, CELL - 2, CELL - 2, 4, ColorUtil.fade(p.surfaceHover(), ht));
                glyph(e.glyph(), cx + CELL / 2f, cy + CELL / 2f, GLYPH_SCALE * (float) (1 + 0.12 * ht));
            }
            R.popClip();
        }
        R.popClip();
        // scrollbar
        int max = maxScroll();
        if (max > 0) {
            int th = Math.max(14, bh * bh / Math.max(1, contentH));
            int ty = by + (int) Math.round((bh - th) * Math.clamp(SCROLL.get() / max, 0, 1));
            R.roundedRect(px() + W - SCROLLBAR - 2, ty, 2, th, 1, ColorUtil.fade(p.textMuted(), 0.45));
        }
        return hovered;
    }

    private static void drawFooter(Theme p, Emoji e, int x, int y) {
        R.rect(x + 1, y, W - 2, 1, p.border());
        if (e == null) {
            R.text("Click to type an emoji", x + PAD, y + 5, p.textMuted());
            R.text("Right-click: favorite · Shift: keep open", x + PAD, y + 15, ColorUtil.fade(p.textMuted(), 0.7));
            return;
        }
        glyph(e.glyph(), x + PAD + 9, y + FOOTER_H / 2f + 1, 2f);
        R.text(e.code(), x + PAD + 24, y + 5, p.text());
        String hint = favorites.contains(e.code()) ? "★ Favorite · Right-click to remove" : "Right-click to favorite";
        R.text(hint, x + PAD + 24, y + 15, ColorUtil.fade(p.textMuted(), 0.8));
    }

    // ------------------------------------------------------------------ input

    static boolean mouseClicked(ChatModule chat, double mx, double my, int button) {
        if (!chat.showsEmojiButton() || screenW == 0) return false;
        if (inButton(mx, my)) {
            if (button == 0) toggle(chat);
            return true;
        }
        if (!open) return false;
        if (!inPanel(mx, my)) {
            close(chat);
            return false; // the click still reaches the chat
        }
        int sx = px() + PAD, sw = W - PAD * 2;
        if (!query.isEmpty() && in(mx, my, sx + sw - 13, py() + PAD, 13, SEARCH_H)) {
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
        scrollTo(scrollTarget - scrollY * CELL * 1.5);
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
