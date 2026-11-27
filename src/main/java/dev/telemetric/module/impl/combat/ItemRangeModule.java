package dev.telemetric.module.impl.combat;

import dev.telemetric.game.CombatAdapter;
import dev.telemetric.game.CombatAdapter.ItemInfo;
import dev.telemetric.game.CombatAdapter.Kind;
import dev.telemetric.game.EntityVisualsAdapter;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.ServerGate;
import dev.telemetric.game.WorldRenderAdapter;
import dev.telemetric.game.WorldRenderAdapter.LineSink;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ColorRamp;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.EnumSetting;
import dev.telemetric.setting.impl.GroupToggleSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.render.ColorUtil;

/**
 * Weapon Range / Ability Range: the reach of the held weapon or ability, drawn on the ground as an arc, circle or
 * line. The range is the item's lore {@code Range} including its trait modifier, or a {@link TelosItemRules} override
 * (offset abilities are centred in front of the player and get a centre marker).
 * <p>
 * One class with two instances ({@link #ability()}, {@link #weapon(ItemRangeModule)}). When both would show the same
 * circle, the weapon one steps aside so the lines don't overlap.
 * <p>
 * It's drawn as one clean edge line with a soft band fading inward on the ground. With Target Highlight on, the colour
 * eases to the target colour while a hittable mob ({@link EntityVisualsAdapter#forEachHittableBox}) is inside the
 * drawn area.
 */
public final class ItemRangeModule extends Module {

    public enum Shape implements EnumSetting.Nameable {
        ARC("Arc"), CIRCLE("Circle"), LINE("Line");
        private final String label;
        Shape(String label) { this.label = label; }
        @Override public String displayName() { return label; }
    }

    private record Area(double range, double offset, TelosItemRules.Marker marker, boolean drawRange) {}

    private static final int CIRCLE_SEGMENTS = 72, ARC_SEGMENTS = 40, LINE_STEPS = 10;
    private static final double ARC_FADE = 0.18; // how much of each arc end is faded
    /** Ground bands inside the edge: {inner offset, outer offset, alpha share of the line colour}. */
    private static final double[][] BANDS = {{0.45, 0.0, 0.30}, {1.1, 0.45, 0.10}};
    private static final double LINE_HIT_SLACK = 0.4; // blocks either side of the aim line that count as a hit
    private static final double BODY_HEIGHT = 1.8;

    private final Kind kind;
    private final ItemRangeModule yieldTo;
    private final double lift;

    private final ColorSetting color;
    private final EnumSetting<Shape> shape;
    private final NumberSetting arcWidth;
    private final BooleanSetting fill;
    private final BooleanSetting aimLine;
    private final NumberSetting width;
    private final NumberSetting aimWidth;
    private final BooleanSetting throughWalls;
    private final GroupToggleSetting highlight;
    private final ColorSetting targetColor;
    private final BooleanSetting emblem; // weapon only

    /** 0 is the range colour, 1 the target colour, eased over time. */
    private double hot;
    private long lastFrame;

    public static ItemRangeModule ability() {
        return new ItemRangeModule(Kind.ABILITY, "Ability Range", 0xE08A3FC0, 0.04, null);
    }

    /** @param ability the ability range module; the weapon range hides when both would draw the same area */
    public static ItemRangeModule weapon(ItemRangeModule ability) {
        return new ItemRangeModule(Kind.WEAPON, "Weapon Range", 0xE0FF3B3B, 0.02, ability);
    }

    private ItemRangeModule(Kind kind, String name, int defaultColor, double lift, ItemRangeModule yieldTo) {
        super(name, "Shows the range of the held " + (kind == Kind.WEAPON ? "weapon" : "ability") + " on the ground",
                Category.COMBAT);
        this.kind = kind;
        this.lift = lift;
        this.yieldTo = yieldTo;
        this.color = add(new ColorSetting("Color", "Colour of the range", defaultColor, true));
        this.shape = add(new EnumSetting<>("Shape", "How the range is drawn", Shape.ARC));
        this.arcWidth = add(new NumberSetting("Arc Width", "How wide the arc in front of you is", 70, 20, 180, 5)
                .withSuffix("°").visibleWhen(() -> shape.get() == Shape.ARC));
        this.fill = add(new BooleanSetting("Ground Fill", "Soft band on the ground inside the edge", true)
                .visibleWhen(() -> shape.get() != Shape.LINE));
        this.aimLine = add(new BooleanSetting("Aim Line", "Also draw a line from you to the edge of the range", false)
                .visibleWhen(() -> shape.get() != Shape.LINE));
        this.width = add(new NumberSetting("Line Width", "Thickness of the arc / circle edge in pixels", 2, 1, 20, 0.5)
                .withSuffix("px").visibleWhen(() -> shape.get() != Shape.LINE));
        this.aimWidth = add(new NumberSetting("Aim Line Width", "Thickness of the aim line in pixels", 2, 1, 20, 0.5)
                .withSuffix("px").visibleWhen(() -> shape.get() == Shape.LINE || aimLine.get()));
        this.throughWalls = add(new BooleanSetting("Through Walls", "Keep the range visible behind blocks", false));
        this.highlight = add(new GroupToggleSetting("Target Highlight",
                "Change colour while a hittable mob is inside the range (not invisible or static hitboxes)", false));
        ColorSetting tc = new ColorSetting("Target Color", "Colour while a mob is in range", 0xE04ADE80, true);
        tc.withLabel("Color");
        this.targetColor = tc;
        add(highlight.child(tc));
        this.emblem = kind == Kind.WEAPON
                ? add(new BooleanSetting("Emblem Bonus", "Add +1 range for an emblem buff", false)) : null;
        WorldRenderAdapter.addDrawer(this::draw);
    }

