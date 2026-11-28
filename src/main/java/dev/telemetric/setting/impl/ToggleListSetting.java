package dev.telemetric.setting.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.telemetric.setting.Setting;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A grouped list of on/off rows that come from data (e.g. every waypoint of the Telos Map), shown as collapsible
 * groups with a switch per row and per group, filtered by {@link #query()} (usually a {@link TextSetting} search
 * field above it).
 * <p>
 * The value holds only the rows the user switched ({@code key → on}); every other row uses its own default, so rows
 * added to the data later get their default. Saved as {@code {"key": true, …}}.
 */
public class ToggleListSetting extends Setting<Map<String, Boolean>> {

    /**
     * One row.
     *
     * @param key       stable id (the saved key)
     * @param group     group header it sits under (rows of a group must be next to each other)
     * @param meta      short muted text on the right ("Shadowlands · 5"), may be empty
     * @param icon      item model, or null
     * @param tooltip   hover text, may be empty
     */
    public record Row(String key, String group, String label, String meta, String icon, String tooltip,
                      boolean defaultOn) {}

    private final Supplier<List<Row>> rows;
    private final Supplier<String> query;

    public ToggleListSetting(String name, String description, Supplier<List<Row>> rows, Supplier<String> query) {
        super(name, description, Map.of());
        this.rows = Objects.requireNonNull(rows);
        this.query = Objects.requireNonNull(query);
    }

    public List<Row> rows() {
        List<Row> r = rows.get();
        return r == null ? List.of() : r;
    }

    /** The search text, never null. */
    public String query() {
        String q = query.get();
        return q == null ? "" : q;
    }

    public boolean isOn(Row row) {
        return get().getOrDefault(row.key(), row.defaultOn());
    }

    /** On/off of {@code key}, or {@code fallback} if the user never switched it. */
    public boolean isOn(String key, boolean fallback) {
        return get().getOrDefault(key, fallback);
    }

    public void set(Row row, boolean on) {
        setAll(List.of(row), on);
    }

    /** Switches every row of {@code rows} on or off (one change). */
    public void setAll(Collection<Row> rows, boolean on) {
        Map<String, Boolean> next = new LinkedHashMap<>(get());
        for (Row r : rows) next.put(r.key(), on);
        set(next);
    }

    @Override
    protected Map<String, Boolean> sanitize(Map<String, Boolean> candidate) {
        if (candidate == null) return Map.of();
        Map<String, Boolean> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> e : candidate.entrySet()) {
            if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null) clean.put(e.getKey(), e.getValue());
        }
        return Collections.unmodifiableMap(clean);
    }

    @Override
    public JsonElement toJson() {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Boolean> e : get().entrySet()) o.addProperty(e.getKey(), e.getValue());
        return o;
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) return;
        Map<String, Boolean> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
            JsonElement v = e.getValue();
            if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) values.put(e.getKey(), v.getAsBoolean());
        }
        set(values);
    }
}
