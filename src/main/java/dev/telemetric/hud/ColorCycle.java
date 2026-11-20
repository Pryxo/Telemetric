package dev.telemetric.hud;

import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.render.ColorUtil;

/**
 * A looping colour gradient for animated text. The {@code frames} are blended in a ring, each character sits
 * {@code charStep} frames further along, and the whole ring scrolls around once per {@code loopMs} (a {@link Durations}
 * token, speed-scaled, and still when animations are off). It's used through {@code game/AnimatedTextAdapter}
 * markers. These are server-style data colours (like {@link ShinyColors}), not theme colours.
 */
public final class ColorCycle {

    private final int[] frames;
    private final double charStep;
    private final int loopMs;

    public ColorCycle(int[] frames, double charStep, int loopMs) {
        this.frames = frames.clone();
        this.charStep = charStep;
        this.loopMs = loopMs;
    }

    /** How many characters until the per-character offset wraps around the whole ring. */
    public int cycleChars() {
        return Math.max(1, (int) Math.round(frames.length / charStep));
    }

    /** Current scroll position in frames (0 when animations are off). */
    private double phase() {
        int period = Durations.loop(loopMs);
        if (period <= 0) return 0;
        return (System.currentTimeMillis() % period) / (double) period * frames.length;
    }

    /** RGB (no alpha) at {@code frame} along the ring, blended between the neighbouring frames. */
    public int rgbAt(double frame) {
        double f = ((frame % frames.length) + frames.length) % frames.length;
        int i = (int) f;
        return ColorUtil.lerp(0xFF000000 | frames[i], 0xFF000000 | frames[(i + 1) % frames.length], (float) (f - i))
                & 0xFFFFFF;
    }

    /** 0xRRGGBB of the {@code index}-th character right now. */
    public int rgbNow(int index) {
        return rgbAt(phase() + index * charStep);
    }

    /** 0xRRGGBB of the {@code index}-th character when standing still. */
    public int still(int index) {
        return rgbAt(index * charStep);
    }
}
