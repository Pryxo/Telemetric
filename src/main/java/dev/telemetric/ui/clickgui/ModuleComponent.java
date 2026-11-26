package dev.telemetric.ui.clickgui;

import dev.telemetric.Telemetric;
import dev.telemetric.config.GuiLayout;
import dev.telemetric.module.Module;
import dev.telemetric.setting.Setting;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.framework.GuiContext;
import dev.telemetric.ui.framework.MouseButton;
import dev.telemetric.ui.framework.UIComponent;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Fx;
import dev.telemetric.ui.render.Icons;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.render.Ripple;
import dev.telemetric.ui.render.WarningColors;
import dev.telemetric.ui.theme.Theme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * One module row plus its right-click menu.
 * <ul>
 *   <li>Left-click the row to toggle it (toggleable modules) or open its Settings window (settings groups).</li>
 *   <li>The gear on the right ("Config") opens the module's Settings window ({@code ui/clickgui/window}).</li>
 *   <li>Right-click the row to open a small menu with the bind and the settings pinned (star) in the window. With
 *   nothing to show there, right-click opens the window.</li>
 * </ul>
 * The menu uses {@link Durations#ACCORDION} with in-out easing, and the content is clipped to the animated height.
 */
public class ModuleComponent extends UIComponent {

    /** Alpha factor for the background of the open menu (the world shows through a little). */
    private static final double SETTINGS_BG_OPACITY = 0.9;
    private static final int GEAR_W = 12;
    /** Strength of the Hover Highlight wash at its left edge. */
    private static final double HOVER_WASH = 0.2;

    private final Ripple ripple = new Ripple();

    private final Module module;
    private final GuiLayout layout;
    private SettingList quick;
    private int pinsVersion = -1;
    private final Set<String> initialOpen;
    private final boolean hasConfig;

    private final Animation hover = new Animation(Durations.HOVER, Easing.OUT_CUBIC);
    private final Animation gearHover = new Animation(Durations.HOVER, Easing.OUT_CUBIC);
    private final Animation enabled;
    private final Animation expand = new Animation(Durations.ACCORDION, Easing.IN_OUT_CUBIC);
    private boolean expanded;
    /** Number of settings matching the ClickGUI search (0 = none or no search). */
    private int searchHits;

    public ModuleComponent(GuiContext ctx, Module module) {
        this(ctx, module, false, Set.of());
    }

    /**
     * @param open         whether the right-click menu starts open (restored from {@link GuiLayout})
     * @param openSettings names of the expandable settings (dropdowns, pickers) that start open
     */
    public ModuleComponent(GuiContext ctx, Module module, boolean open, Set<String> openSettings) {
        super(ctx);
        this.module = module;
        this.layout = Telemetric.get().config().getLayout();
        this.enabled = new Animation(Durations.MICRO, Easing.OUT_CUBIC, module.isEnabled() ? 1 : 0);
        this.initialOpen = openSettings;
        boolean config = false;
        for (Setting<?> s : module.getGuiSettings()) if (s != module.getBind()) config = true;
        this.hasConfig = config;
        rebuildQuick();
        this.expanded = open && hasQuick();
        this.expand.snapTo(this.expanded ? 1 : 0);
    }

    public boolean isExpanded() {
        return expanded;
    }

    private void rebuildQuick() {
        if (pinsVersion == layout.pinsVersion() && quick != null) return;
        Set<String> open = quick != null ? quick.openNames() : initialOpen;
        if (quick != null) quick.onClose();
        pinsVersion = layout.pinsVersion();

        List<String> pinNames = layout.pinsOf(module.getName());
        List<Setting<?>> gui = module.getGuiSettings();
        Set<Setting<?>> pinned = new HashSet<>();
        for (Setting<?> s : gui) if (pinNames.contains(s.getName())) pinned.add(s);
        // followers of a pinned movable setting come along
        for (Setting<?> s : new ArrayList<>(pinned)) pinned.addAll(module.followersOf(s));

        Map<Setting<?>, BooleanSupplier> rules = new HashMap<>();
        Map<Setting<?>, Integer> depth = new HashMap<>();
        List<Setting<?>> rows = new ArrayList<>();
        for (Setting<?> s : gui) {
            if (s == module.getBind()) {
                rows.add(s);
                rules.put(s, s::isVisible);
                depth.put(s, 0);
                continue;
            }
            Setting<?> root = null; // the top-most pinned group above s
            for (Setting<?> g = s.getGroup(); g != null; g = g.getGroup()) if (pinned.contains(g)) root = g;
            if (root != null) {
                Setting<?> r = root;
                rows.add(s);
                rules.put(s, () -> r.isGroupExpanded() && r.isVisibleIgnoringGroups() && s.isVisibleInSection(r));
                depth.put(s, s.groupDepth() - r.groupDepth());
            } else if (pinned.contains(s)) {
                rows.add(s);
                rules.put(s, s::isVisibleIgnoringGroups);
                depth.put(s, 0);
            }
        }
        quick = new SettingList(ctx, this, module, rows, rules::get, s -> depth.getOrDefault(s, 0), false, open);
        if (quick.isEmpty()) expanded = false;
    }

    private boolean hasQuick() {
        return quick != null && !quick.isEmpty();
    }

    /**
     * Applies the ClickGUI search. A module whose name matches, or that has a setting whose label matches, stays
     * listed; matching settings tint the gear (the Settings window highlights them).
     */
    public boolean applySearch(String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        searchHits = 0;
        if (q.isEmpty()) return true;
        for (Setting<?> s : module.getGuiSettings()) {
            boolean named = s.getLabel().toLowerCase(Locale.ROOT).contains(q) || s.getName().toLowerCase(Locale.ROOT).contains(q);
            if (named && s.isVisibleIgnoringGroups()) searchHits++;
        }
        return searchHits > 0 || module.getName().toLowerCase(Locale.ROOT).contains(q);
    }

    /** The names of the expandable settings in the right-click menu that are open right now. */
    public Set<String> openSettingNames() {
        return quick == null ? Set.of() : quick.openNames();
    }

    private int settingsHeight() {
        int total = quick == null ? 0 : quick.contentHeight();
        return total == 0 ? 0 : total + Layout.SETTINGS_PAD_Y * 2;
    }

    @Override
    public int getHeight() {
        return Layout.MODULE_HEIGHT + (int) Math.round(settingsHeight() * expand.get());
    }

    private final Object warningOwner = new Object();
    private final Object gearOwner = new Object();

    /** The warning as one tooltip paragraph, without a closing question (e.g. "Enable anyway?"). */
    private static String tooltipWarning(String warning) {
        StringBuilder sb = new StringBuilder();
        for (String para : warning.split("\n")) {
            String t = para.strip();
            if (t.isEmpty() || t.endsWith("?")) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(t);
        }
        return sb.toString();
    }

    private boolean showsGear() {
        return hasConfig && ctx.hasSettingsWindow();
    }

    private int gearLeft() {
        return x + width - Layout.PAD - GEAR_W + 2;
    }

    private boolean overGear(double mouseX, double mouseY) {
        return showsGear() && mouseX >= gearLeft() - 2 && mouseX < x + width && mouseY >= y && mouseY < y + Layout.MODULE_HEIGHT;
    }

    @Override
    public void render(Render2D r, int mouseX, int mouseY, float delta) {
        rebuildQuick();
        quick.updateVisibility();
        Theme p = ctx.palette();

        boolean rowHovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + Layout.MODULE_HEIGHT;
        hover.animateTo(rowHovered ? 1 : 0);
        enabled.animateTo(module.isEnabled() ? 1 : 0);
        expand.animateTo(expanded && hasQuick() ? 1 : 0);
        double h = hover.get(), e = enabled.get(), ex = expand.get();

        // row background goes from surface to hover, tinted when enabled
        int bg = ColorUtil.lerp(p.surface(), p.surfaceActive(), e);
        bg = ColorUtil.lerp(bg, p.surfaceHover(), h * 0.7);
        r.rect(x, y, width, Layout.MODULE_HEIGHT, bg);

        // Hover Highlight: an accent wash sliding in from the left, plus a faint bar on disabled rows
        int accentHere = Fx.flow(ctx.themes(), p, x, y);
        if (ctx.ui().hoverHighlight.get() && h > 0.01) {
            int washW = (int) Math.round(width * (0.3 + 0.5 * h));
            r.gradientH(x, y, washW, Layout.MODULE_HEIGHT, ColorUtil.fade(accentHere, HOVER_WASH * h),
                    ColorUtil.withAlpha(accentHere, 0));
            if (e < 0.99) {
                int hb = (int) Math.round((Layout.MODULE_HEIGHT - 6) * h);
                SettingList.neonLine(r, x, y + (Layout.MODULE_HEIGHT - hb) / 2, 1, hb, accentHere, 0.45 * h * (1 - e));
            }
        }
        ripple.render(r, x, y, width, Layout.MODULE_HEIGHT, module.isEnabled() ? accentHere : p.text());

        // the enabled accent bar grows from the centre, and while the menu opens it stretches down to join its line
        int barY = 0, barH = 0;
        boolean open = ex > 0.001 && hasQuick();
        if (e > 0.01) {
            barH = (int) Math.round((Layout.MODULE_HEIGHT - 4) * e);
            barY = y + (Layout.MODULE_HEIGHT - barH) / 2;
            if (open) barH = (int) Math.round(barH + (y + Layout.MODULE_HEIGHT - barY - barH) * ex);
            Fx.glow(r, ctx.themes(), x, barY, 2, barH, 0, accentHere, e);
            SettingList.neonLine(r, x, barY, 2, barH, accentHere, e);
        }

        // name (slides right a touch on hover)
        double emphasis = module.isToggleable() ? h : Math.max(h, 0.75);
        int nameColor = ColorUtil.lerp(p.textMuted(), p.text(), emphasis);
        if (e > 0) nameColor = ColorUtil.lerp(nameColor, p.text(), e);
        int nx = x + Layout.PAD + (int) Math.round(h * 2);
        String warning = module.getWarning();
        int reserve = (showsGear() ? GEAR_W + 2 : 4) + (warning != null ? 13 : 0);
        r.text(r.trim(module.getName(), width - Layout.PAD * 2 - reserve), nx, y + Layout.textY(Layout.MODULE_HEIGHT), nameColor);

        // Config gear: opens the Settings window (turns a tooth on hover, accent while its window is open / on a search hit)
        boolean gearHot = overGear(mouseX, mouseY);
        if (showsGear()) {
            gearHover.animateTo(gearHot ? 1 : 0);
            double gh = gearHover.get();
            boolean active = ctx.settingsModule() == module;
            int color = ColorUtil.lerp(p.textDisabled(), p.textMuted(), Math.max(h, 0.0));
            color = ColorUtil.lerp(color, p.text(), gh);
            if (active || searchHits > 0) color = ColorUtil.lerp(color, p.accent(), active ? 1 : 0.85);
            int gcx = x + width - Layout.PAD - 3, gcy = y + Layout.MODULE_HEIGHT / 2;
            Icons.gear(r, gcx - 1, gcy - 1, gh * 0.5, color);
        }

        // warning icon left of the gear (hover = yellow warning tooltip)
        boolean warnHovered = false;
        if (warning != null) {
            int iw = 9, ih = 8;
            int ix = showsGear() ? gearLeft() - 5 - iw : x + width - Layout.PAD - iw;
            int iy = y + (Layout.MODULE_HEIGHT - ih) / 2;
            Icons.warning(r, ix, iy, iw, ih, WarningColors.YELLOW);
            warnHovered = rowHovered && mouseX >= ix - 2 && mouseX < ix + iw + 2;
        }

        if (warnHovered) ctx.requestWarningTooltip(warningOwner, "Warning", tooltipWarning(warning));
        else if (gearHot) ctx.requestTooltip(gearOwner, "Config", searchHits > 0
                ? searchHits + (searchHits == 1 ? " setting matches" : " settings match") + " the search"
                : "Open the settings of " + module.getName());
        else if (rowHovered) ctx.requestTooltip(module, module.getName(), module.getDescription());

        // right-click menu
        if (open) {
            int areaY = y + Layout.MODULE_HEIGHT;
            int areaH = getHeight() - Layout.MODULE_HEIGHT;
            r.pushClip(x, areaY, width, areaH);
            // translucent: the panel leaves this area unpainted, so the world shows through a little
            r.rect(x, areaY, width, areaH, ColorUtil.fade(p.settingsBg(), SETTINGS_BG_OPACITY));
            SettingList.neonLine(r, x, areaY, 2, Math.max(0, areaH - 1), accentHere, 0.55 + e * 0.45);
            boolean mouseInArea = ctx.hasCapture() || (mouseY >= areaY && mouseY < areaY + areaH);
            int my = mouseInArea ? mouseY : Integer.MIN_VALUE / 2;
            quick.render(r, x + Layout.SETTINGS_INDENT, areaY + Layout.SETTINGS_PAD_Y, width - Layout.SETTINGS_INDENT,
                    areaY, areaY + areaH, mouseX, my, delta);
            r.popClip();
        }

        // light pulse down the bar, and on through the menu line while it's open (drawn last, so it's on top)
        if (barH > 0) {
            int end = open ? y + getHeight() - 1 : barY + barH;
            sweep(r, x, barY, 2, end - barY, accentHere, e);
        }
    }

    /** A light pulse that travels down a line from top to bottom, then starts again at the top ({@link Durations#AMBIENT_LOOP}). */
    private static void sweep(Render2D r, int lx, int ly, int w, int h, int color, double strength) {
        int period = Durations.loop(Durations.AMBIENT_LOOP);
        if (period <= 0 || h < 4) return;
        double t = (System.currentTimeMillis() % period) / (double) period;
        int len = Math.max(3, h / 2);
        int center = ly - len + (int) Math.round(t * (h + len * 2));
        int bright = ColorUtil.fade(ColorUtil.lerp(color, 0xFFFFFFFF, 0.45), strength * 0.8);
        int clear = ColorUtil.withAlpha(bright, 0);
        r.pushClip(lx, ly, w, h);
        r.gradientV(lx, center - len, w, len, clear, bright);
        r.gradientV(lx, center, w, len, bright, clear);
        r.popClip();
    }

    private boolean menuInteractive() {
        return expanded && expand.get() > 0.95;
    }

    private void openWindow() {
        if (hasConfig) ctx.openSettings(module);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isHovered(mouseX, mouseY)) return false;
        if (mouseY < y + Layout.MODULE_HEIGHT) {
            if (overGear(mouseX, mouseY) && (button == MouseButton.LEFT || button == MouseButton.RIGHT)) {
                openWindow();
                return true;
            }
            if (button == MouseButton.LEFT) {
                if (ctx.ui().clickRipple.get()) ripple.start(mouseX, mouseY);
                if (module.isToggleable()) module.toggleByUser();
                else if (ctx.hasSettingsWindow()) openWindow();
                else if (hasQuick()) expanded = !expanded;
                return true;
            }
            if (button == MouseButton.RIGHT) {
                if (hasQuick()) expanded = !expanded;
                else if (ctx.hasSettingsWindow()) openWindow();
                return true;
            }
            return false;
        }
        if (!menuInteractive()) return true;
        quick.mouseClicked(mouseX, mouseY, button);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return quick != null && quick.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!menuInteractive() || !isHovered(mouseX, mouseY)) return false;
        return quick.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        return quick != null && quick.keyPressed(key, scancode, modifiers);
    }

    @Override
    public boolean charTyped(int codepoint, int modifiers) {
        return quick != null && quick.charTyped(codepoint, modifiers);
    }

    @Override
    public boolean isHovered(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + getHeight();
    }

    @Override
    public void onClose() {
        if (quick != null) quick.onClose();
    }

    public Module getModule() {
        return module;
    }
}
