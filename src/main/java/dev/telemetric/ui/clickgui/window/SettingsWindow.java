package dev.telemetric.ui.clickgui.window;

import dev.telemetric.Telemetric;
import dev.telemetric.config.GuiLayout;
import dev.telemetric.module.Module;
import dev.telemetric.input.InputUtil;
import dev.telemetric.module.impl.misc.InterfaceModule;
import dev.telemetric.module.impl.misc.ShareSettingsModule;
import dev.telemetric.setting.Setting;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.clickgui.Layout;
import dev.telemetric.ui.clickgui.SettingList;
import dev.telemetric.ui.clickgui.setting.SettingComponent;
import dev.telemetric.ui.confirm.Confirm;
import dev.telemetric.ui.confirm.ConfirmRequest;
import dev.telemetric.ui.framework.GuiContext;
import dev.telemetric.ui.framework.Hit;
import dev.telemetric.ui.framework.MouseButton;
import dev.telemetric.ui.framework.UIComponent;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Fx;
import dev.telemetric.ui.render.Icons;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.HeaderStyle;
import dev.telemetric.ui.theme.Theme;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * The module Settings window: one floating, draggable and resizable window over the ClickGUI that shows every
 * setting of one module. The left sidebar lists the sections ("General" plus each top-level group), the middle shows
 * the selected section's settings, and modules with a {@link SettingsPreview} get a preview column on the right.
 * <ul>
 *   <li>Title bar: drag to move; back arrow (after jumping to another module), Reset, the module's on/off switch and
 *   the close button.</li>
 *   <li>Left / bottom edge and bottom-right corner: drag to resize (the right edge is left free so the scroll bar
 *   can be dragged). Position, size, the open module, each module's section and its
 *   open dropdowns are saved in {@link GuiLayout.WindowState}.</li>
 *   <li>Star left of a row: pin the setting to the module's right-click menu.</li>
 * </ul>
 */
public final class SettingsWindow extends UIComponent {

    private static final int TITLE_H = Layout.HEADER_HEIGHT;
    private static final int SIDE_ROW = 18;
    private static final int SECTION_HEAD = 18;
    private static final int EDGE = 4;
    private static final int CORNER = 10;
    private static final int MIN_W = 200, MIN_H = 140;
    private static final int MIN_SETTINGS_W = 150;
    /** With a preview, the settings column stays this narrow and the preview takes the rest of the window. */
    private static final int PREVIEW_SETTINGS_W = 172;
    private static final int DEFAULT_W = 330, DEFAULT_H = 240;
    private static final int BUTTON = 16;

    private enum Drag { NONE, MOVE, BOTTOM, LEFT, CORNER, SCROLL }

    private final GuiLayout.WindowState state;
    private final GuiLayout layout;

    /** Theme -> Glow strength around the open window (full while it's being dragged). */
    private static final double WINDOW_GLOW = 0.3;
    private Module module;
    private final Deque<Module> history = new ArrayDeque<>();
    private final List<Section> sections = new ArrayList<>();
    private Section selected;
    private SettingsPreview preview;
    private String query = "";

    private final Animation appear = new Animation(Durations.MODAL, Easing.OUT_CUBIC, 0);
    private final Animation contentFade = new Animation(Durations.STANDARD, Easing.OUT_CUBIC, 1);
    private final Animation closeHover = new Animation(Durations.HOVER, Easing.OUT_CUBIC);
    private final Animation backHover = new Animation(Durations.HOVER, Easing.OUT_CUBIC);
    private final Animation enabledAnim = new Animation(Durations.MICRO, Easing.OUT_CUBIC);
    private final Animation edgeHover = new Animation(Durations.HOVER, Easing.OUT_CUBIC);
    private boolean closing;

    private Drag drag = Drag.NONE;
    private double grabX, grabY;
    private int startX, startY, startW, startH;
    /** Scroll bar geometry of the last frame, for dragging the thumb. */
    private int barTrackY, barTrackH, barThumbH;
    private double startScroll;
    private boolean previewHeld;
    private int screenW = 1, screenH = 1;

    /** A text button in the title bar. {@link #renderTitle} lays it out every frame; clicks use that layout. */
    private final class TitleButton {
        final String label;
        final Supplier<String> tooltip;
        final Runnable action;
        final Animation hover = new Animation(Durations.HOVER, Easing.OUT_CUBIC);
        int right, width;

        TitleButton(String label, Supplier<String> tooltip, Runnable action) {
            this.label = label;
            this.tooltip = tooltip;
            this.action = action;
        }

        boolean contains(double mx) {
            return mx >= right - width - 2 && mx < right + 2;
        }
    }

