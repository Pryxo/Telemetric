package dev.telemetric.map;

import java.util.Random;
import java.util.stream.IntStream;

/**
 * The fog over unknown map areas, rendered on the CPU into an ARGB image anchored to the map. A port of the fog of
 * <a href="https://github.com/SunkenInTime/diorama">Diorama</a> by SunkenInTime (MIT License): a soft "known" mask,
 * drifting tileable value noise turned into a cloud height field, and a short ray march through height layers seen
 * at the map's 50° pitch, each layer lit from the top and shaded on the side away from the sun (north-west).
 * Pure Java; safe to run off the client thread.
 */
public final class FogField {

    /** 0 = never mapped … 1 = fully mapped, at map point (u, v). Called from a worker thread. */
    public interface Known {
        float at(double u, double v);
    }

    /**
     * @param height  bank height in map units (Diorama: 10 on the surface, 4 in caves)
     * @param blur    soft edge in map units (Diorama: 7 / 2.5)
     * @param rounded surface look: rounded edges + the double blur (caves use one blur, no rounding)
     * @param lit     colour of the sunlit tops (RGB)
     * @param shade   colour of the shaded sides (RGB)
     */
    public record Style(double height, double blur, boolean rounded, int lit, int shade) {
        public static final int LIT = 0xF7F7FC, SHADE = 0x7A8CAB;

        public static Style surface() {
            return new Style(10, 7, true, LIT, SHADE);
        }

        /** Thin banks for small maps (dungeon minimap), tinted from {@code lit}. */
        public static Style compact(int lit) {
            return new Style(4, 2.5, false, lit & 0xFFFFFF, shadeOf(lit));
        }

        public static Style surface(int lit) {
            return new Style(10, 7, true, lit & 0xFFFFFF, shadeOf(lit));
        }

        /** Diorama's shade / lit ratio applied to any lit colour. */
        private static int shadeOf(int lit) {
            int r = Math.round(((lit >> 16) & 0xFF) * 0.49f), g = Math.round(((lit >> 8) & 0xFF) * 0.57f);
            int b = Math.round((lit & 0xFF) * 0.68f);
            return r << 16 | g << 8 | b;
        }
    }

    /**
     * One render. Texel (i, j) of the result shows map point {@code (u0 + (i+0.5)·texel, v0 + (j+0.5)·texel)}.
     *
     * @param zoom    GUI pixels per map unit (far out the noise grows so clouds keep their size on screen)
     * @param seconds drift time; wraps every {@link #LOOP_SECONDS} without a jump
     */
    public record Job(double u0, double v0, double texel, int w, int h, double zoom, double seconds, Style style,
                      Known known) {}

    public static final double LOOP_SECONDS = 1200;

    private static final int NOISE = 256;
    private static final double NOISE_PERIOD = 512, COS_PITCH = MapProjection.COS;
    private static final int LAYERS = 8;
    private static final float LAYER_ALPHA = 0.45f, FOG_SHADOW = 0.7f, NO_FOG_KNOWN = 0.95f;
    private static final int MAX_MASK = 900;

    /** R, G, B noise channels: 2-octave tileable value noise on 6, 12 and 24 cell lattices. */
    private static final float[][] NOISE_CH = {fractal(6, 1L), fractal(12, 2L), fractal(24, 3L)};

    private FogField() {}

    /** Renders {@code job}; returns ARGB (non-premultiplied), {@code w·h}. */
    public static int[] render(Job job) {
        return new Run(job).render();
    }

    private static final class Run {
        final Job job;
        final Style st;
        final float[] lit, shade, cover;
        final double fogScale, coarsePeriod, frac;
        final double t; // noise drift in texture periods
        final double sunU, sunV;
        // mask grid
        final double mt, mu0, mv0;
        final int mw, mh;
        float[] known, soft;
        int[] foggy; // summed-area table of (soft < NO_FOG_KNOWN)

