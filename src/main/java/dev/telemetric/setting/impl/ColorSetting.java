package dev.telemetric.setting.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.telemetric.setting.Setting;

import java.util.Locale;

/** An ARGB colour, saved as {@code "#AARRGGBB"}. */
public class ColorSetting extends Setting<Integer> {

    private final boolean allowAlpha;

    public ColorSetting(String name, String description, int defaultArgb) {
        this(name, description, defaultArgb, true);
    }

    public ColorSetting(String name, String description, int defaultArgb, boolean allowAlpha) {
        super(name, description, defaultArgb);
        this.allowAlpha = allowAlpha;
    }

    public boolean allowsAlpha() {
        return allowAlpha;
    }

    @Override
    protected Integer sanitize(Integer candidate) {
        if (candidate == null) return getDefault();
        return allowAlpha ? candidate : (candidate | 0xFF000000);
    }

    public String toHex() {
        return String.format(Locale.ROOT, "#%08X", get());
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(toHex());
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) return;
        String raw = element.getAsString().trim();
        if (raw.startsWith("#")) raw = raw.substring(1);
        try {
            long parsed = Long.parseLong(raw, 16);
            if (raw.length() <= 6) parsed |= 0xFF000000L;
            set((int) parsed);
        } catch (NumberFormatException ignored) {
            // keep the current value
        }
    }
}
