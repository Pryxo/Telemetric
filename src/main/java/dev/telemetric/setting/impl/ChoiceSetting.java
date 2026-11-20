package dev.telemetric.setting.impl;

import dev.telemetric.setting.Setting;

import java.util.List;

/**
 * A setting whose value is one of a list of options (shown as a dropdown). It's the shared base for
 * {@link ModeSetting} (strings) and {@link EnumSetting} (enums), so a single UI component handles both.
 */
public abstract class ChoiceSetting<T> extends Setting<T> {

    protected ChoiceSetting(String name, String description, T defaultValue) {
        super(name, description, defaultValue);
    }

    /** The current options. They can change at runtime (e.g. the list of profiles). */
    public abstract List<T> getOptions();

    /** Label shown in the UI for an option. */
    public String display(T option) {
        return String.valueOf(option);
    }

    public int indexOf(T option) {
        return getOptions().indexOf(option);
    }

    public void cycle(int direction) {
        List<T> options = getOptions();
        if (options.isEmpty()) return;
        int index = Math.max(0, indexOf(get()));
        set(options.get(Math.floorMod(index + direction, options.size())));
    }

    @Override
    protected T sanitize(T candidate) {
        List<T> options = getOptions();
        if (candidate != null && options.contains(candidate)) return candidate;
        if (options.contains(getDefault())) return getDefault();
        return options.isEmpty() ? getDefault() : options.getFirst();
    }
}
