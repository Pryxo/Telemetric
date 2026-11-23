package dev.telemetric.ui.clickgui;

import dev.telemetric.module.Module;
import dev.telemetric.setting.Setting;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.clickgui.setting.SettingComponent;
import dev.telemetric.ui.clickgui.setting.SettingComponents;
import dev.telemetric.ui.framework.GuiContext;
import dev.telemetric.ui.framework.MouseButton;
import dev.telemetric.ui.framework.UIComponent;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Icons;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * A vertical list of setting rows, shared by the module right-click menu ({@link ModuleComponent}) and the Settings
 * window. It lays the rows out (indented per group level, one coloured line per level), lets movable settings be
 * dragged by their grip (the other rows slide out of the way), and in the window draws a pin star left of each row.
 * <p>
 * The list doesn't own a frame or background; the container draws those, calls {@link #render} with the list's top
 * and width, and clips it.
 */
public final class SettingList {

    /** Width of the pin-star column left of the rows (only when pins are on). */
    public static final int PIN_GUTTER = 11;
    /** Hue step (0..1) between the lines of nested group-toggle levels. */
    private static final float GROUP_HUE_STEP = 0.12f;

    /** What the rows are pinned to: {@code isPinned} and {@code toggle} by setting. */
    public interface Pins {
        boolean isPinned(Setting<?> setting);

        void toggle(Setting<?> setting);
    }

    private final GuiContext ctx;
    private final Module module;
    private final List<SettingComponent<?>> rows = new ArrayList<>();
    private final ToIntFunction<Setting<?>> depthOf;
    private final boolean reorder;
    private Pins pins;
    private Set<Setting<?>> highlight = Set.of();
    private final UIComponent owner;

    private int orderVersion;
    private SettingComponent<?> dragging;
    private final Map<SettingComponent<?>, Integer> layoutY = new IdentityHashMap<>();
    private Map<SettingComponent<?>, Integer> slideFrom;
    private final Map<SettingComponent<?>, Slide> slides = new IdentityHashMap<>();
    private final Map<Setting<?>, Animation> starHover = new IdentityHashMap<>();

    private int x, y, width;

    private static final class Slide {
        final Animation progress = new Animation(Durations.STANDARD, Easing.OUT_CUBIC, 0);
        int delta;

        int offset() {
            return (int) Math.round(delta * progress.get());
        }
    }

    /**
     * @param owner      the component that takes mouse capture while a row is dragged
     * @param settings   the settings to show, in module GUI order
     * @param visibility the visibility rule of each row (e.g. "visible inside this section")
     * @param depthOf    the indent level of each row (0 = no indent)
     * @param reorder    whether movable settings can be dragged
     * @param openNames  names of the expandable settings (dropdowns, pickers, groups) that start open
     */
    public SettingList(GuiContext ctx, UIComponent owner, Module module, Collection<Setting<?>> settings,
                       Function<Setting<?>, BooleanSupplier> visibility, ToIntFunction<Setting<?>> depthOf,
                       boolean reorder, Set<String> openNames) {
        this.ctx = ctx;
        this.owner = owner;
        this.module = module;
        this.depthOf = depthOf;
        this.reorder = reorder;
        for (Setting<?> setting : settings) {
            SettingComponent<?> c = SettingComponents.create(ctx, setting);
            c.setSearchVisibility(visibility.apply(setting));
            if (c.isExpandable() && openNames.contains(setting.getName())) c.setExpanded(true, true);
            rows.add(c);
        }
        this.orderVersion = module.getOrderVersion();
    }

    public void setPins(Pins pins) {
        this.pins = pins;
    }

