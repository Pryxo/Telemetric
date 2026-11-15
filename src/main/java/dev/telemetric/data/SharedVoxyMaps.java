package dev.telemetric.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.telemetric.Telemetric;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Prebuilt Voxy LOD stores from the shared data repo (Pryxo/Telemetric-Data, {@code LODs/index.json}).
 * <p>
 * Index format:
 * <pre>{"version": 1, "maps": {"telos:goblin_lair": {"file": "LODs/goblin_lair.zip", "version": 1,
 *  "sha256": "&lt;hex of the zip&gt;", "size": 12345}}}</pre>
 * The zip holds the content of one Voxy world folder ({@code storage/…}). It is unpacked into that folder, which
 * then gets a {@value #MARKER} file with the map version. A folder is only replaced when it is missing or carries an
 * older marker — a player's own folder (no marker) is never touched. Pure Java, no Minecraft imports.
 */
public final class SharedVoxyMaps {

    static final String INDEX = "LODs/index.json";
    static final String MARKER = "telemetric-map.json";
    private static final long MAX_ZIP = 300L << 20, MAX_UNPACKED = 2L << 30;

    private static CompletableFuture<JsonObject> index;

    private SharedVoxyMaps() {}

    /**
     * Makes {@code folder} hold the newest shared map for {@code key}, off the main thread. Completes with true when
     * a map was installed, false when nothing was needed/available; never completes exceptionally.
     */
    public static CompletableFuture<Boolean> prepare(String key, Path folder) {
        return index().thenApplyAsync(idx -> {
            try {
                return install(idx, key, folder);
            } catch (IOException | RuntimeException e) {
                Telemetric.LOGGER.warn("Shared Voxy map {} not installed: {}", key, e.toString());
                return false;
            }
        });
    }

    /** The index, fetched once per game session (null JSON on failure — retried next session). */
    private static synchronized CompletableFuture<JsonObject> index() {
        if (index == null) {
            index = DataRepo.HTTP.sendAsync(get(INDEX), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(r -> r.statusCode() == 200 ? JsonParser.parseString(r.body()).getAsJsonObject() : null)
                    .exceptionally(e -> {
                        Telemetric.LOGGER.warn("Shared Voxy map index not loaded: {}", e.toString());
                        return null;
                    });
        }
        return index;
    }

    private static boolean install(JsonObject idx, String key, Path folder) throws IOException {
        if (idx == null || !idx.has("maps") || !idx.getAsJsonObject("maps").has(key)) return false;
        JsonObject entry = idx.getAsJsonObject("maps").getAsJsonObject(key);
        String file = entry.get("file").getAsString();
        int version = entry.get("version").getAsInt();
        String sha = entry.has("sha256") ? entry.get("sha256").getAsString().toLowerCase() : null;
        if (file.contains("..") || file.startsWith("/")) throw new IOException("bad file path " + file);

        if (Files.isDirectory(folder)) {
            Integer local = localVersion(folder);
            if (local == null) return false;        // the player's own LODs: keep them
            if (local >= version) return false;     // up to date
        }

        Path parent = folder.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path zip = Files.createTempFile(parent, "telemetric-map", ".zip");
        Path staging = parent.resolve(folder.getFileName() + ".telemetric-new");
        try {
            download(file, zip, sha);
            deleteTree(staging);
            unzip(zip, staging);
            Files.writeString(staging.resolve(MARKER), "{\"key\":\"" + key + "\",\"version\":" + version + "}");
            deleteTree(folder);
            Files.move(staging, folder, StandardCopyOption.ATOMIC_MOVE);
            Telemetric.LOGGER.info("Installed shared Voxy map {} v{}", key, version);
            return true;
        } finally {
            Files.deleteIfExists(zip);
            deleteTree(staging);
        }
    }

    private static Integer localVersion(Path folder) {
        try {
            Path marker = folder.resolve(MARKER);
            if (!Files.isRegularFile(marker)) return null;
            return JsonParser.parseString(Files.readString(marker)).getAsJsonObject().get("version").getAsInt();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void download(String file, Path to, String sha) throws IOException {
        HttpResponse<InputStream> r;
        try {
            r = DataRepo.HTTP.send(get(file), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (r.statusCode() != 200) {
            r.body().close();
            throw new IOException("HTTP " + r.statusCode() + " for " + file);
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        try (InputStream in = new DigestInputStream(r.body(), digest); OutputStream out = Files.newOutputStream(to)) {
            byte[] buf = new byte[1 << 16];
            long total = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > MAX_ZIP) throw new IOException("map too large");
                out.write(buf, 0, n);
            }
        }
        String got = HexFormat.of().formatHex(digest.digest());
        if (sha != null && !sha.equals(got)) throw new IOException("sha256 mismatch for " + file);
    }

    private static void unzip(Path zip, Path into) throws IOException {
        Path root = into.toAbsolutePath().normalize();
        Files.createDirectories(root);
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            byte[] buf = new byte[1 << 16];
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                Path target = root.resolve(e.getName()).normalize();
                if (!target.startsWith(root)) throw new IOException("bad zip entry " + e.getName());
                String name = target.getFileName().toString();
                if (name.equals("LOCK") || name.startsWith("LOG")) continue; // RocksDB runtime files
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target)) {
                    for (int n; (n = in.read(buf)) > 0; ) {
                        total += n;
                        if (total > MAX_UNPACKED) throw new IOException("map unpacks too large");
                        out.write(buf, 0, n);
                    }
                }
            }
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path q : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(q);
        }
    }

    private static HttpRequest get(String path) {
        return DataRepo.get(path, Duration.ofSeconds(60));
    }
}