        Run(Job job) {
            this.job = job;
            this.st = job.style();
            lit = rgb(st.lit());
            shade = rgb(st.shade());
            cover = mix(shade, lit, 0.7f);
            double scaleLog = Math.min(11.99, log2(Math.max(1, 0.5 / Math.max(1e-6, job.zoom()))));
            int steps = (int) Math.floor(scaleLog);
            frac = scaleLog - steps;
            fogScale = Math.pow(2, scaleLog);
            coarsePeriod = NOISE_PERIOD * Math.pow(2, steps + 1);
            t = (job.seconds() % LOOP_SECONDS) / LOOP_SECONDS;
            sunU = -9.0 * fogScale;
            sunV = -0.4 * 9.0 * fogScale;

            double blurMax = st.rounded() ? st.blur() * 2.4 : st.blur();
            double reach = st.height() * COS_PITCH + 9.0 * fogScale * 1.1 + 1.9 * blurMax + 2;
            double spanU = job.w() * job.texel() + 2 * reach, spanV = job.h() * job.texel() + 2 * reach;
            double m = Math.max(job.texel(), st.blur() / 6);
            while (spanU / m > MAX_MASK || spanV / m > MAX_MASK) m *= 1.5;
            mt = m;
            mu0 = Math.floor((job.u0() - reach) / mt) * mt;
            mv0 = Math.floor((job.v0() - reach) / mt) * mt;
            mw = (int) Math.ceil(spanU / mt) + 2;
            mh = (int) Math.ceil(spanV / mt) + 2;
        }

        int[] render() {
            buildMask();
            int w = job.w(), h = job.h();
            int[] out = new int[w * h];
            IntStream.range(0, h).parallel().forEach(j -> {
                for (int i = 0; i < w; i++) out[j * w + i] = pixel(i, j);
            });
            return out;
        }

        // ------------------------------------------------------------ mask

        void buildMask() {
            int n = mw * mh;
            float[] k = new float[n];
            Known src = job.known();
            IntStream.range(0, mh).parallel().forEach(y -> {
                double v = mv0 + (y + 0.5) * mt;
                for (int x = 0; x < mw; x++) k[y * mw + x] = Math.clamp(src.at(mu0 + (x + 0.5) * mt, v), 0f, 1f);
            });
            if (st.rounded() && mt < 16) {
                int rad = Math.max(1, (int) Math.round(10 / mt));
                float[] b = k.clone();
                for (int pass = 0; pass < 2; pass++) {
                    b = boxH(b, rad);
                    b = boxV(b, rad);
                }
                for (int i = 0; i < n; i++) {
                    float tt = Math.clamp((b[i] - 0.5f) / 0.35f, 0f, 1f);
                    k[i] = Math.min(k[i], tt * tt * (3 - 2 * tt));
                }
            }
            known = k;
            float[] s = new float[n];
            IntStream.range(0, mh).parallel().forEach(y -> {
                for (int x = 0; x < mw; x++) {
                    s[y * mw + x] = st.rounded()
                            ? 0.5f * kernel(x, y, st.blur()) + 0.5f * kernel(x, y, st.blur() * 2.4)
                            : kernel(x, y, st.blur());
                }
            });
            soft = s;
            int[] sat = new int[(mw + 1) * (mh + 1)];
            for (int y = 0; y < mh; y++) {
                int row = 0;
                for (int x = 0; x < mw; x++) {
                    row += s[y * mw + x] < NO_FOG_KNOWN ? 1 : 0;
                    sat[(y + 1) * (mw + 1) + x + 1] = sat[y * (mw + 1) + x + 1] + row;
                }
            }
            foggy = sat;
        }

        /** Diorama's 9-tap soft blur: centre 0.2, 4 axial taps at ±r, 4 diagonal taps at ±1.9r (0.1 each). */
        float kernel(int x, int y, double units) {
            double r = Math.max(units, mt) / mt, d = 1.9 * r;
            double cx = x + 0.5, cy = y + 0.5;
            return 0.2f * grid(known, cx, cy)
                    + 0.1f * (grid(known, cx + r, cy) + grid(known, cx - r, cy) + grid(known, cx, cy + r) + grid(known, cx, cy - r))
                    + 0.1f * (grid(known, cx + d, cy + d) + grid(known, cx - d, cy + d) + grid(known, cx + d, cy - d) + grid(known, cx - d, cy - d));
        }

        float[] boxH(float[] in, int r) {
            float[] out = new float[in.length];
            for (int y = 0; y < mh; y++) {
                float sum = 0;
                int row = y * mw;
                for (int x = -r; x <= r; x++) sum += in[row + Math.clamp(x, 0, mw - 1)];
                for (int x = 0; x < mw; x++) {
                    out[row + x] = sum / (2 * r + 1);
                    sum += in[row + Math.min(x + r + 1, mw - 1)] - in[row + Math.max(x - r, 0)];
                }
            }
            return out;
        }

        float[] boxV(float[] in, int r) {
            float[] out = new float[in.length];
            for (int x = 0; x < mw; x++) {
                float sum = 0;
                for (int y = -r; y <= r; y++) sum += in[Math.clamp(y, 0, mh - 1) * mw + x];
                for (int y = 0; y < mh; y++) {
                    out[y * mw + x] = sum / (2 * r + 1);
                    sum += in[Math.min(y + r + 1, mh - 1) * mw + x] - in[Math.max(y - r, 0) * mw + x];
                }
            }
            return out;
        }

