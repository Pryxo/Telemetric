package dev.telemetric.data;

import dev.telemetric.Telemetric;
import dev.telemetric.config.ModPaths;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Telos Map images ({@code maps/*.png} in the data repo, listed in {@code tables/map.json}). They're too big for
 * the jar, so each one is downloaded the first time a map needs it and kept in {@code config/telemetric/data/cache/}.
 * A file in {@code config/telemetric/data/overrides/<same path>} wins without a hash check (for map makers).
 * Otherwise the cached file must match the table's {@code sha256}, or it gets downloaded again.
 * <p>
 * Everything here runs off the render thread; {@link #path} only reads the current state and starts the work.
 */
public final class MapImages {

    public enum Status { MISSING, LOADING, READY, FAILED }

    private static final long MAX_BYTES = 64L << 20;

    private record Entry(Status status, Path path) {}

    /** Keyed by file + hash, so a table update with a new hash fetches the new image. */
    private static final Map<String, Entry> STATE = new ConcurrentHashMap<>();

    private MapImages() {}

    private static String key(MapTable.Tile t) {
        return t.file() + "#" + t.sha256();
    }

    public static Status status(MapTable.Tile t) {
        Entry e = STATE.get(key(t));
        return e == null ? Status.MISSING : e.status;
    }

    /** The local PNG of a tile once it's there, else null (and the check / download starts in the background). */
    public static Path path(MapTable.Tile t) {
        Entry e = STATE.get(key(t));
        if (e == null) {
            fetch(t);
            return null;
        }
        return e.status == Status.READY ? e.path : null;
    }

    /** Tries failed downloads again (opening the map does this). */
    public static void retryFailed() {
        STATE.values().removeIf(e -> e.status == Status.FAILED);
    }

    private static void fetch(MapTable.Tile t) {
        String k = key(t);
        if (STATE.putIfAbsent(k, new Entry(Status.LOADING, null)) != null) return;
        CompletableFuture.runAsync(() -> {
            try {
                STATE.put(k, new Entry(Status.READY, resolve(t)));
            } catch (IOException | RuntimeException e) {
                Telemetric.LOGGER.warn("Map image {} not loaded: {}", t.file(), e.toString());
                STATE.put(k, new Entry(Status.FAILED, null));
            }
        });
    }

    private static Path resolve(MapTable.Tile t) throws IOException {
        Path override = ModPaths.DATA_OVERRIDES.resolve(t.file());
        if (Files.isRegularFile(override)) return override;
        Path cache = ModPaths.DATA_CACHE.resolve(t.file());
        if (Files.isRegularFile(cache) && matches(Files.readAllBytes(cache), t.sha256())) return cache;
        return download(t, cache);
    }

    private static Path download(MapTable.Tile t, Path target) throws IOException {
        HttpResponse<byte[]> r;
        try {
            r = DataRepo.HTTP.send(DataRepo.get(t.file(), Duration.ofSeconds(60)), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
        byte[] body = r.body();
        if (body.length > MAX_BYTES) throw new IOException("too big (" + body.length + " bytes)");
        if (!matches(body, t.sha256())) throw new IOException("sha256 mismatch");
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), "map", ".tmp");
        try {
            Files.write(tmp, body);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        Telemetric.LOGGER.info("Map image {} downloaded ({} KB)", t.file(), body.length / 1024);
        return target;
    }

    private static boolean matches(byte[] data, String sha256) {
        if (sha256 == null || sha256.isEmpty()) return true;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)).equals(sha256);
        } catch (NoSuchAlgorithmException e) {
            return true;
        }
    }
}
