package dev.telemetric.tracking;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.telemetric.Telemetric;
import dev.telemetric.config.AtomicFiles;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Persistent counters of one Minecraft account: pity per drop ({@code pity}) and lifetime stats ({@code stats}),
 * in {@code config/telemetric/tracking/<account uuid>.json}. Changes are saved 2 s after the last one (background
 * thread) and on game exit. Pure Java (no Minecraft imports). Thread-safe.
 */
public final class TrackingStore {

    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve(Telemetric.MOD_ID).resolve("tracking");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int VERSION = 1;
    private static final long SAVE_DELAY_MS = 2000;
    private static final ScheduledExecutorService SAVER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "telemetric-tracking-save");
        t.setDaemon(true);
        return t;
    });

    private static final Map<String, TrackingStore> STORES = new HashMap<>();
    /** Store of the account seen last (kept while no world is loaded) and its id. */
    private static volatile TrackingStore current;
    private static volatile String currentId;

    private final Path file;
    private final Map<String, Integer> pity = new HashMap<>();
    private final Map<String, Integer> stats = new HashMap<>();
    private ScheduledFuture<?> pendingSave;

    private TrackingStore(Path file) {
        this.file = file;
        load();
    }

    /** The store of this account (loaded on first use); null account = the last one, or a shared "default". */
    public static TrackingStore of(String accountId) {
        TrackingStore c = current;
        if (c != null && (accountId == null || accountId.equals(currentId))) return c; // the per-frame path
        synchronized (TrackingStore.class) {
            String key = accountId == null || accountId.isBlank() ? "default" : accountId.replaceAll("[^A-Za-z0-9-]", "");
            TrackingStore store = STORES.computeIfAbsent(key, k -> new TrackingStore(DIR.resolve(k + ".json")));
            current = store;
            currentId = accountId;
            return store;
        }
    }

    /** Writes every store with unsaved changes now (game exit). */
    public static synchronized void saveAll() {
        for (TrackingStore s : STORES.values()) s.saveNow();
    }

    // ------------------------------------------------------------------ pity

    public synchronized int pity(String dropKey) {
        return pity.getOrDefault(dropKey, 0);
    }

    /** +1 kill without the drop; returns the new count. */
    public synchronized int addPity(String dropKey) {
        int n = pity.merge(dropKey, 1, Integer::sum);
        changed();
        return n;
    }

    /** The drop came: back to 0. Returns the count it dropped at. */
    public synchronized int resetPity(String dropKey) {
        Integer old = pity.remove(dropKey);
        changed();
        return old == null ? 0 : old;
    }

    // ------------------------------------------------------------------ stats

    public synchronized int stat(String key) {
        return stats.getOrDefault(key, 0);
    }

    public synchronized void addStat(String key, int amount) {
        stats.merge(key, amount, Integer::sum);
        changed();
    }

    // ------------------------------------------------------------------ file

    private void changed() {
        if (pendingSave != null) pendingSave.cancel(false);
        pendingSave = SAVER.schedule(this::saveNow, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void saveNow() {
        if (pendingSave == null) return;
        pendingSave = null;
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.add("pity", GSON.toJsonTree(new TreeMap<>(pity)));
        root.add("stats", GSON.toJsonTree(new TreeMap<>(stats)));
        try {
            AtomicFiles.writeString(file, GSON.toJson(root));
        } catch (IOException e) {
            Telemetric.LOGGER.warn("Tracking data not saved to {}: {}", file, e.toString());
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            readInts(root.getAsJsonObject("pity"), pity);
            readInts(root.getAsJsonObject("stats"), stats);
        } catch (IOException | RuntimeException e) {
            Telemetric.LOGGER.warn("Tracking data {} unreadable, backed up, starting empty: {}", file, e.toString());
            pity.clear();
            stats.clear();
            AtomicFiles.backupCorrupt(file); // the next save would overwrite it
        }
    }

    private static void readInts(JsonObject o, Map<String, Integer> into) {
        if (o == null) return;
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (e.getValue().isJsonPrimitive()) into.put(e.getKey(), e.getValue().getAsInt());
        }
    }
}