    /** The held item's area, or null if nothing of this kind with a range is held. */
    private Area area() {
        ItemInfo held = CombatAdapter.held(kind);
        if (held == null) return null;
        TelosItemRules.RangeRule rule = TelosItemRules.range(held.nameKey());
        double range = rule != null ? rule.range() : held.range();
        if (range <= 0) return null;
        if (emblem != null && emblem.get()) range += 1;
        return rule != null ? new Area(range, rule.offset(), rule.marker(), rule.drawRange())
                : new Area(range, 0, TelosItemRules.Marker.DOT, true);
    }

    private void draw(LineSink sink) {
        if (!isEnabled() || PlayerAdapter.isRiding() || !ServerGate.onTelos()) return;
        Area area = area();
        if (area == null) return;
        if (yieldTo != null && yieldTo.isEnabled() && area.equals(yieldTo.area())) return;
        PlayerAdapter.Pose pose = PlayerAdapter.renderPose();
        if (pose == null) return;

        double yaw = Math.toRadians(pose.yaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw); // MC yaw 0 is south (+Z)
        double y = pose.y() + lift;
        double cx = pose.x() + fx * area.offset(), cz = pose.z() + fz * area.offset();
        Shape s = shape.get();
        double half = Math.toRadians(arcWidth.get()) / 2;

        boolean target = highlight.get() && area.drawRange()
                && targetInside(s, pose.x(), pose.y(), pose.z(), cx, cz, fx, fz, area.range(), half);
        // a gradient colour runs along the line / around the ring
        ColorRamp argb = ColorRamp.lerp(color, targetColor, ease(target));
        float w = width.getFloat();
        boolean walls = throughWalls.get();

        if (area.drawRange()) {
            if (s == Shape.LINE || aimLine.get()) {
                fadingLine(sink, pose.x(), y, pose.z(), cx + fx * area.range(), cz + fz * area.range(), argb,
                        aimWidth.getFloat(), walls);
            }
            if (s == Shape.CIRCLE) ring(sink, cx, y, cz, area.range(), 0, Math.PI, 0, CIRCLE_SEGMENTS, argb, w, walls);
            if (s == Shape.ARC) ring(sink, cx, y, cz, area.range(), yaw, half, ARC_FADE, ARC_SEGMENTS, argb, w, walls);
        }
        if (area.offset() != 0) {
            if (area.marker() == TelosItemRules.Marker.SQUARE) square(sink, cx, y, cz, 0.5, yaw, argb, w, walls);
            else ring(sink, cx, y, cz, 0.3, 0, Math.PI, 0, 24, argb, w, walls);
        }
    }

    /** Eases {@link #hot} toward the target state and returns the blend for this frame. */
    private double ease(boolean target) {
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 0 : (now - lastFrame) / 1e6;
        lastFrame = now;
        int ms = Durations.scaled(Durations.HOVER);
        double step = ms <= 0 ? 1 : Math.min(1, dt / ms);
        hot = target ? Math.min(1, hot + step) : Math.max(0, hot - step);
        return hot * hot * (3 - 2 * hot); // smoothstep
    }

