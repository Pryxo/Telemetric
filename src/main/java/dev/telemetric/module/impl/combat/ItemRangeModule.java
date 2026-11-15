package dev.telemetric.module.impl.combat;

import dev.telemetric.game.CombatAdapter;
import dev.telemetric.game.CombatAdapter.ItemInfo;
import dev.telemetric.game.CombatAdapter.Kind;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.ServerGate;
import dev.telemetric.game.WorldRenderAdapter;
import dev.telemetric.game.WorldRenderAdapter.LineSink;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.EnumSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.render.ColorUtil;

/**
 * Weapon Range / Ability Range: the reach of the held weapon / ability drawn on the ground (arc, circle or
 * line). The range is the item's lore {@code Range} incl. its trait modifier, or a {@link TelosItemRules} override
 * (offset abilities are centred in front of the player and get a centre marker).
 * <p>
 * One class, two instances ({@link #ability()}, {@link #weapon(ItemRangeModule)}). When both show the same circle,
 * the weapon one steps aside so the lines do not overlap.
 */
public final class ItemRangeModule extends Module {

    public enum Shape implements EnumSetting.Nameable {
        ARC("Arc"), CIRCLE("Circle"), LINE("Line");
        private final String label;
        Shape(String label) { this.label = label; }
        @Override public String displayName() { return label; }
    }

    /** Where and how big one range is. */
    private record Area(double range, double offset, TelosItemRules.Marker marker, boolean drawRange) {}

    private static final int CIRCLE_SEGMENTS = 64, ARC_SEGMENTS = 32, LINE_STEPS = 8;
    private static final double ARC_HALF_ANGLE = Math.PI / 8; // 45° wide arc
    private static final double ARC_HEIGHT = 0.2, ARC_FADE = 0.2; // ribbon height, faded share of each end
    private static final int ARC_LAYERS = 3;

    private final Kind kind;
    private final ItemRangeModule yieldTo;
    private final double lift;

    private final ColorSetting color;
    private final EnumSetting<Shape> shape;
    private final BooleanSetting aimLine;
    private final NumberSetting width;
    private final BooleanSetting throughWalls;
    private final BooleanSetting emblem; // weapon only

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
        this.color = add(new ColorSetting("Color", "Colour of the range lines", defaultColor, true));
        this.shape = add(new EnumSetting<>("Shape", "How the range is drawn", Shape.ARC));
        this.aimLine = add(new BooleanSetting("Aim Line", "Also draw a line from you to the edge of the range", false)
                .visibleWhen(() -> shape.get() != Shape.LINE));
        this.width = add(new NumberSetting("Line Width", "Thickness in pixels", 2.5, 1, 6, 0.5).withSuffix("px"));
        this.throughWalls = add(new BooleanSetting("Through Walls", "Keep the range visible behind blocks", false));
        this.emblem = kind == Kind.WEAPON
                ? add(new BooleanSetting("Emblem Bonus", "Add +1 range for an emblem buff", false)) : null;
        WorldRenderAdapter.addDrawer(this::draw);
    }

    /** The held item's area, or null when nothing of this kind with a range is held. */
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
        double fx = -Math.sin(yaw), fz = Math.cos(yaw); // MC yaw 0 = south (+Z)
        double y = pose.y() + lift;
        double cx = pose.x() + fx * area.offset(), cz = pose.z() + fz * area.offset();
        int argb = color.get();
        float w = width.getFloat();
        boolean walls = throughWalls.get();

        if (area.drawRange()) {
            Shape s = shape.get();
            if (s == Shape.LINE || aimLine.get()) {
                fadingLine(sink, pose.x(), y, pose.z(), cx + fx * area.range(), cz + fz * area.range(), argb, w, walls);
            }
            if (s == Shape.CIRCLE) circle(sink, cx, y, cz, area.range(), CIRCLE_SEGMENTS, argb, w, walls);
            if (s == Shape.ARC) arc(sink, cx, y, cz, area.range(), yaw, argb, w, walls);
        }
        if (area.offset() != 0) {
            if (area.marker() == TelosItemRules.Marker.SQUARE) square(sink, cx, y, cz, 0.5, yaw, argb, w, walls);
            else circle(sink, cx, y, cz, 0.15, 8, argb, w, walls);
        }
    }

    private static void circle(LineSink sink, double cx, double y, double cz, double r, int segments, int argb,
                               float w, boolean walls) {
        double px = cx + r, pz = cz;
        for (int i = 1; i <= segments; i++) {
            double a = Math.PI * 2 * i / segments;
            double x = cx + Math.cos(a) * r, z = cz + Math.sin(a) * r;
            sink.line(px, y, pz, x, y, z, argb, w, walls);
            px = x;
            pz = z;
        }
    }

    /** Short ribbon in front of the player: stacked arcs whose ends fade out. */
    private static void arc(LineSink sink, double cx, double y, double cz, double r, double yaw, int argb, float w,
                            boolean walls) {
        for (int layer = 0; layer < ARC_LAYERS; layer++) {
            double ly = y + ARC_HEIGHT * layer / (ARC_LAYERS - 1);
            double px = 0, pz = 0;
            for (int i = 0; i <= ARC_SEGMENTS; i++) {
                double t = i / (double) ARC_SEGMENTS;
                double a = yaw - ARC_HALF_ANGLE + 2 * ARC_HALF_ANGLE * t;
                double x = cx - Math.sin(a) * r, z = cz + Math.cos(a) * r;
                if (i > 0) {
                    double edge = Math.abs(t - 0.5) * 2; // 0 = middle, 1 = end
                    double fade = edge > 1 - ARC_FADE ? (1 - edge) / ARC_FADE : 1;
                    sink.line(px, ly, pz, x, ly, z, ColorUtil.fade(argb, fade), w, walls);
                }
                px = x;
                pz = z;
            }
        }
    }

    /** Line that fades in from the player to its end. */
    private static void fadingLine(LineSink sink, double x1, double y, double z1, double x2, double z2, int argb,
                                   float w, boolean walls) {
        for (int i = 0; i < LINE_STEPS; i++) {
            double t0 = i / (double) LINE_STEPS, t1 = (i + 1) / (double) LINE_STEPS;
            sink.line(x1 + (x2 - x1) * t0, y, z1 + (z2 - z1) * t0, x1 + (x2 - x1) * t1, y, z1 + (z2 - z1) * t1,
                    ColorUtil.fade(argb, t1), w, walls);
        }
    }

    /** Square of half-size {@code h} turned with the player. */
    private static void square(LineSink sink, double cx, double y, double cz, double h, double yaw, int argb, float w,
                               boolean walls) {
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        double[][] c = {{-h, -h}, {h, -h}, {h, h}, {-h, h}};
        for (int i = 0; i < 4; i++) {
            double[] a = c[i], b = c[(i + 1) % 4];
            sink.line(cx + a[0] * cos - a[1] * sin, y, cz + a[0] * sin + a[1] * cos,
                    cx + b[0] * cos - b[1] * sin, y, cz + b[0] * sin + b[1] * cos, argb, w, walls);
        }
    }
}