    /** Reset / Import / Copy, right to left. */
    private final List<TitleButton> titleButtons = List.of(
            new TitleButton("Reset", () -> "Reset every setting of " + module.getName() + " to its default", this::confirmReset),
            new TitleButton("Import", () -> "Import " + module.getName() + " settings from a share code in the clipboard",
                    this::confirmImport),
            new TitleButton("Copy", () -> "Copy " + module.getName() + " settings as a share code",
                    () -> ShareSettingsModule.copyCode(List.of(module))));

    // layout of the last frame (input uses it)
    private int sideW, listX, listY, listW, listH;
    private int previewX, previewY, previewW, previewH;

    private final class Section {
        final Setting<?> group; // null = General
        final SettingList list;
        final Animation hover = new Animation(Durations.HOVER, Easing.OUT_CUBIC);
        final Animation active = new Animation(Durations.MICRO, Easing.OUT_CUBIC);
        final Animation scroll = new Animation(Durations.STANDARD, Easing.OUT_CUBIC, 0);
        double scrollTarget;
        int maxScroll;
        boolean matches;
        int rowY; // sidebar row of the last frame

        Section(Setting<?> group, SettingList list) {
            this.group = group;
            this.list = list;
        }

        String name() {
            return group == null ? "General" : group.getLabel();
        }

        boolean visible() {
            return group == null || group.isVisibleIgnoringGroups();
        }

        BooleanSetting toggle() {
            return group instanceof BooleanSetting b ? b : null;
        }
    }

    public SettingsWindow(GuiContext ctx, GuiLayout layout) {
        super(ctx);
        this.layout = layout;
        this.state = layout.window();
    }

    public Module getModule() {
        return closing ? null : module;
    }

    public boolean isOpen() {
        return module != null;
    }

    /** Shows {@code m}. With another module open, it's remembered for the back arrow. */
    public void open(Module m) {
        if (m == null) return;
        if (module == m && !closing) {
            appear.animateTo(1);
            return;
        }
        if (module != null && !closing) {
            saveState();
            history.push(module);
            if (history.size() > 16) history.removeLast();
        } else {
            history.clear();
        }
        show(m);
        if (appear.get() < 0.001 || closing) appear.snapTo(0);
        closing = false;
        appear.animateTo(1);
    }

    /** Re-opens the window that was open when the ClickGUI closed (no back history). */
    public void restore(Module m) {
        if (m == null) return;
        history.clear();
        show(m);
        appear.snapTo(0).animateTo(1, Durations.STAGGER * 4);
    }

    private void back() {
        if (history.isEmpty()) return;
        saveState();
        show(history.pop());
    }

    public void close() {
        if (module == null || closing) return;
        saveState();
        closing = true;
        ctx.clearFocus();
        appear.animateTo(0);
        if (!Durations.isEnabled()) finishClose();
    }

    private void finishClose() {
        disposeContent();
        module = null;
        closing = false;
        history.clear();
        state.module = null;
    }

    private void show(Module m) {
        disposeContent();
        module = m;
        state.module = m.getName();
        build(m);
        preview = Telemetric.get().modules().get(InterfaceModule.class).view3d.get() ? m.createPreview() : null;
        String saved = state.sections.get(m.getName());
        selected = null;
        for (Section s : sections) if (s.name().equals(saved) && s.visible()) selected = s;
        if (selected == null && !sections.isEmpty()) selected = sections.getFirst();
        applySearch();
        if (!query.isBlank()) {
            for (Section s : sections) {
                if (s.matches && s.visible()) {
                    selected = s;
                    break;
                }
            }
        }
        for (Section s : sections) s.active.snapTo(s == selected ? 1 : 0);
        contentFade.snapTo(1);
        enabledAnim.snapTo(m.isEnabled() ? 1 : 0);
    }

    private void disposeContent() {
        drag = Drag.NONE;
        if (previewHeld) {
            previewHeld = false;
            ctx.releaseCapture(this);
        }
        for (Section s : sections) s.list.onClose();
        sections.clear();
        selected = null;
        if (preview != null) preview.onClose();
        preview = null;
    }

    /** Writes the selected section and the open dropdowns of the current module into the layout. */
    public void saveState() {
        if (module == null) return;
        if (selected != null) state.sections.put(module.getName(), selected.name());
        Set<String> open = new LinkedHashSet<>();
        for (Section s : sections) open.addAll(s.list.openNames());
        state.setOpenSettings(module.getName(), open);
    }

    @Override
    public void onClose() {
        saveState();
        if (closing) finishClose();
        else {
            for (Section s : sections) s.list.onClose();
            if (preview != null) preview.onClose();
        }
    }

