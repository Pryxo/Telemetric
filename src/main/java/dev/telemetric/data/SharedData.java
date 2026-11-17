package dev.telemetric.data;

import dev.telemetric.config.ModPaths;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.telemetric.Telemetric;
import dev.telemetric.config.AtomicFiles;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * JSON tables from the shared data repo ({@code data/} submodule → {@link DataRepo}), e.g.
 * {@code tables/Boss.json}.
 * <p>
 * A table is declared once with {@link #file(String, Function, Object)} and read with {@link SharedFile#get()}.
 * Where its content comes from (first one found wins at start, instantly, no network):
 * <ol>
 *   <li>{@code config/telemetric/data/overrides/<path>} — a <b>local override</b> the player edits by hand; never downloaded
 *       over, never replaced;</li>
 *   <li>{@code config/telemetric/data/cache/<path>} — the copy downloaded last time;</li>
 *   <li>the copy bundled in the jar ({@code assets/telemetric/data/<path>}, packed from {@code data/} at build).</li>
 * </ol>
 * Without an override the repo is then asked <b>once per game start</b> (in the background) whether the file
 * changed: the cached copy's ETag goes along ({@code If-None-Match}), so an unchanged file answers
 * {@code 304 Not Modified} with no body. A changed file replaces the cache and the live value, and
 * {@link SharedFile#onChange} listeners run (on the download thread). Parsers must build immutable values. A file
 * that fails to parse is ignored (the previous value stays). Pure Java, no Minecraft imports.
 */
public final class SharedData {

    
    static final Path OVERRIDES = ModPaths.DATA_OVERRIDES;
    static final Path CACHE = ModPaths.DATA_CACHE;
    private static final String BUNDLED = "/assets/telemetric/data/";
    private static final String ETAG_SUFFIX = ".etag";

    private static final Map<String, SharedFile<?>> FILES = new ConcurrentHashMap<>();

    static {
        deleteOldCache(CACHE.resolve("Extractor"));
    }

    private SharedData() {}

    /** Drops downloads of the old {@code Extractor/} layout (tables live in {@code tables/} since 0.34.0). */
    private static void deleteOldCache(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException | RuntimeException e) {
            Telemetric.LOGGER.warn("Old shared data cache {} not removed: {}", dir, e.toString());
        }
    }

    /**
     * Declares a table (once per path; later calls return the same handle) and starts loading it.
     *
     * @param path     repo path, e.g. {@code tables/Boss.json}
     * @param parser   JSON root → immutable value; may throw on bad input
     * @param fallback value while nothing could be loaded
     */
    @SuppressWarnings("unchecked")
    public static <T> SharedFile<T> file(String path, Function<JsonObject, T> parser, T fallback) {
        SharedFile<T> fresh = new SharedFile<>(path, parser, fallback);
        SharedFile<T> known = (SharedFile<T>) FILES.putIfAbsent(path, fresh);
        if (known != null) return known;
        fresh.start(); // outside the map update: listeners may declare other tables
        return fresh;
    }

    /** What {@link #refreshAll()} did: tables downloaded fresh, re-read from a local override, or kept as they were. */
    public record RefreshResult(int downloaded, int overridden, int failed) {
        public int total() { return downloaded + overridden + failed; }
    }

    /**
     * Re-downloads every declared table now (no ETag, so the full current file comes back), saves it to the cache and
     * swaps the value in (listeners run). A table with a local override is re-read from that file instead. A table
     * whose download fails keeps its current value. Completes on the download thread.
     */
    public static CompletableFuture<RefreshResult> refreshAll() {
        List<CompletableFuture<Outcome>> all = new ArrayList<>();
        for (SharedFile<?> f : FILES.values()) all.add(f.refresh());
        return CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).thenApply(v -> {
            int down = 0, over = 0, fail = 0;
            for (CompletableFuture<Outcome> o : all) {
                switch (o.join()) {
                    case DOWNLOADED -> down++;
                    case OVERRIDE -> over++;
                    case FAILED -> fail++;
                }
            }
            return new RefreshResult(down, over, fail);
        });
    }

    private enum Outcome { DOWNLOADED, OVERRIDE, FAILED }

    /** One shared JSON table. */
    public static final class SharedFile<T> {

        private final String path;
        private final Function<JsonObject, T> parser;
        private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();
        private volatile T value;
        private volatile String source = "none";

        private SharedFile(String path, Function<JsonObject, T> parser, T fallback) {
            this.path = path;
            this.parser = parser;
            this.value = fallback;
        }

        /** Current value (never null if the fallback was not null). */
        public T get() {
            return value;
        }

        /** Runs whenever a newer value is loaded (also right away if one is loaded already). */
        public SharedFile<T> onChange(Consumer<T> listener) {
            listeners.add(listener);
            if (!"none".equals(source)) listener.accept(value);
            return this;
        }

        private void start() {
            Path override = OVERRIDES.resolve(path);
            if (Files.isRegularFile(override) && load(read(override), "override")) return; // hand-edited: no download
            Path cache = CACHE.resolve(path);
            boolean fromCache = Files.isRegularFile(cache) && load(read(cache), "cache");
            if (!fromCache) load(bundled(), "bundled");
            String etag = fromCache ? read(etagFile()) : null;
            checkForUpdate(etag == null ? null : etag.trim());
        }

        /** Forced update ({@code /telemetric update-cache}): override re-read, else a full download. */
        private CompletableFuture<Outcome> refresh() {
            Path override = OVERRIDES.resolve(path);
            if (Files.isRegularFile(override)) {
                return CompletableFuture.completedFuture(load(read(override), "override") ? Outcome.OVERRIDE : Outcome.FAILED);
            }
            return checkForUpdate(null).thenApply(ok -> ok ? Outcome.DOWNLOADED : Outcome.FAILED);
        }

        /**
         * One request per start: 304 = the cached copy is current, 200 = new content.
         * Completes with true when a new copy was downloaded and loaded.
         */
        private CompletableFuture<Boolean> checkForUpdate(String etag) {
            HttpRequest.Builder req = DataRepo.request(path, Duration.ofSeconds(20));
            if (etag != null && !etag.isEmpty()) req.header("If-None-Match", etag);
            return DataRepo.HTTP.sendAsync(req.GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(r -> {
                        if (r.statusCode() == 304) {
                            Telemetric.debug("Shared data {} is up to date", path);
                        } else if (r.statusCode() != 200) {
                            Telemetric.LOGGER.warn("Shared data {} not downloaded: HTTP {}", path, r.statusCode());
                        } else if (load(r.body(), "download")) {
                            writeCache(r.body(), r.headers().firstValue("ETag").orElse(null));
                            return true;
                        }
                        return false;
                    })
                    .exceptionally(e -> {
                        Telemetric.LOGGER.warn("Shared data {} not downloaded: {}", path, e.toString());
                        return false;
                    });
        }

        private boolean load(String json, String from) {
            if (json == null) return false;
            T parsed;
            try {
                parsed = parser.apply(JsonParser.parseString(json).getAsJsonObject());
            } catch (RuntimeException e) {
                Telemetric.LOGGER.warn("Shared data {} ({}) ignored: {}", path, from, e.toString());
                return false;
            }
            if (parsed == null) return false;
            value = parsed;
            source = from;
            for (Consumer<T> l : listeners) {
                try {
                    l.accept(parsed);
                } catch (RuntimeException e) {
                    Telemetric.LOGGER.error("Shared data {} listener failed", path, e);
                }
            }
            return true;
        }

        private String bundled() {
            try (InputStream in = SharedData.class.getResourceAsStream(BUNDLED + path)) {
                return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return null;
            }
        }

        private Path etagFile() {
            Path file = CACHE.resolve(path);
            return file.resolveSibling(file.getFileName() + ETAG_SUFFIX);
        }

        private void writeCache(String json, String etag) {
            try {
                AtomicFiles.writeString(CACHE.resolve(path), json);
                if (etag != null) AtomicFiles.writeString(etagFile(), etag);
                else Files.deleteIfExists(etagFile());
            } catch (IOException e) {
                Telemetric.LOGGER.warn("Shared data {} not cached: {}", path, e.toString());
            }
        }

        private static String read(Path file) {
            try {
                return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
            } catch (IOException e) {
                return null;
            }
        }
    }
}
