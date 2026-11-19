package dev.telemetric.game;

import dev.telemetric.hud.ColorCycle;
import dev.telemetric.hud.ShinyColors;
import dev.telemetric.hud.StatusRankColors;
import dev.telemetric.ui.animation.Durations;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * MC ADAPTER — animated text colours and the wave bob (via {@code mixin/FontAnimatedColorMixin}). Animated characters
 * carry a <b>marker</b> {@link TextColor}: an ordinary {@code TextColor.fromRgb} instance (so it serializes, copies and
 * compares like any colour, and its value is the still gradient) that this class knows by identity. While drawing,
 * Minecraft's font asks {@link #color} for every glyph's colour (and {@link #waveY} for its height); markers get the
 * moving colour, everything else passes through after one identity-map check.
 * <p>
 * Marker sets: the shiny chat cycle ({@link #shiny}, switched by {@link #setAnimated} — Misc → Chat Colors) and one
 * set per {@link StatusRankColors.Tier} ({@link #status}, always moving unless animations are off).
 * <p>
 * If another mod replaces text rendering the hook simply doesn't apply and the markers show their still colours;
 * anything that re-creates the colour (e.g. a JSON round trip) does the same.
 */
public final class AnimatedTextAdapter {

    /** What a marker stands for: the {@code index}-th character of {@code cycle}. */
    private record Marker(ColorCycle cycle, int index, boolean wave, boolean chat) {}

    /** Wave height (text pixels, up and down) and phase step per character (radians). */
    private static final float WAVE_PX = 1.0f;
    private static final double WAVE_STEP = 0.6;

    /** Marker → what it stands for. Filled once in the static block, read-only afterwards (safe from any thread). */
    private static final Map<TextColor, Marker> MARKERS = new IdentityHashMap<>();

    private static final TextColor[] SHINY = markers(ShinyColors.CYCLE, false, true);
    private static final TextColor[][] STATUS = new TextColor[StatusRankColors.Tier.values().length][];

    static {
        for (StatusRankColors.Tier t : StatusRankColors.Tier.values()) {
            STATUS[t.ordinal()] = markers(t.cycle, t.wave, false);
        }
    }

    private static TextColor[] markers(ColorCycle cycle, boolean wave, boolean chat) {
        TextColor[] out = new TextColor[cycle.cycleChars()];
        for (int i = 0; i < out.length; i++) {
            out[i] = TextColor.fromRgb(cycle.still(i)); // fromRgb always returns a new instance
            MARKERS.put(out[i], new Marker(cycle, i, wave, chat));
        }
        return out;
    }

    private static volatile boolean animate = true;

    private AnimatedTextAdapter() {}

    /** Turns the shiny chat animation on/off (off = those markers keep their still gradient). */
    public static void setAnimated(boolean on) {
        animate = on;
    }

    /** Marker colour of the {@code index}-th shiny character of a chat text. */
    public static TextColor shiny(int index) {
        return SHINY[Math.floorMod(index, SHINY.length)];
    }

    /** Marker colour of the {@code index}-th character of a status title in {@code tier}. */
    public static TextColor status(StatusRankColors.Tier tier, int index) {
        TextColor[] set = STATUS[tier.ordinal()];
        return set[Math.floorMod(index, set.length)];
    }

    /** Hook: colour (0xRRGGBB) to draw for {@code color}; {@code value} = what Minecraft would use. */
    public static int color(TextColor color, int value) {
        if (color == null) return value;
        Marker m = MARKERS.get(color);
        if (m == null || (m.chat && !animate)) return value;
        return m.cycle.rgbNow(m.index);
    }

    /** Hook: height offset (text pixels, + = down) for a glyph in {@code style}; 0 unless it is a wave marker. */
    public static float waveY(Style style) {
        TextColor color = style == null ? null : style.getColor();
        if (color == null) return 0;
        Marker m = MARKERS.get(color);
        if (m == null || !m.wave) return 0;
        int period = Durations.loop(Durations.STATUS_WAVE_LOOP);
        if (period <= 0) return 0;
        double t = (System.currentTimeMillis() % period) / (double) period * Math.PI * 2;
        return (float) (Math.sin(t - m.index * WAVE_STEP) * WAVE_PX);
    }
}
