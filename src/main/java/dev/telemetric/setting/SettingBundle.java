package dev.telemetric.setting;

import java.util.List;

/**
 * A reusable block of settings that several modules share (e.g. a sound picker), so the settings and the code that
 * reads them only get written once. Register it with {@code Module#addBundle(SettingBundle)}. Every setting in
 * {@link #settings()} is added to the module in order, exactly as if each one were added by hand.
 * <p>
 * Setting names are config keys and have to be unique inside one module, so bundles build their names from a prefix
 * the module gives them (and show short labels through {@link Setting#withLabel}).
 */
public interface SettingBundle {

    /** The bundle's settings in GUI order. Called once, when the bundle is added to a module. */
    List<Setting<?>> settings();
}
