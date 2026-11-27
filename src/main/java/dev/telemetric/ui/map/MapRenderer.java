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
        return s * 0.3;
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
        if (shape == Shape.DIAMOND) {
            diamond(r, sx, sy, s * 0.3 + 1, outline);
            diamond(r, sx, sy, s * 0.3, fill);
        } else {
            dot(r, sx, sy, s, fill, outline);
        }
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
            badge(r, c.shape(), model, cx, cy, c.shape() == Shape.DIAMOND ? half * 0.95 : half - 1, color, p);
        } else if (c.shape() == Shape.DIAMOND) {
            diamond(r, cx, cy, half - 1, outline);
            diamond(r, cx, cy, half - 2, color);
        } else {
            disc(r, cx, cy, half - 1.5, outline);
            disc(r, cx, cy, half - 2.5, color);
        }
    }

    private static void dot(Render2D r, double sx, double sy, double s, int fill, int outline) {
        double rad = Math.max(2, s * 0.25);
        disc(r, sx, sy, rad + 1, outline);
        disc(r, sx, sy, rad, fill);
    }

    /** Inner radius of an icon badge (disc radius, or the diamond's half diagonal) for size {@code s}. */
    private static double badgeRadius(Shape shape, double s) {
        return shape == Shape.DIAMOND ? s * 0.72 : s * 0.55;
    }

    /**
     * An icon badge: soft drop shadow, dark outline, a 1-unit {@code ring} in the marker's colour, a panel-coloured
     * face and the item model on top, sized to fill the face (a diamond's face only fits a square of its half
     * diagonal, so its item is relatively smaller).
     */
    private static void badge(Render2D r, Shape shape, String icon, double cx, double cy, double rad, int ring, Theme p) {
        int shadow = ColorUtil.withAlpha(p.shadow(), 90), outline = ColorUtil.withAlpha(p.shadow(), 230);
        int face = ColorUtil.withAlpha(p.panel(), 240);
        double is;
        if (shape == Shape.DIAMOND) {
            diamond(r, cx, cy + 1, rad + 2, shadow);
            diamond(r, cx, cy, rad + 2, outline);
            diamond(r, cx, cy, rad + 0.8, ring);
            diamond(r, cx, cy, rad - 0.6, face);
            is = Math.max(7, rad * 1.15);
        } else {
            disc(r, cx, cy + 1, rad + 1.5, shadow);
            disc(r, cx, cy, rad + 1.5, outline);
            disc(r, cx, cy, rad + 0.5, ring);
            disc(r, cx, cy, rad - 0.5, face);
            is = Math.max(7, rad * 1.5);
        }
        r.itemModel(icon, (float) (cx - is / 2), (float) (cy - is / 2), (float) is);
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