    /** True if a hittable mob's hitbox overlaps the drawn area. */
    private static boolean targetInside(Shape s, double px, double py, double pz, double cx, double cz, double fx,
                                        double fz, double range, double half) {
        boolean[] hit = {false};
        EntityVisualsAdapter.forEachHittableBox(range + 4, (k, x0, y0, z0, x1, y1, z1) -> {
            if (hit[0]) return;
            double gy = Math.max(0, Math.max(y0 - (py + BODY_HEIGHT), py - y1)); // vertical gap to your body
            double r = Math.max(x1 - x0, z1 - z0) / 2;
            if (s == Shape.LINE) {
                double bx = (x0 + x1) / 2 - px, bz = (z0 + z1) / 2 - pz;
                double along = bx * fx + bz * fz, side = Math.abs(bx * fz - bz * fx);
                hit[0] = along >= -r && along <= range + r && side <= r + LINE_HIT_SLACK && gy <= range;
                return;
            }
            // nearest point of the box to the centre, on the ground plane
            double nx = Math.clamp(cx, x0, x1) - cx, nz = Math.clamp(cz, z0, z1) - cz;
            if (Math.sqrt(nx * nx + nz * nz + gy * gy) > range) return;
            if (s == Shape.CIRCLE || (nx == 0 && nz == 0)) {
                hit[0] = true;
                return;
            }
            double bx = (x0 + x1) / 2 - cx, bz = (z0 + z1) / 2 - cz;
            double dist = Math.max(1e-3, Math.sqrt(bx * bx + bz * bz));
            double angle = Math.abs(Math.atan2(bx * fz - bz * fx, bx * fx + bz * fz));
            hit[0] = angle <= half + Math.atan(r / dist);
        });
        return hit[0];
    }

    /**
     * Ground ring of radius {@code r} centred on {@code yaw}, spanning ±{@code half} (pi is a full circle). It's one
     * edge line plus, with Ground Fill, translucent bands fading inward. {@code fade} is how much of each end is faded.
     */
    private void ring(LineSink sink, double cx, double y, double cz, double r, double yaw, double half, double fade,
                      int segments, ColorRamp argb, float w, boolean walls) {
        boolean loop = half >= Math.PI - 1e-6; // a full circle: there and back, so the gradient has no seam
        boolean bands = fill.get() && r > 1;
        double px = 0, pz = 0, pa = 0;
        for (int i = 0; i <= segments; i++) {
            double t = i / (double) segments;
            double a = yaw - half + 2 * half * t;
            double sin = -Math.sin(a), cos = Math.cos(a);
            double x = cx + sin * r, z = cz + cos * r;
            if (i > 0) {
                double edge = Math.abs(t - 0.5 / segments - 0.5) * 2; // 0 is the middle, 1 the end
                double f = fade > 0 && edge > 1 - fade ? (1 - edge) / fade : 1;
                double mid = t - 0.5 / segments;
                int c = ColorUtil.fade(argb.at((float) (loop ? 1 - Math.abs(2 * mid - 1) : mid)), f);
                sink.line(px, y, pz, x, y, z, c, w, walls);
                if (bands) {
                    double ps = -Math.sin(pa), pc = Math.cos(pa);
                    for (double[] b : BANDS) {
                        double ri = Math.max(0, r - b[0]), ro = r - b[1];
                        sink.quad(cx + ps * ri, y, cz + pc * ri, cx + ps * ro, y, cz + pc * ro,
                                cx + sin * ro, y, cz + cos * ro, cx + sin * ri, y, cz + cos * ri,
                                ColorUtil.fade(c, b[2]), walls);
                    }
                }
            }
            px = x;
            pz = z;
            pa = a;
        }
    }

    /** A line that fades in from the player towards its end. */
    private static void fadingLine(LineSink sink, double x1, double y, double z1, double x2, double z2, ColorRamp argb,
                                   float w, boolean walls) {
        for (int i = 0; i < LINE_STEPS; i++) {
            double t0 = i / (double) LINE_STEPS, t1 = (i + 1) / (double) LINE_STEPS;
            sink.line(x1 + (x2 - x1) * t0, y, z1 + (z2 - z1) * t0, x1 + (x2 - x1) * t1, y, z1 + (z2 - z1) * t1,
                    ColorUtil.fade(argb.at((float) t0), t0), ColorUtil.fade(argb.at((float) t1), t1), w, walls);
        }
    }

    private static void square(LineSink sink, double cx, double y, double cz, double h, double yaw, ColorRamp argb, float w,
                               boolean walls) {
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        double[][] c = {{-h, -h}, {h, -h}, {h, h}, {-h, h}};
        for (int i = 0; i < 4; i++) {
            double[] a = c[i], b = c[(i + 1) % 4];
            sink.line(cx + a[0] * cos - a[1] * sin, y, cz + a[0] * sin + a[1] * cos,
                    cx + b[0] * cos - b[1] * sin, y, cz + b[0] * sin + b[1] * cos,
                    argb.at(1 - Math.abs(i / 2f - 1)), argb.at(1 - Math.abs((i + 1) / 2f - 1)), w, walls);
        }
    }
}
