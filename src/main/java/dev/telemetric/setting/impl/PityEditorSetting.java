package dev.telemetric.setting.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import dev.telemetric.setting.Setting;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * The Pity Counter's hand editor: a list of the drops matching {@link #query()} (usually a {@link TextSetting} search
 * field above it) with each one's pity for the character you're on, editable in place. Stateless: the counters live
 * in {@code tracking/TrackingStore}, so nothing is saved in profiles.
 */
public class PityEditorSetting extends Setting<Boolean> {

    private final Supplier<String> query;

    public PityEditorSetting(String name, String description, Supplier<String> query) {
        super(name, description, Boolean.FALSE);
        this.query = Objects.requireNonNull(query);
        notPersistent();
    }

    /** The search text, never null. */
    public String query() {
        String q = query.get();
        return q == null ? "" : q;
    }

    @Override
    public JsonElement toJson() {
        return JsonNull.INSTANCE;
    }

    @Override
    public void fromJson(JsonElement element) {
        // stateless
    }
}
