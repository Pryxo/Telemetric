package dev.telemetric.ui.map;

/**
 * World ↔ screen transform of a map: world (x, z) around {@code (centerX, centerZ)} is scaled by {@code scale}
 * (GUI units per block), rotated by {@code rotation} (radians, 0 = north up) and placed at {@code (screenX, screenY)}.
 * World +X is screen right and +Z screen down when not rotated.
 */
public final class MapView {

    public double centerX, centerZ;
    public double scale = 1;
    public double rotation;
    public float screenX, screenY;

    public MapView set(double centerX, double centerZ, double scale, double rotation, float screenX, float screenY) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.scale = scale;
        this.rotation = rotation;
        this.screenX = screenX;
        this.screenY = screenY;
        return this;
    }

    public float sx(double wx, double wz) {
        double dx = wx - centerX, dz = wz - centerZ;
        return (float) (screenX + (dx * Math.cos(rotation) - dz * Math.sin(rotation)) * scale);
    }

    public float sy(double wx, double wz) {
        double dx = wx - centerX, dz = wz - centerZ;
        return (float) (screenY + (dx * Math.sin(rotation) + dz * Math.cos(rotation)) * scale);
    }

    public double wx(double sx, double sy) {
        double dx = (sx - screenX) / scale, dy = (sy - screenY) / scale;
        return centerX + dx * Math.cos(rotation) + dy * Math.sin(rotation);
    }

    public double wz(double sx, double sy) {
        double dx = (sx - screenX) / scale, dy = (sy - screenY) / scale;
        return centerZ - dx * Math.sin(rotation) + dy * Math.cos(rotation);
    }

    /** Screen angle (radians, 0 = up, clockwise) of a world yaw (degrees, 0 = south / +Z, Minecraft style). */
    public double screenAngle(float yawDegrees) {
        return Math.toRadians(yawDegrees) + Math.PI + rotation;
    }
}
