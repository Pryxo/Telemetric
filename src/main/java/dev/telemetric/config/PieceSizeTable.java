package dev.telemetric.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.telemetric.Telemetric;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Piece size per dungeon, in {@code config/telemetric/dungeon-sizes.json} — a plain file meant to be shared
 * (copy it into another player's config folder). Each Telos dungeon always uses the same piece size, so once
 * it is known the room detector no longer has to guess it.
 * <pre>
 * { "format": 1,
 *   "dungeons": {
 *     "Treasure Cave": { "size": 26, "locked": true,  "runs": {} },
 *     "Corvus Crypt":  { "size": 24, "locked": false, "runs": { "24": 3, "25": 1 } } } }
 * </pre>
 * <ul>
 *   <li>{@code size} — used for the grid (0 / missing = still unknown, measured each run).</li>
 *   <li>{@code runs} — piece size the detector measured at the end of each run (votes).</li>
 *   <li>{@code locked} — never changed by votes (verified built-ins, or set by hand).</li>
 * </ul>
 * Learning: an unlocked size follows the most-voted size once it has {@link #LEARN_RUNS} votes and more than
 * the current one. Built-in verified sizes are merged in when missing. Names match ignoring case/punctuation.
 */
public final class PieceSizeTable {

    public static final int FORMAT_VERSION = 1;
    /** Votes a measured size needs before an unlocked entry adopts it. */
    static final int LEARN_RUNS = 2;

    /** Verified in game (see docs/dungeon.md); locked so a systematic mis-measure cannot override them. */
    private static final Map<String, Integer> BUILT_IN = new LinkedHashMap<>();

    static {
        BUILT_IN.put("Goblin Lair", 20);
        BUILT_IN.put("Desert Temple", 20);
        BUILT_IN.put("Treasure Cave", 26);
        BUILT_IN.put("Kobold's Den", 26);
    }

    private static final PieceSizeTable INSTANCE = new PieceSizeTable(
            FabricLoader.getInstance().getConfigDir().resolve(Telemetric.MOD_ID).resolve("dungeon-sizes.json"));

    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path file;
    /** Display name → entry, insertion ordered. */
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean loaded;

    private static final class Entry {
        int size;
        boolean locked;
        final TreeMap<Integer, Integer> runs = new TreeMap<>();
    }

    private PieceSizeTable(Path file) {
        this.file = file;
    }

    public static PieceSizeTable get() {
        return INSTANCE;
    }

    /** Piece size to use for this dungeon, 0 when unknown. */
    public synchronized int size(String dungeon) {
        ensureLoaded();
        Entry e = find(dungeon);
        return e == null ? 0 : e.size;
    }

    /** Records one run's measured piece size (0 = nothing measured, ignored) and saves. */
    public synchronized void recordRun(String dungeon, int measured) {
        if (dungeon == null || dungeon.isBlank() || measured <= 0) return;
        ensureLoaded();
        Entry e = find(dungeon);
        if (e == null) {
            e = new Entry();
            entries.put(dungeon, e);
        }
        e.runs.merge(measured, 1, Integer::sum);
        if (!e.locked) {
            int best = e.size, bestVotes = e.runs.getOrDefault(e.size, 0);
            for (var v : e.runs.entrySet()) {
                if (v.getValue() >= LEARN_RUNS && v.getValue() > bestVotes) {
                    best = v.getKey();
                    bestVotes = v.getValue();
                }
            }
            if (best != e.size) {
                Telemetric.LOGGER.info("Dungeon sizes: {} piece size {} -> {} ({} runs)", dungeon, e.size, best,
                        bestVotes);
                e.size = best;
            }
        }
        save();
    }

    private Entry find(String dungeon) {
        if (dungeon == null) return null;
        String key = normalize(dungeon);
        for (var e : entries.entrySet()) if (normalize(e.getKey()).equals(key)) return e.getValue();
        return null;
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // ------------------------------------------------------------------ file

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        load();
        boolean added = false;
        for (var b : BUILT_IN.entrySet()) {
            if (find(b.getKey()) != null) continue;
            Entry e = new Entry();
            e.size = b.getValue();
            e.locked = true;
            entries.put(b.getKey(), e);
            added = true;
        }
        if (added || !Files.isRegularFile(file)) save();
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || !root.isJsonObject() || !root.getAsJsonObject().has("dungeons")) return;
            for (var d : root.getAsJsonObject().getAsJsonObject("dungeons").entrySet()) {
                if (!d.getValue().isJsonObject()) continue;
                JsonObject o = d.getValue().getAsJsonObject();
                Entry e = new Entry();
                e.size = o.has("size") ? Math.max(0, o.get("size").getAsInt()) : 0;
                e.locked = o.has("locked") && o.get("locked").getAsBoolean();
                if (o.has("runs") && o.get("runs").isJsonObject()) {
                    for (var r : o.getAsJsonObject("runs").entrySet()) {
                        try {
                            e.runs.put(Integer.parseInt(r.getKey()), r.getValue().getAsInt());
                        } catch (RuntimeException ignored) {
                            // skip a hand-edited bad vote
                        }
                    }
                }
                entries.put(d.getKey(), e);
            }
        } catch (IOException | RuntimeException ex) {
            Telemetric.LOGGER.error("Could not read {}, backing it up", file, ex);
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis()),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private void save() {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT_VERSION);
        JsonObject dungeons = new JsonObject();
        for (var d : entries.entrySet()) {
            Entry e = d.getValue();
            JsonObject o = new JsonObject();
            o.addProperty("size", e.size);
            o.addProperty("locked", e.locked);
            JsonObject runs = new JsonObject();
            for (var r : e.runs.entrySet()) runs.addProperty(String.valueOf(r.getKey()), r.getValue());
            o.add("runs", runs);
            dungeons.add(d.getKey(), o);
        }
        root.add("dungeons", dungeons);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                gson.toJson(root, writer);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            Telemetric.LOGGER.error("Could not write {}", file, ex);
        }
    }
}
