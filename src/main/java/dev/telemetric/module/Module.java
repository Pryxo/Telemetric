package dev.telemetric.module;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.telemetric.config.JsonSerializable;
import dev.telemetric.setting.Setting;
import dev.telemetric.setting.SettingBundle;
import dev.telemetric.setting.impl.KeybindSetting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Base class for everything shown as a row in the ClickGUI.
 * <p>
 * There are two flavours:
 * <ul>
 *   <li><b>Toggleable</b> (the default): a feature with an on/off state and a keybind.
 *       Left-click toggles it, right-click opens its settings.</li>
 *   <li><b>Settings group</b> ({@code toggleable = false}), e.g. Theme or Interface. No on/off and no keybind; any
 *       click opens the settings.</li>
 * </ul>
 * Subclasses declare their settings as fields through {@link #add(Setting)}:
 * <pre>{@code
 * public class ExampleModule extends Module {
 *     private final BooleanSetting flag = add(new BooleanSetting("Flag", "Does a thing", true));
 *     public ExampleModule() { super("Example", "Example module", Category.MISC); }
 *     @Override public void onTick() { if (flag.get()) { ... } }
 * }
 * }</pre>
 */
public abstract class Module implements JsonSerializable {

    private final String name;
    private final String description;
    private final Category category;
    private final boolean toggleable;

    private final List<Setting<?>> settings = new ArrayList<>();
    private final KeybindSetting bind;
    private boolean enabled;

    /** Movable settings ({@link Setting#movable()}) in the user's order. Fixed settings keep their slot. */
    private final List<Setting<?>> order = new ArrayList<>();
    private int orderVersion;

    protected Module(String name, String description, Category category) {
        this(name, description, category, true);
    }

    protected Module(String name, String description, Category category, boolean toggleable) {
        this.name = Objects.requireNonNull(name);
        this.description = description == null ? "" : description;
        this.category = Objects.requireNonNull(category);
        this.toggleable = toggleable;
        this.bind = toggleable ? new KeybindSetting("Bind", "Key that toggles " + name, KeybindSetting.NONE) : null;
    }

    // ------------------------------------------------------------------ settings

    /** Registers a setting and returns it, for use in field initialisers. */
    protected <S extends Setting<?>> S add(S setting) {
        for (Setting<?> existing : settings) {
            if (existing.getName().equalsIgnoreCase(setting.getName())) {
                throw new IllegalStateException("Duplicate setting '" + setting.getName() + "' in " + name);
            }
        }
        settings.add(setting);
        if (setting.isMovable()) order.add(setting);
        return setting;
    }

    /** Registers every setting of a reusable {@link SettingBundle} (e.g. a sound picker) and returns the bundle. */
    protected <B extends SettingBundle> B addBundle(B bundle) {
        for (Setting<?> setting : bundle.settings()) add(setting);
        return bundle;
    }

    public List<Setting<?>> getSettings() {
        return Collections.unmodifiableList(settings);
    }

    /**
     * Settings in UI order: the declared settings, with the movable ones (and the followers attached to them) placed in
     * the user's order, then the bind for toggleable modules.
     */
    public List<Setting<?>> getGuiSettings() {
        List<Setting<?>> list = new ArrayList<>(settings.size() + 1);
        int slot = 0;
        for (Setting<?> setting : settings) {
            if (setting.isMovable()) {
                Setting<?> head = order.get(slot++);
                list.add(head);
                list.addAll(followersOf(head));
            } else if (!order.contains(rootOf(setting))) {
                list.add(setting);
            }
        }
        if (bind != null) list.add(bind);
        return list;
    }

    /** Movable settings in the user's current order. */
    public List<Setting<?>> getSettingOrder() {
        return Collections.unmodifiableList(order);
    }

    /** Settings attached to {@code head}, directly or through other followers, in declaration order. */
    public List<Setting<?>> followersOf(Setting<?> head) {
        List<Setting<?>> out = new ArrayList<>();
        for (Setting<?> s : settings) if (s != head && rootOf(s) == head) out.add(s);
        return out;
    }

    private static Setting<?> rootOf(Setting<?> setting) {
        Setting<?> s = setting;
        for (int guard = 0; s.getAttachedTo() != null && guard < 16; guard++) s = s.getAttachedTo();
        return s;
    }

    /** Moves a movable setting to position {@code index} among the movable settings. */
    public void moveSetting(Setting<?> head, int index) {
        int from = order.indexOf(head);
        if (from < 0) return;
        int to = Math.max(0, Math.min(order.size() - 1, index));
        if (from == to) return;
        order.remove(from);
        order.add(to, head);
        orderVersion++;
    }

    /** Bumped every time the setting order changes (drag, profile load, reset). */
    public int getOrderVersion() {
        return orderVersion;
    }

    private void resetOrder() {
        List<Setting<?>> declared = new ArrayList<>();
        for (Setting<?> s : settings) if (s.isMovable()) declared.add(s);
        if (!declared.equals(order)) {
            order.clear();
            order.addAll(declared);
            orderVersion++;
        }
    }

    public KeybindSetting getBind() {
        return bind;
    }

    // ------------------------------------------------------------------ state

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (!toggleable || this.enabled == enabled) return;
        this.enabled = enabled;
        if (enabled) onEnable();
        else onDisable();
        for (Runnable sync : syncs) sync.run();
    }

    private final List<Runnable> syncs = new ArrayList<>();

    /**
     * Runs {@code sync} whenever the module is turned on or off and whenever one of {@code watched} changes. Meant for
     * modules that only push their settings into an adapter. {@code sync} reads {@link #isEnabled()} itself:
     * <pre>{@code syncOn(() -> ViewAdapter.setFullbright(isEnabled() ? strength.getFloat() : 0), strength);}</pre>
     */
    protected final void syncOn(Runnable sync, Setting<?>... watched) {
        syncs.add(sync);
        for (Setting<?> s : watched) s.onChange(v -> sync.run());
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    /** Restores every persistent setting and the enabled state to defaults. */
    public void resetToDefaults() {
        for (Setting<?> setting : settings) if (setting.isPersistent()) setting.reset();
        if (bind != null) bind.reset();
        resetOrder();
        setEnabled(isEnabledByDefault());
    }

    /** The enabled state after {@link #resetToDefaults()} (also used for modules missing from a profile). */
    protected boolean isEnabledByDefault() {
        return false;
    }

    // ------------------------------------------------------------------ lifecycle hooks

    /** Called when the module turns on. */
    protected void onEnable() {}

    /** Called when the module turns off. */
    protected void onDisable() {}

    /** Called every client tick while enabled (or always, for non-toggleable modules; see ModuleManager). */
    public void onTick() {}

    /** Called once after all modules are registered and the config has been loaded. */
    public void onPostInit() {}

    // ------------------------------------------------------------------ meta

    public String getName() { return name; }
    public String getDescription() { return description; }
    public Category getCategory() { return category; }
    public boolean isToggleable() { return toggleable; }

    /** Override this to hide a module from the ClickGUI. It still loads and saves. */
    public boolean isVisibleInGui() { return true; }

    // ------------------------------------------------------------------ serialisation

    @Override
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        if (toggleable) {
            json.addProperty("enabled", enabled);
            json.add("bind", bind.toJson());
        }
        JsonObject values = new JsonObject();
        for (Setting<?> setting : settings) {
            if (setting.isPersistent()) values.add(setting.getName(), setting.toJson());
        }
        json.add("settings", values);
        if (!order.isEmpty()) {
            JsonArray names = new JsonArray();
            for (Setting<?> s : order) names.add(s.getName());
            json.add("order", names);
        }
        return json;
    }

    @Override
    public void fromJson(JsonObject json) {
        if (json.has("settings") && json.get("settings").isJsonObject()) {
            JsonObject values = json.getAsJsonObject("settings");
            for (Setting<?> setting : settings) {
                if (!setting.isPersistent()) continue;
                JsonElement element = values.get(setting.getName());
                if (element != null) {
                    try {
                        setting.fromJson(element);
                    } catch (RuntimeException ignored) {
                        // malformed value, so keep the current one
                    }
                }
            }
        }
        readOrder(json.get("order"));
        if (toggleable) {
            if (json.has("bind")) bind.fromJson(json.get("bind"));
            JsonElement en = json.get("enabled");
            if (en != null && en.isJsonPrimitive() && en.getAsJsonPrimitive().isBoolean()) setEnabled(en.getAsBoolean());
        }
    }

    /** Applies a saved order. Unknown names are skipped, and settings missing from it keep their declared order at the end. */
    private void readOrder(JsonElement element) {
        if (order.isEmpty()) return;
        if (element == null || !element.isJsonArray()) {
            resetOrder();
            return;
        }
        List<Setting<?>> next = new ArrayList<>();
        for (JsonElement e : element.getAsJsonArray()) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) continue;
            String n = e.getAsString();
            for (Setting<?> s : order) {
                if (s.getName().equalsIgnoreCase(n) && !next.contains(s)) { next.add(s); break; }
            }
        }
        for (Setting<?> s : settings) if (s.isMovable() && !next.contains(s)) next.add(s);
        if (!next.equals(order)) {
            order.clear();
            order.addAll(next);
            orderVersion++;
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
