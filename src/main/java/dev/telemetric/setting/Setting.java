package dev.telemetric.setting;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Base class for every configurable value.
 * <p>
 * A setting owns its value, default, change listeners, an optional visibility rule and its own
 * JSON (de)serialisation. Subclasses only need to implement {@link #toJson()} / {@link #fromJson(JsonElement)}
 * and optionally {@link #sanitize(Object)} (clamping / validation).
 * <p>
 * Fluent helpers ({@link #visibleWhen}, {@link #onChange}, {@link #notPersistent}) return the concrete
 * type thanks to the generic self-cast, so this works:
 * <pre>{@code
 * private final NumberSetting speed = add(new NumberSetting("Speed", "…", 1, 0, 5, 0.1).visibleWhen(enabled::get));
 * }</pre>
 *
 * @param <T> value type
 */
public abstract class Setting<T> {

    private final String name;
    private final String description;
    private final T defaultValue;
    protected T value;

    private BooleanSupplier visibility = () -> true;
    private boolean persistent = true;
    private boolean movable;
    private Setting<?> attachedTo;
    private Setting<?> group;
    private final List<Consumer<T>> listeners = new ArrayList<>();

    protected Setting(String name, String description, T defaultValue) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description == null ? "" : description;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    // ------------------------------------------------------------------ value

    public T get() {
        return value;
    }

    /** Sets a new value (sanitised). Listeners fire only when the value actually changes. */
    public void set(T newValue) {
        T clean = sanitize(newValue);
        if (Objects.equals(clean, value)) return;
        value = clean;
        for (Consumer<T> listener : listeners) listener.accept(clean);
    }

    public void reset() {
        set(defaultValue);
    }

    public T getDefault() {
        return defaultValue;
    }

    /** Hook for validation / clamping. Must never return {@code null} for non-null defaults. */
    protected T sanitize(T candidate) {
        return candidate == null ? defaultValue : candidate;
    }

    // ------------------------------------------------------------------ meta

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isVisible() {
        return visibility.getAsBoolean() && (group == null || group.showsChildren());
    }

    /** Only group settings (see {@code GroupToggleSetting}) show children; they override this. */
    protected boolean showsChildren() {
        return false;
    }

    /** The group toggle this setting is a child of, or {@code null}. */
    public Setting<?> getGroup() {
        return group;
    }

    /** Nesting depth under group toggles (0 = top level). */
    public int groupDepth() {
        int d = 0;
        for (Setting<?> g = group; g != null; g = g.group) d++;
        return d;
    }

    /** Used by {@code GroupToggleSetting#child}: this setting is shown only while the group is open. */
    public void setGroup(Setting<?> group) {
        this.group = group;
    }

    /** Whether this setting is written to / read from profiles. Actions and session-only values are not. */
    public boolean isPersistent() {
        return persistent;
    }

    // ------------------------------------------------------------------ fluent configuration

    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S visibleWhen(BooleanSupplier rule) {
        this.visibility = Objects.requireNonNull(rule);
        return (S) this;
    }

    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S onChange(Consumer<T> listener) {
        listeners.add(Objects.requireNonNull(listener));
        return (S) this;
    }

    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S notPersistent() {
        this.persistent = false;
        return (S) this;
    }

    /**
     * Lets the user drag this setting to a new position in the ClickGUI (grip on the row's left edge).
     * Movable settings swap places only with each other; fixed settings keep their slot. The order is
     * saved per module (see {@code Module#getSettingOrder}).
     */
    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S movable() {
        this.movable = true;
        return (S) this;
    }

    /** Makes this setting follow {@code parent} when the parent is dragged (e.g. a "Real %" toggle under its stat). */
    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S attachedTo(Setting<?> parent) {
        this.attachedTo = Objects.requireNonNull(parent);
        return (S) this;
    }

    public boolean isMovable() {
        return movable && attachedTo == null;
    }

    /** The setting this one follows when reordered, or {@code null}. */
    public Setting<?> getAttachedTo() {
        return attachedTo;
    }

    // ------------------------------------------------------------------ serialisation

    public abstract JsonElement toJson();

    /** Must tolerate malformed input: on any problem keep the current value. */
    public abstract void fromJson(JsonElement element);

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + name + "=" + value + "]";
    }
}
