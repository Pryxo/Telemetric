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
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One module row plus its settings accordion.
 * <ul>
 *   <li>Left-click row: toggle (toggleable modules) or expand (settings groups).</li>
 *   <li>Right-click row: expand / collapse settings.</li>
 *   <li>Left-drag the grip (dots) of a movable setting: reorder it; other rows slide out of the way.</li>
 * </ul>
 * Accordion uses {@link Durations#ACCORDION} with in-out easing; content is clipped to the animated height.
 */
public class ModuleComponent extends UIComponent {

    /** Alpha factor of the open settings area's background (the world shows through a little). */
    private static final double SETTINGS_BG_OPACITY = 0.72;
    /** Hue step (0..1) between the lines of nested group-toggle levels. */
    private static final float GROUP_HUE_STEP = 0.12f;

    private final Module module;
    private final List<SettingComponent<?>> settings = new ArrayList<>();

    private final Animation hover = new Animation(Durations.HOVER, Easing.OUT_CUBIC);
    private final Animation enabled;
    private final Animation expand = new Animation(Durations.ACCORDION, Easing.IN_OUT_CUBIC);
    private boolean expanded;
    /** Opened by the ClickGUI search because a setting matched; never persisted (see {@link #isExpanded()}). */
    private boolean searchOpen;

    // ---- reordering of movable settings (Setting#movable)
    private int orderVersion;
    /** Head component being dragged, or null. */
    private SettingComponent<?> dragging;
    /** Unanimated layout Y of each component this frame (drag hit-testing ignores slides). */
    private final Map<SettingComponent<?>, Integer> layoutY = new IdentityHashMap<>();
    /** Visual Y of each component just before a reorder; consumed by the next layout to start slides. */
    private Map<SettingComponent<?>, Integer> slideFrom;
    private final Map<SettingComponent<?>, Slide> slides = new IdentityHashMap<>();

    private static final class Slide {
        final Animation progress = new Animation(Durations.STANDARD, Easing.OUT_CUBIC, 0);
        int delta;

        int offset() {
            return (int) Math.round(delta * progress.get());
        }
    }

    public ModuleComponent(GuiContext ctx, Module module) {
        this(ctx, module, false, Set.of());
    }

    /**
     * @param open         whether the settings accordion starts open (restored from {@link dev.telemetric.config.GuiLayout})
     * @param openSettings names of expandable settings (dropdowns, pickers) that start open
     */
    public ModuleComponent(GuiContext ctx, Module module, boolean open, Set<String> openSettings) {
        super(ctx);
        this.module = module;
        this.enabled = new Animation(Durations.MICRO, Easing.OUT_CUBIC, module.isEnabled() ? 1 : 0);
        for (Setting<?> setting : module.getGuiSettings()) {
            SettingComponent<?> component = SettingComponents.create(ctx, setting);
            if (component.isExpandable() && openSettings.contains(setting.getName())) component.setExpanded(true, true);
            settings.add(component);
        }
        this.orderVersion = module.getOrderVersion();
        this.expanded = open && hasSettings();
        this.expand.snapTo(this.expanded ? 1 : 0);
    }

    /** Whether the user opened the settings accordion (a search-only open does not count, so it is not saved). */
    public boolean isExpanded() {
        return expanded;
    }

    private boolean isOpen() {
        return expanded || searchOpen;
    }

    private void toggleOpen() {
        expanded = !isOpen();
        searchOpen = false;
    }

    /**
     * Applies the ClickGUI search. A module whose name matches shows normally; otherwise only the settings whose
     * label matches stay, together with their parent group toggles (the children of a matching group follow it),
     * and the accordion opens to show them. Empty query = everything back to normal.
     *
     * @return whether this module stays listed
     */
    public boolean applySearch(String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty() || module.getName().toLowerCase(Locale.ROOT).contains(q)) {
            searchOpen = false;
            for (SettingComponent<?> c : settings) c.setSearchVisibility(null);
            return true;
        }
        Set<Setting<?>> matches = new HashSet<>();
        for (SettingComponent<?> c : settings) {
            Setting<?> s = c.getSetting();
            boolean named = s.getLabel().toLowerCase(Locale.ROOT).contains(q) || s.getName().toLowerCase(Locale.ROOT).contains(q);
            if (named && s.isVisibleIgnoringGroups()) { // settings hidden by their own rules don't count
                matches.add(s);
            }
        }
        Set<Setting<?>> keep = new HashSet<>(matches);
        for (Setting<?> m : matches) for (Setting<?> g = m.getGroup(); g != null; g = g.getGroup()) keep.add(g);
        for (SettingComponent<?> c : settings) {
            Setting<?> s = c.getSetting();
            if (keep.contains(s)) {
                c.setSearchVisibility(s::isVisibleIgnoringGroups);
                continue;
            }
            Setting<?> root = null; // nearest matching group above this setting
            for (Setting<?> g = s.getGroup(); g != null && root == null; g = g.getGroup()) if (matches.contains(g)) root = g;
            Setting<?> r = root;
            c.setSearchVisibility(r == null ? () -> false : () -> s.isVisibleUnder(r));
        }
        searchOpen = !matches.isEmpty();
        return searchOpen;
    }

    /** Names of expandable settings that are currently open. */
    public Set<String> openSettingNames() {
        Set<String> names = new LinkedHashSet<>();
        for (SettingComponent<?> c : settings) {
            if (c.isExpandable() && c.isExpanded()) names.add(c.getSetting().getName());
        }
        return names;
    }

    private int settingsHeight() {
        int total = 0;
        for (SettingComponent<?> c : settings) total += c.getHeight();
        return total == 0 ? 0 : total + Layout.SETTINGS_PAD_Y * 2;
    }

    @Override
    public int getHeight() {
        return Layout.MODULE_HEIGHT + (int) Math.round(settingsHeight() * expand.get());
    }

    public boolean hasSettings() {
        return !settings.isEmpty();
    }

    @Override
    public void render(Render2D r, int mouseX, int mouseY, float delta) {
        syncOrder();
        for (SettingComponent<?> c : settings) c.updateVisibility();
        Theme p = ctx.palette();

        boolean rowHovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + Layout.MODULE_HEIGHT;
        hover.animateTo(rowHovered ? 1 : 0);
        enabled.animateTo(module.isEnabled() ? 1 : 0);
        expand.animateTo(isOpen() ? 1 : 0);
        double h = hover.get(), e = enabled.get(), ex = expand.get();

        // row background: surface → hover, tinted when enabled
        int bg = ColorUtil.lerp(p.surface(), p.surfaceActive(), e);
        bg = ColorUtil.lerp(bg, p.surfaceHover(), h * 0.7);
        r.rect(x, y, width, Layout.MODULE_HEIGHT, bg);

        // enabled accent bar grows from the centre; while the settings open it stretches down to join their line
        int barY = 0, barH = 0;
        boolean open = ex > 0.001 && hasSettings();
        if (e > 0.01) {
            barH = (int) Math.round((Layout.MODULE_HEIGHT - 4) * e);
            barY = y + (Layout.MODULE_HEIGHT - barH) / 2;
            if (open) barH = (int) Math.round(barH + (y + Layout.MODULE_HEIGHT - barY - barH) * ex);
            neonLine(r, x, barY, 2, barH, p.accent(), e);
        }

        // name (slides right a touch on hover)
        double emphasis = module.isToggleable() ? h : Math.max(h, 0.75);
        int nameColor = ColorUtil.lerp(p.textMuted(), p.text(), emphasis);
        if (e > 0) nameColor = ColorUtil.lerp(nameColor, 0xFFFFFFFF, e * 0.6);
        int nx = x + Layout.PAD + (int) Math.round(h * 2);
        r.text(r.trim(module.getName(), width - Layout.PAD * 2 - 12), nx, y + Layout.textY(Layout.MODULE_HEIGHT), nameColor);

        // expand indicator: plus that morphs into a minus
        if (hasSettings()) Icons.plusMinus(r, x + width - Layout.PAD - 3, y + Layout.MODULE_HEIGHT / 2, ex,
                ColorUtil.lerp(p.textDisabled(), p.textMuted(), Math.max(h, ex)));

        if (rowHovered) ctx.requestTooltip(module, module.getDescription());

        // settings accordion
        if (ex > 0.001 && hasSettings()) {
            int areaY = y + Layout.MODULE_HEIGHT;
            int areaH = getHeight() - Layout.MODULE_HEIGHT;
            r.pushClip(x, areaY, width, areaH);
            // translucent: the panel leaves this area unpainted, so the world shows through a little
            r.rect(x, areaY, width, areaH, ColorUtil.fade(p.settingsBg(), SETTINGS_BG_OPACITY));
            // left accent line of the whole settings area
            neonLine(r, x, areaY, 2, Math.max(0, areaH - 1), p.accent(), 0.55 + e * 0.45);
            int cy = areaY + Layout.SETTINGS_PAD_Y;
            int innerX = x + Layout.SETTINGS_INDENT;
            int innerW = width - Layout.SETTINGS_INDENT;
            boolean mouseInArea = ctx.hasCapture() || (mouseY >= areaY && mouseY < areaY + areaH);
            int my = mouseInArea ? mouseY : Integer.MIN_VALUE / 2;
            for (SettingComponent<?> c : settings) {
                int ch = c.getHeight();
                layoutY.put(c, cy);
                int depth = c.getSetting().groupDepth();
                int indent = depth * Layout.GROUP_INDENT;
                c.setBounds(innerX + indent, cy + slideOffset(c, cy), innerW - indent, ch);
                if (ch > 0 && c.getY() < areaY + areaH) {
                    // one line per nesting level; each level inside a group toggle gets its own colour
                    for (int d = 1; d <= depth; d++) {
                        neonLine(r, innerX + d * Layout.GROUP_INDENT - 3, c.getY(), 1, ch,
                                groupLineColor(p, d), 0.75 * c.shown());
                    }
                    if (c == dragging) {
                        int gh = groupHeight(c);
                        r.rect(c.getX(), c.getY(), c.getWidth(), gh, ColorUtil.fade(p.accent(), 0.14));
                        r.outline(c.getX(), c.getY(), c.getWidth(), gh, ColorUtil.fade(p.accent(), 0.7));
                    }
                    c.render(r, mouseX, my, delta);
                    if (c.getSetting().isMovable()) renderGrip(r, p, c, mouseX, my);
                }
                cy += ch;
            }
            slideFrom = null;
            r.popClip();
            if (dragging != null) updateDrag(mouseY);
        }

        // light pulse: down the bar, and on through the settings line while they are open (drawn last = on top)
        if (barH > 0) {
            int end = open ? y + getHeight() - 1 : barY + barH;
            sweep(r, x, barY, 2, end - barY, p.accent(), e);
        }
    }

    // ------------------------------------------------------------------ reordering

    /** Rebuilds the component order after a drag, profile load or reset changed the module's setting order. */
    private void syncOrder() {
        if (module.getOrderVersion() == orderVersion) return;
        orderVersion = module.getOrderVersion();
        Map<Setting<?>, SettingComponent<?>> bySetting = new HashMap<>();
        Map<SettingComponent<?>, Integer> before = new IdentityHashMap<>();
        for (SettingComponent<?> c : settings) {
            bySetting.put(c.getSetting(), c);
            before.put(c, c.getY());
        }
        List<SettingComponent<?>> next = new ArrayList<>(settings.size());
        for (Setting<?> s : module.getGuiSettings()) {
            SettingComponent<?> c = bySetting.get(s);
            if (c != null) next.add(c);
        }
        if (next.size() != settings.size()) return; // settings never change at runtime; be defensive
        settings.clear();
        settings.addAll(next);
        if (expand.get() > 0.001) slideFrom = before;
    }

    /** Visual offset of {@code c} at layout position {@code cy}; starts a slide when the row just moved. */
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
        for (SettingComponent<?> c : settings) if (followers.contains(c.getSetting())) h += c.getHeight();
        return h;
    }

    /** Vertical neon line: clean bright core (tinted towards white), no halo. */
    private static void neonLine(Render2D r, int lx, int ly, int w, int h, int color, double strength) {
        if (h <= 0 || strength <= 0.001) return;
        r.rect(lx, ly, w, h, ColorUtil.fade(ColorUtil.lerp(color, 0xFFFFFFFF, 0.25), strength));
    }

    /** Light pulse travelling down a line top → bottom, then again from the top ({@link Durations#AMBIENT_LOOP}). */
    private static void sweep(Render2D r, int lx, int ly, int w, int h, int color, double strength) {
        int period = Durations.loop(Durations.AMBIENT_LOOP);
        if (period <= 0 || h < 4) return;
        double t = (System.currentTimeMillis() % period) / (double) period;
        int len = Math.max(3, h / 2);
        int center = ly - len + (int) Math.round(t * (h + len * 2));
        int bright = ColorUtil.fade(ColorUtil.lerp(color, 0xFFFFFFFF, 0.7), strength);
        int clear = ColorUtil.withAlpha(bright, 0);
        r.pushClip(lx, ly, w, h);
        r.gradientV(lx, center - len, w, len, clear, bright);
        r.gradientV(lx, center, w, len, bright, clear);
        r.popClip();
    }

    /** Line colour of group-toggle nesting level {@code depth} (1+): the accent, hue-shifted per level. */
    private static int groupLineColor(Theme p, int depth) {
        float[] hsv = ColorUtil.toHsv(p.accent());
        float hue = (hsv[0] + depth * GROUP_HUE_STEP) % 1f;
        return ColorUtil.hsv(hue, hsv[1], hsv[2], 255);
    }

    private static int gripRowHeight(SettingComponent<?> c) {
        return Math.min(c.getHeight(), Layout.SETTING_HEIGHT);
    }

    private static boolean overGrip(SettingComponent<?> c, double mouseX, double mouseY) {
        return mouseX >= c.getX() && mouseX < c.getX() + Layout.GRIP_WIDTH
                && mouseY >= c.getY() && mouseY < c.getY() + gripRowHeight(c);
    }

    /** 2x3 dot grip: faint at rest, brighter on row hover, accent while dragged. */
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
     * Moves the dragged group past every neighbour whose toggle row (its head row, not its whole group) the mouse
     * reached: the move happens the moment the cursor is over the neighbour's toggle. Both directions test the
     * neighbour's head row, so a swap never immediately undoes itself (no flicker).
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

    /** {top, bottom, headRowHeight} layout span of a movable setting plus its followers, or null when hidden. */
    private int[] span(Setting<?> head) {
        SettingComponent<?> hc = null;
        for (SettingComponent<?> c : settings) {
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

    private void stopDrag() {
        if (dragging == null) return;
        dragging = null;
        ctx.releaseCapture(this);
    }

    // ------------------------------------------------------------------ input

    private boolean settingsInteractive() {
        return isOpen() && expand.get() > 0.95;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isHovered(mouseX, mouseY)) return false;
        if (mouseY < y + Layout.MODULE_HEIGHT) {
            if (button == MouseButton.LEFT) {
                if (module.isToggleable()) module.toggle();
                else toggleOpen();
                return true;
            }
            if (button == MouseButton.RIGHT) {
                if (hasSettings()) toggleOpen();
                return true;
            }
            return false;
        }
        if (!settingsInteractive()) return true;
        if (button == MouseButton.LEFT) {
            for (SettingComponent<?> c : settings) {
                if (c.getSetting().isMovable() && c.getHeight() > 0 && overGrip(c, mouseX, mouseY)) {
                    dragging = c;
                    ctx.capture(this);
                    return true;
                }
            }
        }
        for (SettingComponent<?> c : settings) {
            if (c.mouseClicked(mouseX, mouseY, button)) return true;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = false;
        if (dragging != null && button == MouseButton.LEFT) {
            stopDrag();
            handled = true;
        }
        for (SettingComponent<?> c : settings) handled |= c.mouseReleased(mouseX, mouseY, button);
        return handled;
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        for (SettingComponent<?> c : settings) if (c.keyPressed(key, scancode, modifiers)) return true;
        return false;
    }

    @Override
    public boolean charTyped(int codepoint, int modifiers) {
        for (SettingComponent<?> c : settings) if (c.charTyped(codepoint, modifiers)) return true;
        return false;
    }

    @Override
    public boolean isHovered(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + getHeight();
    }

    @Override
    public void onClose() {
        stopDrag();
        settings.forEach(UIComponent::onClose);
    }

    public Module getModule() {
        return module;
    }
}
