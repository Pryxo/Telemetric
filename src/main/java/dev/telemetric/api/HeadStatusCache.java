package dev.telemetric.api;

import dev.telemetric.Telemetric;
import dev.telemetric.game.PlayerAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Head statuses of the players loaded around you (yourself included), for features that show them.
 * Every {@link #SCAN_MS} it checks the loaded players; anyone not cached, or cached longer than {@link #TTL_MS}, is
 * asked for in {@code /status-bulk} requests of up to {@link HeadStatusApi#BULK_MAX}. A player without a status is
 * cached as "none" too, so they are not asked again before the TTL ends. Read with {@link #get}.
 */
public final class HeadStatusCache {

    /** How long one player's status is kept before it is asked for again. */
    public static final long TTL_MS = 15 * 60_000L;
    /** How often the loaded players are checked for missing / stale statuses. */
    public static final long SCAN_MS = 10_000L;

    private record Entry(HeadStatusApi.HeadStatus status, long at) {}

    /** Normalised UUID (lower case, no dashes) → cached status. */
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    /** Normalised UUIDs in a running request (never asked twice at once). */
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
    private static long lastScan;

    private HeadStatusCache() {}

    /**
     * Cached status of a player: empty if they have none or it is not loaded yet / older than {@link #TTL_MS}
     * ({@link #known} tells those apart).
     */
    public static Optional<HeadStatusApi.HeadStatus> get(String uuid) {
        Entry e = fresh(uuid);
        return e == null ? Optional.empty() : Optional.ofNullable(e.status());
    }

    /** True if a fresh answer is cached for this player (a status or "none"). */
    public static boolean known(String uuid) {
        return fresh(uuid) != null;
    }

    /** Drops one player's entry so the next scan asks again (e.g. after setting / clearing your own status). */
    public static void invalidate(String uuid) {
        if (uuid != null) CACHE.remove(key(uuid));
    }

    private static Entry fresh(String uuid) {
        if (uuid == null) return null;
        Entry e = CACHE.get(key(uuid));
        return e != null && System.currentTimeMillis() - e.at() < TTL_MS ? e : null;
    }

    /** Client tick (on Telos): every {@link #SCAN_MS}, asks for the loaded players without a fresh entry. */
    public static void tick() {
        long now = System.currentTimeMillis();
        if (now - lastScan < SCAN_MS) return;
        lastScan = now;
        CACHE.values().removeIf(e -> now - e.at() >= TTL_MS);

        List<String> due = new ArrayList<>();
        for (String id : PlayerAdapter.loadedPlayerIds()) {
            String k = key(id);
            if (!CACHE.containsKey(k) && !PENDING.contains(k)) due.add(id);
        }
        for (int i = 0; i < due.size(); i += HeadStatusApi.BULK_MAX) {
            request(List.copyOf(due.subList(i, Math.min(due.size(), i + HeadStatusApi.BULK_MAX))));
        }
    }

    private static void request(List<String> ids) {
        ids.forEach(id -> PENDING.add(key(id)));
        HeadStatusApi.statusBulk(ids).whenComplete((res, e) -> {
            try {
                if (e != null) {
                    // nothing cached: the next scan (10 s) tries again
                    Telemetric.LOGGER.warn("[Telemetric] HeadStatus bulk status failed: {}", HeadStatusApi.rootMessage(e));
                    return;
                }
                long at = System.currentTimeMillis();
                Map<String, HeadStatusApi.HeadStatus> byKey = new ConcurrentHashMap<>();
                res.forEach((uuid, st) -> byKey.put(key(uuid), st));
                for (String id : ids) {
                    String k = key(id);
                    CACHE.put(k, new Entry(byKey.get(k), at)); // null status = player has none
                }
            } finally {
                ids.forEach(id -> PENDING.remove(key(id)));
            }
        });
    }

    private static String key(String uuid) {
        return uuid.replace("-", "").toLowerCase(Locale.ROOT);
    }
}
