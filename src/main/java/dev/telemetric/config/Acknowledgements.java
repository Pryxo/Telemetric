package dev.telemetric.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.telemetric.Telemetric;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Warnings the user has confirmed once and shouldn't see again (e.g. a module's enable warning). Global, not per
 * profile: {@code config/telemetric/settings/acknowledged.json}, a JSON array of keys.
 */
public final class Acknowledgements {

    private static final Path FILE = ModPaths.SETTINGS.resolve("acknowledged.json");
    private static Set<String> keys;

    private Acknowledgements() {}

    public static synchronized boolean has(String key) {
        return load().contains(key);
    }

    public static synchronized void add(String key) {
        if (load().add(key)) save();
    }

    /** Forgets every acknowledgement, so all warnings show again. Returns how many there were. */
    public static synchronized int clear() {
        int n = load().size();
        keys.clear();
        save();
        return n;
    }

    private static Set<String> load() {
        if (keys != null) return keys;
        keys = new LinkedHashSet<>();
        if (!Files.exists(FILE)) return keys;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8));
            if (root.isJsonArray()) {
                for (JsonElement e : root.getAsJsonArray()) {
                    if (e.isJsonPrimitive()) keys.add(e.getAsString());
                }
            }
        } catch (Exception e) {
            Telemetric.LOGGER.warn("Could not read {}", FILE, e);
            AtomicFiles.backupCorrupt(FILE);
        }
        return keys;
    }

    private static void save() {
        JsonArray arr = new JsonArray();
        keys.forEach(arr::add);
        try {
            AtomicFiles.writeString(FILE, arr.toString());
        } catch (Exception e) {
            Telemetric.LOGGER.warn("Could not write {}", FILE, e);
        }
    }
}