    /** Rows to tint (e.g. ClickGUI search matches). */
    public void setHighlight(Set<Setting<?>> settings) {
        this.highlight = settings == null ? Set.of() : settings;
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    public List<SettingComponent<?>> rows() {
        return rows;
    }

    /** Whether any row is visible (or still fading out). */
    public boolean hasVisibleRows() {
        for (SettingComponent<?> c : rows) if (c.getHeight() > 0) return true;
        return false;
    }

    /** Height of all rows right now (animated). Call {@link #updateVisibility()} first each frame. */
    public int contentHeight() {
        int total = 0;
        for (SettingComponent<?> c : rows) total += c.getHeight();
        return total;
    }

    public void updateVisibility() {
        syncOrder();
        for (SettingComponent<?> c : rows) c.updateVisibility();
    }

    /** The names of the expandable settings that are open right now. */
    public Set<String> openNames() {
        Set<String> names = new LinkedHashSet<>();
        for (SettingComponent<?> c : rows) if (c.isExpandable() && c.isExpanded()) names.add(c.getSetting().getName());
        return names;
    }

    private int gutter() {
        return pins != null ? PIN_GUTTER : 0;
    }

    // ------------------------------------------------------------------ rendering

    /**
     * Draws the rows from {@code top}. Rows outside {@code [clipTop, clipBottom)} are skipped (the caller clips).
     * {@code mouseY} should already be moved off-screen when the mouse isn't over the visible area.
     */
    public void render(Render2D r, int left, int top, int w, int clipTop, int clipBottom, int mouseX, int mouseY, float delta) {
        Theme p = ctx.palette();
        this.x = left;
        this.y = top;
        this.width = w;
        int innerX = left + gutter();
        int innerW = w - gutter();
        int cy = top;
        for (SettingComponent<?> c : rows) {
            int ch = c.getHeight();
            layoutY.put(c, cy);
            int depth = Math.max(0, depthOf.applyAsInt(c.getSetting()));
            int indent = depth * Layout.GROUP_INDENT;
            c.setBounds(innerX + indent, cy + slideOffset(c, cy), innerW - indent, ch);
            if (ch > 0 && c.getY() < clipBottom && c.getY() + ch > clipTop) {
                for (int d = 1; d <= depth; d++) {
                    neonLine(r, innerX + d * Layout.GROUP_INDENT - 3, c.getY(), 1, ch, groupLineColor(p, d), 0.75 * c.shown());
                }
                if (highlight.contains(c.getSetting())) {
                    int rh = Math.min(ch, Layout.SETTING_HEIGHT);
                    r.rect(c.getX(), c.getY(), c.getWidth(), rh, ColorUtil.fade(p.accent(), 0.13 * c.shown()));
                    r.rect(c.getX(), c.getY(), 1, rh, ColorUtil.fade(p.accent(), 0.9 * c.shown()));
                }
                if (c == dragging) {
                    int gh = groupHeight(c);
                    r.rect(c.getX(), c.getY(), c.getWidth(), gh, ColorUtil.fade(p.accent(), 0.14));
                    r.outline(c.getX(), c.getY(), c.getWidth(), gh, ColorUtil.fade(p.accent(), 0.7));
                }
                c.render(r, mouseX, mouseY, delta);
                if (reorder && c.getSetting().isMovable()) renderGrip(r, p, c, mouseX, mouseY);
                if (pins != null) renderStar(r, p, c, left, mouseX, mouseY);
            }
            cy += ch;
        }
        slideFrom = null;
        if (dragging != null) updateDrag(mouseY);
    }

    /** A vertical neon line: a clean bright core (tinted towards white), no halo. */
    public static void neonLine(Render2D r, int lx, int ly, int w, int h, int color, double strength) {
        if (h <= 0 || strength <= 0.001) return;
        r.rect(lx, ly, w, h, ColorUtil.fade(ColorUtil.lerp(color, 0xFFFFFFFF, 0.1), strength));
    }

    /** The line colour for group nesting level {@code depth} (1 and up): the accent, hue-shifted per level. */
    public static int groupLineColor(Theme p, int depth) {
        float[] hsv = ColorUtil.toHsv(p.accent());
        float hue = (hsv[0] + depth * GROUP_HUE_STEP) % 1f;
        return ColorUtil.hsv(hue, hsv[1], hsv[2], 255);
    }

    // ------------------------------------------------------------------ pin stars

    /** Every row can be pinned except the bind, which the right-click menu always shows. */
    private boolean pinnable(Setting<?> s) {
        return s != module.getBind();
    }

    private boolean overStar(SettingComponent<?> c, double mouseX, double mouseY) {
        int rh = Math.min(c.getHeight(), Layout.SETTING_HEIGHT);
        return mouseX >= x && mouseX < x + PIN_GUTTER && mouseY >= c.getY() && mouseY < c.getY() + rh;
    }

    private void renderStar(Render2D r, Theme p, SettingComponent<?> c, int left, int mouseX, int mouseY) {
        int rh = Math.min(c.getHeight(), Layout.SETTING_HEIGHT);
        if (rh < 8) return;
        Setting<?> s = c.getSetting();
        if (!pinnable(s)) return;
        boolean pinned = pins.isPinned(s);
        boolean rowHover = mouseY >= c.getY() && mouseY < c.getY() + rh && mouseX >= left && mouseX < left + width;
        boolean hot = dragging == null && overStar(c, mouseX, mouseY);
        Animation a = starHover.computeIfAbsent(s, k -> new Animation(Durations.HOVER, Easing.OUT_CUBIC, 0));
        a.animateTo(pinned || rowHover ? 1 : 0);
        double t = a.get() * c.shown();
        if (t <= 0.01) return;
        int color;
        if (pinned) color = hot ? ColorUtil.lerp(p.accent(), 0xFFFFFFFF, 0.3) : p.accent();
        else color = hot ? p.text() : ColorUtil.fade(p.textDisabled(), 0.8);
        Icons.star(r, left + PIN_GUTTER / 2, c.getY() + rh / 2, pinned, ColorUtil.fade(color, t));
        if (hot) ctx.requestTooltip(this, pinned ? "Unpin from the right-click menu" : "Pin to the right-click menu");
    }

    // ------------------------------------------------------------------ reordering

    /** Rebuilds the row order after a drag, profile load or reset changed the module's setting order. */
    private void syncOrder() {
        if (module.getOrderVersion() == orderVersion) return;
        orderVersion = module.getOrderVersion();
        Map<Setting<?>, SettingComponent<?>> bySetting = new HashMap<>();
        Map<SettingComponent<?>, Integer> before = new IdentityHashMap<>();
        for (SettingComponent<?> c : rows) {
            bySetting.put(c.getSetting(), c);
            before.put(c, c.getY());
        }
        List<SettingComponent<?>> next = new ArrayList<>(rows.size());
        for (Setting<?> s : module.getGuiSettings()) {
            SettingComponent<?> c = bySetting.get(s);
            if (c != null) next.add(c);
        }
        if (next.size() != rows.size()) return;
        rows.clear();
        rows.addAll(next);
        slideFrom = before;
    }

    private int slideOffset(SettingComponent<?> c, int cy) {
        Slide slide = slides.get(c);
        if (slideFrom != null) {
            Integer from = slideFrom.get(c);
            int d = from == null ? 0 : from - cy;
            if (d != 0) {
                if (slide == null) slides.put(c, slide = new Slide());
                slide.delta = d;
                slide.progress.snapTo(1).animateTo(0);
            }
        }
        if (slide != null && slide.offset() == 0 && !slide.progress.isRunning()) {
            slides.remove(c);
            return 0;
        }
        return slide == null ? 0 : slide.offset();
    }

    private int groupHeight(SettingComponent<?> head) {
        int h = head.getHeight();
        List<Setting<?>> followers = module.followersOf(head.getSetting());
        for (SettingComponent<?> c : rows) if (followers.contains(c.getSetting())) h += c.getHeight();
        return h;
    }

    private static int gripRowHeight(SettingComponent<?> c) {
        return Math.min(c.getHeight(), Layout.SETTING_HEIGHT);
    }

    private static boolean overGrip(SettingComponent<?> c, double mouseX, double mouseY) {
        return mouseX >= c.getX() && mouseX < c.getX() + Layout.GRIP_WIDTH
                && mouseY >= c.getY() && mouseY < c.getY() + gripRowHeight(c);
    }

    /** A 2x3 dot grip: faint at rest, brighter when the row is hovered, accent-coloured while dragged. */
    private void renderGrip(Render2D r, Theme p, SettingComponent<?> c, int mouseX, int mouseY) {
        int rh = gripRowHeight(c);
        if (rh < 6) return;
        boolean rowHover = mouseX >= c.getX() && mouseX < c.getX() + c.getWidth() && mouseY >= c.getY() && mouseY < c.getY() + rh;
        boolean gripHover = dragging == null && overGrip(c, mouseX, mouseY);
        int color;
        if (c == dragging) color = p.accent();
        else if (gripHover) color = p.text();
        else if (rowHover) color = p.textMuted();
        else color = ColorUtil.fade(p.textDisabled(), 0.6);
        int gx = c.getX() + 1, gy = c.getY() + (rh - 5) / 2;
        for (int col = 0; col < 2; col++) {
            for (int row = 0; row < 3; row++) r.rect(gx + col * 2, gy + row * 2, 1, 1, color);
        }
        if (gripHover) ctx.requestTooltip(this, "Drag to reorder");
    }

    /**
     * Moves the dragged group past every neighbour whose head row the mouse has reached. Both directions test the
     * neighbour's head row, so a swap never undoes itself right away (no flicker).
     */
    private void updateDrag(int mouseY) {
        Setting<?> head = dragging.getSetting();
        List<Setting<?>> order = module.getSettingOrder();
        int from = order.indexOf(head);
        if (from < 0) return;
        int target = from;
        for (int i = 0; i < order.size(); i++) {
            if (i == from) continue;
            int[] span = span(order.get(i));
            if (span == null) continue;
            if (i < from && mouseY < span[0] + span[2] && target == from) target = i;
            if (i > from && mouseY >= span[0]) target = i;
        }
        if (target != from) {
            module.moveSetting(head, target);
            syncOrder();
        }
    }

    private int[] span(Setting<?> head) {
        SettingComponent<?> hc = null;
        for (SettingComponent<?> c : rows) {
            if (c.getSetting() == head) {
                hc = c;
                break;
            }
        }
        if (hc == null || hc.getHeight() <= 0) return null;
        Integer top = layoutY.get(hc);
        if (top == null) return null;
        return new int[] {top, top + groupHeight(hc), gripRowHeight(hc)};
    }

    public void stopDrag() {
        if (dragging == null) return;
        dragging = null;
        ctx.releaseCapture(owner);
    }

    // ------------------------------------------------------------------ input

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (pins != null && button == MouseButton.LEFT) {
            for (SettingComponent<?> c : rows) {
                if (c.getHeight() > 0 && c.shown() > 0.9 && pinnable(c.getSetting()) && overStar(c, mouseX, mouseY)) {
                    pins.toggle(c.getSetting());
                    return true;
                }
            }
        }
        if (reorder && button == MouseButton.LEFT) {
            for (SettingComponent<?> c : rows) {
                if (c.getSetting().isMovable() && c.getHeight() > 0 && overGrip(c, mouseX, mouseY)) {
                    dragging = c;
                    ctx.capture(owner);
                    return true;
                }
            }
        }
        for (SettingComponent<?> c : rows) if (c.mouseClicked(mouseX, mouseY, button)) return true;
        return false;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = false;
        if (dragging != null && button == MouseButton.LEFT) {
            stopDrag();
            handled = true;
        }
        for (SettingComponent<?> c : rows) handled |= c.mouseReleased(mouseX, mouseY, button);
        return handled;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        for (SettingComponent<?> c : rows) if (c.mouseScrolled(mouseX, mouseY, amount)) return true;
        return false;
    }

    public boolean keyPressed(int key, int scancode, int modifiers) {
        for (SettingComponent<?> c : rows) if (c.keyPressed(key, scancode, modifiers)) return true;
        return false;
    }

    public boolean charTyped(int codepoint, int modifiers) {
        for (SettingComponent<?> c : rows) if (c.charTyped(codepoint, modifiers)) return true;
        return false;
    }

    public void onClose() {
        stopDrag();
        rows.forEach(UIComponent::onClose);
    }
}
