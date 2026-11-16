package dev.telemetric.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Small JSON readers shared by the table parsers (no registry, no Fabric — the parsers stay testable). */
final class JsonFields {

    private JsonFields() {}

    /** String field, "" when missing / null / not a primitive. */
    static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
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
