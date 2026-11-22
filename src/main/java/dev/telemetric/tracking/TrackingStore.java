package dev.telemetric.tracking;

import dev.telemetric.config.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.telemetric.Telemetric;
import dev.telemetric.config.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Persistent counters for one Minecraft account, stored in {@code config/telemetric/saved/tracking/<account uuid>.json}.
 * That's the pity per drop for each pity scope ({@code pity}: {@code normal} / {@code seasonal} /
 * {@code hardcore:<id>}, see {@link PityScopes}), the known hardcore characters ({@code hardcore}), and the lifetime
 * stats ({@code stats}, account-wide). Pity calls use the active scope ({@link #setPityScope}). Version 1 files (one
 * flat pity map) load as the {@code normal} scope. Changes are saved 2 s after the last one (on a background thread)
 * and on game exit. Pure Java (no Minecraft imports) and thread-safe.
 */
public final class TrackingStore {

    private static final Path DIR = ModPaths.TRACKING;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int VERSION = 2;
    private static final long SAVE_DELAY_MS = 2000;
    private static final ScheduledExecutorService SAVER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "telemetric-tracking-save");
        t.setDaemon(true);
        return t;
    });

    private static final Map<String, TrackingStore> STORES = new HashMap<>();
    /** The store of the account seen last (kept while no world is loaded), and its id. */
    private static volatile TrackingStore current;
    private static volatile String currentId;

    private final Path file;
    /** Scope to drop key to the kills without it. */
    private final Map<String, Map<String, Integer>> pity = new HashMap<>();
    private final Map<String, PityScopes.HardcoreChar> hardcore = new LinkedHashMap<>();
    private String scope = PityScopes.NORMAL;
    private final Map<String, Integer> stats = new HashMap<>();
    private ScheduledFuture<?> pendingSave;

    private TrackingStore(Path file) {
        this.file = file;
        load();
    }

    /** The store for this account (loaded on first use). A null account means the last one, or a shared "default". */
    public static TrackingStore of(String accountId) {
        TrackingStore c = current;
        if (c != null && (accountId == null || accountId.equals(currentId))) return c; // this is the per-frame path
        synchronized (TrackingStore.class) {
            String key = accountId == null || accountId.isBlank() ? "default" : accountId.replaceAll("[^A-Za-z0-9-]", "");
            TrackingStore store = STORES.computeIfAbsent(key, k -> new TrackingStore(DIR.resolve(k + ".json")));
            current = store;
            currentId = accountId;
            return store;
        }
    }

    /** Writes every store that has unsaved changes right now (game exit). */
    public static synchronized void saveAll() {
        for (TrackingStore s : STORES.values()) s.saveNow();
    }

    // ------------------------------------------------------------------ pity

    /** The scope that the pity calls below use (from {@link PityScopes}). */
    public synchronized void setPityScope(String scope) {
        this.scope = scope == null || scope.isBlank() ? PityScopes.NORMAL : scope;
    }

    private Map<String, Integer> pool() {
        return pity.computeIfAbsent(scope, k -> new HashMap<>());
    }

    public synchronized int pity(String dropKey) {
        Map<String, Integer> p = pity.get(scope);
        return p == null ? 0 : p.getOrDefault(dropKey, 0);
    }

    /** Adds one kill without the drop and returns the new count. */
    public synchronized int addPity(String dropKey) {
        int n = pool().merge(dropKey, 1, Integer::sum);
        changed();
        return n;
    }

    /** Takes back one {@link #addPity} (a kill counted by mistake). Never goes below 0. */
    public synchronized void removePity(String dropKey) {
        Map<String, Integer> p = pity.get(scope);
        Integer n = p == null ? null : p.get(dropKey);
        if (n == null) return;
        if (n <= 1) p.remove(dropKey);
        else p.put(dropKey, n - 1);
        changed();
    }

    /** The drop came, so it goes back to 0. Returns the count it dropped at. */
    public synchronized int resetPity(String dropKey) {
        Map<String, Integer> p = pity.get(scope);
        Integer old = p == null ? null : p.remove(dropKey);
        changed();
        return old == null ? 0 : old;
    }

    /** Clears a whole scope (a new season, where every seasonal character starts at 0). Returns how many counters it had. */
    public synchronized int clearPityScope(String scope) {
        Map<String, Integer> old = pity.remove(scope);
        changed();
        return old == null ? 0 : old.size();
    }

    // ------------------------------------------------------------------ hardcore characters

    public synchronized PityScopes.HardcoreChar hardcoreChar(String id) {
        return hardcore.get(id);
    }

    public synchronized PityScopes.HardcoreChar hardcoreBySignature(String signature) {
        for (PityScopes.HardcoreChar c : hardcore.values()) if (c.signature().equals(signature)) return c;
        return null;
    }

    public synchronized void putHardcoreChar(PityScopes.HardcoreChar c) {
        hardcore.put(c.id(), c);
        changed();
    }

    // ------------------------------------------------------------------ stats

    public synchronized int stat(String key) {
        return stats.getOrDefault(key, 0);
    }

    public synchronized void addStat(String key, int amount) {
        stats.merge(key, amount, Integer::sum);
        changed();
    }

    /** Overwrites a stat (from the /wiki update, where the server's own all-time numbers replace the tracked ones). */
    public synchronized void setStat(String key, int value) {
        stats.put(key, value);
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
        JsonObject pools = new JsonObject();
        for (Map.Entry<String, Map<String, Integer>> e : new TreeMap<>(pity).entrySet()) {
            if (!e.getValue().isEmpty()) pools.add(e.getKey(), GSON.toJsonTree(new TreeMap<>(e.getValue())));
        }
        root.add("pity", pools);
        JsonArray chars = new JsonArray();
        for (PityScopes.HardcoreChar c : hardcore.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", c.id());
            o.addProperty("signature", c.signature());
            o.addProperty("class", c.className());
            o.addProperty("level", c.level());
            o.addProperty("lastSeen", c.lastSeen());
            chars.add(o);
        }
        root.add("hardcore", chars);
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
            int version = root.has("version") ? root.get("version").getAsInt() : 1;
            JsonObject p = root.getAsJsonObject("pity");
            if (version < 2) {
                readInts(p, pity.computeIfAbsent(PityScopes.NORMAL, k -> new HashMap<>()));
            } else if (p != null) {
                for (Map.Entry<String, JsonElement> e : p.entrySet()) {
                    if (e.getValue().isJsonObject()) {
                        readInts(e.getValue().getAsJsonObject(), pity.computeIfAbsent(e.getKey(), k -> new HashMap<>()));
                    }
                }
            }
            JsonArray chars = root.getAsJsonArray("hardcore");
            if (chars != null) {
                for (JsonElement el : chars) {
                    JsonObject o = el.getAsJsonObject();
                    PityScopes.HardcoreChar c = new PityScopes.HardcoreChar(o.get("id").getAsString(),
                            o.get("signature").getAsString(), o.has("class") ? o.get("class").getAsString() : "",
                            o.has("level") ? o.get("level").getAsInt() : -1,
                            o.has("lastSeen") ? o.get("lastSeen").getAsLong() : 0);
                    hardcore.put(c.id(), c);
                }
            }
            readInts(root.getAsJsonObject("stats"), stats);
        } catch (IOException | RuntimeException e) {
            Telemetric.LOGGER.warn("Tracking data {} unreadable, backed up, starting empty: {}", file, e.toString());
            pity.clear();
            hardcore.clear();
            stats.clear();
            AtomicFiles.backupCorrupt(file); // keep a copy, because the next save would overwrite it
        }
    }

    private static void readInts(JsonObject o, Map<String, Integer> into) {
        if (o == null) return;
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (e.getValue().isJsonPrimitive()) into.put(e.getKey(), e.getValue().getAsInt());
        }
    }
}
