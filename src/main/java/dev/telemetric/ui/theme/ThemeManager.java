package dev.telemetric.ui.theme;

import dev.telemetric.Telemetric;
import dev.telemetric.module.Category;
import dev.telemetric.module.impl.misc.ThemeModule;
import dev.telemetric.setting.impl.ColorRamp;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.render.ColorUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Works out the active {@link Theme} from {@link ThemeModule} and cross-fades between palettes when the user changes
 * the preset, accent or opacity, so colours never jump.
 * <p>
 * Call {@link #update()} once per frame (ClickGuiScreen does), then read {@link #palette()} anywhere.
 */
public final class ThemeManager {

    private final Animation transition = new Animation(Durations.DRAWER, Easing.IN_OUT_CUBIC, 1);
    private Theme from;
    private Theme target;
    private Theme current = ThemePreset.ARCTIC.theme();
    /** 0 = every panel uses the theme accent, 1 = Category Colors fully applied (fades when toggled). */
    private final Animation categoryMix = new Animation(Durations.DRAWER, Easing.IN_OUT_CUBIC, 0);

    public void update() {
        Theme resolved = resolve();
        boolean categories = module().categoryColors.get();
        if (target == null) {
            from = target = current = resolved;
            categoryMix.snapTo(categories ? 1 : 0);
            return;
        }
        categoryMix.animateTo(categories ? 1 : 0);
        if (!resolved.equals(target)) {
            from = current;
            target = resolved;
            transition.snapTo(0).animateTo(1);
        }
        current = Theme.lerp(from, target, transition.get());
    }

    /** Palettes that a category's panel always uses instead of the theme (registered by add-ons). */
    private static final Map<Category, Theme> FIXED_PALETTES = new HashMap<>();

    /**
     * Makes {@code category}'s panel always use {@code palette}. No preset, custom accent, panel opacity or cross-fade
     * applies to it.
     */
    public static void registerFixedPalette(Category category, Theme palette) {
        FIXED_PALETTES.put(Objects.requireNonNull(category), Objects.requireNonNull(palette));
    }

    /** The current palette (possibly mid-transition). */
    public Theme palette() {
        return current;
    }

    /**
     * The palette for {@code category}'s panel and its modules' Settings windows: its fixed palette, else the theme
     * with the Category Colors accent blended in, else {@code null} (follow the theme).
     */
    public Theme paletteFor(Category category) {
        Theme fixed = FIXED_PALETTES.get(category);
        if (fixed != null || category == null) return fixed;
        double mix = categoryMix.get();
        if (mix <= 0.001) return null;
        int accent = module().categoryAccent(category, current.accent());
        int accentAlt = ColorUtil.shade(ColorUtil.hueShift(accent, 0.06f), 0.28);
        Theme t = current.withAccent(ColorUtil.lerp(current.accent(), accent, mix),
                ColorUtil.lerp(current.accentAlt(), accentAlt, mix));
        return t.withSurfaceActive(ColorUtil.lerp(current.surfaceActive(),
                ColorUtil.withAlpha(ColorUtil.lerp(current.surfaceActive(), accent, 0.12), ColorUtil.alpha(current.surfaceActive())), mix));
    }

    private Theme resolve() {
        ThemeModule module = module();
        return module.resolvedPalette().withSurfaceOpacity(module.panelOpacity.get() / 100.0);
    }

    private static ThemeModule module() {
        return Telemetric.get().modules().get(ThemeModule.class);
    }

    // convenience accessors for frequently read look settings
    public int cornerRadius() {
        return Telemetric.get().modules().get(ThemeModule.class).cornerRadius.getInt();
    }

    public boolean textShadow() {
        return Telemetric.get().modules().get(ThemeModule.class).textShadow.get();
    }

    public boolean shadows() {
        return Telemetric.get().modules().get(ThemeModule.class).shadows.get();
    }

    public boolean headerGradient() {
        return Telemetric.get().modules().get(ThemeModule.class).headerGradient.get();
    }

    public HeaderStyle headerStyle() {
        return module().headerStyle.get();
    }

    public boolean categoryIcons() {
        return module().categoryIcons.get();
    }

    /** Theme → Flowing Gradient. */
    public boolean flowing() {
        return module().flowingGradient.get();
    }

    /** Theme → Glow as 0..1. */
    public double glow() {
        return module().glow.get() / 100.0;
    }

    public BackdropStyle backdrop() {
        return module().backdrop.get();
    }

    public boolean blurWorld() {
        return module().blurWorld.get();
    }

    /** The label ("index") colour of HUD rows that don't use the server colours: Theme → Custom HUD Colors, or else the accent. */
    public int hudIndex() {
        ThemeModule module = Telemetric.get().modules().get(ThemeModule.class);
        return module.customHudColors.get() ? module.hudIndexColor.get() : current.accent();
    }

    /** The value colour of HUD rows that don't use the server colours: Theme → Custom HUD Colors, or else the text colour. */
    public int hudValue() {
        ThemeModule module = Telemetric.get().modules().get(ThemeModule.class);
        return module.customHudColors.get() ? module.hudValueColor.get() : current.text();
    }

    /** {@link #hudIndex()} as a ramp, so a Custom HUD Colors gradient runs across the element. */
    public ColorRamp hudIndexRamp() {
        ThemeModule module = Telemetric.get().modules().get(ThemeModule.class);
        return module.customHudColors.get() ? module.hudIndexColor : ColorRamp.solid(current.accent());
    }

    /** {@link #hudValue()} as a ramp. */
    public ColorRamp hudValueRamp() {
        ThemeModule module = Telemetric.get().modules().get(ThemeModule.class);
        return module.customHudColors.get() ? module.hudValueColor : ColorRamp.solid(current.text());
    }
}
