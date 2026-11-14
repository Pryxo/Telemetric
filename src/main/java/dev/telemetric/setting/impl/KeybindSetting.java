package dev.telemetric.setting.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.telemetric.input.InputUtil;
import dev.telemetric.setting.Setting;

/** A keyboard key code (InputConstants key codes). {@link #NONE} means unbound. */
public class KeybindSetting extends Setting<Integer> {

    public static final int NONE = -1;

    public KeybindSetting(String name, String description, int defaultKey) {
        super(name, description, defaultKey);
    }

    public boolean isBound() {
        return get() != NONE;
    }

    public void unbind() {
        set(NONE);
    }

    public String keyName() {
        return isBound() ? InputUtil.keyName(get()) : "None";
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(get());
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            set(element.getAsInt());
        }
    }
}
