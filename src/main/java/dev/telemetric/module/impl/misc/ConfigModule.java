package dev.telemetric.module.impl.misc;

import dev.telemetric.Telemetric;
import dev.telemetric.config.ConfigManager;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.ActionSetting;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ModeSetting;
import dev.telemetric.setting.impl.TextSetting;
import dev.telemetric.ui.notification.Toasts;

import java.util.ArrayList;
import java.util.List;

/**
 * UI-only: profile management (save, load, create, delete). All of its settings are session state and are
 * <b>not</b> written into the profiles themselves.
 */
public final class ConfigModule extends Module {

    /** Cached so the dropdown never hits the disk every frame. Refreshed when the GUI opens and after file operations. */
    private List<String> profileCache = new ArrayList<>(List.of(ConfigManager.DEFAULT_PROFILE));

    public final ModeSetting profile = add(new ModeSetting(
            "Profile", "Profile to save to / load from", ConfigManager.DEFAULT_PROFILE, () -> profileCache)
            .notPersistent());

    public final ActionSetting save = add(new ActionSetting(
            "Save", "Save current settings into the selected profile", this::saveSelected));

    public final ActionSetting load = add(new ActionSetting(
            "Load", "Load the selected profile", this::loadSelected));

    public final TextSetting newName = add(new TextSetting(
            "New Name", "Name for a new profile (A-Z, 0-9, _ and -)", "", 32)
            .filter(ConfigManager::isProfileChar)
            .notPersistent());

    public final ActionSetting create = add(new ActionSetting(
            "Create", "Save current settings as a new profile", this::createNew));

    public final ActionSetting delete = add(new ActionSetting(
            "Delete", "Delete the selected profile (not the active one)", this::deleteSelected));

    public final BooleanSetting autoSave = add(new BooleanSetting(
            "Auto Save", "Save the active profile when closing the GUI or the game", true)
            .notPersistent());

    public final ActionSetting resetAll = add(new ActionSetting(
            "Reset Defaults", "Reset every module to its default settings (not saved until you save)", () -> {
                Telemetric.get().modules().getModules().forEach(Module::resetToDefaults);
                Toasts.info("All settings reset to defaults");
            }));

    public ConfigModule() {
        super("Config", "Save, load and manage profiles", Category.MISC, false);
    }

    @Override
    public void onPostInit() {
        ConfigManager config = Telemetric.get().config();
        autoSave.set(config.isAutoSave());
        autoSave.onChange(config::setAutoSave);
        refreshProfiles();
    }

    /** Re-reads the profile list from disk and selects the active profile. */
    public void refreshProfiles() {
        ConfigManager config = Telemetric.get().config();
        profileCache = new ArrayList<>(config.listProfiles());
        profile.set(config.getActiveProfile());
    }

    // ------------------------------------------------------------------ actions

    private void saveSelected() {
        String name = profile.get();
        if (Telemetric.get().config().saveProfile(name)) Toasts.success("Saved profile '" + name + "'");
        else Toasts.error("Could not save '" + name + "'");
        refreshProfiles();
    }

    private void loadSelected() {
        String name = profile.get();
        if (Telemetric.get().config().loadProfile(name)) Toasts.success("Loaded profile '" + name + "'");
        else Toasts.error("Could not load '" + name + "'");
        refreshProfiles();
    }

    private void createNew() {
        ConfigManager config = Telemetric.get().config();
        String name = ConfigManager.sanitizeName(newName.get());
        if (name == null) {
            Toasts.error("Enter a valid name first");
            return;
        }
        if (config.profileExists(name)) {
            Toasts.error("Profile '" + name + "' already exists");
            return;
        }
        if (config.saveProfile(name)) {
            newName.set("");
            Toasts.success("Created profile '" + name + "'");
        } else {
            Toasts.error("Could not create '" + name + "'");
        }
        refreshProfiles();
    }

    private void deleteSelected() {
        ConfigManager config = Telemetric.get().config();
        String name = profile.get();
        if (name.equals(config.getActiveProfile())) {
            Toasts.error("Can't delete the active profile");
            return;
        }
        if (config.deleteProfile(name)) Toasts.success("Deleted profile '" + name + "'");
        else Toasts.error("Could not delete '" + name + "'");
        refreshProfiles();
    }
}