    /** A top-level group becomes its own section, unless it's a reorderable row (those stay in General). */
    private static boolean isSectionHead(Setting<?> s) {
        return s.isGroup() && s.getGroup() == null && !s.isMovable() && s.getAttachedTo() == null;
    }

    private static Setting<?> topOf(Setting<?> s) {
        Setting<?> t = s;
        for (int guard = 0; t.getGroup() != null && guard < 16; guard++) t = t.getGroup();
        return t;
    }

    private static boolean isUnder(Setting<?> s, Setting<?> group) {
        for (Setting<?> g = s.getGroup(); g != null; g = g.getGroup()) if (g == group) return true;
        return false;
    }

    private void build(Module m) {
        List<Setting<?>> gui = m.getGuiSettings();
        Set<String> open = state.openSettingsOf(m.getName());
        List<Setting<?>> general = new ArrayList<>();
        List<Setting<?>> heads = new ArrayList<>();
        for (Setting<?> s : gui) {
            Setting<?> top = topOf(s);
            if (!isSectionHead(top)) general.add(s);
            else if (s == top) heads.add(s);
        }
        if (!general.isEmpty()) {
            sections.add(new Section(null, list(m, general, s -> s::isVisible, Setting::groupDepth, open)));
        }
        for (Setting<?> g : heads) {
            List<Setting<?>> members = new ArrayList<>();
            for (Setting<?> s : gui) if (isUnder(s, g)) members.add(s);
            int base = g.groupDepth() + 1;
            sections.add(new Section(g, list(m, members, s -> () -> s.isVisibleInSection(g),
                    s -> s.groupDepth() - base, open)));
        }
    }

    private SettingList list(Module m, List<Setting<?>> settings, Function<Setting<?>, BooleanSupplier> visibility,
                             ToIntFunction<Setting<?>> depth, Set<String> open) {
        SettingList list = new SettingList(ctx, this, m, settings, visibility, depth, true, open);
        String name = m.getName();
        list.setPins(new SettingList.Pins() {
            @Override
            public boolean isPinned(Setting<?> setting) {
                return layout.isPinned(name, setting.getName());
            }

            @Override
            public void toggle(Setting<?> setting) {
                layout.setPinned(name, setting.getName(), !isPinned(setting));
            }
        });
        return list;
    }

    private boolean hasSidebar() {
        int shown = 0;
        for (Section s : sections) if (s.visible()) shown++;
        return shown > 1;
    }

    private void select(Section s) {
        if (s == selected) return;
        selected = s;
        contentFade.snapTo(0).animateTo(1);
    }

    /** The ClickGUI search: matching rows get tinted and their sections a dot in the sidebar. */
    public void setSearch(String q) {
        String next = q == null ? "" : q;
        if (next.equals(query)) return;
        query = next;
        applySearch();
    }

    private void applySearch() {
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (Section s : sections) {
            Set<Setting<?>> hits = new HashSet<>();
            if (!q.isEmpty()) {
                for (SettingComponent<?> c : s.list.rows()) {
                    Setting<?> set = c.getSetting();
                    boolean named = set.getLabel().toLowerCase(Locale.ROOT).contains(q)
                            || set.getName().toLowerCase(Locale.ROOT).contains(q);
                    if (named && set.isVisibleIgnoringGroups()) {
                        hits.add(set);
                        // open the groups between the section and the match, so it shows
                        for (Setting<?> g = set.getGroup(); g != null && g != s.group; g = g.getGroup()) openGroup(s, g);
                    }
                }
            }
            s.list.setHighlight(hits);
            s.matches = !hits.isEmpty() || (s.group != null && !q.isEmpty()
                    && s.group.getLabel().toLowerCase(Locale.ROOT).contains(q));
        }
    }

    private static void openGroup(Section s, Setting<?> group) {
        for (SettingComponent<?> c : s.list.rows()) {
            if (c.getSetting() == group && c.isExpandable() && !c.isExpanded()) c.setExpanded(true, false);
        }
    }

    private int sidebarWidth(Render2D r) {
        if (!hasSidebar()) return 0;
        int widest = 0;
        for (Section s : sections) if (s.visible()) widest = Math.max(widest, r.textWidth(s.name()) + (s.toggle() != null ? 18 : 0));
        return Math.clamp(widest + Layout.PAD * 2 + 4, 64, 120);
    }

    private int minWidth(int side) {
        int w = side + MIN_SETTINGS_W;
        if (preview != null) w += preview.minWidth() + Layout.PAD;
        return Math.max(MIN_W, w);
    }

