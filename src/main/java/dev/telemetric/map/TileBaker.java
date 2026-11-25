package dev.telemetric.map;

import static dev.telemetric.map.MapProjection.PX_PER_UNIT;
import static dev.telemetric.map.MapProjection.TILE_PX;
import static dev.telemetric.map.MapProjection.TILE_UNITS;
import static dev.telemetric.map.MapProjection.Z_PER_Y;

/**
 * Bakes one map tile: every pixel is a ray through the blocks along the {@link MapProjection} camera, from the cut
 * height down. The first solid block decides the colour (its texture's 4×4 grid), shaded by face direction, ambient
 * occlusion from the neighbours, a sun shadow ray and depth below the cut. Water, glass and small plants tint what's
 * behind them. A last pass draws dark outlines where a nearer surface ends in front of a farther one.
 * <p>
 * Pure Java; runs on a background thread.
 */
public final class TileBaker {

    /** Sun direction (towards the sun): from the south-west, 40° up, so south faces are lit and shadows fall north-east. */
    private static final double SUN_EL = Math.toRadians(40);
    private static final double LX = -0.878 * Math.cos(SUN_EL), LY = Math.sin(SUN_EL), LZ = 0.479 * Math.cos(SUN_EL);
    private static final double SHADOW_STEP = 0.5, SHADOW_REACH = 32;

    private static final float AMBIENT = 0.52f, SUN = 0.62f, SIDE_AMBIENT = 0.82f;
    private static final float[] AMB_TINT = {0.92f, 0.96f, 1.08f}, SUN_TINT = {1.06f, 1.0f, 0.9f};
    private static final float[] AO = {1.0f, 0.86f, 0.73f, 0.6f};
    private static final float EXPOSURE = 1.06f, SATURATION = 1.15f;
    /** A pixel gets an outline when a neighbour's surface is this much nearer (blocks along the ray). */
    private static final float OUTLINE_DEPTH = 1.5f, OUTLINE_DARK = 0.62f;
    /** Surfaces this far below the cut are darkest (a quarter darker). */
    private static final float DEPTH_FADE = 30f;

    private static final int TOP = 0, SIDE = 1;

    /** One baked tile, before the mask. */
    public static final class Baked {
        public final int tu, tv;
        /** ARGB per pixel, alpha 0 where the ray hit nothing. */
        public final int[] argb = new int[TILE_PX * TILE_PX];
        /** Block the ray hit (y, z; x follows from the pixel column). */
        public final short[] hitY = new short[TILE_PX * TILE_PX];
        public final int[] hitZ = new int[TILE_PX * TILE_PX];

        Baked(int tu, int tv) {
            this.tu = tu;
            this.tv = tv;
        }

        /** World block x of pixel column {@code px}. */
        public int hitX(int px) {
            return (int) Math.floor(tu * TILE_UNITS + (px + 0.5) / PX_PER_UNIT);
        }
    }

    private TileBaker() {}

    /**
     * @param cut  highest block layer drawn per column (everything above is cut away, like a cave view)
     * @param minY lowest block layer a ray looks at
     */
    public static Baked bake(VoxelStore.Reader rd, int tu, int tv, MapCut cut, int minY) {
        Baked out = new Baked(tu, tv);
        float[] depth = new float[TILE_PX * TILE_PX];
        int cutY = cut.max();
        if (cutY == Integer.MIN_VALUE) return out;
        double yTop = cutY + 1;
        for (int py = 0; py < TILE_PX; py++) {
            double v = tv * TILE_UNITS + (py + 0.5) / PX_PER_UNIT;
            double z0 = MapProjection.zAt(v, yTop);
            for (int px = 0; px < TILE_PX; px++) {
                double u = tu * TILE_UNITS + (px + 0.5) / PX_PER_UNIT;
                int i = py * TILE_PX + px;
                depth[i] = Float.MAX_VALUE;
                trace(rd, out, depth, i, u, z0, yTop, cutY, cut, minY);
            }
        }
        outline(out.argb, depth);
        return out;
    }

