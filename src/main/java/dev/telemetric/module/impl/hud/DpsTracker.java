package dev.telemetric.module.impl.hud;

/**
 * Pure fight bookkeeping (no Minecraft). Times are milliseconds from any monotonic clock.
 * A fight starts with the first hit and ends {@code timeoutMs} after the last one; the next hit after
 * that starts a fresh fight.
 */
final class DpsTracker {

    private long start = -1, lastHitTime = -1;
    private double total, max, last;
    private int hits;

    void add(long now, double amount, long timeoutMs) {
        if (!active(now, timeoutMs)) {
            start = now;
            total = max = 0;
            hits = 0;
        }
        lastHitTime = now;
        total += amount;
        max = Math.max(max, amount);
        last = amount;
        hits++;
    }

    void reset() {
        start = lastHitTime = -1;
        total = max = last = 0;
        hits = 0;
    }

    /** True from the first hit until {@code timeoutMs} after the last one. */
    boolean active(long now, long timeoutMs) {
        return lastHitTime >= 0 && now - lastHitTime <= timeoutMs;
    }

    /** Fight length so far (first hit → now), in ms. */
    long duration(long now) {
        return start < 0 ? 0 : Math.max(0, now - start);
    }

    /** Total damage ÷ fight length (at least 1 s). */
    double dps(long now) {
        return start < 0 ? 0 : total / Math.max(1.0, duration(now) / 1000.0);
    }

    double total() { return total; }
    double max() { return max; }
    double last() { return last; }
    int hits() { return hits; }
}