        /** Bilinear sample of a mask grid at grid coords (texel centres at +0.5), clamped at the edges. */
        float grid(float[] g, double gx, double gy) {
            double fx = gx - 0.5, fy = gy - 0.5;
            int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
            float tx = (float) (fx - x0), ty = (float) (fy - y0);
            int xa = Math.clamp(x0, 0, mw - 1), xb = Math.clamp(x0 + 1, 0, mw - 1);
            int ya = Math.clamp(y0, 0, mh - 1), yb = Math.clamp(y0 + 1, 0, mh - 1);
            float top = g[ya * mw + xa] + (g[ya * mw + xb] - g[ya * mw + xa]) * tx;
            float bot = g[yb * mw + xa] + (g[yb * mw + xb] - g[yb * mw + xa]) * tx;
            return top + (bot - top) * ty;
        }

        float knownAt(double u, double v) {
            return grid(known, (u - mu0) / mt, (v - mv0) / mt);
        }

        float softAt(double u, double v) {
            return grid(soft, (u - mu0) / mt, (v - mv0) / mt);
        }

        /** True if no fog can reach the area [u0,u1]×[v0,v1] (all of it known). */
        boolean clear(double u0, double v0, double u1, double v1) {
            int x0 = Math.clamp((int) Math.floor((u0 - mu0) / mt) - 1, 0, mw), x1 = Math.clamp((int) Math.ceil((u1 - mu0) / mt) + 1, 0, mw);
            int y0 = Math.clamp((int) Math.floor((v0 - mv0) / mt) - 1, 0, mh), y1 = Math.clamp((int) Math.ceil((v1 - mv0) / mt) + 1, 0, mh);
            int W = mw + 1;
            return foggy[y1 * W + x1] - foggy[y0 * W + x1] - foggy[y1 * W + x0] + foggy[y0 * W + x0] == 0;
        }

        // ------------------------------------------------------------ fog

        float noiseLayer(double nu, double nv) {
            float a = tex(NOISE_CH[0], nu + 5 * t, nv + 2 * t);
            float b = tex(NOISE_CH[1], nu - 8 * t, nv + 3 * t);
            float c = tex(NOISE_CH[2], nu + 13 * t, nv - 9 * t);
            return a * 0.55f + b * 0.3f + c * 0.15f;
        }

        float noiseAt(double u, double v) {
            double cu = u / coarsePeriod, cv = v / coarsePeriod;
            float fine = noiseLayer(cu * 2, cv * 2);
            if (frac <= 0.002) return fine;
            return fine + (noiseLayer(cu, cv) - fine) * (float) frac;
        }

        float thicknessFrom(float known, float n) {
            float unknown = 1 - known;
            float edge = smoothstep(0f, 0.35f, unknown);
            float amount = smoothstep(0.12f, 0.75f, unknown + (n - 0.5f) * 1.5f * edge);
            return (float) st.height() * amount * (0.45f + 0.75f * n);
        }

        float thickness(double u, double v) {
            float k = softAt(u, v);
            return k >= NO_FOG_KNOWN ? 0 : thicknessFrom(k, noiseAt(u, v));
        }

