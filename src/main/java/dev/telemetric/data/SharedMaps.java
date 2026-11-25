package dev.telemetric.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.telemetric.Telemetric;
import dev.telemetric.config.ModPaths;
import dev.telemetric.map.WorldTiles;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pre-baked world map tiles from the shared data repo ({@code Maps/index.json}, see docs/map.md → Shared tiles).
 * The index is fetched once per game start (a cached copy in {@code config/telemetric/maps/} is used when offline);
 * tiles are only downloaded when a map asks for them ({@link #tileFile}), checked against their sha256 and cached in
 * {@code config/telemetric/maps/<area>/}. Pure Java, no Minecraft imports.
 */
public final class SharedMaps {

    static final String INDEX = "Maps/index.json";
    public static final Path DIR = ModPaths.MAPS;
    private static final int MAX_DOWNLOADS = 3;
    private static final long MAX_TILE = 8L << 20;

    /**
     * One mapped area.
     *
     * @param key       folder name ({@code hub})
     * @param dimension dimension id it belongs to; a realm instance {@code telos:realm/3} matches {@code telos:realm}
     * @param bounds    zoom-0 tile bounds {@code [tu0, tv0, tu1, tv1]} (inclusive)
     * @param files     tile path ({@link WorldTiles#file}) → sha256
     */
    public record Area(String key, String dimension, int version, int maxZoom, int[] bounds, Map<String, String> files) {
        public boolean has(int zoom, int tu, int tv) {
            return files.containsKey(WorldTiles.file(zoom, tu, tv));
        }
    }

    private static volatile Map<String, Area> areas;
    private static boolean started;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(MAX_DOWNLOADS, r -> {
        Thread t = new Thread(r, "Telemetric-MapDownload");
        t.setDaemon(true);
        return t;
    });
    /** Tiles known good on disk this session (path inside DIR). */
    private static final Set<String> verified = ConcurrentHashMap.newKeySet();
    /** Tiles being downloaded or checked, and tiles that failed (not tried again this session). */
    private static final Set<String> busy = ConcurrentHashMap.newKeySet(), failed = ConcurrentHashMap.newKeySet();
    private record Job(String key, Runnable run) {}

    private static final ArrayDeque<Job> queue = new ArrayDeque<>();
    private static int running;

    private SharedMaps() {}

    /** Starts the index check (once per start). Safe to call often. */
    public static synchronized void start() {
        if (started) return;
        started = true;
        DataRepo.HTTP.sendAsync(DataRepo.get(INDEX, Duration.ofSeconds(30)), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenAccept(r -> {
                    if (r.statusCode() == 200) {
                        areas = parse(r.body());
                        try {
                            Files.createDirectories(DIR);
                            Files.writeString(DIR.resolve("index.json"), r.body());
                        } catch (IOException e) {
                            Telemetric.LOGGER.warn("Shared map index not cached: {}", e.toString());
                        }
                    } else {
                        loadCached("HTTP " + r.statusCode());
                    }
                })
                .exceptionally(e -> {
                    loadCached(e.toString());
                    return null;
                });
    }

    private static void loadCached(String why) {
        Path cached = DIR.resolve("index.json");
        try {
            areas = Files.isRegularFile(cached) ? parse(Files.readString(cached)) : Map.of();
        } catch (IOException | RuntimeException e) {
            areas = Map.of();
        }
        Telemetric.LOGGER.info("Shared map index not loaded ({}), {} cached area(s)", why, areas.size());
    }

    static Map<String, Area> parse(String body) {
        Map<String, Area> out = new HashMap<>();
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has("areas")) return out;
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("areas").entrySet()) {
            try {
                String key = e.getKey();
                if (!key.matches("[a-z0-9_.-]+")) continue;
                JsonObject o = e.getValue().getAsJsonObject();
                JsonArray b = o.getAsJsonArray("bounds");
                int[] bounds = {b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt(), b.get(3).getAsInt()};
                Map<String, String> files = new HashMap<>();
                for (Map.Entry<String, JsonElement> f : o.getAsJsonObject("files").entrySet()) {
                    if (f.getKey().contains("..") || f.getKey().startsWith("/")) continue;
                    files.put(f.getKey(), f.getValue().getAsString().toLowerCase());
                }
                out.put(key, new Area(key, o.get("dimension").getAsString(), o.get("version").getAsInt(),
                        Math.min(WorldTiles.MAX_ZOOM, o.has("zooms") ? o.get("zooms").getAsInt() - 1 : 0), bounds,
                        Collections.unmodifiableMap(files)));
            } catch (RuntimeException ex) {
                Telemetric.LOGGER.warn("Shared map area {} skipped: {}", e.getKey(), ex.toString());
            }
        }
        return out;
    }

    /** True once the index (or its cached copy) is known; until then {@link #areaFor} returns null. */
    public static boolean ready() {
        return areas != null;
    }

    /** The area for a dimension id, or null (none shipped, or the index isn't known yet). */
    public static Area areaFor(String dimension) {
        Map<String, Area> a = areas;
        if (a == null || dimension == null) return null;
        Area best = null;
        for (Area area : a.values()) {
            if (dimension.equals(area.dimension())) return area;
            if (dimension.startsWith(area.dimension() + "/") && (best == null
                    || area.dimension().length() > best.dimension().length())) best = area;
        }
        return best;
    }

    /**
     * The cached PNG of a tile if it's on disk and checked, else null: a missing or outdated one gets downloaded in
     * the background (ask again next frame). Tiles the area doesn't have are always null.
     */
    public static Path tileFile(Area area, int zoom, int tu, int tv) {
        String rel = WorldTiles.file(zoom, tu, tv);
        String sha = area.files().get(rel);
        if (sha == null) return null;
        String key = area.key() + "/" + rel;
        Path file = DIR.resolve(area.key()).resolve(rel);
        if (verified.contains(key + "@" + sha)) return file;
        if (failed.contains(key + "@" + sha) || !busy.add(key)) return null;
        enqueue(key, () -> {
            try {
                if (!sha.equals(sha256(file))) download(area.key() + "/" + rel, file, sha);
                verified.add(key + "@" + sha);
            } catch (IOException | RuntimeException e) {
                failed.add(key + "@" + sha);
                Telemetric.LOGGER.warn("Shared map tile {} not loaded: {}", key, e.toString());
            } finally {
                busy.remove(key);
            }
        });
        return null;
    }

    private static synchronized void enqueue(String key, Runnable job) {
        queue.add(new Job(key, job));
        pump();
    }

    private static synchronized void pump() {
        while (running < MAX_DOWNLOADS && !queue.isEmpty()) {
            Job job = queue.poll();
            running++;
            POOL.execute(() -> {
                try {
                    job.run().run();
                } finally {
                    finished();
                }
            });
        }
    }

    private static synchronized void finished() {
        running--;
        pump();
    }

    /** Drops queued (not yet started) downloads, e.g. when the map moved far away or closed. */
    public static synchronized void cancelQueued() {
        for (Job j : queue) busy.remove(j.key());
        queue.clear();
    }

    private static void download(String repoPath, Path to, String sha) throws IOException {
        HttpResponse<InputStream> r;
        try {
            r = DataRepo.HTTP.send(DataRepo.get("Maps/" + repoPath, Duration.ofSeconds(60)), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (r.statusCode() != 200) {
            r.body().close();
            throw new IOException("HTTP " + r.statusCode());
        }
        byte[] data;
        try (InputStream in = r.body()) {
            data = in.readNBytes((int) MAX_TILE + 1);
        }
        if (data.length > MAX_TILE) throw new IOException("tile too large");
        String got = HexFormat.of().formatHex(digest().digest(data));
        if (!sha.equals(got)) throw new IOException("sha256 mismatch");
        Files.createDirectories(to.getParent());
        Path tmp = to.resolveSibling(to.getFileName() + ".part");
        Files.write(tmp, data);
        Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String sha256(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            return HexFormat.of().formatHex(digest().digest(Files.readAllBytes(file)));
        } catch (IOException e) {
            return null;
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Area keys known right now (for the debug line / docs). */
    public static Set<String> areaKeys() {
        Map<String, Area> a = areas;
        return a == null ? Set.of() : new HashSet<>(a.keySet());
    }
}
