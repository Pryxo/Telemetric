package dev.telemetric.game;

import dev.telemetric.hud.ShinyColors;
import net.minecraft.network.chat.TextColor;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * MC ADAPTER — animated text colours (via {@code mixin/FontAnimatedColorMixin}). Animated characters carry a
 * <b>marker</b> {@link TextColor}: an ordinary {@code TextColor.fromRgb} instance (so it serializes, copies and
 * compares like any colour, and its value is the still gradient) that this class knows by identity. While drawing,
 * Minecraft's font asks {@link #color} for every glyph's colour; markers get the moving colour, everything else
 * passes through after one boolean / identity-map check.
 * <p>
 * If another mod replaces text rendering the hook simply doesn't apply and the markers show their still colours;
 * anything that re-creates the colour (e.g. a JSON round trip) does the same.
 */
public final class AnimatedTextAdapter {

    /** One marker per position in the shiny cycle; the cycle repeats every {@link ShinyColors#CYCLE_CHARS}. */
    private static final TextColor[] SHINY = new TextColor[ShinyColors.CYCLE_CHARS];
    /** Marker → cycle index. Filled once in the static block, read-only afterwards (safe from any thread). */
    private static final Map<TextColor, Integer> INDEX = new IdentityHashMap<>();

    static {
        int[] still = ShinyColors.gradient(SHINY.length);
        for (int i = 0; i < SHINY.length; i++) {
            SHINY[i] = TextColor.fromRgb(still[i]); // fromRgb always returns a new instance
            INDEX.put(SHINY[i], i);
        }
    }

    private static volatile boolean animate = true;

    private AnimatedTextAdapter() {}

    /** Turns the animation on/off (off = markers keep their still gradient). */
    public static void setAnimated(boolean on) {
        animate = on;
    }

    /** Marker colour of the {@code index}-th shiny character of a text. */
    public static TextColor shiny(int index) {
        return SHINY[Math.floorMod(index, SHINY.length)];
    }

    /** Hook: colour (0xRRGGBB) to draw for {@code color}; {@code value} = what Minecraft would use. */
    public static int color(TextColor color, int value) {
        if (!animate) return value;
        Integer i = INDEX.get(color);
        return i == null ? value : ShinyColors.rgbNow(i);
    }
}