    private void clampGeometry(int side) {
        int maxW = Math.max(MIN_W, screenW - 8), maxH = Math.max(MIN_H, screenH - 8);
        int minW = Math.min(minWidth(side), maxW);
        if (!state.placed) {
            state.w = Math.min(Math.max(DEFAULT_W + (preview != null ? 230 : 0), minW), maxW);
            state.h = Math.min(DEFAULT_H + (preview != null ? 90 : 0), maxH);
            state.x = (screenW - state.w) / 2;
            state.y = Math.max(Layout.PANEL_MARGIN_TOP, (screenH - state.h) / 2);
            state.placed = true;
        }
        state.w = Math.clamp(state.w, minW, maxW);
        state.h = Math.clamp(state.h, Math.min(MIN_H, maxH), maxH);
        state.x = Math.clamp(state.x, -state.w + 40, Math.max(0, screenW - 40));
        state.y = Math.clamp(state.y, 0, Math.max(0, screenH - TITLE_H));
    }

    private void applyDrag(int mouseX, int mouseY) {
        if (drag == Drag.NONE) return;
        int dx = (int) Math.round(mouseX - grabX), dy = (int) Math.round(mouseY - grabY);
        switch (drag) {
            case MOVE -> {
                state.x = startX + dx;
                state.y = startY + dy;
            }
            case SCROLL -> {
                Section s = selected;
                int range = barTrackH - barThumbH;
                if (s != null && range > 0) s.scrollTarget = Math.clamp(startScroll + dy * (s.maxScroll / (double) range), 0, s.maxScroll);
            }
            case BOTTOM -> state.h = startH + dy;
            case CORNER -> {
                state.w = startW + dx;
                state.h = startH + dy;
            }
            case LEFT -> {
                int w = Math.clamp(startW - dx, Math.min(minWidth(sideW), Math.max(MIN_W, screenW - 8)),
                        Math.max(MIN_W, screenW - 8)); // clamped here, so the right edge stays put
                state.x = startX + startW - w;
                state.w = w;
            }
            default -> {}
        }
    }

    private Drag edgeAt(double mx, double my) {
        boolean inY = my >= y + TITLE_H && my < y + height + EDGE;
        if (mx >= x + width - CORNER && mx < x + width + EDGE && my >= y + height - CORNER && my < y + height + EDGE) return Drag.CORNER;
        if (inY && mx >= x - EDGE && mx < x + EDGE) return Drag.LEFT;
        if (mx >= x && mx < x + width && my >= y + height - EDGE && my < y + height + EDGE) return Drag.BOTTOM;
        return Drag.NONE;
    }

    /** Whether the point is over the window (resize edges included), so the panels behind ignore it. */
    public boolean isOver(double mx, double my) {
        if (module == null || closing) return false;
        return mx >= x - EDGE && mx < x + width + EDGE && my >= y && my < y + height + EDGE;
    }

    public void render(Render2D r, int mouseX, int mouseY, float delta, int screenW, int screenH) {
        if (module == null) return;
        if (closing && !appear.isRunning() && appear.get() <= 0.001) {
            finishClose();
            return;
        }
        this.screenW = screenW;
        this.screenH = screenH;
        if (selected == null || !selected.visible()) {
            for (Section s : sections) if (s.visible()) {
                selected = s;
                break;
            }
        }
        sideW = sidebarWidth(r);
        applyDrag(mouseX, mouseY);
        clampGeometry(sideW);
        x = state.x;
        y = state.y;
        width = state.w;
        height = state.h;

        int mx = closing ? Integer.MIN_VALUE / 2 : mouseX, my = closing ? Integer.MIN_VALUE / 2 : mouseY;
        double a = appear.get();
        float s = (float) (0.96 + 0.04 * a);
        float cx = x + width / 2f, cy = y + height / 2f;
        Theme previous = ctx.overridePalette(ctx.themes().paletteFor(module.getCategory()));
        r.pushAlpha(a);
        r.pushTransform(cx * (1 - s), cy * (1 - s), s);
        try {
            renderWindow(r, mx, my, delta);
        } finally {
            r.popTransform();
            r.popAlpha();
            ctx.overridePalette(previous);
        }
    }

    @Override
    public void render(Render2D r, int mouseX, int mouseY, float delta) {
        throw new UnsupportedOperationException("Use render(r, mx, my, delta, screenW, screenH)");
    }

