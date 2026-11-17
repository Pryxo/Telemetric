package dev.telemetric.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.telemetric.Telemetric;
import dev.telemetric.config.AtomicFiles;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * JSON tables from the shared data repo ({@code data/} submodule → {@link DataRepo}), e.g.
 * {@code Extractor/Boss.json}.
 * <p>
 * A table is declared once with {@link #file(String, Function, Object)} and read with {@link SharedFile#get()}.
 * Where its content comes from (first one found wins at start, instantly, no network):
 * <ol>
 *   <li>{@code config/telemetric/data/<path>} — a <b>local override</b> the player edits by hand; never downloaded
 *       over, never replaced;</li>
 *   <li>{@code config/telemetric/data-cache/<path>} — the copy downloaded last time;</li>
 *   <li>the copy bundled in the jar ({@code assets/telemetric/data/<path>}, packed from {@code data/} at build).</li>
 * </ol>
 * Without an override the repo is then asked <b>once per game start</b> (in the background) whether the file
 * changed: the cached copy's ETag goes along ({@code If-None-Match}), so an unchanged file answers
 * {@code 304 Not Modified} with no body. A changed file replaces the cache and the live value, and
 * {@link SharedFile#onChange} listeners run (on the download thread). Parsers must build immutable values. A file
 * that fails to parse is ignored (the previous value stays). Pure Java, no Minecraft imports.
 */
public final class SharedData {

    private static final Path ROOT = FabricLoader.getInstance().getConfigDir().resolve(Telemetric.MOD_ID);
    static final Path OVERRIDES = ROOT.resolve("data");
    static final Path CACHE = ROOT.resolve("data-cache");
    private static final String BUNDLED = "/assets/telemetric/data/";
    private static final String ETAG_SUFFIX = ".etag";

    private static final Map<String, SharedFile<?>> FILES = new ConcurrentHashMap<>();

    private SharedData() {}

    /**
     * Declares a table (once per path; later calls return the same handle) and starts loading it.
     *
     * @param path     repo path, e.g. {@code Extractor/Boss.json}
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

        /** One request per start: 304 = the cached copy is current, 200 = new content. */
        private void checkForUpdate(String etag) {
            HttpRequest.Builder req = DataRepo.request(path, Duration.ofSeconds(20));
            if (etag != null && !etag.isEmpty()) req.header("If-None-Match", etag);
            DataRepo.HTTP.sendAsync(req.GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenAccept(r -> {
                        if (r.statusCode() == 304) {
                            Telemetric.debug("Shared data {} is up to date", path);
                        } else if (r.statusCode() != 200) {
                            Telemetric.LOGGER.warn("Shared data {} not downloaded: HTTP {}", path, r.statusCode());
                        } else if (load(r.body(), "download")) {
                            writeCache(r.body(), r.headers().firstValue("ETag").orElse(null));
                        }
                    })
                    .exceptionally(e -> {
                        Telemetric.LOGGER.warn("Shared data {} not downloaded: {}", path, e.toString());
                        return null;
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
