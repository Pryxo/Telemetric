package dev.telemetric.ui.theme;

import dev.telemetric.Telemetric;
import dev.telemetric.module.Category;
import dev.telemetric.module.impl.misc.ThemeModule;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves the active {@link Theme} from {@link ThemeModule} and cross-fades between palettes when the
 * user changes preset / accent / opacity (no hard colour jumps).
 * <p>
 * Call {@link #update()} once per frame (ClickGuiScreen does), then read {@link #palette()} anywhere.
 */
public final class ThemeManager {

    private final Animation transition = new Animation(Durations.DRAWER, Easing.IN_OUT_CUBIC, 1);
    private Theme from;
    private Theme target;
    private Theme current = ThemePreset.MIDNIGHT.theme();

    public void update() {
        Theme resolved = resolve();
        if (target == null) {
            from = target = current = resolved;
            return;
        }
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
     * Makes {@code category}'s panel always use {@code palette}: no preset, custom accent, panel opacity or
     * cross-fade applies to it.
     */
    public static void registerFixedPalette(Category category, Theme palette) {
        FIXED_PALETTES.put(Objects.requireNonNull(category), Objects.requireNonNull(palette));
    }

    /** Current (possibly mid-transition) palette. */
    public Theme palette() {
        return current;
    }

    /** A palette that a category's panel always uses instead of the theme, or {@code null} to follow the theme. */
    public Theme fixedPalette(Category category) {
        return FIXED_PALETTES.get(category);
    }

    private Theme resolve() {
        ThemeModule module = Telemetric.get().modules().get(ThemeModule.class);
        return module.resolvedPalette().withSurfaceOpacity(module.panelOpacity.get() / 100.0);
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

    /** Label ("index") colour of HUD rows that don't use the server colours: Theme → Custom HUD Colors, else accent. */
    public int hudIndex() {
        ThemeModule module = Telemetric.get().modules().get(ThemeModule.class);
        return module.customHudColors.get() ? module.hudIndexColor.get() : current.accent();
    }

    /** Value colour of HUD rows that don't use the server colours: Theme → Custom HUD Colors, else text. */
    public int hudValue() {
        ThemeModule module = Telemetric.get().modules().get(ThemeModule.class);
        return module.customHudColors.get() ? module.hudValueColor.get() : current.text();
    }
}
