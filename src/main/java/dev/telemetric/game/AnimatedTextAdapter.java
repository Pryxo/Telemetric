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
 * MC ADAPTER: animated text colours and the wave bob (through {@code mixin/FontAnimatedColorMixin}). Animated
 * characters carry a <b>marker</b> {@link TextColor}. It's an ordinary {@code TextColor.fromRgb} instance, so it
 * serializes, copies and compares like any colour and its value is the still gradient, but this class recognises it
 * by identity. While drawing, Minecraft's font asks {@link #color} for every glyph's colour (and {@link #waveY} for its
 * height). Markers get the moving colour; everything else passes through after a single identity-map check.
 * <p>
 * There are two kinds of marker sets: the shiny chat cycle ({@link #shiny}, switched by {@link #setAnimated} from
 * Misc → Chat Colors), and one set per {@link StatusRankColors.Tier} ({@link #status}, always moving unless
 * animations are off). Status markers only remember their tier, and the colour comes from the current
 * {@link StatusRankColors.Theme} at draw time.
 * <p>
 * If another mod replaces text rendering, the hook just doesn't apply and the markers show their still colours.
 * The same happens to anything that re-creates the colour (a JSON round trip, say).
 */
public final class AnimatedTextAdapter {

    /** What a marker stands for: the {@code index}-th character of {@code cycle}. */
    private record Marker(ColorCycle cycle, StatusRankColors.Tier tier, int index, boolean wave, boolean chat) {}

    /** Markers per status tier. It's longer than any status line, so the gradient never has a seam inside one. */
    private static final int STATUS_CHARS = 64;

    /** Wave height (text pixels, up and down) and phase step per character (radians). */
    private static final float WAVE_PX = 1.0f;
    private static final double WAVE_STEP = 0.6;

    /** Marker to what it stands for. Filled once in the static block and read-only after that, so any thread can read it. */
    private static final Map<TextColor, Marker> MARKERS = new IdentityHashMap<>();

    private static final TextColor[] SHINY = markers(ShinyColors.CYCLE, false, true);
    private static final TextColor[][] STATUS = new TextColor[StatusRankColors.Tier.values().length][];

    /** White markers that only wave (no colour change), for icons that bob along with a wave title. */
    private static final TextColor[] WAVE_ONLY = new TextColor[ShinyColors.CYCLE.cycleChars()];

    static {
        for (int i = 0; i < WAVE_ONLY.length; i++) {
            WAVE_ONLY[i] = TextColor.fromRgb(0xFFFFFF);
            MARKERS.put(WAVE_ONLY[i], new Marker(null, null, i, true, false));
        }
        for (StatusRankColors.Tier t : StatusRankColors.Tier.values()) {
            TextColor[] set = new TextColor[STATUS_CHARS];
            for (int i = 0; i < set.length; i++) {
                set[i] = TextColor.fromRgb(StatusRankColors.cycle(t).still(i));
                MARKERS.put(set[i], new Marker(null, t, i, t.wave, false));
            }
            STATUS[t.ordinal()] = set;
        }
    }

    private static TextColor[] markers(ColorCycle cycle, boolean wave, boolean chat) {
        TextColor[] out = new TextColor[cycle.cycleChars()];
        for (int i = 0; i < out.length; i++) {
            out[i] = TextColor.fromRgb(cycle.still(i)); // fromRgb always returns a new instance
            MARKERS.put(out[i], new Marker(cycle, null, i, wave, chat));
        }
        return out;
    }

    private static volatile boolean animate = true;

    private static volatile boolean wave = true;

    private AnimatedTextAdapter() {}

    /** Turns the wave bob of the #1 status title on or off (Nametags → Top 1 Wave). */
    public static void setWave(boolean on) {
        wave = on;
    }

    /** Turns the shiny chat animation on or off. When off, those markers keep their still gradient. */
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

    /** White marker for the {@code index}-th character that only bobs (wave), e.g. an icon inside a #1 title. */
    public static TextColor waveOnly(int index) {
        return WAVE_ONLY[Math.floorMod(index, WAVE_ONLY.length)];
    }

    /** Hook: the colour (0xRRGGBB) to draw for {@code color}. {@code value} is what Minecraft would use. */
    public static int color(TextColor color, int value) {
        if (color == null) return value;
        Marker m = MARKERS.get(color);
        if (m == null) return value;
        if (m.tier != null) return StatusRankColors.cycle(m.tier).rgbNow(m.index);
        if (m.cycle == null || (m.chat && !animate)) return value;
        return m.cycle.rgbNow(m.index);
    }

    /** Hook: height offset (text pixels, + is down) for a glyph in {@code style}. 0 unless it's a wave marker. */
    public static float waveY(Style style) {
        TextColor color = style == null ? null : style.getColor();
        if (color == null) return 0;
        Marker m = MARKERS.get(color);
        if (m == null || !m.wave || !wave) return 0;
        int period = Durations.loop(Durations.STATUS_WAVE_LOOP);
        if (period <= 0) return 0;
        double t = (System.currentTimeMillis() % period) / (double) period * Math.PI * 2;
        return (float) (Math.sin(t - m.index * WAVE_STEP) * WAVE_PX);
    }
}
