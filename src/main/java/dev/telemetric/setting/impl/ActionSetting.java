package dev.telemetric.setting.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import dev.telemetric.setting.Setting;

import java.util.Objects;

/** A clickable button. Holds no value and is never persisted. */
public class ActionSetting extends Setting<Boolean> {

    private final Runnable action;

    public ActionSetting(String name, String description, Runnable action) {
        super(name, description, Boolean.FALSE);
        this.action = Objects.requireNonNull(action);
        notPersistent();
    }

    public void run() {
        action.run();
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
