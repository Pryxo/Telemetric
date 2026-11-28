package dev.telemetric.ui.profile;

import dev.telemetric.Telemetric;
import dev.telemetric.api.TelosApi;
import dev.telemetric.api.TelosApi.TelosException;
import dev.telemetric.data.TelosData;
import dev.telemetric.game.GameItem;
import dev.telemetric.game.ProfileAvatarAdapter;
import dev.telemetric.game.ServerGate;
import dev.telemetric.input.InputUtil;
import dev.telemetric.profile.ProfileData;
import dev.telemetric.profile.ProfileData.CharacterDetail;
import dev.telemetric.profile.ProfileData.CharacterSummary;
import dev.telemetric.profile.ProfileData.ClassInfo;
import dev.telemetric.profile.ProfileData.Item;
import dev.telemetric.profile.ProfileData.Player;
import dev.telemetric.profile.ProfileData.Profile;
import dev.telemetric.profile.ProfileData.StashPage;
import dev.telemetric.profile.ProfileItems;
import dev.telemetric.profile.ProfileItems.View;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.framework.Hit;
import dev.telemetric.ui.framework.MouseButton;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Fx;
import dev.telemetric.ui.render.Icons;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;
import dev.telemetric.ui.theme.ThemeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * {@code /profile <player>}: a Telos player's profile, laid out like Melinoe's viewer (player model sidebar, tabs for
 * Overview / Classes / Characters / Companions / Stash, a character detail view) in Telemetric's own look: theme
 * palette, the 2/4/6/8 spacing scale, one radius family, time-based animation.
 * <p>
 * Immediate-mode: each frame draws and records clickable / hoverable rects ({@link Hot}); input reads last frame's.
 * Extras over Melinoe: a player lookup field in the header with back history, an online dot (tab list), stash filter,
 * data age / stale notice, Fame by Class bars. See {@code docs/profile.md}.
 */
public final class ProfileScreen extends Screen {

    // layout tokens (GUI units, docs/ui-guidelines.md)
    private static final int PAD = 8, GAP = 4, HEADER_H = 20, TAB_H = 14, ROW_H = 12, CELL = 18, FIELD_W = 104, FIELD_H = 14;
    private static final int R_PANEL = 4, R_CELL = 3;
    private static final int SCROLL_STEP = 24;
    private static final int MAX_RECENT = 8;

    private static final Deque<String> RECENT = new ArrayDeque<>();

    private enum Tab {
        OVERVIEW("Overview", "Info"), CLASSES("Classes", "Classes"), CHARACTERS("Characters", "Chars"),
        COMPANIONS("Companions", "Pets"), STASH("Stash", "Stash");
        final String label, compact;
        Tab(String label, String compact) { this.label = label; this.compact = compact; }
    }

    private enum Sub {
        GEAR("Gear"), BACKPACK("Backpack"), SKILLS("Skill Tree");
        final String label;
        Sub(String label) { this.label = label; }
    }

    /** Which stash the Stash tab shows: normal and seasonal pages are never mixed. */
    private enum StashMode {
        NORMAL("Normal"), SEASONAL("Seasonal");
        final String label;
        StashMode(String label) { this.label = label; }
    }

    private enum Focus { NONE, LOOKUP, FILTER }

    /** A rect recorded while drawing: clickable ({@code click}) and/or hoverable (item tooltip, text tooltip). */
    private record Hot(int x, int y, int w, int h, Runnable click, GameItem item, String hint) {
        boolean contains(double mx, double my) { return Hit.inside(mx, my, x, y, w, h); }
    }

    private final Render2D r = new Render2D();
    private final Animation open = new Animation(Durations.SCREEN, Easing.OUT_CUBIC, 0);
    private final Animation contentIn = new Animation(Durations.STANDARD, Easing.OUT_CUBIC, 1);
    private final Animation tabX = new Animation(Durations.STANDARD, Easing.OUT_CUBIC, -1);
    private final Animation tabW = new Animation(Durations.STANDARD, Easing.OUT_CUBIC, 0);
    private final Animation subX = new Animation(Durations.STANDARD, Easing.OUT_CUBIC, -1);
    private final Animation subW = new Animation(Durations.STANDARD, Easing.OUT_CUBIC, 0);
    private final Animation stashX = new Animation(Durations.STANDARD, Easing.OUT_CUBIC, -1);
    private final Animation stashW = new Animation(Durations.STANDARD, Easing.OUT_CUBIC, 0);
    private final Map<String, Animation> hover = new HashMap<>();
    private final Map<String, Animation> scroll = new HashMap<>();
    private final Map<String, Double> scrollTarget = new HashMap<>();
    private final List<Hot> hots = new ArrayList<>();
    private final Deque<String> history = new ArrayDeque<>();

    private String query;
    private Profile profile;
    private TelosException error;
    private int request;
    /** When the current profile / character request started (for the "Telos is slow" hint). */
    private long loadStartedAt;

    private Tab tab = Tab.OVERVIEW;
    private CharacterSummary openChar;
    private CharacterDetail detail;
    private TelosException detailError;
    private Sub sub = Sub.GEAR;

    private Focus focus = Focus.NONE;
    private String lookup = "";
    private String filter = "";
    private StashMode stashMode = StashMode.NORMAL;
    private Set<String> online = Set.of();
    private long onlineCheckedAt;

    // per-frame layout
    private int px, py, pw, ph, sideW, mainX, mainW, viewY, viewH;
    private int clipX, clipY, clipW, clipH;
    private int mouseX, mouseY;
    private String scrollKey = "";
    /** {@link #hots} indices drawn inside the scrolled, clipped content: they only count where it is visible. */
    private int contentFrom, contentTo;
    private int contentH;

    public ProfileScreen(String player) {
        super(Component.literal("Profile"));
        open.animateTo(1);
        load(player, false);
        ProfileItems.setDefinitions(null, null); // views of an earlier screen may predate a server change
        TelosApi.definitions("items").thenAcceptBoth(TelosApi.definitions("item-traits"), (items, traits) ->
                Minecraft.getInstance().execute(() -> ProfileItems.setDefinitions(items, traits)));
    }

    /** Players looked up this session, newest first (for {@code /profile} suggestions). */
    public static List<String> recent() {
        synchronized (RECENT) {
            return new ArrayList<>(RECENT);
        }
    }

    // ------------------------------------------------------------------ loading

    private void load(String player, boolean fresh) {
        query = player;
        profile = null;
        error = null;
        closeCharacter();
        loadStartedAt = System.currentTimeMillis();
        int id = ++request;
        TelosApi.profile(player, fresh).whenComplete((a, t) -> Minecraft.getInstance().execute(() -> {
            if (id != request) return;
            if (t != null) {
                error = TelosApi.unwrap(t);
                return;
            }
            profile = ProfileData.profile(a.body(), a.fetchedAt(), a.stale());
            String name = profile.player().username().isEmpty() ? player : profile.player().username();
            synchronized (RECENT) {
                RECENT.removeIf(n -> n.equalsIgnoreCase(name));
                RECENT.addFirst(name);
                while (RECENT.size() > MAX_RECENT) RECENT.removeLast();
            }
            contentIn.snapTo(0).animateTo(1);
        }));
    }

    private void lookUp(String name) {
        if (name.isEmpty() || name.equalsIgnoreCase(currentName())) return;
        history.push(currentName());
        tab = Tab.OVERVIEW;
        load(name, false);
    }

    private void back() {
        if (openChar != null) {
            closeCharacter();
            contentIn.snapTo(0).animateTo(1);
        } else if (!history.isEmpty()) {
            tab = Tab.OVERVIEW;
            load(history.pop(), false);
        }
    }

    private void openCharacter(CharacterSummary c, boolean fresh) {
        openChar = c;
        detail = null;
        detailError = null;
        sub = Sub.GEAR;
        subX.snapTo(-1);
        contentIn.snapTo(0).animateTo(1);
        loadStartedAt = System.currentTimeMillis();
        int id = request;
        TelosApi.character(c.id(), fresh).whenComplete((a, t) -> Minecraft.getInstance().execute(() -> {
            if (id != request || openChar != c) return;
            if (t != null) detailError = TelosApi.unwrap(t);
            else detail = ProfileData.character(a.body(), a.fetchedAt());
        }));
    }

    private void closeCharacter() {
        openChar = null;
        detail = null;
        detailError = null;
    }

    private void retry() {
        TelosException shown = openChar != null ? detailError : error;
        if (shown != null && (!retryable(shown) || shown.secondsLeft() > 0)) return; // the server would answer the same
        if (openChar != null && (detailError != null || detail != null)) openCharacter(openChar, true);
        else load(query, true);
    }

    private String currentName() {
        return profile != null && !profile.player().username().isEmpty() ? profile.player().username() : query;
    }