        int pixel(int i, int j) {
            double tx = job.texel();
            double u = job.u0() + (i + 0.5) * tx, v = job.v0() + (j + 0.5) * tx;
            double H = st.height();
            if (clear(u + sunU, v + sunV, u, v + H * COS_PITCH)) return argb(cover, 0);
            float knownHere = knownAt(u, v), softHere = softAt(u, v);
            // jitter tied to the map (not the screen), so it doesn't crawl while panning
            float jitter = hash((long) Math.floor(u / tx), (long) Math.floor(v / tx)) - 0.5f;
            float r = 0, g = 0, b = 0, alpha = 0;
            for (int l = 0; l < LAYERS; l++) {
                double h = H * (1 - (l + 0.5 + jitter * 0.6) / LAYERS);
                double gv = v + h * COS_PITCH;
                float th = thickness(u, gv);
                float a = smoothstep((float) h - 1.4f, (float) h + 1.4f, th);
                if (a <= 0.002f) continue;
                float toward = thickness(u + sunU, gv + sunV);
                float occlusion = Math.clamp((toward - th) / (float) H * 2.2f, 0f, 1f);
                float light = Math.clamp(0.2f + 0.7f * (float) (h / H) + 0.3f - occlusion * FOG_SHADOW, 0f, 1f);
                float la = a * LAYER_ALPHA, wgt = (1 - alpha) * la;
                r += wgt * (shade[0] + (lit[0] - shade[0]) * light);
                g += wgt * (shade[1] + (lit[1] - shade[1]) * light);
                b += wgt * (shade[2] + (lit[2] - shade[2]) * light);
                alpha += wgt;
                if (alpha > 0.985f) break;
            }
            float floorFog = smoothstep(0.5f, 2.5f, thickness(u, v));
            float wgt = (1 - alpha) * floorFog;
            r += wgt * shade[0];
            g += wgt * shade[1];
            b += wgt * shade[2];
            alpha += wgt;
            float here = st.rounded() ? softHere : knownHere;
            float keep = 1 - 0.85f * smoothstep(0.45f, 0.95f, here); // known ground stays visible
            alpha *= keep;
            r *= keep;
            g *= keep;
            b *= keep;
            float empty = 1 - smoothstep(0.3f, 0.85f, knownHere); // opaque cover where there's no map at all
            float cr, cg, cb;
            if (alpha > 0.02f) {
                cr = r / alpha;
                cg = g / alpha;
                cb = b / alpha;
            } else {
                cr = cover[0];
                cg = cover[1];
                cb = cover[2];
            }
            wgt = (1 - alpha) * empty;
            r += wgt * cr;
            g += wgt * cg;
            b += wgt * cb;
            alpha += wgt;
            if (alpha <= 0.004f) return argb(cover, 0);
            float d = jitter / 255f; // dither
            return argb(new float[]{r / alpha + d, g / alpha + d, b / alpha + d}, alpha);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Bilinear, wrapping sample of a {@value #NOISE}² noise channel at texture coords (1 = one period). */
    private static float tex(float[] ch, double s, double t) {
        double x = (s - Math.floor(s)) * NOISE - 0.5, y = (t - Math.floor(t)) * NOISE - 0.5;
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        float fx = (float) (x - x0), fy = (float) (y - y0);
        int xa = x0 & (NOISE - 1), xb = (x0 + 1) & (NOISE - 1), ya = y0 & (NOISE - 1), yb = (y0 + 1) & (NOISE - 1);
        float top = ch[ya * NOISE + xa] + (ch[ya * NOISE + xb] - ch[ya * NOISE + xa]) * fx;
        float bot = ch[yb * NOISE + xa] + (ch[yb * NOISE + xb] - ch[yb * NOISE + xa]) * fx;
        return top + (bot - top) * fy;
    }

    private static float[] fractal(int cells, long seed) {
        float[] a = valueNoise(cells, seed), b = valueNoise(cells * 2, seed * 31 + 7);
        float[] out = new float[a.length];
        for (int i = 0; i < out.length; i++) out[i] = a[i] * 0.7f + b[i] * 0.3f;
        return out;
    }

    private static float[] valueNoise(int cells, long seed) {
        Random random = new Random(seed);
        float[] lattice = new float[cells * cells];
        for (int i = 0; i < lattice.length; i++) lattice[i] = random.nextFloat();
        float[] out = new float[NOISE * NOISE];
        for (int y = 0; y < NOISE; y++) {
            float fy = (float) y * cells / NOISE;
            int y0 = (int) fy;
            float ty = quintic(fy - y0);
            for (int x = 0; x < NOISE; x++) {
                float fx = (float) x * cells / NOISE;
                int x0 = (int) fx;
                float tx = quintic(fx - x0);
                float v00 = lattice[(y0 % cells) * cells + x0 % cells];
                float v10 = lattice[(y0 % cells) * cells + (x0 + 1) % cells];
                float v01 = lattice[((y0 + 1) % cells) * cells + x0 % cells];
                float v11 = lattice[((y0 + 1) % cells) * cells + (x0 + 1) % cells];
                float top = v00 + (v10 - v00) * tx, bottom = v01 + (v11 - v01) * tx;
                out[y * NOISE + x] = top + (bottom - top) * ty;
            }
        }
        return out;
    }

    private static float quintic(float t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Math.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3 - 2 * t);
    }

    private static float hash(long x, long y) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    private static float[] rgb(int c) {
        return new float[]{((c >> 16) & 0xFF) / 255f, ((c >> 8) & 0xFF) / 255f, (c & 0xFF) / 255f};
    }

    private static float[] mix(float[] a, float[] b, float t) {
        return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    private static int argb(float[] c, float a) {
        int r = Math.clamp(Math.round(c[0] * 255), 0, 255), g = Math.clamp(Math.round(c[1] * 255), 0, 255);
        int b = Math.clamp(Math.round(c[2] * 255), 0, 255), al = Math.clamp(Math.round(a * 255), 0, 255);
        return al << 24 | r << 16 | g << 8 | b;
    }
}
