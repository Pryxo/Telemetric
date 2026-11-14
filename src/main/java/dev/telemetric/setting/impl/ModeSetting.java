package dev.telemetric.setting.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** String choice. Options may be static or supplied dynamically. */
public class ModeSetting extends ChoiceSetting<String> {

    private final Supplier<List<String>> options;

    public ModeSetting(String name, String description, String defaultValue, String... options) {
        this(name, description, defaultValue, () -> List.of(options));
    }

    public ModeSetting(String name, String description, String defaultValue, Supplier<List<String>> options) {
        super(name, description, defaultValue);
        this.options = Objects.requireNonNull(options);
    }

    @Override
    public List<String> getOptions() {
        List<String> list = options.get();
        return list == null ? List.of() : list;
    }

    public boolean is(String mode) {
        return get().equalsIgnoreCase(mode);
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(get());
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element != null && element.isJsonPrimitive()) set(element.getAsString());
    }
}