    // ------------------------------------------------------------------ screen

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        ProfileAvatarAdapter.clear();
        super.removed();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        Theme p = Telemetric.get().theme().palette();
        graphics.fill(0, 0, width, height, ColorUtil.fade(p.backdrop(), 0.6 * open.get()));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        ThemeManager themes = Telemetric.get().theme();
        themes.update();
        Theme p = themes.palette();
        hots.clear();
        contentFrom = contentTo = 0;
        layout();
        refreshOnline();
        r.begin(graphics, themes.textShadow());
        try {
            r.pushAlpha(open.get());
            if (themes.shadows()) r.shadow(px, py, pw, ph, R_PANEL, p.shadow(), 6);
            r.roundedRect(px, py, pw, ph, R_PANEL, p.panel());
            r.outline(px, py, pw, ph, p.border());
            Fx.accentFill(r, themes, p, px + R_PANEL, py, pw - 2 * R_PANEL, 1, 0, 0, 1);
            header(p);
            if (profile == null) {
                status(p);
            } else {
                sidebar(graphics, themes, p);
                main(themes, p);
            }
            r.popAlpha();
            tooltip(p);
        } finally {
            r.end();
        }
    }

    private void layout() {
        pw = width < 360 ? width - 8 : Math.min(width - 24, 680);
        ph = height < 240 ? height - 8 : Math.min(height - 24, 400);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        sideW = Math.max(100, Math.min(140, pw / 4));
        mainX = px + sideW + 1 + PAD;
        mainW = px + pw - PAD - mainX;
        viewY = py + HEADER_H + PAD + TAB_H + GAP + 2;
        viewH = py + ph - PAD - viewY;
    }

    private void refreshOnline() {
        long now = System.currentTimeMillis();
        if (now - onlineCheckedAt < 2000) return;
        onlineCheckedAt = now;
        Set<String> s = new LinkedHashSet<>();
        for (String n : ProfileAvatarAdapter.onlineNames()) s.add(n.toLowerCase(Locale.ROOT));
        online = s;
    }

    // ------------------------------------------------------------------ header

    private void header(Theme p) {
        int y = py + (HEADER_H - 8) / 2 + 1;
        int x = px + PAD;
        boolean canBack = openChar != null || !history.isEmpty();
        if (canBack) {
            int bx = x - 2, by = py + (HEADER_H - 12) / 2;
            double h = hoverAnim("hdr-back", Hit.inside(mouseX, mouseY, bx, by, 12, 12));
            r.icon("chevron-left", bx + 6, by + 6, Icons.SIZE, ColorUtil.lerp(p.textMuted(), p.text(), h));
            hots.add(new Hot(bx, by, 12, 12, this::back, null, openChar != null ? "Back to characters" : "Back to " + history.peek()));
            x += 12;
        }
        r.text("Profile", x, y, p.textMuted());
        x += r.textWidth("Profile ");
        if (openChar != null) r.text("· " + ProfileData.pretty(openChar.type()), x, y, p.textDisabled());

        // close, refresh, lookup field (right to left)
        int bx = px + pw - PAD - 12 + 2, by = py + (HEADER_H - 12) / 2;
        iconButton("hdr-close", "x", bx, by, p, this::onClose, "Close");
        bx -= 12 + GAP;
        iconButton("hdr-refresh", "rotate-ccw", bx, by, p, this::retry, "Refresh");
        int fx = bx - GAP - FIELD_W, fy = py + (HEADER_H - FIELD_H) / 2;
        field(p, fx, fy, FIELD_W, lookup, "Look up player", focus == Focus.LOOKUP);
        hots.add(new Hot(fx, fy, FIELD_W, FIELD_H, () -> focus = Focus.LOOKUP, null, null));
        r.rect(px + 1, py + HEADER_H, pw - 2, 1, p.border());
    }

    private void iconButton(String key, String icon, int x, int y, Theme p, Runnable click, String hint) {
        boolean over = Hit.inside(mouseX, mouseY, x, y, 12, 12);
        double h = hoverAnim(key, over);
        r.icon(icon, x + 6, y + 6, Icons.SIZE, ColorUtil.lerp(p.textMuted(), p.text(), h));
        hots.add(new Hot(x, y, 12, 12, click, null, hint));
    }

    private void field(Theme p, int x, int y, int w, String value, String placeholder, boolean focused) {
        double h = hoverAnim("field-" + placeholder, focused || Hit.inside(mouseX, mouseY, x, y, w, FIELD_H));
        r.roundedRect(x, y, w, FIELD_H, R_CELL, p.control());
        r.outline(x, y, w, FIELD_H, ColorUtil.lerp(p.border(), p.accent(), focused ? 1 : h * 0.5));
        Icons.magnifier(r, x + 8, y + FIELD_H / 2, p.textMuted(), p.control());
        int tx = x + 16, ty = y + (FIELD_H - 8) / 2;
        if (value.isEmpty() && !focused) {
            r.text(r.trim(placeholder, w - 20), tx, ty, p.textDisabled());
        } else {
            String shown = value;
            while (r.textWidth(shown) > w - 22 && !shown.isEmpty()) shown = shown.substring(1);
            r.text(shown, tx, ty, p.text());
            int period = Math.max(1, Durations.CARET_BLINK * 2);
            if (focused && System.currentTimeMillis() % period < Durations.CARET_BLINK) {
                r.rect(tx + r.textWidth(shown) + 1, ty - 1, 1, 10, p.accent());
            }
        }
    }

    // ------------------------------------------------------------------ loading / error

    private void status(Theme p) {
        int cx = px + pw / 2, cy = py + HEADER_H + (ph - HEADER_H) / 2;
        if (error == null) {
            long t = System.currentTimeMillis();
            for (int i = 0; i < 3; i++) {
                double phase = ((t / (double) Durations.loop(Durations.AMBIENT_LOOP)) - i * 0.15) % 1.0;
                double a = 0.35 + 0.65 * Math.max(0, Math.sin(Math.PI * phase));
                r.circle(cx - 8 + i * 8, cy - 10, 2, ColorUtil.fade(p.accent(), a));
            }
            r.textCentered("Loading " + r.trim(query, 160) + "…", cx, cy, p.textMuted());
            if (slow()) r.textCentered("Telos is still sending this player — hang on", cx, cy + 12, p.textDisabled());
            return;
        }
        List<String> lines = r.wrap(message(error), Math.min(280, pw - 2 * PAD));
        int y = cy - 6 - lines.size() * 10;
        Icons.warning(r, cx - 5, y - 14, 10, 10, p.textMuted());
        for (String l : lines) {
            r.textCentered(l, cx, y, p.text());
            y += 10;
        }
        if (retryable(error)) {
            int left = error.secondsLeft();
            String label = left > 0 ? "Try again in " + left + " s" : "Retry";
            int bw = Math.max(56, r.textWidth(label) + 16), bh = 16, bx = cx - bw / 2, by = y + 6;
            double h = left > 0 ? 0 : hoverAnim("retry", Hit.inside(mouseX, mouseY, bx, by, bw, bh));
            r.roundedRect(bx, by, bw, bh, R_CELL, ColorUtil.lerp(p.surface(), p.surfaceHover(), h));
            r.outline(bx, by, bw, bh, ColorUtil.lerp(p.border(), p.accent(), h));
            r.textCentered(label, cx, by + (bh - 8) / 2, left > 0 ? p.textDisabled() : p.text());
            if (left == 0) {
                hots.add(new Hot(bx, by, bw, bh, this::retry, null, null));
                r.textCentered("or press R", cx, by + bh + 6, p.textDisabled());
            }
        }
    }

    private String message(TelosException e) {
        return switch (e.error) {
            case "not_found" -> "Player \"" + query + "\" was not found.";
            case "invalid_player", "invalid_character", "invalid_kind" -> "Usage: /profile <username>";
            case "rate_limited" -> "Too many lookups — wait a moment.";
            default -> e.getMessage();
        };
    }

    private static boolean retryable(TelosException e) {
        return !e.error.equals("not_found") && !e.error.startsWith("invalid") && !e.error.equals("telos_disabled")
                && !e.error.equals("telos_maintenance");
    }

    /** The current request is taking a while: Telos is slow (the server waits up to 15 s, then answers 202 and we repeat). */
    private boolean slow() {
        return System.currentTimeMillis() - loadStartedAt > 4_000L;
    }

    // ------------------------------------------------------------------ sidebar

    private void sidebar(GuiGraphicsExtractor graphics, ThemeManager themes, Theme p) {
        Player pl = profile.player();
        int x = px + PAD, w = sideW - PAD, y = py + HEADER_H + PAD;
        r.rect(px + sideW, py + HEADER_H + 1, 1, ph - HEADER_H - 2, p.border());

        String name = r.trim(pl.username().isEmpty() ? query : pl.username(), w);
        int nw = r.textWidth(name);
        r.text(name, x + (w - nw) / 2, y, Fx.flow(themes, p, x + (w - nw) / 2, y));
        y += 11;
        boolean on = online.contains(currentName().toLowerCase(Locale.ROOT));
        String seen = on ? "Online" : pl.lastPlayed() > 0 ? "Seen " + ago(pl.lastPlayed()) : "";
        if (!seen.isEmpty()) {
            int sw = r.textWidth(seen) + (on ? 6 : 0);
            int sx = x + (w - sw) / 2;
            if (on) {
                r.circle(sx + 2, y + 3.5, 2, ProfileColors.ONLINE);
                sx += 6;
            }
            r.text(seen, sx, y, p.textMuted());
        }
        y += 10 + GAP;

        int footer = 2 * 10 + GAP;
        int stageH = py + ph - PAD - footer - y;
        r.gradientV(x, y, w, stageH, p.surface(), ColorUtil.fade(p.surface(), 0.25));
        r.outline(x, y, w, stageH, p.border());
        int floorY = y + stageH - stageH / 7;
        r.roundedRect(x + w / 4, floorY - 1, w / 2, 3, 1, ColorUtil.fade(p.accent(), 0.25));
        // the model is a picture-in-picture: it ignores our alpha, so it only appears once the screen has faded in
        if (open.get() > 0.9) {
            int size = Math.max(20, Math.min(w * 2 / 5, (int) (stageH * 0.36)));
            boolean drawn = ProfileAvatarAdapter.draw(graphics, pl.id(), currentName(), x + 2, y + 2, x + w - 2,
                    floorY, size, mouseX, mouseY);
            if (!drawn) r.textCentered("…", x + w / 2, y + stageH / 2 - 4, p.textDisabled());
        }
        int fy = py + ph - PAD - footer + GAP;
        String updated = "Updated " + ago(profile.fetchedAt());
        r.textCentered(r.trim(updated, w), x + w / 2, fy, p.textDisabled());
        if (profile.stale()) {
            String s = "May be out of date";
            r.textCentered(r.trim(s, w), x + w / 2, fy + 10, p.textMuted());
            hots.add(new Hot(x, fy + 8, w, 12, null, null, "Telos did not answer; this is the last good copy"));
        }
    }

    // ------------------------------------------------------------------ main pane

    private void main(ThemeManager themes, Theme p) {
        int ty = py + HEADER_H + PAD;
        if (openChar != null) {
            pills(themes, p, Sub.values(), sub, ty, subX, subW, s -> {
                if (sub != s) {
                    sub = s;
                    contentIn.snapTo(0).animateTo(1);
                }
            }, s -> s.label);
        } else {
            int full = 0;
            for (Tab t : Tab.values()) full += r.textWidth(t.label) + 12 + GAP;
            boolean narrow = full - GAP > mainW; // small windows: short labels so the row never overflows
            pills(themes, p, Tab.values(), tab, ty, tabX, tabW, this::selectTab, t -> narrow ? t.compact : t.label);
        }

        scrollKey = openChar != null ? "char-" + openChar.id() + "-" + sub : tab.name();
        Animation s = scroll.computeIfAbsent(scrollKey, k -> new Animation(Durations.STANDARD, Easing.OUT_CUBIC, 0));
        double t = contentIn.get();
        int slide = (int) Math.round((1 - t) * 4);
        clipX = mainX - 2;
        clipY = viewY;
        clipW = mainW + 4;
        clipH = viewH;
        r.pushClip(clipX, clipY, clipW, clipH);
        r.pushAlpha(t);
        int y0 = viewY - (int) Math.round(s.get()) + slide;
        contentFrom = hots.size();
        int h;
        if (openChar != null) h = characterDetail(themes, p, y0);
        else h = switch (tab) {
            case OVERVIEW -> overview(themes, p, y0);
            case CLASSES -> classes(themes, p, y0);
            case CHARACTERS -> characters(p, y0);
            case COMPANIONS -> companions(p, y0);
            case STASH -> stash(p, y0);
        };
        contentTo = hots.size();
        r.popAlpha();
        r.popClip();
        contentH = h;
        clampScroll();
        scrollbar(p, s.get());
    }

    private void selectTab(Tab t) {
        if (tab == t) return;
        tab = t;
        contentIn.snapTo(0).animateTo(1);
        if (focus == Focus.FILTER) focus = Focus.NONE;
    }

    private interface Label<T> { String of(T t); }
    private interface Pick<T> { void pick(T t); }

    private <T> void pills(ThemeManager themes, Theme p, T[] items, T current, int y, Animation ax, Animation aw,
                           Pick<T> pick, Label<T> label) {
        pills(p, items, current, mainX, y, ax, aw, pick, label);
    }

    private <T> int pillsWidth(T[] items, Label<T> label) {
        int w = 0;
        for (T it : items) w += r.textWidth(label.of(it)) + 12 + GAP;
        return Math.max(0, w - GAP);
    }

    private <T> void pills(Theme p, T[] items, T current, int x0, int y, Animation ax, Animation aw,
                           Pick<T> pick, Label<T> label) {
        int x = x0;
        for (T it : items) {
            String l = label.of(it);
            int w = r.textWidth(l) + 12;
            if (it == current) {
                if (ax.getTarget() < 0) { ax.snapTo(x); aw.snapTo(w); }
                ax.animateTo(x);
                aw.animateTo(w);
            }
            x += w + GAP;
        }
        // soft accent tint + underline: the label stays light text (a solid accent fill turned it black on light accents)
        int sx = (int) Math.round(ax.get()), sw = (int) Math.round(aw.get());
        r.roundedRect(sx, y, sw, TAB_H, R_CELL, ColorUtil.fade(p.accent(), 0.22));
        r.rect(sx + 2, y + TAB_H - 1, Math.max(0, sw - 4), 1, p.accent());
        x = x0;
        for (T it : items) {
            String l = label.of(it);
            int w = r.textWidth(l) + 12;
            boolean over = Hit.inside(mouseX, mouseY, x, y, w, TAB_H);
            double h = hoverAnim("pill-" + l, over && it != current);
            if (it != current && h > 0.01) r.roundedRect(x, y, w, TAB_H, R_CELL, ColorUtil.fade(p.surfaceHover(), h));
            int color = it == current ? p.text() : ColorUtil.lerp(p.textMuted(), p.text(), h);
            r.text(l, x + 6, y + (TAB_H - 8) / 2, color);
            hots.add(new Hot(x, y, w, TAB_H, () -> pick.pick(it), null, null));
            x += w + GAP;
        }
    }

    private void scrollbar(Theme p, double offset) {
        if (contentH <= viewH) return;
        int trackX = px + pw - 4;
        int thumbH = Math.max(12, viewH * viewH / contentH);
        int thumbY = viewY + (int) Math.round((viewH - thumbH) * (offset / (contentH - viewH)));
        r.roundedRect(trackX, thumbY, 2, thumbH, 1, ColorUtil.fade(p.textMuted(), 0.5));
    }

    private void clampScroll() {
        double max = Math.max(0, contentH - viewH);
        double target = scrollTarget.getOrDefault(scrollKey, 0.0);
        if (target > max || target < 0) {
            target = Math.max(0, Math.min(max, target));
            scrollTarget.put(scrollKey, target);
            scroll.get(scrollKey).animateTo(target);
        }
    }

    // ------------------------------------------------------------------ overview

    private int overview(ThemeManager themes, Theme p, int y0) {
        Player pl = profile.player();
        int y = y0, x = mainX, w = mainW;
        int maxed = 0;
        for (ClassInfo c : pl.classes()) if (c.transcendence() >= 10) maxed++;
        // {label, value, pack icon model (or the ❖ glyph for Glory, like /balance)}
        String[][] cards = {
                {"Playtime", hours(pl.playTime()), "icon/misc/questicon"},
                {"Glory", compact(pl.glory()), GLORY_GLYPH},
                {"Seasonal Glory", compact(pl.seasonalGlory()), GLORY_GLYPH},
                {"Total Fame", compact(pl.totalFame()), "material/misc/battlepass"},
                {"Deaths", group(pl.deaths()), "icon/misc/dead"},
                {"Transcended", maxed + " / " + pl.classes().size(), masteryIcon(maxed, pl.classes().size())},
        };
        int[] colors = {p.accentAlt(), ProfileColors.GLORY, ProfileColors.SEASONAL, p.accent(), p.textMuted(), p.accentAlt()};
        int cols = w >= 300 ? 3 : 2, cw = (w - (cols - 1) * GAP) / cols, chh = 30;
        for (int i = 0; i < cards.length; i++) {
            int cx = x + (i % cols) * (cw + GAP), cy = y + (i / cols) * (chh + GAP);
            r.roundedRect(cx, cy, cw, chh, R_CELL, p.surface());
            r.rect(cx + R_CELL, cy, cw - 2 * R_CELL, 1, ColorUtil.fade(colors[i], 0.8));
            int tx = cx + 6;
            r.text(r.trim(cards[i][0], cw - 36), tx, cy + 5, p.textMuted());
            if (cards[i][2].equals(GLORY_GLYPH)) {
                // the /balance look: ❖ (white = Glory, gold = Seasonal) in front of the aqua value
                r.text(GLORY_GLYPH, tx, cy + 17, colors[i]);
                int vx = tx + r.textWidth(GLORY_GLYPH) + 1;
                r.text(r.trim(cards[i][1], cw - 12 - (vx - tx)), vx, cy + 17, ProfileColors.GLORY_VALUE);
                continue;
            }
            packIcon(cards[i][2], cx + cw - 6 - 18, cy + (chh - 18) / 2, 18);
            r.text(r.trim(cards[i][1], cw - 36), tx, cy + 17, p.text());
        }
        y += ((cards.length + cols - 1) / cols) * (chh + GAP) + GAP;

        // details
        r.text("Details", x, y, p.textMuted());
        y += 10 + 2;
        int filled = 0;
        for (CharacterSummary c : profile.characters()) if (c.deathTime() <= 0) filled++;
        CharacterSummary active = activeCharacter();
        y = detailRow(p, x, y, w, null, "Last Seen", pl.lastPlayed() > 0 ? ago(pl.lastPlayed()) : "—", p.text());
        if (active != null) {
            y = detailRow(p, x, y, w, classIcon(active.type(), false), "Playing",
                    ProfileData.pretty(active.type()) + " · Lv " + ProfileData.level(active.fame()), p.text());
        }
        y = detailRow(p, x, y, w, "material/misc/mega_seasonpass", "Season Pass",
                (pl.premiumPass() ? "Premium" : "Free") + " · " + compact(pl.passXp()) + " XP", p.text());
        y = detailRow(p, x, y, w, "material/misc/wiki_pets", "Companions", pl.companions().unlocked().size() + " unlocked", p.text());
        y = detailRow(p, x, y, w, "material/misc/stash_slot", "Stash", group(pl.stashItems()) + " items · " + pl.stash().size() + " pages", p.text());
        y = detailRow(p, x, y, w, "material/misc/character_slot", "Character Slots", filled + " / " + pl.characterSlots(), p.text());
        y = detailRow(p, x, y, w, "material/misc/soulpoint1", "Soul Points", group(pl.soulPoints()), p.text());
        View sticker = pl.sticker() == null ? null : ProfileItems.ofKey(pl.sticker());
        y = detailRow(p, x, y, w, sticker == null ? null : "material/sticker/" + ProfileData.stripNs(pl.sticker()), "Sticker",
                sticker == null ? "None" : sticker.name(), p.text());
        y += GAP * 2;

        // fame by class
        List<ClassInfo> byFame = new ArrayList<>(pl.classes());
        byFame.sort(Comparator.comparingLong(ClassInfo::totalFame).reversed());
        if (!byFame.isEmpty() && byFame.getFirst().totalFame() > 0) {
            r.text("Fame by Class", x, y, p.textMuted());
            y += 10 + 2;
            long top = byFame.getFirst().totalFame();
            int labelW = 0;
            for (ClassInfo c : byFame) labelW = Math.max(labelW, r.textWidth(ProfileData.pretty(c.type())));
            int lx = x + (ServerGate.onTelos() ? 14 : 0);
            int valueW = r.textWidth("000.0M");
            int barX = lx + labelW + 6, barW = x + w - valueW - 6 - barX;
            for (ClassInfo c : byFame) {
                packIcon(classIcon(c.type(), c.transcendence() >= 10), x, y, 12);
                r.text(ProfileData.pretty(c.type()), lx, y + 2, p.textMuted());
                r.roundedRect(barX, y + 4, barW, 4, 2, p.control());
                int fw = (int) Math.round(barW * (c.totalFame() / (double) top));
                if (fw > 0) Fx.accentFill(r, themes, p, barX, y + 4, Math.max(4, fw), 4, 2, Render2D.CORNERS_ALL, 1);
                r.textRight(compact(c.totalFame()), x + w, y + 2, p.text());
                y += ROW_H + 2;
            }
        }
        return y - y0;
    }

    /** Telos's Glory sign, as {@code /balance} prints it. */
    private static final String GLORY_GLYPH = "❖";

    /** A detail row with an optional 12 px pack icon (an item model path under {@code telos:}) before the label. */
    private int detailRow(Theme p, int x, int y, int w, String icon, String label, String value, int valueColor) {
        int lx = x;
        if (packIcon(icon, x, y, 12)) lx += 14;
        r.text(label, lx, y + 2, p.textMuted());
        r.textRight(r.trim(value, w - (lx - x) - r.textWidth(label) - 12), x + w, y + 2, valueColor);
        r.rect(x, y + ROW_H - 1, w, 1, ColorUtil.fade(p.border(), 0.5));
        return y + ROW_H;
    }

    private int detailRow(Theme p, int x, int y, int w, String label, String value, int valueColor) {
        return detailRow(p, x, y, w, null, label, value, valueColor);
    }

    /**
     * Draws a server resource pack item model ({@code telos:<model>}) as a {@code size} px icon. Only on Telos (elsewhere
     * the model is missing and would draw as the purple/black cube); returns whether it was drawn.
     */
    private boolean packIcon(String model, int x, int y, int size) {
        if (model == null || !ServerGate.onTelos()) return false;
        if (model.startsWith(TEX + CLASS_SYMBOL)) {
            // the class symbols only fill the left/middle of their 64 px glyph canvas (x 0–35, y 20–49): draw that
            // square so the symbol fills the icon
            r.texture("telos:textures/" + model.substring(TEX.length()) + ".png", x, y, size, size, 0, 16, 36, 36, 64, 64, 0xFFFFFFFF);
            return true;
        }
        if (model.startsWith(TEX)) {
            // a plain pack texture (telos:textures/<path>.png), drawn whole
            r.texture("telos:textures/" + model.substring(TEX.length()) + ".png", x, y, size, size, size, size);
            return true;
        }
        r.itemModel(model.contains(":") ? model : "telos:" + model, x, y, size);
        return true;
    }

    /** {@link #packIcon} prefix for a pack texture instead of an item model. */
    private static final String TEX = "tex:";

    /**
     * The mastery banner of the pack ({@code icon/mastery/0…6}) for {@code transcended} of {@code total} classes; the
     * glowing one once every class is transcended.
     */
    private static String masteryIcon(int transcended, int total) {
        int n = Math.clamp(transcended, 0, 6);
        return TEX + "icon/mastery/" + (total > 0 && transcended >= total ? "glowing_" : "") + n;
    }

    /**
     * The ruleset icon of the pack ({@code icon/ruleset/normal|seasonal|hardcore_ironman}, group characters
     * {@code group_type/<colour>} when the group is a known colour) — the same icons as the Tab "Class" mode glyph.
     */
    private static String modeIcon(String ruleset, String group) {
        String mode = ProfileData.mode(ruleset, group);
        String base = TEX + "icon/ruleset/";
        return switch (mode) {
            case "Seasonal" -> base + "seasonal";
            case "Ironman" -> base + "hardcore_ironman";
            case "Group Ironman" -> {
                String g = group == null ? "" : group.toLowerCase(Locale.ROOT).replace(' ', '_');
                yield GROUP_COLOURS.contains(g) ? base + "group_type/" + g : base + "hardcore_ironman";
            }
            default -> base + "normal";
        };
    }

    private static final java.util.Set<String> GROUP_COLOURS = java.util.Set.of("black", "blue", "brown", "gray",
            "green", "light_blue", "light_brown", "light_green", "light_orange", "light_pink", "light_purple",
            "light_red", "light_yellow", "orange", "pink", "purple", "red", "yellow");

    /**
     * The class symbol of the pack's tooltip glyphs ({@code glyph/tooltip/symbol/huntress0}). {@code mastered} is kept
     * for callers; the pack has one symbol per class.
     */
    private static String classIcon(String type, boolean mastered) {
        if (type == null || type.isEmpty()) return null;
        return TEX + CLASS_SYMBOL + type.toLowerCase(Locale.ROOT) + "0";
    }

    private static final String CLASS_SYMBOL = "glyph/tooltip/symbol/";

    private ClassInfo classOf(String type) {
        for (ClassInfo ci : profile.player().classes()) if (ci.type().equalsIgnoreCase(type)) return ci;
        return null;
    }

    private CharacterSummary activeCharacter() {
        String id = profile.player().activeCharacter();
        if (id == null) return null;
        for (CharacterSummary c : profile.characters()) if (id.equalsIgnoreCase(c.id())) return c;
        return null;
    }

    // ------------------------------------------------------------------ classes (transcendence)

    private int classes(ThemeManager themes, Theme p, int y0) {
        Player pl = profile.player();
        int x = mainX, w = mainW, y = y0;
        packIcon("material/misc/soulpoint1", x, y - 2, 12);
        int lx = x + (ServerGate.onTelos() ? 14 : 0);
        r.text("Soul Points", lx, y, p.textMuted());
        r.textRight(group(pl.soulPoints()), x + w, y, p.text());
        y += 10 + GAP;
        if (pl.classes().isEmpty()) {
            r.textCentered("No class data", x + w / 2, y + 20, p.textDisabled());
            return y + 40 - y0;
        }
        List<ClassInfo> list = new ArrayList<>(pl.classes());
        list.sort(Comparator.comparingLong(ClassInfo::totalFame).reversed());
        int cols = w >= 330 ? 3 : 2, cw = (w - (cols - 1) * GAP) / cols;
        int treeH = 10 + 2 + skillTreeHeight(MINI_NODE, MINI_GAP) + 3 + 9;
        int ch = 6 + 16 + 4 + 3 + 4 + 5 * 10 + 6 + treeH + 6;
        for (int i = 0; i < list.size(); i++) {
            ClassInfo c = list.get(i);
            int cx = x + (i % cols) * (cw + GAP), cy = y + (i / cols) * (ch + GAP);
            boolean max = c.transcendence() >= 10;
            r.roundedRect(cx, cy, cw, ch, R_CELL, p.surface());
            if (max) r.outline(cx, cy, cw, ch, ColorUtil.fade(p.accent(), 0.5));
            int ix = cx + 6, iw = cw - 12, iy = cy + 6;
            int nx = ix + (packIcon(classIcon(c.type(), max), ix, iy, 16) ? 20 : 0);
            r.text(ProfileData.pretty(c.type()), nx, iy + 4, p.text());
            String badge = "T" + c.transcendence();
            int bw = r.textWidth(badge) + 6;
            if (max) {
                r.roundedRect(ix + iw - bw, iy + 3, bw, 10, 2, ColorUtil.fade(p.accent(), 0.3));
                r.text(badge, ix + iw - bw + 3, iy + 4, p.text());
            } else {
                r.roundedRect(ix + iw - bw, iy + 3, bw, 10, 2, p.control());
                r.text(badge, ix + iw - bw + 3, iy + 4, p.textMuted());
            }
            hots.add(new Hot(ix + iw - bw, iy + 3, bw, 10, null, null, "Transcendence " + c.transcendence() + " / 10"));
            iy += 16 + 4;
            r.roundedRect(ix, iy, iw, 3, 1, p.control());
            int fw = (int) Math.round(iw * Math.min(1, c.transcendence() / 10.0));
            if (fw > 0) Fx.accentFill(r, themes, p, ix, iy, Math.max(2, fw), 3, 1, Render2D.CORNERS_ALL, 1);
            iy += 3 + 4;
            // {label, value, pack icon} — the same icons as the Overview cards / detail rows
            String[][] rows = {
                    {"Soul Points", compact(c.soulPoints()), "material/misc/soulpoint1"},
                    {"Highest Fame", compact(c.maxFame()), "material/misc/battlepass"},
                    {"Total Fame", compact(c.totalFame()), "material/misc/battlepass"},
                    {"Playtime", hours(c.playTime()), "icon/misc/questicon"},
                    {"Deaths", group(c.deaths()), "icon/misc/dead"},
            };
            for (String[] row : rows) {
                int rx = ix + (packIcon(row[2], ix, iy - 1, 9) ? 11 : 0);
                r.text(r.trim(row[0], iw / 2 + 10 - (rx - ix)), rx, iy, p.textMuted());
                r.textRight(row[1], ix + iw, iy, p.text());
                iy += 10;
            }
            iy += 6;
            r.rect(ix, iy - 3, iw, 1, ColorUtil.fade(p.border(), 0.5));
            r.text("Skill Tree", ix, iy, p.textMuted());
            iy += 10 + 2;
            skillTrees(themes, p, c, ix, iy, iw, MINI_NODE, MINI_GAP, false);
        }
        y += ((list.size() + cols - 1) / cols) * (ch + GAP);
        return y - y0;
    }

    // ------------------------------------------------------------------ skill tree

    /**
     * Tier sizes of a Telos skill tree from the bottom (root) up, as built in game: each tier is a row of a 3-column
     * grid, 2-node tiers use columns 0 and 2 (drawn a third of a column in). Checked against the in-world tree.
     */
    private static final int[] TREE_TIERS = {1, 2, 2, 3, 2, 3};
    private static final int MINI_NODE = 6, MINI_GAP = 3, BIG_NODE = 16, BIG_GAP = 8,
            SIDE_NODE = 10, SIDE_GAP = 5;
    /** The mystery fragment item model, the icon of the character detail's Fragments row. */
    private static final String FRAGMENT_ICON = "material/misc/fragment";
    /** Known path names per class (path A, path B); others show "Path A" / "Path B". */
    private static final Map<String, String[]> PATH_NAMES = Map.of(
            "HUNTRESS", new String[]{"Traps", "Bombs"},
            "ARCANIST", new String[]{"Jewels", "Orbs"},
            "ASSASSIN", new String[]{"Poisons", "Cloaks"},
            "NECROMANCER", new String[]{"Scriptures", "Skulls"},
            "KNIGHT", new String[]{"Shields", "Emblems"},
            "SAMURAI", new String[]{"Kunais", "Stars"});

    private static int skillTreeHeight(int node, int gap) {
        return TREE_TIERS.length * node + (TREE_TIERS.length - 1) * gap;
    }

    private static String pathName(String type, int path) {
        String[] names = type == null ? null : PATH_NAMES.get(type.toUpperCase(Locale.ROOT));
        return names != null && path < names.length ? names[path] : "Path " + (char) ('A' + path);
    }

    /** Both paths of a class's tree side by side, the chosen one highlighted. Returns the height used. */
    private int skillTrees(ThemeManager themes, Theme p, ClassInfo c, int x, int y, int w, int node, int gap, boolean big) {
        int treeW = 3 * node + 2 * gap;
        int labelH = big ? 12 : 0;
        int half = w / 2;
        for (int path = 0; path < 2; path++) {
            List<Integer> picks = path < c.skillTree().size() ? c.skillTree().get(path) : List.of();
            int px0 = x + path * half;
            int tx = px0 + (half - treeW) / 2;
            String name = pathName(c.type(), path);
            int picked = 0;
            for (Integer pk : picks) if (pk != null) picked++;
            if (big) {
                r.textCentered(name, px0 + half / 2, y, picks.isEmpty() ? p.textDisabled() : p.text());
                String sub = picks.isEmpty() ? "Not used" : picked + " / " + TREE_TIERS.length + " tiers";
                r.textCentered(sub, px0 + half / 2, y + skillTreeHeight(node, gap) + labelH + 6, p.textDisabled());
            }
            skillTree(themes, p, picks, tx, y + labelH, node, gap, name, big);
            if (!big) {
                r.textCentered(r.trim(name, half - 4), px0 + half / 2, y + skillTreeHeight(node, gap) + 3,
                        picks.isEmpty() ? p.textDisabled() : p.textMuted());
                hots.add(new Hot(tx, y, treeW, skillTreeHeight(node, gap), null, null,
                        name + (picks.isEmpty() ? " · not used" : " · " + picked + " / " + TREE_TIERS.length + " tiers")));
            }
        }
        return labelH + skillTreeHeight(node, gap) + (big ? 6 + 10 : 3 + 9);
    }

    /** One path: root at the bottom, picked nodes filled and linked, the rest outlined (dimmed if the path is unused). */
    private void skillTree(ThemeManager themes, Theme p, List<Integer> picks, int x, int y, int node, int gap, String path, boolean big) {
        boolean used = !picks.isEmpty();
        int bottom = y + skillTreeHeight(node, gap) - node;
        int prevX = -1, prevY = -1;
        int line = Math.max(1, node / 6);
        // links first, so nodes cover their ends
        for (int t = 0; t < TREE_TIERS.length; t++) {
            Integer pick = t < picks.size() ? picks.get(t) : null;
            if (pick == null) { prevX = -1; continue; }
            int nx = nodeX(x, node, gap, t, pick) + node / 2, ny = bottom - t * (node + gap) + node / 2;
            if (prevX >= 0) link(p, prevX, prevY, nx, ny, line);
            prevX = nx;
            prevY = ny;
        }
        for (int t = 0; t < TREE_TIERS.length; t++) {
            Integer pick = t < picks.size() ? picks.get(t) : null;
            int ny = bottom - t * (node + gap);
            for (int col : tierColumns(t)) {
                int nx = nodeX(x, node, gap, t, col);
                boolean sel = pick != null && pick == col;
                int radius = Math.max(1, node / 4);
                if (sel) {
                    Fx.accentFill(r, themes, p, nx, ny, node, node, radius, Render2D.CORNERS_ALL, 1);
                } else {
                    r.roundedRect(nx, ny, node, node, radius, used ? p.control() : ColorUtil.fade(p.control(), 0.5));
                    r.outline(nx, ny, node, node, used ? p.border() : ColorUtil.fade(p.border(), 0.5));
                }
                if (big) {
                    String side = TREE_TIERS[t] == 1 ? "" : col == 0 ? " · Left" : col == 2 ? " · Right" : " · Middle";
                    hots.add(new Hot(nx, ny, node, node, null, null,
                            path + " · Tier " + (t + 1) + side + (sel ? " · picked" : "")));
                }
            }
        }
    }

    /** Grid columns (0..2) that tier {@code t} has nodes in. */
    private static int[] tierColumns(int t) {
        return switch (TREE_TIERS[t]) {
            case 1 -> new int[]{1};
            case 2 -> new int[]{0, 2};
            default -> new int[]{0, 1, 2};
        };
    }

    /** Left edge of a node: 2-node tiers sit a third of a column in (like in game), so columns 0 / 2 move inwards. */
    private static int nodeX(int x, int node, int gap, int tier, int col) {
        int step = node + gap;
        int base = x + col * step;
        if (TREE_TIERS[tier] == 2) base += (col == 0 ? 1 : -1) * step / 3;
        return base;
    }

    /** A {@code w}-thick line between two node centres, as axis-aligned steps (enough for these short links). */
    private void link(Theme p, int x1, int y1, int x2, int y2, int w) {
        int color = ColorUtil.fade(p.accent(), 0.6);
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        for (int i = 0; i <= steps; i++) {
            int lx = x1 + (int) Math.round((x2 - x1) * (i / (double) Math.max(1, steps)));
            int ly = y1 + (int) Math.round((y2 - y1) * (i / (double) Math.max(1, steps)));
            r.rect(lx - w / 2, ly - w / 2, w, w, color);
        }
    }

    // ------------------------------------------------------------------ characters

    private static final String[] EQUIP_KEYS = {"helmet", "chestplate", "leggings", "boots", "offHand", "mainHand"};

    private int characters(Theme p, int y0) {
        int x = mainX, w = mainW, y = y0;
        List<CharacterSummary> list = new ArrayList<>();
        for (CharacterSummary c : profile.characters()) if (c.deathTime() <= 0) list.add(c);
        String activeId = profile.player().activeCharacter();
        list.sort(Comparator.<CharacterSummary>comparingInt(c -> c.id().equalsIgnoreCase(activeId) ? 0 : 1)
                .thenComparing(Comparator.comparingLong(CharacterSummary::fame).reversed()));
        if (list.isEmpty()) {
            r.textCentered("No living characters", x + w / 2, y + 20, p.textDisabled());
            return 40;
        }
        int rowH = 30;
        for (CharacterSummary c : list) {
            boolean active = c.id().equalsIgnoreCase(activeId);
            boolean over = inView(mouseX, mouseY) && Hit.inside(mouseX, mouseY, x, y, w, rowH);
            double h = hoverAnim("char-" + c.id(), over);
            r.roundedRect(x, y, w, rowH, R_CELL, ColorUtil.lerp(p.surface(), p.surfaceHover(), h));
            if (active) r.rect(x, y + 4, 2, rowH - 8, p.accent());
            int tx = x + 8;
            if (packIcon(classIcon(c.type(), false), tx, y + (rowH - 20) / 2, 20)) tx += 24;
            String title = ProfileData.pretty(c.type());
            r.text(title, tx, y + 6, p.text());
            int lx = tx + r.textWidth(title) + GAP;
            r.text("Lv " + ProfileData.level(c.fame()), lx, y + 6, p.textMuted());
            if (active) {
                int ax = lx + r.textWidth("Lv " + ProfileData.level(c.fame())) + GAP;
                r.text("Playing", ax, y + 6, p.accent());
            }
            String mode = ProfileData.mode(c.ruleset(), c.group());
            int mx = tx;
            if (packIcon(modeIcon(c.ruleset(), c.group()), mx, y + 16, 9)) mx += 11;
            if (!mode.equals("Normal")) {
                r.text(mode, mx, y + 17, modeColor(mode, p));
                mx += r.textWidth(mode);
                r.text(" · ", mx, y + 17, p.textDisabled());
                mx += r.textWidth(" · ");
            }
            int slotsW = EQUIP_KEYS.length * (CELL + 2) - 2;
            String info = compact(c.fame()) + " fame · " + hours(c.playTime());
            r.text(r.trim(info, x + w - slotsW - 12 - mx), mx, y + 17, p.textDisabled());
            hots.add(new Hot(x, y, w - slotsW - 8, rowH, () -> openCharacter(c, false), null, null));
            int sx = x + w - 6 - slotsW, sy = y + (rowH - CELL) / 2;
            for (String k : EQUIP_KEYS) {
                itemCell(p, ProfileItems.view(c.equipped().get(k), null), sx, sy, false, false);
                sx += CELL + 2;
            }
            // the gaps between equipment slots still open the character
            hots.add(new Hot(x + w - 6 - slotsW, y, slotsW + 6, rowH, () -> openCharacter(c, false), null, null));
            y += rowH + GAP;
        }
        return y - y0;
    }

    private static int modeColor(String mode, Theme p) {
        return switch (mode) {
            case "Ironman" -> ProfileColors.IRONMAN;
            case "Group Ironman" -> ProfileColors.GROUP_IRONMAN;
            case "Seasonal" -> ProfileColors.SEASONAL;
            default -> p.textMuted();
        };
    }

    // ------------------------------------------------------------------ character detail

    private int characterDetail(ThemeManager themes, Theme p, int y0) {
        int x = mainX, w = mainW, y = y0;
        CharacterSummary c = openChar;
        String title = ProfileData.pretty(c.type()) + "  Lv " + ProfileData.level(c.fame());
        int titleX = x + (packIcon(classIcon(c.type(), false), x, y - 3, 12) ? 14 : 0);
        r.text(title, titleX, y, p.text());
        String mode = ProfileData.mode(c.ruleset(), c.group());
        r.textRight(mode, x + w, y, modeColor(mode, p));
        packIcon(modeIcon(c.ruleset(), c.group()), x + w - r.textWidth(mode) - 11, y - 1, 9);
        y += 10 + GAP;
        if (detail == null) {
            if (detailError == null) {
                r.textCentered("Loading character…", x + w / 2, y + 24, p.textMuted());
                if (slow()) r.textCentered("Telos is still sending this — hang on", x + w / 2, y + 36, p.textDisabled());
            } else {
                r.textCentered(r.trim(message(detailError), w), x + w / 2, y + 20, p.text());
                if (retryable(detailError)) {
                    int left = detailError.secondsLeft();
                    r.textCentered(left > 0 ? "Try again in " + left + " s" : "Press R to retry", x + w / 2, y + 32,
                            p.textDisabled());
                }
            }
            return 60;
        }
        CharacterDetail d = detail;
        if (sub == Sub.SKILLS) {
            ClassInfo ci = d.type().skillTree().isEmpty() ? classOf(c.type()) : d.type();
            if (ci == null || ci.skillTree().isEmpty()) {
                r.textCentered("No skill tree data", x + w / 2, y + 20, p.textDisabled());
                return y + 40 - y0;
            }
            r.text("Skill tree of the " + ProfileData.pretty(c.type()) + " class (shared by its characters)", x, y, p.textDisabled());
            y += 10 + PAD;
            y += skillTrees(themes, p, ci, x, y, w, BIG_NODE, BIG_GAP, true);
            return y - y0;
        }
        if (sub == Sub.BACKPACK) {
            int n = 0;
            for (Item it : d.backpack()) if (it != null) n++;
            if (d.backpack().isEmpty()) {
                r.textCentered("No backpack", x + w / 2, y + 20, p.textDisabled());
                return y + 40 - y0;
            }
            r.text("Backpack", x, y, p.textMuted());
            r.textRight(n + " / " + d.backpack().size(), x + w, y, p.textDisabled());
            y += 10 + 2;
            y = grid(p, d.backpack(), 0, d.backpack().size(), d.expanded(), x, y, -1, null);
            return y - y0;
        }

        // equipment column + stats
        String[] labels = {"Helmet", "Chest", "Legs", "Boots", "Offhand", "Weapon"};
        int[] slots = {39, 38, 37, 36, 40, d.hotBarSlot()};
        int eqX = x, eqY = y;
        for (int i = 0; i < slots.length; i++) {
            int col = i % 2, row = i / 2;
            int cx = eqX + col * (CELL + 2), cy = eqY + row * (CELL + 2);
            Item it = slots[i] >= 0 && slots[i] < d.inventory().size() ? d.inventory().get(slots[i]) : null;
            View v = ProfileItems.view(it, d.expanded());
            itemCell(p, v, cx, cy, false, false);
            if (v == null) hots.add(new Hot(cx, cy, CELL, CELL, null, null, labels[i]));
        }
        int sx = x + 2 * (CELL + 2) + PAD, sw = w - (sx - x);
        int sy = y;
        List<String[]> stats = new ArrayList<>();
        // {label, value, pack icon} — same icons as the Overview cards
        stats.add(new String[]{"Fame", group(d.fame()), "material/misc/battlepass"});
        stats.add(new String[]{"Potions", group(d.potions()), null});
        stats.add(new String[]{"Playtime", hours(d.playTime()), "icon/misc/questicon"});
        if (d.created() > 0) stats.add(new String[]{"Created", date(d.created()), null});
        if (d.lastPlayed() > 0) stats.add(new String[]{"Last Played", ago(d.lastPlayed()), null});
        for (String[] s : stats) sy = detailRow(p, sx, sy, sw, s[2], s[0], s[1], p.text());
        if (!d.fragments().isEmpty()) {
            // the mystery fragment as the row icon, each value with its own fragment icon
            int lx = sx + (packIcon(FRAGMENT_ICON, sx, sy, 12) ? 14 : 0);
            r.text("Fragments", lx, sy + 2, p.textMuted());
            int fx = sx + sw;
            List<Map.Entry<String, Double>> frags = new ArrayList<>(d.fragments().entrySet());
            for (int i = frags.size() - 1; i >= 0; i--) {
                Map.Entry<String, Double> e = frags.get(i);
                String v = trimNumber(e.getValue());
                TelosData.Rarity rar = TelosData.RARITIES.get().any(e.getKey());
                int color = rar != null ? 0xFF000000 | rar.rgb() : p.text();
                int vw = r.textWidth(v);
                int iw = packIcon("material/fragment/" + e.getKey().toLowerCase(Locale.ROOT), fx - 10, sy + 1, 10) ? 11 : 0;
                r.textRight(v, fx - iw, sy + 2, color);
                hots.add(new Hot(fx - iw - vw, sy, vw + iw, ROW_H, null, null, ProfileData.pretty(e.getKey()) + " fragments"));
                fx -= vw + iw + 6;
            }
            sy += ROW_H;
        }
        y = Math.max(eqY + 3 * (CELL + 2), sy) + PAD;
        int treeTop = sy + PAD;

        r.text("Inventory", x, y, p.textMuted());
        y += 10 + 2;
        y = grid(p, d.inventory(), 9, Math.min(36, d.inventory().size()), d.expanded(), x, y, -1, null);
        y += GAP;
        y = grid(p, d.inventory(), 0, Math.min(9, d.inventory().size()), d.expanded(), x, y, d.hotBarSlot(), null);

        // the class's skill tree, smaller, right of the inventory under the fragments (full size on its own sub-tab);
        // below the inventory when the panel is too narrow
        ClassInfo ci = d.type().skillTree().isEmpty() ? classOf(c.type()) : d.type();
        int tx = x + 9 * CELL + PAD, tw = w - (tx - x);
        if (ci != null && !ci.skillTree().isEmpty() && tw >= 2 * (3 * SIDE_NODE + 2 * SIDE_GAP) + 2 * PAD) {
            r.text("Skill Tree", tx, treeTop, p.textMuted());
            int th = skillTrees(themes, p, ci, tx, treeTop + 10 + GAP, tw, SIDE_NODE, SIDE_GAP, true);
            y = Math.max(y, treeTop + 10 + GAP + th);
        } else if (ci != null && !ci.skillTree().isEmpty()) {
            y += PAD;
            r.rect(x, y - PAD / 2, w, 1, ColorUtil.fade(p.border(), 0.5));
            r.text("Skill Tree", x, y, p.textMuted());
            y += 10 + PAD;
            y += skillTrees(themes, p, ci, x, y, w, BIG_NODE, BIG_GAP, true);
        }
        return y - y0;
    }

    private static String trimNumber(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    /**
     * A 9-wide grid of items {@code from..to} of {@code items}. {@code mark} (an index) gets an accent outline; with a
     * {@code match} filter non-matching items are dimmed. Returns the y below the grid.
     */
    private int grid(Theme p, List<Item> items, int from, int to, Map<String, ProfileData.Expanded> expanded, int x,
                     int y, int mark, String match) {
        int n = Math.max(0, to - from);
        int rows = (n + 8) / 9;
        for (int i = 0; i < n; i++) {
            int idx = from + i;
            Item it = idx < items.size() ? items.get(idx) : null;
            View v = ProfileItems.view(it, expanded);
            boolean dim = match != null && v != null && !matches(v, match);
            itemCell(p, v, x + (i % 9) * CELL, y + (i / 9) * CELL, dim, idx == mark);
        }
        return y + rows * CELL;
    }

    private static boolean matches(View v, String q) {
        return v.name().toLowerCase(Locale.ROOT).contains(q) || v.key().toLowerCase(Locale.ROOT).contains(q);
    }

    /**
     * One inventory slot, drawn like vanilla's: a flat inset square (dark top-left edge, light bottom-right edge), the
     * Item Rarity module's shape (like in the inventory), the item model at 16 px and a white hover wash.
     */
    private void itemCell(Theme p, View v, int x, int y, boolean dim, boolean mark) {
        boolean over = v != null && inView(mouseX, mouseY) && Hit.inside(mouseX, mouseY, x, y, CELL, CELL);
        slot(p, x, y, CELL);
        if (mark) r.outline(x, y, CELL, CELL, p.accent());
        if (v == null) return;
        if (dim) r.pushAlpha(0.3);
        if (ServerGate.onTelos() && v.item() != null) {
            // skin tokens have no rarity tooltip style: they get the shape in their own (name) colour
            r.itemRarity(v.item(), x + 1, y + 1, v.key().startsWith("skin/") && v.rgb() != 0 ? v.rgb() : -1);
            r.itemIcon(v.item(), x + 1, y + 1);
        } else {
            String letter = v.name().isEmpty() ? "?" : v.name().substring(0, 1).toUpperCase(Locale.ROOT);
            int rgb = v.rgb() != 0 ? 0xFF000000 | v.rgb() : p.text();
            r.textCentered(letter, x + CELL / 2 + 1, y + (CELL - 8) / 2, rgb);
        }
        if (dim) r.popAlpha();
        if (over) r.rect(x + 1, y + 1, CELL - 2, CELL - 2, ColorUtil.fade(p.text(), 0.25));
        hots.add(new Hot(x, y, CELL, CELL, null, v.item(), ServerGate.onTelos() ? null : v.name()));
    }

    /** A vanilla-like slot background of {@code size} px. */
    private void slot(Theme p, int x, int y, int size) {
        int base = p.control();
        r.rect(x, y, size, size, base);
        r.rect(x, y, size - 1, 1, ColorUtil.lerp(base, p.backdrop(), 0.55));
        r.rect(x, y, 1, size - 1, ColorUtil.lerp(base, p.backdrop(), 0.55));
        r.rect(x + 1, y + size - 1, size - 1, 1, ColorUtil.lerp(base, p.text(), 0.18));
        r.rect(x + size - 1, y + 1, 1, size - 1, ColorUtil.lerp(base, p.text(), 0.18));
    }

    // ------------------------------------------------------------------ companions

    private int companions(Theme p, int y0) {
        Player pl = profile.player();
        int x = mainX, w = mainW, y = y0;
        int cw = (w - GAP) / 2, ch = 44;
        companionCard(p, "Pet", pl.companions().pet(), pl.companions().petSkin(), x, y, cw, ch);
        companionCard(p, "Mount", pl.companions().mount(), pl.companions().mountSkin(), x + cw + GAP, y, cw, ch);
        y += ch + PAD + GAP;
        y = companionSection(p, "Pets", "pet/", pl.companions().pet(), x, y, w) + PAD + GAP;
        y = companionSection(p, "Mounts", "mount/", pl.companions().mount(), x, y, w);
        return y - y0;
    }

    /** Starter companions ({@code pet/starter/3}) rank up per maxed class: ranks 0–6. */
    private static final int STARTER_MAX_RANK = 6;
    private static final Pattern STARTER = Pattern.compile("(pet|mount)/starter(/\\d+)?");

    private static boolean isStarter(String key) {
        return key != null && STARTER.matcher(ProfileData.stripNs(key)).matches();
    }

    private static int starterRank(String key) {
        String k = ProfileData.stripNs(key);
        try {
            return Integer.parseInt(k.substring(k.lastIndexOf('/') + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The companion tier ({@code companions.json}) of a pet / mount key, by its name colour; null if unknown. */
    private static TelosData.CompanionTier tierOf(String key) {
        View v = ProfileItems.ofKey(key);
        return v == null ? null : TelosData.COMPANION_TIERS.get().byColor(v.rgb());
    }

    private static int tierColor(TelosData.CompanionTier t, Theme p) {
        return t == null ? p.text() : 0xFF000000 | t.rgb();
    }

    /**
     * The equipped companion: a big slot with the companion itself (never the skin), its name in its tier colour, the
     * tier (or starter rank) and a second slot on the right with the applied skin.
     */
    private void companionCard(Theme p, String label, String key, String skin, int x, int y, int w, int h) {
        r.roundedRect(x, y, w, h, R_CELL, p.surface());
        View base = key == null ? null : ProfileItems.ofKey(key);
        int s = h - 8, sx = x + 4, sy = y + 4;
        slot(p, sx, sy, s);
        if (base != null && ServerGate.onTelos()) {
            r.itemModel(base.model(), sx + 2, sy + 2, s - 4);
            hots.add(new Hot(sx, sy, s, s, null, base.item(), null));
        }
        // skin slot on the right
        int skx = x + w - 6 - CELL, sky = y + h - 6 - CELL;
        r.textCentered("Skin", skx + CELL / 2 + 1, y + 6, p.textMuted());
        if (skin != null) itemCell(p, ProfileItems.ofKey(skin), skx, sky, false, false);
        else slot(p, skx, sky, CELL);

        int tx = sx + s + 6, tw = skx - 6 - tx;
        TelosData.CompanionTier tier = key == null ? null : tierOf(key);
        r.text(label, tx, y + 6, p.textMuted());
        r.text(r.trim(key == null ? "None" : ProfileItems.name(key), tw), tx, y + 18, key == null ? p.textDisabled() : tierColor(tier, p));
        String sub = key == null ? "" : isStarter(key) ? "Rank " + starterRank(key) + " / " + STARTER_MAX_RANK
                : tier != null ? tier.label() : "";
        if (skin != null) sub = (sub.isEmpty() ? "" : sub + " · ") + ProfileItems.name(skin);
        if (!sub.isEmpty()) r.text(r.trim(sub, tw), tx, y + 30, p.textDisabled());
    }

    /** Minimum width of one companion chip (slot + name). */
    private static final int CHIP_MIN_W = 104;

    /**
     * All pets or mounts (skins and starter ranks left out), one block per tier, each companion as a chip with its
     * slot and name so they read at a glance; locked ones dimmed, the equipped one outlined.
     */
    private int companionSection(Theme p, String title, String prefix, String equipped, int x, int y, int w) {
        Set<String> unlocked = new LinkedHashSet<>();
        for (String u : profile.player().companions().unlocked()) if (u.startsWith(prefix) && !isStarter(u)) unlocked.add(u);
        List<String> all = new ArrayList<>();
        if (ProfileItems.hasDefinitions()) {
            for (String k : ProfileItems.keysWithPrefix(prefix)) if (!isStarter(k) && ProfileItems.isCompanion(k)) all.add(k);
        }
        for (String u : unlocked) if (!all.contains(u)) all.add(u);
        all.sort(Comparator.<String>comparingInt(k -> unlocked.contains(k) ? 0 : 1).thenComparing(ProfileItems::name));

        r.text(title, x, y, p.text());
        String count = ProfileItems.hasDefinitions() ? unlocked.size() + " / " + all.size() : String.valueOf(unlocked.size());
        r.textRight(count, x + w, y, p.textMuted());
        y += 10 + 2;
        if (ProfileItems.hasDefinitions() && !all.isEmpty()) {
            r.roundedRect(x, y, w, 2, 1, p.control());
            int fw = (int) Math.round(w * unlocked.size() / (double) all.size());
            if (fw > 0) r.roundedRect(x, y, Math.max(2, fw), 2, 1, p.accent());
            y += 2 + GAP + 2;
        }
        if (all.isEmpty()) {
            r.text("None unlocked", x, y, p.textDisabled());
            return y + 10;
        }

        // one block per tier, lowest first; unknown colours last
        Map<Integer, List<String>> groups = new TreeMap<>();
        Map<Integer, TelosData.CompanionTier> tiers = new HashMap<>();
        for (String k : all) {
            TelosData.CompanionTier t = tierOf(k);
            int idx = t == null ? Integer.MAX_VALUE : t.index();
            groups.computeIfAbsent(idx, i -> new ArrayList<>()).add(k);
            if (t != null) tiers.put(idx, t);
        }
        int cols = Math.max(1, (w + GAP) / (CHIP_MIN_W + GAP));
        int chipW = (w - (cols - 1) * GAP) / cols;
        String eq = equipped == null ? null : ProfileData.stripNs(equipped);
        boolean first = true;
        for (Map.Entry<Integer, List<String>> g : groups.entrySet()) {
            if (!first) y += GAP;
            first = false;
            TelosData.CompanionTier t = tiers.get(g.getKey());
            List<String> keys = g.getValue();
            int have = 0;
            for (String k : keys) if (unlocked.contains(k)) have++;
            r.text(t == null ? "Other" : t.label(), x, y, tierColor(t, p));
            r.textRight(have + " / " + keys.size(), x + w, y, p.textDisabled());
            y += 10 + 2;
            for (int i = 0; i < keys.size(); i++) {
                String k = keys.get(i);
                int cx = x + (i % cols) * (chipW + GAP), cy = y + (i / cols) * (CELL + 2);
                companionChip(p, k, cx, cy, chipW, !unlocked.contains(k), k.equals(eq), t);
            }
            y += ((keys.size() + cols - 1) / cols) * (CELL + 2);
        }
        return y;
    }

    /** One companion: its slot, then its name in its tier colour (grey when locked); hovering either shows the tooltip. */
    private void companionChip(Theme p, String key, int x, int y, int w, boolean locked, boolean equipped, TelosData.CompanionTier tier) {
        View v = ProfileItems.ofKey(key);
        boolean over = inView(mouseX, mouseY) && Hit.inside(mouseX, mouseY, x + CELL, y, w - CELL, CELL);
        r.roundedRect(x, y, w, CELL, R_CELL, over ? p.surfaceHover() : p.surface());
        itemCell(p, v, x, y, locked, false);
        String name = v == null ? ProfileData.pretty(key) : v.name();
        r.text(r.trim(name, w - CELL - 8), x + CELL + 4, y + (CELL - 8) / 2, locked ? p.textDisabled() : tierColor(tier, p));
        if (equipped) r.outline(x, y, w, CELL, p.accent());
        if (v != null) hots.add(new Hot(x + CELL, y, w - CELL, CELL, null, v.item(), ServerGate.onTelos() ? null : name));
    }

    // ------------------------------------------------------------------ stash

    private int stash(Theme p, int y0) {
        Player pl = profile.player();
        int x = mainX, w = mainW, y = y0;
        boolean seasonal = stashMode == StashMode.SEASONAL;
        // Normal | Seasonal switch first (only one stash is ever shown), then the filter field
        Label<StashMode> modeLabel = m -> m.label;
        int sw = pillsWidth(StashMode.values(), modeLabel);
        pills(p, StashMode.values(), stashMode, x, y, stashX, stashW, m -> {
            if (stashMode != m) {
                stashMode = m;
                contentIn.snapTo(0).animateTo(1);
            }
        }, modeLabel);
        int fx = x + sw + PAD;
        int fw = Math.max(40, Math.min(140, w - sw - PAD - 64));
        field(p, fx, y, fw, filter, "Filter stash", focus == Focus.FILTER);
        if (inView(mouseX, mouseY)) hots.add(new Hot(fx, y, fw, FIELD_H, () -> focus = Focus.FILTER, null, null));
        String q = filter.trim().toLowerCase(Locale.ROOT);
        int total = 0, matched = 0;
        List<StashPage> pages = new ArrayList<>();
        for (StashPage sp : pl.stash()) {
            if (sp.seasonal() != seasonal) continue;
            int n = 0, m = 0;
            for (Item it : sp.items()) {
                if (it == null) continue;
                n++;
                if (!q.isEmpty() && matches(ProfileItems.view(it, pl.expanded()), q)) m++;
            }
            total += n;
            matched += m;
            if (n > 0 && (q.isEmpty() || m > 0)) pages.add(sp);
        }
        String summary = q.isEmpty() ? group(total) + " items" : matched + " match" + (matched == 1 ? "" : "es");
        r.textRight(summary, x + w, y + (FIELD_H - 8) / 2, p.textDisabled());
        y += FIELD_H + PAD;
        if (pages.isEmpty()) {
            r.textCentered(q.isEmpty() ? (seasonal ? "Seasonal" : "Normal") + " stash is empty" : "No matches", x + w / 2, y + 16, p.textDisabled());
            return y + 32 - y0;
        }
        int gridW = 9 * CELL;
        int minGap = 12;
        int perRow = Math.max(1, (w + minGap) / (gridW + minGap));
        // spread the pages over the full width: leftover space goes into the gaps, not to the right edge
        int gapX = perRow > 1 ? Math.min(32, (w - perRow * gridW) / (perRow - 1)) : 0;
        int used = perRow * gridW + (perRow - 1) * gapX;
        int left = x + (w - used) / 2;
        int rowY = y, rowH = 0;
        for (int i = 0; i < pages.size(); i++) {
            StashPage sp = pages.get(i);
            int col = i % perRow;
            if (col == 0 && i > 0) {
                rowY += rowH + PAD;
                rowH = 0;
            }
            int gx = left + col * (gridW + gapX);
            String label = "Page " + (sp.page() + 1);
            r.text(r.trim(label, gridW), gx, rowY, p.textMuted());
            int end = grid(p, sp.items(), 0, sp.items().size(), pl.expanded(), gx, rowY + 10 + 2, -1, q.isEmpty() ? null : q);
            rowH = Math.max(rowH, end - rowY);
        }
        return rowY + rowH - y0;
    }

    // ------------------------------------------------------------------ tooltip

    private void tooltip(Theme p) {
        Hot hot = null;
        for (int i = hots.size() - 1; i >= 0; i--) {
            Hot h = hots.get(i);
            if ((h.item() != null || h.hint() != null) && h.contains(mouseX, mouseY) && reachable(i, mouseX, mouseY)) {
                hot = h;
                break;
            }
        }
        if (hot == null) return;
        if (hot.item() != null && ServerGate.onTelos()) {
            r.itemTooltip(hot.item(), mouseX, mouseY);
            return;
        }
        if (hot.hint() == null) return;
        r.nextLayer();
        String s = r.trim(hot.hint(), width - 16);
        int tw = r.textWidth(s) + 8, th = 14;
        int tx = Math.min(width - tw - 2, mouseX + 8), ty = Math.max(2, mouseY - th - 4);
        r.roundedRect(tx, ty, tw, th, R_CELL, p.panel());
        r.outline(tx, ty, tw, th, p.border());
        r.text(s, tx + 4, ty + (th - 8) / 2, p.text());
    }

    // ------------------------------------------------------------------ input

    /** Hot {@code i} can be used at (mx, my): chrome always, content only inside the visible content area. */
    private boolean reachable(int i, double mx, double my) {
        return i < contentFrom || i >= contentTo || inView(mx, my);
    }

    private boolean inView(double mx, double my) {
        return Hit.inside(mx, my, clipX, clipY, clipW, clipH);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == MouseButton.RIGHT && (openChar != null || !history.isEmpty())) {
            back(); // right-click anywhere = back, like a mouse back button
            return true;
        }
        if (event.button() != MouseButton.LEFT) return super.mouseClicked(event, doubleClick);
        Focus before = focus;
        focus = Focus.NONE;
        for (int i = hots.size() - 1; i >= 0; i--) {
            Hot h = hots.get(i);
            if (h.click() == null || !h.contains(event.x(), event.y()) || !reachable(i, event.x(), event.y())) continue;
            h.click().run();
            return true;
        }
        if (before != Focus.NONE && !Hit.inside(event.x(), event.y(), px, py, pw, ph)) return true;
        if (!Hit.inside(event.x(), event.y(), px, py, pw, ph)) {
            onClose();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (profile == null || !inView(mouseX, mouseY)) return false;
        double max = Math.max(0, contentH - viewH);
        double t = Math.max(0, Math.min(max, scrollTarget.getOrDefault(scrollKey, 0.0) - scrollY * SCROLL_STEP));
        scrollTarget.put(scrollKey, t);
        scroll.computeIfAbsent(scrollKey, k -> new Animation(Durations.STANDARD, Easing.OUT_CUBIC, 0)).animateTo(t);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (focus != Focus.NONE) {
            if (key == InputUtil.KEY_ESCAPE) {
                focus = Focus.NONE;
                return true;
            }
            if (key == InputUtil.KEY_BACKSPACE) {
                if (focus == Focus.LOOKUP && !lookup.isEmpty()) lookup = lookup.substring(0, lookup.length() - 1);
                if (focus == Focus.FILTER && !filter.isEmpty()) filter = filter.substring(0, filter.length() - 1);
                return true;
            }
            if (key == InputUtil.KEY_ENTER) {
                if (focus == Focus.LOOKUP) {
                    String n = lookup.trim();
                    lookup = "";
                    focus = Focus.NONE;
                    lookUp(n);
                } else {
                    focus = Focus.NONE;
                }
                return true;
            }
            return true; // typing never triggers shortcuts
        }
        if (key == InputUtil.KEY_ESCAPE && openChar != null) {
            back();
            return true;
        }
        if (key == InputUtil.KEY_R) {
            retry();
            return true;
        }
        if (profile != null && openChar == null) {
            Tab[] tabs = Tab.values();
            if (key >= InputUtil.KEY_1 && key < InputUtil.KEY_1 + tabs.length) {
                selectTab(tabs[key - InputUtil.KEY_1]);
                return true;
            }
            if (key == InputUtil.KEY_LEFT || key == InputUtil.KEY_RIGHT) {
                int d = key == InputUtil.KEY_LEFT ? -1 : 1;
                selectTab(tabs[(tab.ordinal() + d + tabs.length) % tabs.length]);
                return true;
            }
        }
        if (openChar != null && key >= InputUtil.KEY_1 && key < InputUtil.KEY_1 + Sub.values().length) {
            Sub s = Sub.values()[key - InputUtil.KEY_1];
            if (sub != s) {
                sub = s;
                contentIn.snapTo(0).animateTo(1);
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        String c = event.codepointAsString();
        if (focus == Focus.LOOKUP) {
            if (c.matches("[A-Za-z0-9_]") && lookup.length() < 16) lookup += c;
            return true;
        }
        if (focus == Focus.FILTER) {
            if (event.isAllowedChatCharacter() && filter.length() < 32) filter += c;
            return true;
        }
        if (c.equals("/")) { // quick lookup
            focus = Focus.LOOKUP;
            return true;
        }
        return super.charTyped(event);
    }

    // ------------------------------------------------------------------ helpers

    private double hoverAnim(String key, boolean on) {
        return hover.computeIfAbsent(key, k -> new Animation(Durations.HOVER, Easing.OUT_CUBIC, 0)).animateTo(on ? 1 : 0).get();
    }

    /** {@code 5337755534} ms → {@code 1,482h}; under an hour {@code 42m}. */
    private static String hours(long ms) {
        long m = ms / 60_000;
        if (m < 60) return m + "m";
        return group(m / 60) + "h";
    }

    private static String ago(long ts) {
        long s = Math.max(0, (System.currentTimeMillis() - ts) / 1000);
        if (s < 60) return "just now";
        if (s < 3600) return s / 60 + "m ago";
        if (s < 86_400) return s / 3600 + "h ago";
        if (s < 30L * 86_400) return s / 86_400 + "d ago";
        return date(ts);
    }

    private static String date(long ts) {
        return new SimpleDateFormat("d MMM yyyy", Locale.ROOT).format(new Date(ts));
    }

    private static String group(long v) {
        return String.format(Locale.ROOT, "%,d", v);
    }

    /** {@code 16653107} → {@code 16.7M}. */
    private static String compact(double v) {
        double a = Math.abs(v);
        if (a >= 1e9) return String.format(Locale.ROOT, "%.1fB", v / 1e9);
        if (a >= 1e6) return String.format(Locale.ROOT, "%.1fM", v / 1e6);
        if (a >= 1e4) return String.format(Locale.ROOT, "%.1fK", v / 1e3);
        return group(Math.round(v));
    }
}
