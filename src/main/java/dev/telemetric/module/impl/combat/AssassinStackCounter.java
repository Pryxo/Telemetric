package dev.telemetric.module.impl.combat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Counts Assassin stacks from the server's particles (pure logic, no Minecraft). Telos draws each stack as an
 * electric spark + a dust particle at the same spot on a ring around the player; stacks sit 22.5° apart and fill
 * up to {@value #LAYERS} rings stacked {@value #LAYER_SPACING} blocks apart from the feet up.
 * <p>
 * Feed every spark / dust with {@link #accept} (position relative to the player's feet); a stack counts once both
 * particles were seen at one spot within {@value #LIFETIME_MS} ms. No particle for {@value #TIMEOUT_MS} ms = 0.
 * Algorithm from Melinoe (House-Hades); the ring-wrap spacing check and layer pick are Telemetric's.
 */
final class AssassinStackCounter {

    static final int LAYERS = 4;
    static final double LAYER_SPACING = 0.6;
    private static final double RADIUS = 1.5;
    private static final double ANGLE_TOLERANCE = 12, DISTANCE_TOLERANCE = 0.5;
    private static final double STACK_SPACING = 22.5, SPACING_TOLERANCE = 8;
    static final long LIFETIME_MS = 1000, TIMEOUT_MS = 2000;

    private record Mark(double angle, double distance, long time) {
        boolean near(Mark o) {
            double d = Math.abs(angle - o.angle);
            if (d > 180) d = 360 - d;
            return d < ANGLE_TOLERANCE && Math.abs(distance - o.distance) < DISTANCE_TOLERANCE;
        }
    }

    @SuppressWarnings("unchecked")
    private final List<Mark>[] sparks = new List[LAYERS], dust = new List[LAYERS];
    private final int[] layerStacks = new int[LAYERS];
    private long lastAdded;
    private int total;

    AssassinStackCounter() {
        for (int i = 0; i < LAYERS; i++) {
            sparks[i] = new ArrayList<>();
            dust[i] = new ArrayList<>();
        }
    }

    /** One particle at (dx, dy, dz) blocks from the player's feet. */
    void accept(boolean spark, double dx, double dy, double dz, long now) {
        if (now - lastAdded > TIMEOUT_MS) reset();
        int layer = (int) Math.clamp(Math.round(dy / LAYER_SPACING), 0, LAYERS - 1); // nearest ring by height
        double ly = dy - layer * LAYER_SPACING;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (Math.sqrt(horizontal * horizontal + ly * ly) > RADIUS) return;
        double angle = Math.toDegrees(Math.atan2(dz, dx));
        Mark mark = new Mark(angle < 0 ? angle + 360 : angle, horizontal, now);
        List<Mark> into = spark ? sparks[layer] : dust[layer];
        for (Mark m : into) if (m.near(mark)) return; // already seen this spot
        into.add(mark);
        lastAdded = now;
        recount(now);
    }

    /** Current stack count (0 after {@value #TIMEOUT_MS} ms without particles). */
    int stacks(long now) {
        if (total != 0 && now - lastAdded > TIMEOUT_MS) reset();
        return total;
    }

    void reset() {
        for (int i = 0; i < LAYERS; i++) {
            sparks[i].clear();
            dust[i].clear();
        }
        Arrays.fill(layerStacks, 0);
        total = 0;
    }

    private void recount(long now) {
        int sum = 0;
        for (int i = 0; i < LAYERS; i++) {
            sparks[i].removeIf(m -> now - m.time() > LIFETIME_MS);
            dust[i].removeIf(m -> now - m.time() > LIFETIME_MS);
            List<Double> angles = new ArrayList<>();
            for (Mark s : sparks[i]) {
                for (Mark d : dust[i]) {
                    if (s.near(d)) {
                        angles.add(s.angle());
                        break;
                    }
                }
            }
            // a half-seen ring (odd spacing) keeps the last good count instead of flickering
            if (angles.size() < 2 || evenlySpaced(angles)) layerStacks[i] = angles.size();
            sum += layerStacks[i];
        }
        total = sum;
    }

    /** True if the angles sit {@value #STACK_SPACING}° apart around the ring, allowing one open gap (the unfilled part). */
    private static boolean evenlySpaced(List<Double> angles) {
        angles.sort(null);
        int odd = 0;
        for (int i = 0; i < angles.size(); i++) {
            double next = i + 1 < angles.size() ? angles.get(i + 1) : angles.getFirst() + 360;
            if (Math.abs(next - angles.get(i) - STACK_SPACING) > SPACING_TOLERANCE) odd++;
        }
        return odd <= 1;
    }
}