    private void renderWindow(Render2D r, int mouseX, int mouseY, float delta) {
        Theme p = ctx.palette();
        int radius = ctx.radius();

        if (ctx.themes().shadows()) r.shadow(x, y, width, height, radius, p.shadow(), Layout.SHADOW_SIZE + 2);
        Fx.glow(r, ctx.themes(), x, y, width, height, radius, p.accent(), drag == Drag.MOVE ? 1 : WINDOW_GLOW);
        r.roundedRect(x, y, width, height, radius, p.panel());

        renderTitle(r, p, radius, mouseX, mouseY);

        int bodyY = y + TITLE_H, bodyH = height - TITLE_H;
        if (sideW > 0) renderSidebar(r, p, radius, bodyY, bodyH, mouseX, mouseY);

        int contentX = x + sideW, contentW = width - sideW;
        int settingsW = contentW;
        if (preview != null) {
            int wantSettings = Math.min(PREVIEW_SETTINGS_W, Math.max(MIN_SETTINGS_W, (int) (contentW * 0.4)));
            previewW = Math.max(preview.minWidth(), contentW - wantSettings - Layout.PAD);
            previewX = x + width - previewW - Layout.PAD + 2;
            previewY = bodyY + Layout.PAD - 2;
            previewH = bodyH - Layout.PAD * 2 + 4;
            settingsW = contentW - previewW - Layout.PAD;
        }
        renderSettings(r, p, contentX, bodyY, settingsW, bodyH, mouseX, mouseY, delta);

        if (preview != null) {
            boolean in = previewHeld || Hit.inside(mouseX, mouseY, previewX, previewY, previewW, previewH);
            r.pushClip(previewX, previewY, previewW, previewH);
            try {
                preview.render(r, ctx, previewX, previewY, previewW, previewH,
                        in ? mouseX : Integer.MIN_VALUE / 2, in ? mouseY : Integer.MIN_VALUE / 2, delta);
            } finally {
                r.popClip();
            }
        }

        // resize affordances: grip dots in the corner, an accent line along a hovered edge
        Drag edge = drag != Drag.NONE && drag != Drag.MOVE && drag != Drag.SCROLL ? drag : edgeAt(mouseX, mouseY);
        edgeHover.animateTo(edge != Drag.NONE ? 1 : 0);
        Icons.resizeGrip(r, x + width - 2, y + height - 2,
                ColorUtil.lerp(ColorUtil.fade(p.textDisabled(), 0.7), p.accent(), edge == Drag.CORNER ? edgeHover.get() : 0));
        double eh = edgeHover.get();
        if (eh > 0.01) {
            int c = ColorUtil.fade(p.accent(), 0.8 * eh);
            switch (edge) {
                case LEFT -> r.rect(x, y + TITLE_H, 1, height - TITLE_H - radius, c);
                case BOTTOM -> r.rect(x + radius, y + height - 1, width - radius * 2, 1, c);
                default -> {}
            }
            if (edge != Drag.NONE && drag == Drag.NONE) ctx.requestTooltip(this, "Drag to resize");
        }
    }


    private int closeX() {
        return x + width - BUTTON - 2;
    }

    private int switchX() {
        return closeX() - 6 - 16;
    }

    private int resetRight(boolean hasSwitch) {
        return (hasSwitch ? switchX() : closeX()) - 6;
    }

