package dev.telemetric.dungeon;

/** Plain 3D point (world coordinates). */
public record Point(double x, double y, double z) {

    public double distanceSq(double ox, double oy, double oz) {
        double dx = x - ox, dy = y - oy, dz = z - oz;
        return dx * dx + dy * dy + dz * dz;
    }

    public Point lerp(Point o, double t) {
        return new Point(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t);
    }
}
