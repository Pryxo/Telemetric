package dev.telemetric.module;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.telemetric.config.JsonSerializable;
import dev.telemetric.setting.Setting;
import dev.telemetric.config.Acknowledgements;
import dev.telemetric.ui.confirm.Confirm;
import dev.telemetric.ui.confirm.ConfirmRequest;
import dev.telemetric.setting.SettingBundle;
import dev.telemetric.setting.impl.KeybindSetting;
import dev.telemetric.ui.clickgui.window.SettingsPreview;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A row in the ClickGUI. Toggleable by default (on/off + keybind); with {@code toggleable = false} it is a plain
 * settings group (Theme, Interface) where any click opens the settings.
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


    protected <S extends Setting<?>> S add(S setting) {
        for (Setting<?> existing : settings) {
            if (existing.getName().equalsIgnoreCase(setting.getName())) {
                throw new IllegalStateException("Duplicate setting '" + setting.getName() + "' in " + name);
            }
        }
        // a group's child lands right after the group's last setting, so children added later (e.g. by a subclass)
        // still show under their group
        int at = settings.size();
        if (setting.getGroup() != null) {
            int last = settings.lastIndexOf(setting.getGroup());
            for (int i = 0; i < settings.size(); i++) if (isInside(settings.get(i), setting.getGroup())) last = i;
            if (last >= 0) at = last + 1;
        }
        settings.add(at, setting);
        if (setting.isMovable()) order.add(setting);
        return setting;
    }

    /** Registers {@code setting} directly in front of {@code anchor} (or at the end if the anchor isn't registered). */
    protected <S extends Setting<?>> S addBefore(Setting<?> anchor, S setting) {
        int at = anchor == null ? -1 : settings.indexOf(anchor);
        if (at < 0) return add(setting);
        for (Setting<?> existing : settings) {
            if (existing.getName().equalsIgnoreCase(setting.getName())) {
                throw new IllegalStateException("Duplicate setting '" + setting.getName() + "' in " + name);
            }
        }
        settings.add(at, setting);
        if (setting.isMovable()) order.add(setting);
        return setting;
    }

    private static boolean isInside(Setting<?> setting, Setting<?> group) {
        for (Setting<?> g = setting.getGroup(); g != null; g = g.getGroup()) if (g == group) return true;
        return false;
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

    /**
     * Toggle asked for by the user (ClickGUI click, keybind). Turning on a module with an {@link #enableWarning()}
     * opens a confirmation dialog first and only enables it on "Enable". Config/profile loads use {@link #setEnabled}.
     */
    public void toggleByUser() {
        String warning = enabled ? null : getWarning();
        if (warning == null || Acknowledgements.has(warningKey())) {
            toggle();
            return;
        }
        Confirm.show(ConfirmRequest.warning(name, warning, () -> {
            Acknowledgements.add(warningKey()); // asked once: never again after "Enable"
            setEnabled(true);
        }));
    }

    /**
     * Override to warn about this module: the text is asked once (confirmation dialog) the first time the user turns it
     * on, and shown as a yellow ⚠ tooltip on its ClickGUI row. A line break starts a new paragraph. {@code null} = none.
     */
    public String getWarning() {
        return null;
    }

    private String warningKey() {
        return "enable:" + name;
    }

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


    protected void onEnable() {}

    protected void onDisable() {}

    /** Called every client tick while enabled (or always, for non-toggleable modules; see ModuleManager). */
    public void onTick() {}

    /** After all modules are registered and the config is loaded. */
    public void onPostInit() {}


    public String getName() { return name; }
    public String getDescription() { return description; }
    public Category getCategory() { return category; }
    public boolean isToggleable() { return toggleable; }

    /** Override this to hide a module from the ClickGUI. It still loads and saves. */
    public boolean isVisibleInGui() { return true; }

    /**
     * A live preview for the right column of this module's Settings window (e.g. a 3D player model), or null for
     * none. Called each time the window opens this module.
     */
    public SettingsPreview createPreview() {
        return null;
    }

    private String requestedSection;

    /**
     * Asks an open Settings window of this module to switch to the section with this label (e.g. a tab that just
     * appeared). Ignored if no window shows this module.
     */
    protected void requestSection(String label) {
        requestedSection = label;
    }

    /** The section asked for by {@link #requestSection}, once (then null). */
    public String takeRequestedSection() {
        String s = requestedSection;
        requestedSection = null;
        return s;
    }


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