    private void renderTitle(Render2D r, Theme p, int radius, int mouseX, int mouseY) {
        boolean overTitle = Hit.inside(mouseX, mouseY, x, y, width, TITLE_H);
        int header = ColorUtil.lerp(p.header(), ColorUtil.shade(p.header(), 0.06), overTitle || drag == Drag.MOVE ? 1 : 0);
        r.roundedRect(x, y, width, TITLE_H, radius, header, Render2D.CORNERS_TOP);
        if (ctx.themes().headerGradient() || ctx.themes().headerStyle() == HeaderStyle.FILLED) {
            Fx.accentFill(r, ctx.themes(), p, x, y + TITLE_H - 1, width, 1, 0, 0, 0.85);
        }
        int ty = y + Layout.textY(TITLE_H);
        int cy = y + TITLE_H / 2;

        // close (top right)
        int bx = closeX(), by = y + (TITLE_H - BUTTON) / 2;
        boolean closeHot = drag == Drag.NONE && Hit.inside(mouseX, mouseY, bx, by, BUTTON, BUTTON);
        closeHover.animateTo(closeHot ? 1 : 0);
        double ch = closeHover.get();
        Icons.close(r, bx + BUTTON / 2, by + BUTTON / 2, ColorUtil.lerp(p.textMuted(), p.text(), ch));
        if (closeHot) ctx.requestTooltip(this, "Close (Esc)");

        // on/off switch
        boolean toggleable = module.isToggleable();
        if (toggleable) {
            enabledAnim.animateTo(module.isEnabled() ? 1 : 0);
            double t = enabledAnim.get();
            int sx = switchX(), sy = cy - 4;
            int accent = Fx.flow(ctx.themes(), p, sx, sy);
            Fx.glow(r, ctx.themes(), sx, sy, 16, 8, 4, accent, t);
            r.pill(sx, sy, 16, 8, ColorUtil.lerp(p.control(), accent, t));
            double knob = 3;
            r.circle(sx + 1 + knob + (16 - 2 - knob * 2) * t, sy + 4.0, knob, ColorUtil.lerp(p.textMuted(), 0xFFFFFFFF, t));
            if (drag == Drag.NONE && mouseX >= sx - 2 && mouseX < sx + 18 && mouseY >= y && mouseY < y + TITLE_H) {
                ctx.requestTooltip(this, module.isEnabled() ? "Enabled" : "Disabled");
            }
        }

        // reset / import / copy
        int right = resetRight(toggleable);
        for (TitleButton b : titleButtons) {
            b.right = right;
            b.width = r.textWidth(b.label);
            boolean hot = drag == Drag.NONE && overTitle && b.contains(mouseX);
            b.hover.animateTo(hot ? 1 : 0);
            r.textRight(b.label, b.right, ty, ColorUtil.lerp(p.textDisabled(), p.text(), b.hover.get()));
            if (hot) ctx.requestTooltip(this, b.tooltip.get());
            right -= b.width + 8;
        }

        // back arrow + title
        int tx = x + Layout.PAD;
        if (!history.isEmpty()) {
            boolean backHot = drag == Drag.NONE && mouseX >= x + 2 && mouseX < x + 14 && overTitle;
            backHover.animateTo(backHot ? 1 : 0);
            Icons.back(r, x + 7, cy, ColorUtil.lerp(p.textMuted(), p.accent(), backHover.get()));
            if (backHot) ctx.requestTooltip(this, "Back to " + history.peek().getName());
            tx = x + 15;
        }
        int room = right - tx;
        String title = module.getName();
        String category = module.getCategory().getName();
        r.text(r.trim(title, room), tx, ty, p.text());
        int used = r.textWidth(title) + 6;
        if (used + r.textWidth(category) <= room) r.text(category, tx + used, ty, p.textDisabled());
    }


    private void renderSidebar(Render2D r, Theme p, int radius, int bodyY, int bodyH, int mouseX, int mouseY) {
        int bg = ColorUtil.lerp(p.panel(), p.backdrop(), 0.25);
        r.roundedRect(x, bodyY, sideW, bodyH, radius, bg, Render2D.CORNER_BL);
        r.rect(x + sideW - 1, bodyY, 1, bodyH, ColorUtil.fade(p.border(), 0.6));
        r.pushClip(x, bodyY, sideW, bodyH);
        int ry = bodyY + 4;
        for (Section s : sections) {
            if (!s.visible()) {
                s.rowY = Integer.MIN_VALUE;
                continue;
            }
            s.rowY = ry;
            boolean hot = drag == Drag.NONE && Hit.inside(mouseX, mouseY, x, ry, sideW - 1, SIDE_ROW);
            s.hover.animateTo(hot ? 1 : 0);
            s.active.animateTo(s == selected ? 1 : 0);
            double h = s.hover.get(), act = s.active.get();
            if (act > 0.01) {
                int barH = (int) Math.round((SIDE_ROW - 4) * act);
                SettingList.neonLine(r, x + 2, ry + (SIDE_ROW - barH) / 2, 2, barH, p.accent(), act);
            }
            BooleanSetting toggle = s.toggle();
            int textRoom = sideW - Layout.PAD * 2 - 4 - (toggle != null ? 16 : 0);
            int color = ColorUtil.lerp(ColorUtil.lerp(p.textMuted(), p.text(), h), p.text(), act);
            r.text(r.trim(s.name(), textRoom), x + Layout.PAD + 2 + (int) Math.round(h * 1.5), ry + Layout.textY(SIDE_ROW), color);
            if (toggle != null) {
                int sx = x + sideW - Layout.PAD - 12, sy = ry + SIDE_ROW / 2 - 3;
                boolean on = toggle.get();
                r.pill(sx, sy, 12, 6, on ? p.accent() : p.control());
                r.circle(on ? sx + 9 : sx + 3, sy + 3.0, 2, on ? 0xFFFFFFFF : p.textMuted());
            }
            if (s.matches) r.circle(x + sideW - 6, ry + 4, 1.5, p.accent());
            if (hot && s.group != null) ctx.requestTooltip(s, s.name(), s.group.getDescription());
            ry += SIDE_ROW;
        }
        r.popClip();
    }


