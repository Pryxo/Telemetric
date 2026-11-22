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
 * Head statuses of the players loaded around you (you included), for the features that show them.
 * Every {@link #SCAN_MS} it checks the loaded players. Anyone not cached, or cached longer than {@link #TTL_MS}, gets
 * requested through {@code /status-bulk} (collected for {@link #BATCH_MS} first) in batches of up to
 * {@link HeadStatusApi#BULK_MAX}. Players without a
 * status are cached as "none" too, so we don't ask about them again before the TTL runs out. Read with {@link #get}.
 */
public final class HeadStatusCache {

    /** How long one player's status is kept before it is asked for again. */
    public static final long TTL_MS = 5 * 60_000L;
    /** How often the loaded players are checked for missing or stale statuses (cheap: only reads the cache). */
    public static final long SCAN_MS = 250L;
    /** How long newly due players are collected before one bulk request goes out (a crowd loading together). */
    public static final long BATCH_MS = 400L;
    /** Pause after a failed request before asking again. */
    public static final long RETRY_MS = 5_000L;

    private record Entry(HeadStatusApi.HeadStatus status, long at) {}

    /** Normalised UUID (lower case, no dashes) to its cached status. */
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    /** Normalised UUIDs that are part of a running request, so none is asked for twice at once. */
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
    private static long lastScan;
    /** When the first currently due player was seen (0 = none due). */
    private static long dueSince;
    /** No request before this time (set after a failure). */
    private static volatile long retryAt;

    private HeadStatusCache() {}

    /**
     * Cached status of a player. Empty if they have none, or if it isn't loaded yet or is older than {@link #TTL_MS};
     * {@link #known} tells those cases apart.
     */
    public static Optional<HeadStatusApi.HeadStatus> get(String uuid) {
        Entry e = fresh(uuid);
        return e == null ? Optional.empty() : Optional.ofNullable(e.status());
    }

    /** True if a fresh answer is cached for this player (a status or "none"). */
    public static boolean known(String uuid) {
        return fresh(uuid) != null;
    }

    /** Drops one player's entry so the next scan asks again, e.g. after you set or clear your own status. */
    public static void invalidate(String uuid) {
        if (uuid != null) CACHE.remove(key(uuid));
    }

    private static Entry fresh(String uuid) {
        if (uuid == null) return null;
        Entry e = CACHE.get(key(uuid));
        return e != null && System.currentTimeMillis() - e.at() < TTL_MS ? e : null;
    }

    /** Client tick (on Telos). Every {@link #SCAN_MS} it requests the loaded players that have no fresh entry. */
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
        if (due.isEmpty()) {
            dueSince = 0;
            return;
        }
        if (dueSince == 0) dueSince = now;
        if (now - dueSince < BATCH_MS || now < retryAt) return;
        dueSince = 0;
        for (int i = 0; i < due.size(); i += HeadStatusApi.BULK_MAX) {
            request(List.copyOf(due.subList(i, Math.min(due.size(), i + HeadStatusApi.BULK_MAX))));
        }
    }

    private static void request(List<String> ids) {
        ids.forEach(id -> PENDING.add(key(id)));
        HeadStatusApi.statusBulk(ids).whenComplete((res, e) -> {
            try {
                if (e != null) {
                    // nothing gets cached, so a scan after RETRY_MS tries again
                    retryAt = System.currentTimeMillis() + RETRY_MS;
                    Telemetric.LOGGER.warn("[Telemetric] HeadStatus bulk status failed: {}", HeadStatusApi.rootMessage(e));
                    return;
                }
                long at = System.currentTimeMillis();
                Map<String, HeadStatusApi.HeadStatus> byKey = new ConcurrentHashMap<>();
                res.forEach((uuid, st) -> byKey.put(key(uuid), st));
                for (String id : ids) {
                    String k = key(id);
                    CACHE.put(k, new Entry(byKey.get(k), at)); // a null status means the player has none
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