    /** Applies the mask: the pixel's alpha times the mask value of the block it shows. */
    public static int[] compose(Baked b, MapMask mask) {
        int[] out = new int[b.argb.length];
        for (int py = 0; py < TILE_PX; py++) {
            for (int px = 0; px < TILE_PX; px++) {
                int i = py * TILE_PX + px;
                int c = b.argb[i];
                int a = c >>> 24;
                if (a == 0) continue;
                float m = mask.alpha(b.hitX(px), b.hitY[i], b.hitZ[i]);
                if (m <= 0.004f) continue;
                int na = Math.min(255, Math.round(a * Math.min(1f, m)));
                out[i] = na << 24 | (c & 0xFFFFFF);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ ray

    private static void trace(VoxelStore.Reader rd, Baked out, float[] depth, int i, double u, double z0,
                              double yTop, int cutY, MapCut cut, int minY) {
        int x = (int) Math.floor(u);
        double fx = u - x;
        int cy = cutY, cz = (int) Math.floor(z0);
        double tMaxY = 1.0, tDZ = 1.0 / Z_PER_Y, tMaxZ = (z0 - cz) * tDZ;
        double t = 0;
        int face = TOP;
        // tint layers in front of the hit (water, glass, plants)
        float tr = 0, tg = 0, tb = 0, trans = 1f;
        boolean decorDone = false, inWater = false;
        double waterT = 0;
        int waterColor = 0;
        boolean first = true;
        int colZ = Integer.MIN_VALUE, colTop = Integer.MIN_VALUE;
        while (cy >= minY) {
            if (cz != colZ) {
                colZ = cz;
                colTop = cut.top(x, cz);
            }
            int id = cy > colTop ? 0 : rd.id(x, cy, cz);
            if (id != 0) {
                BlockLook look = rd.look(id);
                double yHit = yTop - t, zHit = z0 - Z_PER_Y * t;
                double sub = face == TOP ? zHit - cz : yHit - cy;
                switch (look.kind()) {
                    case BlockLook.SOLID -> {
                        int c = shade(rd, look, face, x, cy, cz, fx, clamp01(sub), yHit, zHit, cutY, cut, colTop);
                        if (inWater) {
                            float a = (float) Math.min(0.88, 0.45 + (t - waterT) * 0.12);
                            tr += trans * a * r(waterColor);
                            tg += trans * a * g(waterColor);
                            tb += trans * a * b(waterColor);
                            trans *= 1 - a;
                        }
                        out.argb[i] = 0xFF000000 | mix(tr, tg, tb, trans, c);
                        if (first) record(out, i, cy, cz);
                        depth[i] = (float) t;
                        return;
                    }
                    case BlockLook.WATER -> {
                        if (!inWater) {
                            inWater = true;
                            waterT = t;
                            waterColor = face == TOP ? look.top()[5] : look.side()[5];
                            if (first) {
                                record(out, i, cy, cz);
                                first = false;
                            }
                        }
                    }
                    case BlockLook.GLASS -> {
                        int c = face == TOP ? look.top()[5] : look.side()[5];
                        tr += trans * 0.3f * r(c);
                        tg += trans * 0.3f * g(c);
                        tb += trans * 0.3f * b(c);
                        trans *= 0.7f;
                        if (first) {
                            record(out, i, cy, cz);
                            first = false;
                        }
                    }
                    case BlockLook.DECOR -> {
                        if (!decorDone && face == TOP) {
                            decorDone = true;
                            int c = look.top()[cell(fx, sub)];
                            if ((c >>> 24) > 0) {
                                tr += trans * 0.45f * r(c);
                                tg += trans * 0.45f * g(c);
                                tb += trans * 0.45f * b(c);
                                trans *= 0.55f;
                            }
                        }
                    }
                    default -> {}
                }
                if (look.kind() != BlockLook.WATER && inWater) inWater = look.kind() == BlockLook.DECOR;
            } else if (inWater) {
                inWater = false; // left the water through air (rare): just keep the tint so far
                tr += trans * 0.6f * r(waterColor);
                tg += trans * 0.6f * g(waterColor);
                tb += trans * 0.6f * b(waterColor);
                trans *= 0.4f;
            }
            if (tMaxY < tMaxZ) {
                t = tMaxY;
                cy--;
                tMaxY += 1;
                face = TOP;
            } else {
                t = tMaxZ;
                cz--;
                tMaxZ += tDZ;
                face = SIDE;
            }
        }
        // nothing solid: only water shows as a surface, anything else stays empty
        if (inWater) {
            out.argb[i] = 0xFF000000 | mix(tr, tg, tb, trans, waterColor);
            depth[i] = (float) t;
        } else if (!first && trans < 0.75f) {
            out.argb[i] = 0xFF000000 | mix(tr, tg, tb, trans, 0xFF101418);
        }
    }

    private static void record(Baked out, int i, int y, int z) {
        out.hitY[i] = (short) y;
        out.hitZ[i] = z;
    }

    private static int shade(VoxelStore.Reader rd, BlockLook look, int face, int x, int y, int z, double fx,
                             double sub, double yHit, double zHit, int cutY, MapCut cut, int colTop) {
        int base = face == TOP ? look.top()[cell(fx, sub)] : look.side()[cell(fx, 1 - sub)];
        float ao;
        if (face == TOP) {
            boolean n = solid(rd, cut, x, y + 1, z - 1), s = solid(rd, cut, x, y + 1, z + 1);
            boolean w = solid(rd, cut, x - 1, y + 1, z), e = solid(rd, cut, x + 1, y + 1, z);
            float c00 = AO[occ(w, n, solid(rd, cut, x - 1, y + 1, z - 1))];
            float c10 = AO[occ(e, n, solid(rd, cut, x + 1, y + 1, z - 1))];
            float c01 = AO[occ(w, s, solid(rd, cut, x - 1, y + 1, z + 1))];
            float c11 = AO[occ(e, s, solid(rd, cut, x + 1, y + 1, z + 1))];
            ao = lerp(lerp(c00, c10, fx), lerp(c01, c11, fx), sub);
        } else {
            int fz = z + 1;
            boolean up = solid(rd, cut, x, y + 1, fz), dn = solid(rd, cut, x, y - 1, fz);
            boolean w = solid(rd, cut, x - 1, y, fz), e = solid(rd, cut, x + 1, y, fz);
            float top0 = AO[occ(w, up, solid(rd, cut, x - 1, y + 1, fz))];
            float top1 = AO[occ(e, up, solid(rd, cut, x + 1, y + 1, fz))];
            float bot0 = AO[occ(w, dn, solid(rd, cut, x - 1, y - 1, fz))];
            float bot1 = AO[occ(e, dn, solid(rd, cut, x + 1, y - 1, fz))];
            ao = lerp(lerp(bot0, bot1, fx), lerp(top0, top1, fx), sub);
        }
        double px = x + fx, py = yHit, pz = zHit;
        if (face == TOP) py += 0.01;
        else pz += 0.01;
        boolean lit = !shadowed(rd, px, py, pz, cutY, cut);
        float ndl = (float) (face == TOP ? LY : LZ);
        float amb = AMBIENT * ao * (face == TOP ? 1f : SIDE_AMBIENT);
        float sun = lit ? SUN * ndl * (0.6f + 0.4f * ao) : 0f;
        float glow = look.emission() / 15f * 0.9f;
        if (glow > amb + sun) amb = glow - sun;
        float below = (float) Math.min(1, Math.max(0, (colTop - y - 2) / DEPTH_FADE));
        float fade = (1f - 0.25f * below) * variation(x, y, z) * EXPOSURE;
        float lr = (amb * AMB_TINT[0] + sun * SUN_TINT[0]) * fade;
        float lg = (amb * AMB_TINT[1] + sun * SUN_TINT[1]) * fade;
        float lb = (amb * AMB_TINT[2] + sun * SUN_TINT[2]) * fade;
        float r = r(base) * lr, g = g(base) * lg, b = b(base) * lb;
        float l = 0.299f * r + 0.587f * g + 0.114f * b;
        r = l + (r - l) * SATURATION;
        g = l + (g - l) * SATURATION;
        b = l + (b - l) * SATURATION;
        return rgb(r, g, b);
    }

    private static boolean shadowed(VoxelStore.Reader rd, double x, double y, double z, int cutY, MapCut cut) {
        for (double s = 0.6; s <= SHADOW_REACH; s += SHADOW_STEP) {
            double sy = y + LY * s;
            if (sy >= cutY + 1) return false;
            int bx = (int) Math.floor(x + LX * s), by = (int) Math.floor(sy), bz = (int) Math.floor(z + LZ * s);
            if (by > cut.top(bx, bz)) continue;
            int id = rd.id(bx, by, bz);
            if (id == 0) continue;
            byte k = rd.look(id).kind();
            if (k == BlockLook.SOLID || k == BlockLook.GLASS) return true;
        }
        return false;
    }

    private static boolean solid(VoxelStore.Reader rd, MapCut cut, int x, int y, int z) {
        if (y > cut.top(x, z)) return false;
        int id = rd.id(x, y, z);
        return id != 0 && rd.look(id).kind() == BlockLook.SOLID;
    }

    private static int occ(boolean s1, boolean s2, boolean corner) {
        if (s1 && s2) return 3;
        return (s1 ? 1 : 0) + (s2 ? 1 : 0) + (corner ? 1 : 0);
    }

    /** Index into a 4×4 grid from two 0..1 fractions (column, row). */
    private static int cell(double col, double row) {
        int c = Math.min(3, Math.max(0, (int) (col * 4))), rw = Math.min(3, Math.max(0, (int) (row * 4)));
        return rw * 4 + c;
    }

    /** ±4% brightness per block, so flat floors don't look like plastic. */
    private static float variation(int x, int y, int z) {
        int h = x * 73856093 ^ y * 19349663 ^ z * 83492791;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return 0.96f + (h & 0xFF) / 255f * 0.08f;
    }

    // ------------------------------------------------------------------ outline

    private static void outline(int[] argb, float[] depth) {
        int n = TILE_PX;
        boolean[] dark = new boolean[argb.length];
        for (int py = 0; py < n; py++) {
            for (int px = 0; px < n; px++) {
                int i = py * n + px;
                float d = depth[i];
                if (d == Float.MAX_VALUE) continue;
                float lim = d - OUTLINE_DEPTH;
                if ((py > 0 && depth[i - n] < lim) || (py < n - 1 && depth[i + n] < lim)
                        || (px > 0 && depth[i - 1] < lim) || (px < n - 1 && depth[i + 1] < lim)) dark[i] = true;
            }
        }
        for (int i = 0; i < argb.length; i++) {
            if (!dark[i]) continue;
            int c = argb[i];
            argb[i] = (c & 0xFF000000) | rgb(r(c) * OUTLINE_DARK, g(c) * OUTLINE_DARK, b(c) * OUTLINE_DARK);
        }
    }

    // ------------------------------------------------------------------ colour helpers (0..1 floats)

    private static float r(int c) { return ((c >> 16) & 0xFF) / 255f; }
    private static float g(int c) { return ((c >> 8) & 0xFF) / 255f; }
    private static float b(int c) { return (c & 0xFF) / 255f; }

    private static int rgb(float r, float g, float b) {
        return to255(r) << 16 | to255(g) << 8 | to255(b);
    }

    private static int to255(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255)));
    }

    /** Front tint layers over a base colour: {@code acc + trans * base}. */
    private static int mix(float tr, float tg, float tb, float trans, int base) {
        return rgb(tr + trans * r(base), tg + trans * g(base), tb + trans * b(base));
    }

    private static float lerp(float a, float b, double t) {
        return (float) (a + (b - a) * t);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : v > 0.9999 ? 0.9999 : v;
    }
}
