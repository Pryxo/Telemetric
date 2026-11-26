package dev.telemetric.hud;

import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.render.ColorUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * The "shiny" colour animation: the 9-frame pastel cycle from Telos' own shiny textures (one colour per frame of
 * {@code telos:textures/mob/pouch/shiny_outline.png}; {@code gui/sprites/tooltip/shiny_background.png} and
 * {@code mob/pouch/shiny.png} cycle through the same hues). It's server data like the rarity colours, not a theme
 * colour. Each character is one step further along the cycle, and the whole cycle scrolls around once per
 * {@link Durations#SHINY_LOOP}, which makes shiny text shimmer. With animations off it stands still.
 */
public final class ShinyColors {

    /** 0xRRGGBB per frame, read off {@code shiny_outline.png} (16×144, 9 frames). */
    private static final int[] FRAMES = {
            0xF7D8E0, 0xE8AEF1, 0xCBDCFC, 0xB5D5FF, 0x89FFE8, 0xADFFF4, 0xBAFFA3, 0xFFF3B8, 0xFFD2BE
    };
    /** Cycle offset per character, in frames. */
    private static final double CHAR_STEP = 0.45;

    private ShinyColors() {}

    /** Fixed start and end of the cycle for one-off texts (chat): the first frame and the middle frame. */
    public static final int START = FRAMES[0], MIDDLE = FRAMES[FRAMES.length / 2];

    /** Current scroll position in frames (0 when animations are off). */
    private static double phase() {
        int period = Durations.loop(Durations.SHINY_LOOP);
        if (period <= 0) return 0;
        return (System.currentTimeMillis() % period) / (double) period * FRAMES.length;
    }

    /** RGB (no alpha) at {@code frame} along the cycle, blended between the neighbouring frames. */
    public static int rgbAt(double frame) {
        double f = ((frame % FRAMES.length) + FRAMES.length) % FRAMES.length;
        int i = (int) f;
        return ColorUtil.lerp(0xFF000000 | FRAMES[i], 0xFF000000 | FRAMES[(i + 1) % FRAMES.length], (float) (f - i))
                & 0xFFFFFF;
    }

    /** ARGB of the {@code index}-th character right now. */
    public static int argb(int index) {
        return 0xFF000000 | rgbAt(phase() + index * CHAR_STEP);
    }

    /** One run per character in the moving shiny colours. {@code offset} is how many characters were already drawn before it. */
    public static List<TextRun> runs(String text, int offset) {
        List<TextRun> out = new ArrayList<>(text.length());
        int i = offset;
        for (int c = 0; c < text.length(); ) {
            int cp = text.codePointAt(c);
            int n = Character.charCount(cp);
            out.add(new TextRun(text.substring(c, c + n), argb(i++), null));
            c += n;
        }
        return out;
    }

    /** The same shiny cycle as a {@link ColorCycle}, for animated text markers and status titles. */
    public static final ColorCycle CYCLE = new ColorCycle(FRAMES, CHAR_STEP, Durations.SHINY_LOOP);

    /** 0xRRGGBB of the {@code index}-th character right now (moving, or still when animations are off). */
    public static int rgbNow(int index) {
        return rgbAt(phase() + index * CHAR_STEP);
    }

    /** 0xRRGGBB per character for a still gradient, one step per character. Chat lines can't move. */
    public static int[] gradient(int length) {
        int[] out = new int[Math.max(0, length)];
        for (int i = 0; i < out.length; i++) out[i] = rgbAt(i * CHAR_STEP);
        return out;
    }
}