    private void renderSettings(Render2D r, Theme p, int cx, int bodyY, int cw, int bodyH, int mouseX, int mouseY, float delta) {
        Section s = selected;
        if (s == null) {
            r.textCentered("No settings", cx + cw / 2, bodyY + bodyH / 2 - 4, p.textDisabled());
            return;
        }
        double fade = contentFade.get();
        int slide = (int) Math.round((1 - fade) * 6);
        int innerX = cx + 3;
        int innerW = cw - 6;
        int top = bodyY + 2;

        r.pushAlpha(fade);
        try {
            if (sideW > 0) {
                // section header: name (description in the tooltip), and the group's switch for group toggles
                int hy = top + slide;
                r.text(r.trim(s.name(), innerW - Layout.PAD * 2 - 24), innerX + Layout.PAD, hy + Layout.textY(SECTION_HEAD), p.text());
                BooleanSetting toggle = s.toggle();
                if (toggle != null) {
                    int sx = innerX + innerW - Layout.PAD - 16, sy = hy + SECTION_HEAD / 2 - 4;
                    boolean on = toggle.get();
                    r.pill(sx, sy, 16, 8, on ? p.accent() : p.control());
                    r.circle(on ? sx + 12 : sx + 4, sy + 4.0, 3, on ? 0xFFFFFFFF : p.textMuted());
                }
                r.gradientH(innerX + Layout.PAD, hy + SECTION_HEAD - 2, innerW - Layout.PAD * 2, 1,
                        ColorUtil.fade(p.accent(), 0.5), ColorUtil.withAlpha(p.accent(), 0));
                boolean headHot = Hit.inside(mouseX, mouseY, innerX, hy, innerW, SECTION_HEAD);
                if (headHot && s.group != null && !s.group.getDescription().isBlank()) {
                    ctx.requestTooltip(s, s.name(), s.group.getDescription());
                }
                top += SECTION_HEAD;
            }

            listX = innerX;
            listY = top;
            listW = innerW;
            listH = Math.max(0, bodyY + bodyH - top - 3);

            SettingList list = s.list;
            list.updateVisibility();
            int content = list.contentHeight() + Layout.SETTINGS_PAD_Y;
            s.maxScroll = Math.max(0, content - listH);
            s.scrollTarget = Math.clamp(s.scrollTarget, 0, s.maxScroll);
            s.scroll.animateTo(s.scrollTarget);

            boolean inList = ctx.hasCapture() || Hit.inside(mouseX, mouseY, listX, listY, listW, listH);
            int lmx = inList ? mouseX : Integer.MIN_VALUE / 2, lmy = inList ? mouseY : Integer.MIN_VALUE / 2;
            r.pushClip(listX, listY, listW, listH);
            try {
                int rowsTop = listY + 2 + slide - (int) Math.round(s.scroll.get());
                list.render(r, listX, rowsTop, listW - 3, listY, listY + listH, lmx, lmy, delta);
                if (!list.hasVisibleRows()) {
                    r.textCentered("Nothing to set here right now", listX + listW / 2, listY + 10, p.textDisabled());
                }
            } finally {
                r.popClip();
            }

            if (s.maxScroll > 0) {
                int trackH = listH - 4;
                int thumbH = Math.max(10, (int) (trackH * (listH / (double) content)));
                int thumbY = listY + 2 + (int) Math.round((trackH - thumbH) * (s.scroll.get() / s.maxScroll));
                barTrackY = listY + 2;
                barTrackH = trackH;
                barThumbH = thumbH;
                boolean barHot = drag == Drag.SCROLL || (!ctx.hasCapture() && overScrollBar(mouseX, mouseY));
                int bw = barHot ? 3 : 2;
                r.pill(listX + listW - bw, thumbY, bw, thumbH, ColorUtil.fade(p.accent(), barHot ? 1.0 : inList ? 0.9 : 0.4));
            } else {
                barTrackH = 0;
            }
        } finally {
            r.popAlpha();
        }
    }

    private boolean overList(double mx, double my) {
        return Hit.inside(mx, my, listX, listY, listW, listH);
    }

    /** The scroll bar strip at the right of the settings list (only while the section can scroll). */
    private boolean overScrollBar(double mx, double my) {
        return selected != null && selected.maxScroll > 0 && barTrackH > 0
                && mx >= listX + listW - 6 && mx < listX + listW + 2 && my >= listY && my < listY + listH;
    }

