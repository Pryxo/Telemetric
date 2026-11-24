package dev.telemetric.ui.clickgui;

import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.Random;

/**
 * Slow, softly twinkling accent-coloured particles that drift upwards behind the panels (Interface → Particles).
 * Positions are a pure function of the clock, so they don't depend on the frame rate; with animations off they hold
 * still. Bigger particles move faster (a little depth).
 */
final class Particles {

    private static final int MAX = 150;
    /** Base alpha of a particle at full twinkle. */
    private static final double ALPHA = 0.8;
    /** Alpha of the soft halo around bigger particles, relative to the particle. */
    private static final double HALO = 0.22;

    private final float[] x0 = new float[MAX], y0 = new float[MAX], size = new float[MAX], speed = new float[MAX];
    private final float[] phase = new float[MAX], swayFreq = new float[MAX], sway = new float[MAX];
    private final float[] twinkle = new float[MAX], mix = new float[MAX];

    Particles() {
        Random rnd = new Random(0x7E1E3E7L);
        for (int i = 0; i < MAX; i++) {
            x0[i] = rnd.nextFloat();
            y0[i] = rnd.nextFloat();
            size[i] = 0.9f + rnd.nextFloat() * rnd.nextFloat() * 1.8f;
            speed[i] = 0.45f + size[i] * 0.45f;
            phase[i] = rnd.nextFloat() * (float) (Math.PI * 2);
            swayFreq[i] = 2 + rnd.nextFloat() * 4;
            sway[i] = 3 + rnd.nextFloat() * 9;
            twinkle[i] = 6 + rnd.nextFloat() * 10;
            mix[i] = rnd.nextFloat();
        }
    }

    /** Draws {@code count} particles over a {@code w × h} area (ClickGUI units). */
    void render(Render2D r, Theme p, int w, int h, int count, double sizeScale) {
        int period = Durations.loop(Durations.PARTICLE_DRIFT_LOOP);
        double cycles = period > 0 ? System.currentTimeMillis() / (double) period : 0;
        int n = Math.min(MAX, count);
        double span = h + 12;
        for (int i = 0; i < n; i++) {
            double fy = y0[i] - cycles * speed[i];
            fy -= Math.floor(fy);
            double py = fy * span - 6;
            double px = x0[i] * w + Math.sin(cycles * 2 * Math.PI * swayFreq[i] + phase[i]) * sway[i];
            double tw = 0.55 + 0.45 * Math.sin(cycles * 2 * Math.PI * twinkle[i] + phase[i]);
            // fade in at the bottom and out at the top instead of popping
            double edge = Math.min(1, Math.min(fy, 1 - fy) * 8);
            double alpha = ALPHA * tw * edge;
            if (alpha < 0.02) continue;
            int color = ColorUtil.lerp(p.accent(), p.accentAlt(), mix[i]);
            double radius = size[i] * sizeScale;
            if (radius > 0.9) r.circle(px, py, radius * 2.6, ColorUtil.fade(color, alpha * HALO));
            r.circle(px, py, radius, ColorUtil.fade(color, alpha));
        }
    }
}
