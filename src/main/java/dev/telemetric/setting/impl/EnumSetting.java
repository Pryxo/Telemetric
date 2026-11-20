package dev.telemetric.setting.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.List;

/**
 * An enum choice. If the enum implements {@link Nameable}, its display name is used in the UI. It's saved by
 * {@link Enum#name()}, so renaming display names never breaks configs.
 */
public class EnumSetting<E extends Enum<E>> extends ChoiceSetting<E> {

    /** Optional interface for enums that want a nicer UI label. */
    public interface Nameable {
        String displayName();
    }

    private final List<E> options;
    private final Class<E> type;

    public EnumSetting(String name, String description, E defaultValue) {
        super(name, description, defaultValue);
        this.type = defaultValue.getDeclaringClass();
        this.options = List.of(type.getEnumConstants());
    }

    @Override
    public List<E> getOptions() {
        return options;
    }

    @Override
    public String display(E option) {
        return option instanceof Nameable n ? n.displayName() : option.name();
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(get().name());
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) return;
        String raw = element.getAsString();
        for (E constant : options) {
            if (constant.name().equalsIgnoreCase(raw)) {
                set(constant);
                return;
            }
        }
    }

    public Class<E> getType() {
        return type;
    }
}
