package dev.telemetric.setting.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.telemetric.setting.Setting;

import java.math.BigDecimal;
import java.util.Locale;

/** Bounded, stepped number shown as a slider. */
public class NumberSetting extends Setting<Double> {

    private final double min;
    private final double max;
    private final double step;
    private final int decimals;
    private String suffix = "";

    public NumberSetting(String name, String description, double defaultValue, double min, double max, double step) {
        super(name, description, defaultValue);
        if (max <= min) throw new IllegalArgumentException("max must be > min for " + name);
        this.min = min;
        this.max = max;
        this.step = step <= 0 ? 0 : step;
        this.decimals = this.step == 0 ? 2 : Math.max(0, BigDecimal.valueOf(this.step).stripTrailingZeros().scale());
        this.value = sanitize(defaultValue);
    }

    /** Unit appended in the UI, e.g. "%" or "ms". */
    public NumberSetting withSuffix(String suffix) {
        this.suffix = suffix == null ? "" : suffix;
        return this;
    }

    @Override
    protected Double sanitize(Double candidate) {
        if (candidate == null || candidate.isNaN() || candidate.isInfinite()) return getDefault();
        double v = Math.clamp(candidate, min, max);
        if (step > 0) {
            v = min + Math.round((v - min) / step) * step;
            v = Math.clamp(v, min, max);
            v = BigDecimal.valueOf(v).setScale(decimals, java.math.RoundingMode.HALF_UP).doubleValue();
        }
        return v;
    }

    /** Sets the value from a 0..1 slider position. */
    public void setNormalized(double t) {
        set(min + Math.clamp(t, 0, 1) * (max - min));
    }

    public double getNormalized() {
        return (get() - min) / (max - min);
    }

    public double getMin() { return min; }
    public double getMax() { return max; }
    public double getStep() { return step; }
    public float getFloat() { return get().floatValue(); }
    public int getInt() { return (int) Math.round(get()); }

    public String format() {
        return String.format(Locale.ROOT, "%." + decimals + "f", get()) + suffix;
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(get());
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            set(element.getAsDouble());
        }
    }
}
