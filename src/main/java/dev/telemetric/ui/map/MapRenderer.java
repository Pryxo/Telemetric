package dev.telemetric.ui.map;

import dev.telemetric.data.MapImages;
import dev.telemetric.data.MapTable;
import dev.telemetric.game.MapTextureAdapter;
import dev.telemetric.game.ResourceAdapter;
import dev.telemetric.data.MapMarkerTable.Category;
import dev.telemetric.data.MapMarkerTable.Shape;
import dev.telemetric.map.MapMarkers;
import dev.telemetric.map.MapMarkers.Marker;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drawing shared by the Telos Map screen and the minimap: the region's image tiles clipped to any convex shape
 * (rectangle or circle), markers and player heads. Everything, icons included, is float-precise
 * ({@link Render2D#polygon}, {@link Render2D#texturedPolygon}), so markers stay glued to the map while it pans, zooms
 * and rotates.
 */
public final class MapRenderer {

    /** Tile load state for the overlay text. */
    public record TileStatus(int total, int ready, int failed) {
        public boolean loading() {
            return ready + failed < total;
        }
    }

    private static final Map<String, String> ICON_FALLBACK = new ConcurrentHashMap<>();

    private MapRenderer() {}

    /**
     * Draws every tile of {@code region} inside the convex screen polygon {@code clip} ({@code n} corners). Missing
     * images get a {@code placeholder} fill.
     *
     * @param pixelsPerUnit window pixels per GUI unit at this spot (GUI scale × any HUD scale), for the texture level
     */
    public static TileStatus drawTiles(Render2D r, MapTable.Region region, MapView v, float[] clip, int n,
                                       double pixelsPerUnit, int placeholder) {
        double blocksPerPixel = 1.0 / Math.max(1e-6, v.scale * pixelsPerUnit);
        double[] world = new double[n * 2];
        for (int i = 0; i < n; i++) {
            world[i * 2] = v.wx(clip[i * 2], clip[i * 2 + 1]);
            world[i * 2 + 1] = v.wz(clip[i * 2], clip[i * 2 + 1]);
        }
        int ready = 0, failed = 0;
        for (MapTable.Tile t : region.tiles()) {
            double[] poly = clipToRect(world, n, t.x1(), t.z1(), t.x2() + 1, t.z2() + 1);
            String tex = MapTextureAdapter.texture(t, blocksPerPixel);
            boolean bad = tex == null && MapTextureAdapter.failed(t);
            if (tex != null) ready++;
            else if (bad) failed++;
            if (poly == null) continue;
            int m = poly.length / 2;
            float[] xy = new float[m * 2], uv = new float[m * 2];
            for (int i = 0; i < m; i++) {
                double wx = poly[i * 2], wz = poly[i * 2 + 1];
                xy[i * 2] = v.sx(wx, wz);
                xy[i * 2 + 1] = v.sy(wx, wz);
                uv[i * 2] = (float) ((wx - t.x1()) / t.width());
                uv[i * 2 + 1] = (float) ((wz - t.z1()) / t.height());
            }
            if (tex != null) r.texturedPolygon(tex, xy, uv, m, 0xFFFFFFFF);
            else r.polygon(xy, m, placeholder);
        }
        return new TileStatus(region.tiles().size(), ready, failed);
    }

    /** True while any tile of the region is still downloading. */
    public static boolean downloading(MapTable.Region region) {
        for (MapTable.Tile t : region.tiles()) if (MapImages.status(t) == MapImages.Status.LOADING) return true;
        return false;
    }

    /** Sutherland–Hodgman: the convex polygon {@code p} cut to the rectangle, or null if nothing is left. */
    static double[] clipToRect(double[] p, int n, double minX, double minZ, double maxX, double maxZ) {
        double[] cur = java.util.Arrays.copyOf(p, n * 2);
        int count = n;
        for (int edge = 0; edge < 4 && count > 0; edge++) {
            double[] next = new double[(count + 4) * 2];
            int k = 0;
            for (int i = 0; i < count; i++) {
                double ax = cur[i * 2], az = cur[i * 2 + 1];
                int j = (i + 1) % count;
                double bx = cur[j * 2], bz = cur[j * 2 + 1];
                boolean ain = inside(edge, ax, az, minX, minZ, maxX, maxZ), bin = inside(edge, bx, bz, minX, minZ, maxX, maxZ);
                if (ain) {
                    if (k * 2 + 1 >= next.length) next = java.util.Arrays.copyOf(next, next.length * 2);
                    next[k * 2] = ax;
                    next[k * 2 + 1] = az;
                    k++;
                }
                if (ain != bin) {
                    double t = switch (edge) {
                        case 0 -> (minX - ax) / (bx - ax);
                        case 1 -> (maxX - ax) / (bx - ax);
                        case 2 -> (minZ - az) / (bz - az);
                        default -> (maxZ - az) / (bz - az);
                    };
                    if (k * 2 + 1 >= next.length) next = java.util.Arrays.copyOf(next, next.length * 2);
                    next[k * 2] = ax + (bx - ax) * t;
                    next[k * 2 + 1] = az + (bz - az) * t;
                    k++;
                }
            }
            cur = next;
            count = k;
        }
        return count < 3 ? null : java.util.Arrays.copyOf(cur, count * 2);
    }

    private static boolean inside(int edge, double x, double z, double minX, double minZ, double maxX, double maxZ) {
        return switch (edge) {
            case 0 -> x >= minX;
            case 1 -> x <= maxX;
            case 2 -> z >= minZ;
            default -> z <= maxZ;
        };
    }

    // ---- shapes -----------------------------------------------------------------------------------------------------

    /** A round polygon (enough corners to look round at its size). */
    public static void disc(Render2D r, double cx, double cy, double radius, int color) {
        if (radius <= 0) return;
        int seg = (int) Math.clamp(Math.round(radius * 3), 12, 48);
        r.polygon(circle(cx, cy, radius, seg), seg, color);
    }

    /** Corners of a circle, clockwise from the top. */
    public static float[] circle(double cx, double cy, double radius, int segments) {
        float[] xy = new float[segments * 2];
        for (int i = 0; i < segments; i++) {
            double a = Math.PI * 2 * i / segments;
            xy[i * 2] = (float) (cx + Math.sin(a) * radius);
            xy[i * 2 + 1] = (float) (cy - Math.cos(a) * radius);
        }
        return xy;
    }

    /** An axis-aligned rectangle as polygon corners (float-precise, unlike {@link Render2D#rect}). */
    static float[] quad(double x, double y, double w, double h) {
        return new float[]{(float) x, (float) y, (float) (x + w), (float) y, (float) (x + w), (float) (y + h),
                (float) x, (float) (y + h)};
    }

    private static final float[] FACE_UV = {8 / 64f, 8 / 64f, 16 / 64f, 8 / 64f, 16 / 64f, 16 / 64f, 8 / 64f, 16 / 64f};
    private static final float[] HAT_UV = {40 / 64f, 8 / 64f, 48 / 64f, 8 / 64f, 48 / 64f, 16 / 64f, 40 / 64f, 16 / 64f};

    /**
     * A player's face (skin face + hat layer) centred on (cx, cy), {@code size} wide, with a {@code border}-wide
     * frame. A null skin draws a plain {@code fallback} square.
     */
    public static void head(Render2D r, String skin, double cx, double cy, double size, double border, int frame,
                            int fallback) {
        double x = cx - size / 2, y = cy - size / 2;
        if (border > 0) r.polygon(quad(x - border, y - border, size + border * 2, size + border * 2), 4, frame);
        float[] xy = quad(x, y, size, size);
        if (skin == null) {
            r.polygon(xy, 4, fallback);
            return;
        }
        r.texturedPolygon(skin, xy, FACE_UV, 4, 0xFFFFFFFF);
        r.texturedPolygon(skin, xy, HAT_UV, 4, 0xFFFFFFFF);
    }

    /**
     * A small arrow just outside a head of radius {@code inner} centred on (cx, cy), pointing at screen angle
     * {@code angle} (radians, 0 = up, clockwise; see {@link MapView#screenAngle}). {@code len} is the arrow length.
     */
    public static void facingArrow(Render2D r, double cx, double cy, double angle, double inner, double len,
                                   int color, int outline) {
        double dx = Math.sin(angle), dy = -Math.cos(angle);
        double w = len * 0.75;
        arrowTri(r, cx, cy, dx, dy, inner - 0.6, inner + len + 1.0, w + 1.0, outline);
        arrowTri(r, cx, cy, dx, dy, inner, inner + len, w, color);
    }

    private static void arrowTri(Render2D r, double cx, double cy, double dx, double dy, double base, double tip,
                                 double halfW, int color) {
        double bx = cx + dx * base, by = cy + dy * base;
        r.polygon(new float[]{
                (float) (cx + dx * tip), (float) (cy + dy * tip),
                (float) (bx - dy * halfW), (float) (by + dx * halfW),
                (float) (bx + dy * halfW), (float) (by - dx * halfW)}, 3, color);
    }

    /** A small rotated square. */
    public static void diamond(Render2D r, double cx, double cy, double half, int color) {
        r.polygon(new float[]{(float) cx, (float) (cy - half), (float) (cx + half), (float) cy, (float) cx,
                (float) (cy + half), (float) (cx - half), (float) cy}, 4, color);
    }

    // ---- markers ----------------------------------------------------------------------------------------------------

    /** Half the hit size of a marker drawn at {@code size} (before its category's size factor). */
    public static double hitRadius(Marker m, double size) {
        double s = size * m.category().size();
        if (m.category().shape() == Shape.HEAD) return Math.max(4, s * 0.7) / 2 + 1;
        if (iconOf(m) != null) return badgeRadius(m.category().shape(), s) + 1;
        return Math.max(3, s * 0.4);
    }

    /**
     * Draws a marker centred on (sx, sy) the way its category says ({@code mapMarkers.json} {@code shape}).
     * {@code size} is the icon size in GUI units before the category's size factor. A marker with an icon in the
     * resource pack is drawn as a badge in its shape (round for {@code icon}/{@code dot}, diamond for
     * {@code diamond}) with the item inside; without one it is a plain dot or diamond.
     */
    public static void marker(Render2D r, Marker m, double sx, double sy, double size, boolean hovered, Theme p) {
        double s = size * m.category().size();
        int outline = ColorUtil.withAlpha(p.shadow(), 230);
        Shape shape = m.category().shape();
        if (shape == Shape.HEAD) {
            head(r, m.skin(), sx, sy, Math.max(4, s * 0.7), 1, hovered ? p.text() : outline, p.textMuted());
            return;
        }
        String icon = iconOf(m);
        if (icon != null) {
            badge(r, shape, icon, sx, sy, badgeRadius(shape, s), hovered ? p.text() : m.color(), p);
            return;
        }
        int fill = hovered ? p.text() : m.color();
        glow(r, sx, sy, Math.max(3, s * 0.4), fill);
    }

    /**
     * A category's legend swatch, {@code size} wide, centred on (cx, cy): the same badge as its markers.
     * {@code icon} is the item model to show (the category's, or one of its markers'), or null; {@code skin} is for
     * head categories.
     */
    public static void swatch(Render2D r, Category c, String icon, double cx, double cy, double size, String skin,
                              Theme p) {
        int outline = ColorUtil.withAlpha(p.shadow(), 230);
        int color = MapMarkers.legendColor(c);
        double half = size / 2;
        if (c.shape() == Shape.HEAD) {
            head(r, skin, cx, cy, size - 2, 1, outline, p.textMuted());
            return;
        }
        String model = usable(icon);
        if (model != null) {
            badge(r, c.shape(), model, cx, cy, half * 0.7, color, p);
        } else {
            glow(r, cx, cy, half - 1, color);
        }
    }

    /** Radius of an icon marker (its glow is a bit larger) for size {@code s}. */
    private static double badgeRadius(Shape shape, double s) {
        return s * 0.55;
    }

    /**
     * An icon marker: a soft round glow in the marker's colour (stacked translucent discs, densest in the centre)
     * with the item model on top. No outline or shape; {@code shape} is ignored.
     */
    private static void badge(Render2D r, Shape shape, String icon, double cx, double cy, double rad, int glow, Theme p) {
        glow(r, cx, cy, rad * 1.35, glow);
        double is = Math.max(7, rad * 1.6);
        r.itemModel(icon, (float) (cx - is / 2), (float) (cy - is / 2), (float) is);
    }

    /** A soft round glow of {@code color} with radius {@code rad}, fading out towards the edge. */
    private static void glow(Render2D r, double cx, double cy, double rad, int color) {
        final int layers = 7;
        for (int i = 0; i < layers; i++) {
            double t = 1 - (double) i / layers;            // 1 = outermost
            disc(r, cx, cy, rad * t, ColorUtil.withAlpha(color, 26 + i * 6));
        }
    }

    /** The marker's item model ({@code mapMarkers.json} {@code icon}) if the resource pack has it, else null. */
    static String iconOf(Marker m) {
        return usable(m.icon());
    }

    /** {@code model} if the resource pack has it ({@code ns:items/<path>.json}), else null. Cached. */
    static String usable(String model) {
        if (model == null || model.isEmpty()) return null;
        return ICON_FALLBACK.computeIfAbsent(model, id -> {
            int colon = id.indexOf(':');
            String ns = colon < 0 ? "minecraft" : id.substring(0, colon), path = id.substring(colon + 1);
            return ResourceAdapter.exists(ns + ":items/" + path + ".json") ? id : "";
        }).isEmpty() ? null : model;
    }

    /** Forgets the icons that were missing (the server resource pack may have loaded since). */
    public static void forgetIcons() {
        ICON_FALLBACK.values().removeIf(String::isEmpty);
    }

    /** "1.2km" / "340m". */
    public static String distance(double blocks) {
        return blocks >= 1000 ? String.format(Locale.ROOT, "%.1fkm", blocks / 1000) : Math.round(blocks) + "m";
    }
}
