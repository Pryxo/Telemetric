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
 * A setting owns its value, its default, its change listeners, an optional visibility rule and its own JSON
 * (de)serialisation. Subclasses only have to implement {@link #toJson()} / {@link #fromJson(JsonElement)}, and
 * optionally {@link #sanitize(Object)} (clamping or validation).
 * <p>
 * The fluent helpers ({@link #visibleWhen}, {@link #onChange}, {@link #notPersistent}) return the concrete type
 * thanks to the generic self-cast, so this works:
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
    private boolean searchable;
    private Setting<?> attachedTo;
    private Setting<?> group;
    private final List<Consumer<T>> listeners = new ArrayList<>();

    protected Setting(String name, String description, T defaultValue) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description == null ? "" : description;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    public T get() {
        return value;
    }

    /** Sets a new value (sanitised). Listeners only fire if the value actually changes. */
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

    /** Hook for validation and clamping. Must never return {@code null} for non-null defaults. */
    protected T sanitize(T candidate) {
        return candidate == null ? defaultValue : candidate;
    }

    /** Unique key inside the module (config files, GUI memory). See {@link #getLabel()} for the shown text. */
    public String getName() {
        return name;
    }

    private String label;

    /** The text shown in the ClickGUI, {@link #getName()} by default. This lets several settings share one label. */
    public String getLabel() {
        return label != null ? label : name;
    }

    /** Shows {@code label} instead of the name (the name stays the config key). */
    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S withLabel(String label) {
        this.label = label;
        return (S) this;
    }

    public String getDescription() {
        return description;
    }

    public boolean isVisible() {
        return visibility.getAsBoolean() && (group == null || group.showsChildren());
    }

    /** Only group settings (see {@code GroupToggleSetting}) show children, and they override this. */
    protected boolean showsChildren() {
        return false;
    }

    /** The setting's own visibility rules up the group chain, ignoring whether the groups are open (used by the ClickGUI search). */
    public boolean isVisibleIgnoringGroups() {
        return visibility.getAsBoolean() && (group == null || group.isVisibleIgnoringGroups());
    }

    /**
     * Visibility inside a Settings-window section. {@code section} is a top-level group whose children fill the
     * section, so it counts as open; groups between this setting and the section still have to be open.
     */
    public boolean isVisibleInSection(Setting<?> section) {
        if (!visibility.getAsBoolean() || group == null) return false;
        if (group == section) return section.isVisibleIgnoringGroups();
        return group.isGroupExpanded() && group.isVisibleInSection(section);
    }

    /** True for group settings (a header with children). */
    public boolean isGroup() {
        return false;
    }

    /** For groups: whether the children are expanded right now. */
    public boolean isGroupExpanded() {
        return false;
    }

    /** The group toggle this setting is a child of, or {@code null}. */
    public Setting<?> getGroup() {
        return group;
    }

    /** Nesting depth under group toggles (0 is the top level). */
    public int groupDepth() {
        int d = 0;
        for (Setting<?> g = group; g != null; g = g.group) d++;
        return d;
    }

    /** Used by {@code GroupToggleSetting#child}: this setting only shows while the group is open. */
    public void setGroup(Setting<?> group) {
        this.group = group;
    }

    /** Whether this setting is written to and read from profiles. Actions and session-only values aren't. */
    public boolean isPersistent() {
        return persistent;
    }

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
     * Lets the user drag this setting to a new position in the ClickGUI (with the grip on the row's left edge).
     * Movable settings only swap places with each other, and fixed settings keep their slot. The order is saved per
     * module (see {@code Module#getSettingOrder}).
     */
    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S movable() {
        this.movable = true;
        return (S) this;
    }

    /** Makes this setting follow {@code parent} when the parent gets dragged (e.g. a "Real %" toggle under its stat). */
    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S attachedTo(Setting<?> parent) {
        this.attachedTo = Objects.requireNonNull(parent);
        return (S) this;
    }

    /**
     * Dropdowns only ({@code ChoiceSetting}, {@code MultiChoiceSetting}): the open list gets a search field and shows a
     * few rows at most (the scroll wheel shows the rest). Other setting types ignore this.
     */
    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S searchable() {
        this.searchable = true;
        return (S) this;
    }

    public boolean isSearchable() {
        return searchable;
    }

    public boolean isMovable() {
        return movable && attachedTo == null;
    }

    /** The setting this one follows when reordered, or {@code null}. */
    public Setting<?> getAttachedTo() {
        return attachedTo;
    }

    public abstract JsonElement toJson();

    /** Has to cope with malformed input. On any problem, keep the current value. */
    public abstract void fromJson(JsonElement element);

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + name + "=" + value + "]";
    }
}
