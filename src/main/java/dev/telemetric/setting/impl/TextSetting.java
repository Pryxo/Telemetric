package dev.telemetric.setting.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.telemetric.setting.Setting;

import java.util.function.IntPredicate;

/** Free text input with an optional character filter and max length. */
public class TextSetting extends Setting<String> {

    private final int maxLength;
    private IntPredicate allowedChars = c -> c >= 32 && c != 127;

    public TextSetting(String name, String description, String defaultValue, int maxLength) {
        super(name, description, defaultValue);
        this.maxLength = Math.max(1, maxLength);
    }

    /** Restricts which code points can be typed (e.g. file-name safe characters). */
    public TextSetting filter(IntPredicate allowed) {
        this.allowedChars = allowed;
        return this;
    }

    public boolean accepts(int codepoint) {
        return allowedChars.test(codepoint);
    }

    public int getMaxLength() {
        return maxLength;
    }

    @Override
    protected String sanitize(String candidate) {
        if (candidate == null) return getDefault();
        StringBuilder out = new StringBuilder();
        candidate.codePoints().filter(allowedChars).limit(maxLength).forEach(out::appendCodePoint);
        return out.toString();
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
