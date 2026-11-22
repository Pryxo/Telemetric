package dev.telemetric.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.telemetric.Telemetric;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Players online with the mod, from the API server's {@code players} push ({@link TelemetricServer}). The server only
 * sends UUIDs, so names come from the local tab list ({@link #learn}) or Mojang's public profile lookup, cached for the
 * session. {@link #isUser(String)} is what the chat / tab-list badge checks. No Minecraft imports.
 */
public final class ModUsers {

    private static final String LOOKUP = "https://api.minecraftservices.com/minecraft/profile/lookup/";
    /** Mojang allows ~600 lookups per 10 min; one every 250 ms stays far below that. */
    private static final long LOOKUP_GAP_MS = 250;
    private static final long RETRY_MS = 60_000;

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Telemetric Mod Users");
        t.setDaemon(true);
        return t;
    });

    /** uuid (dashed, lowercase) → name; never forgotten during the session. */
    private static final Map<String, String> NAMES = new ConcurrentHashMap<>();
    /** UUIDs whose lookup failed (unknown / rate limit) → time of the failure. */
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static final Set<String> QUEUED = ConcurrentHashMap.newKeySet();

    private static volatile Set<String> online = Set.of();
    private static volatile Set<String> onlineNames = Set.of();

    private ModUsers() {}

    /** True if {@code name} (any case) is online with the mod right now. */
    public static boolean isUser(String name) {
        return name != null && !name.isEmpty() && onlineNames.contains(name.toLowerCase(Locale.ROOT));
    }

    /** True if anyone's name is known (lets callers skip work while the list is empty). */
    public static boolean any() {
        return !onlineNames.isEmpty();
    }

    /** Replaces the online list (full list from the server, never a diff). */
    static void update(Collection<String> uuids) {
        Set<String> next = new HashSet<>();
        for (String u : uuids) next.add(normalize(u));
        online = Set.copyOf(next);
        rebuildNames();
        long now = System.currentTimeMillis();
        for (String u : next) {
            if (NAMES.containsKey(u)) continue;
            Long failed = FAILED.get(u);
            if (failed != null && now - failed < RETRY_MS) continue;
            if (QUEUED.add(u)) EXEC.schedule(() -> lookup(u), LOOKUP_GAP_MS * QUEUED.size(), TimeUnit.MILLISECONDS);
        }
    }

    /** Link to the API server is gone: nobody is known to be online. */
    static void clear() {
        online = Set.of();
        onlineNames = Set.of();
    }

    /** A name seen locally for a UUID (tab list entries carry the real profile), so no lookup is needed. */
    public static void learn(String uuid, String name) {
        if (uuid == null || name == null || name.isEmpty()) return;
        String u = normalize(uuid);
        if (name.equals(NAMES.put(u, name))) return;
        if (online.contains(u)) rebuildNames();
    }

    private static void lookup(String uuid) {
        try {
            if (NAMES.containsKey(uuid) || !online.contains(uuid)) return;
            HttpRequest req = HttpRequest.newBuilder(URI.create(LOOKUP + uuid.replace("-", "")))
                    .timeout(Duration.ofSeconds(10)).header("User-Agent", "Telemetric").GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                FAILED.put(uuid, System.currentTimeMillis());
                return;
            }
            JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
            String name = o.has("name") ? o.get("name").getAsString() : null;
            if (name == null || name.isEmpty()) {
                FAILED.put(uuid, System.currentTimeMillis());
                return;
            }
            NAMES.put(uuid, name);
            FAILED.remove(uuid);
            rebuildNames();
        } catch (Exception e) {
            FAILED.put(uuid, System.currentTimeMillis());
            Telemetric.LOGGER.debug("[Telemetric] Name lookup for {} failed: {}", uuid, e.toString());
        } finally {
            QUEUED.remove(uuid);
        }
    }

    private static synchronized void rebuildNames() {
        List<String> names = new ArrayList<>();
        for (String u : online) {
            String n = NAMES.get(u);
            if (n != null) names.add(n.toLowerCase(Locale.ROOT));
        }
        onlineNames = Set.copyOf(names);
    }

    private static String normalize(String uuid) {
        String u = uuid.trim().toLowerCase(Locale.ROOT);
        if (u.length() == 32) {
            u = u.substring(0, 8) + "-" + u.substring(8, 12) + "-" + u.substring(12, 16) + "-"
                    + u.substring(16, 20) + "-" + u.substring(20);
        }
        return u;
    }
}
