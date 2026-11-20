package dev.telemetric.setting.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import dev.telemetric.setting.Setting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Any number of string options can be ticked at once (shown as a dropdown with check boxes). Typically used for
 * blacklists and whitelists.
 * <p>
 * The value is an immutable set in insertion order, and matching ignores case ({@link #contains}). Selected entries
 * that aren't (or are no longer) in {@link #getOptions()} are kept, so a saved blacklist survives option lists that
 * change at runtime. The UI lists them after the options.
 */
public class MultiChoiceSetting extends Setting<Set<String>> {

    private final Supplier<List<String>> options;

    public MultiChoiceSetting(String name, String description, Supplier<List<String>> options) {
        this(name, description, options, Set.of());
    }

    /** @param defaults options ticked by default (and after a reset) */
    public MultiChoiceSetting(String name, String description, Supplier<List<String>> options, Set<String> defaults) {
        super(name, description, Collections.unmodifiableSet(new LinkedHashSet<>(defaults)));
        this.options = Objects.requireNonNull(options);
    }

    /** The options to show: the supplied ones, plus any selected entry that isn't among them. */
    public List<String> getOptions() {
        List<String> supplied = options.get();
        List<String> list = new ArrayList<>(supplied == null ? List.of() : supplied);
        for (String selected : get()) {
            if (indexOfIgnoreCase(list, selected) < 0) list.add(selected);
        }
        return list;
    }

    public boolean contains(String option) {
        if (option == null) return false;
        for (String s : get()) if (s.equalsIgnoreCase(option)) return true;
        return false;
    }

    /** Ticks or unticks one option. */
    public void toggle(String option) {
        if (option == null || option.isBlank()) return;
        Set<String> next = new LinkedHashSet<>(get());
        if (!next.removeIf(s -> s.equalsIgnoreCase(option))) next.add(option);
        set(next);
    }

    public void clear() {
        set(Set.of());
    }

    /** Header label: "None", the single entry, or "N selected". */
    public String summary() {
        Set<String> v = get();
        if (v.isEmpty()) return "None";
        if (v.size() == 1) return v.iterator().next();
        return v.size() + " selected";
    }

    @Override
    protected Set<String> sanitize(Set<String> candidate) {
        if (candidate == null) return Set.of();
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        Set<String> seen = new java.util.HashSet<>();
        for (String s : candidate) {
            if (s == null || s.isBlank()) continue;
            if (seen.add(s.toLowerCase(Locale.ROOT))) clean.add(s.strip());
        }
        return Collections.unmodifiableSet(clean);
    }

    @Override
    public JsonElement toJson() {
        JsonArray array = new JsonArray();
        for (String s : get()) array.add(s);
        return array;
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element == null || !element.isJsonArray()) return;
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonElement e : element.getAsJsonArray()) {
            if (e != null && e.isJsonPrimitive()) values.add(e.getAsString());
        }
        set(values);
    }

    private static int indexOfIgnoreCase(List<String> list, String value) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).equalsIgnoreCase(value)) return i;
        return -1;
    }
}