    private boolean overPreview(double mx, double my) {
        return preview != null && Hit.inside(mx, my, previewX, previewY, previewW, previewH);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (module == null || closing) return false;
        if (!isOver(mx, my)) return false;

        if (button == MouseButton.LEFT) {
            Drag edge = edgeAt(mx, my);
            if (edge != Drag.NONE) {
                startDrag(edge, mx, my);
                return true;
            }
        }

        if (my < y + TITLE_H) return clickTitle(mx, my, button);

        if (sideW > 0 && mx < x + sideW) {
            for (Section s : sections) {
                if (!s.visible() || my < s.rowY || my >= s.rowY + SIDE_ROW) continue;
                BooleanSetting toggle = s.toggle();
                if (toggle != null && button == MouseButton.LEFT && mx >= x + sideW - Layout.PAD - 16) {
                    toggle.toggle();
                    return true;
                }
                if (button == MouseButton.LEFT || button == MouseButton.RIGHT) select(s);
                return true;
            }
            return true;
        }

        if (overPreview(mx, my)) {
            if (preview.mouseClicked(mx, my, button)) {
                previewHeld = true;
                ctx.capture(this);
            }
            return true;
        }

        Section s = selected;
        if (s != null && sideW > 0 && s.toggle() != null && button == MouseButton.LEFT
                && my >= listY - SECTION_HEAD && my < listY && mx >= listX + listW - Layout.PAD - 20 && mx < listX + listW) {
            s.toggle().toggle();
            return true;
        }
        if (s != null && button == MouseButton.LEFT && overScrollBar(mx, my)) {
            int range = barTrackH - barThumbH;
            int thumbY = barTrackY + (int) Math.round(range * (s.scroll.get() / s.maxScroll));
            if ((my < thumbY || my >= thumbY + barThumbH) && range > 0) // track click: jump the thumb under the mouse
                s.scrollTarget = Math.clamp((my - barTrackY - barThumbH / 2.0) / range * s.maxScroll, 0, s.maxScroll);
            startDrag(Drag.SCROLL, mx, my);
            startScroll = s.scrollTarget;
            return true;
        }
        if (s != null && overList(mx, my)) s.list.mouseClicked(mx, my, button);
        return true;
    }

    private void confirmReset() {
        Module m = module;
        Confirm.show(new ConfirmRequest("Reset " + m.getName(),
                "Reset every setting of " + m.getName() + " to its default? This can't be undone.",
                "Reset", "Cancel", m::resetToDefaults, null));
    }

    private void confirmImport() {
        Module m = module;
        String clip = InputUtil.getClipboard();
        Confirm.show(new ConfirmRequest("Import " + m.getName(),
                "Overwrite the settings of " + m.getName() + " with the share code in your clipboard?",
                "Import", "Cancel", () -> ShareSettingsModule.applyCode(clip, m), null));
    }

    private boolean clickTitle(double mx, double my, int button) {
        int bx = closeX();
        if (mx >= bx && mx < bx + BUTTON) {
            if (button == MouseButton.LEFT) close();
            return true;
        }
        boolean toggleable = module.isToggleable();
        if (toggleable && mx >= switchX() - 2 && mx < switchX() + 18) {
            if (button == MouseButton.LEFT) module.toggleByUser();
            return true;
        }
        for (TitleButton b : titleButtons) {
            if (b.contains(mx)) {
                if (button == MouseButton.LEFT) b.action.run();
                return true;
            }
        }
        if (!history.isEmpty() && mx >= x + 2 && mx < x + 14) {
            if (button == MouseButton.LEFT) back();
            return true;
        }
        if (button == MouseButton.LEFT) startDrag(Drag.MOVE, mx, my);
        return true;
    }

    private void startDrag(Drag d, double mx, double my) {
        drag = d;
        grabX = mx;
        grabY = my;
        startX = state.x;
        startY = state.y;
        startW = state.w;
        startH = state.h;
        ctx.capture(this);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (module == null) return false;
        boolean handled = false;
        if (drag != Drag.NONE && button == MouseButton.LEFT) {
            drag = Drag.NONE;
            ctx.releaseCapture(this);
            handled = true;
        }
        if (previewHeld) {
            preview.mouseReleased(mx, my, button);
            previewHeld = false;
            ctx.releaseCapture(this);
            handled = true;
        }
        if (selected != null) handled |= selected.list.mouseReleased(mx, my, button);
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (module == null || closing || !isOver(mx, my)) return false;
        if (overPreview(mx, my)) {
            preview.mouseScrolled(mx, my, amount);
            return true;
        }
        Section s = selected;
        if (s != null && overList(mx, my)) {
            if (s.list.mouseScrolled(mx, my, amount)) return true;
            s.scrollTarget = Math.clamp(s.scrollTarget - amount * Layout.SCROLL_STEP, 0, s.maxScroll);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        return selected != null && selected.list.keyPressed(key, scancode, modifiers);
    }

    @Override
    public boolean charTyped(int codepoint, int modifiers) {
        return selected != null && selected.list.charTyped(codepoint, modifiers);
    }

    @Override
    public boolean isHovered(double mx, double my) {
        return isOver(mx, my);
    }
}
