package dev.telemetric.module.impl.hud;

/**
 * Pure fight bookkeeping (no Minecraft). Times are milliseconds from any monotonic clock.
 * A fight starts with the first hit and ends {@code timeoutMs} after the last one, and the next hit after that starts
 * a fresh fight. An ended fight keeps its numbers (frozen at its last hit) until the next one.
 */
final class DpsTracker {

    /** Hits of the current fight kept for the rolling DPS window, as (time, amount). */
    private final java.util.ArrayDeque<long[]> recent = new java.util.ArrayDeque<>();
    private static final long MAX_WINDOW_MS = 60_000;

    private long start = -1, lastHitTime = -1, maxTime = -1;
    private double total, max, last;
    private int hits;

    void add(long now, double amount, long timeoutMs) {
        if (!active(now, timeoutMs)) {
            start = now;
            total = max = 0;
            hits = 0;
            recent.clear();
        }
        lastHitTime = now;
        recent.addLast(new long[]{now, Double.doubleToRawLongBits(amount)});
        while (now - recent.peekFirst()[0] > MAX_WINDOW_MS) recent.pollFirst();
        total += amount;
        if (amount >= max) {
            max = amount;
            maxTime = now;
        }
        last = amount;
        hits++;
    }

    /** Drops the max hit once it's older than {@code afterMs}, so the next hit becomes the new max. */
    void expireMax(long now, long afterMs) {
        if (maxTime >= 0 && now - maxTime > afterMs) {
            max = 0;
            maxTime = -1;
        }
    }

    void reset() {
        start = lastHitTime = maxTime = -1;
        total = max = last = 0;
        hits = 0;
        recent.clear();
    }

    boolean hasFight() {
        return lastHitTime >= 0;
    }

    /** True from the first hit until {@code timeoutMs} after the last one. */
    boolean active(long now, long timeoutMs) {
        return lastHitTime >= 0 && now - lastHitTime <= timeoutMs;
    }

    long duration(long end) {
        return start < 0 ? 0 : Math.max(0, end - start);
    }

    /** Total damage divided by the fight length up to {@code end} (at least 1 s). */
    double dps(long end) {
        return start < 0 ? 0 : total / Math.max(1.0, duration(end) / 1000.0);
    }

    /**
     * Damage in the last {@code windowMs} before {@code end}, divided by the window (or by the fight length while the
     * fight is shorter, at least 1 s). A {@code windowMs <= 0} means the whole fight ({@link #dps(long)}).
     */
    double dps(long end, long windowMs) {
        if (windowMs <= 0) return dps(end);
        if (start < 0) return 0;
        double sum = 0;
        for (long[] h : recent) if (end - h[0] < windowMs) sum += Double.longBitsToDouble(h[1]);
        double secs = Math.max(1.0, Math.min(windowMs, duration(end)) / 1000.0);
        return sum / secs;
    }

    double total() { return total; }
    double max() { return max; }
    double last() { return last; }
    int hits() { return hits; }
}
