package dev.telemetric.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small JSON readers shared by the table parsers (no registry, no Fabric — the parsers stay testable). */
final class JsonFields {

    private JsonFields() {}

    /** String field, "" when missing / null / not a primitive. */
    static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }

    /** Int field, {@code fallback} when missing / not a number. */
    static int integer(JsonObject o, String key, int fallback) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return fallback;
        return e.getAsInt();
    }

    /** Boolean field, {@code fallback} when missing / not a boolean. */
    static boolean bool(JsonObject o, String key, boolean fallback) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) return fallback;
        return e.getAsBoolean();
    }

    /** Object field, or null. */
    static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** The string entries of an array field (non-strings skipped), empty when missing. */
    static List<String> strings(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonElement x : e.getAsJsonArray()) {
            if (x.isJsonPrimitive() && x.getAsJsonPrimitive().isString()) out.add(x.getAsString());
        }
        return List.copyOf(out);
    }

    /** The object entries of an array field (others skipped), empty when missing. */
    static List<JsonObject> objects(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonArray()) return List.of();
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement x : e.getAsJsonArray()) if (x.isJsonObject()) out.add(x.getAsJsonObject());
        return out;
    }

    /** String values of an object field, in file order ({@code "key": "value"}), empty when missing. */
    static Map<String, String> stringMap(JsonObject o, String key) {
        JsonObject m = obj(o, key);
        if (m == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : m.entrySet()) {
            if (e.getValue().isJsonPrimitive()) out.put(e.getKey(), e.getValue().getAsString());
        }
        return Collections.unmodifiableMap(out);
    }

    /** {@code #RRGGBB} → 0xRRGGBB; a colour name or bad value → white. */
    static int rgb(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            return (int) (Long.parseLong(h, 16) & 0xFFFFFF);
        } catch (NumberFormatException e) {
            return 0xFFFFFF;
        }
    }
}
