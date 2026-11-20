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

    private Runnable pressAction;

    /**
     * Turns this into an <b>action key</b>: {@code action} runs on the key's rising edge while the owning module is
     * enabled and no screen is open ({@code ModuleKeyHandler} polls it, the same as module binds).
     */
    public KeybindSetting onPress(Runnable action) {
        this.pressAction = action;
        return this;
    }

    /** The action of an action key, or null for a plain key value (e.g. a module's toggle bind). */
    public Runnable getPressAction() {
        return pressAction;
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
