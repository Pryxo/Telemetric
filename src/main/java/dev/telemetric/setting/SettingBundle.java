package dev.telemetric.setting;

import java.util.List;

/**
 * A reusable block of settings that several modules share (e.g. a sound picker), so the settings and the
 * code that reads them are written once. Register it with {@code Module#addBundle(SettingBundle)}: every setting
 * in {@link #settings()} is added to the module in order, exactly as if each were added by hand.
 * <p>
 * Setting names are config keys and must be unique inside one module, so bundles derive their names from a
 * prefix given by the module (and show short labels via {@link Setting#withLabel}).
 */
public interface SettingBundle {

    /** The bundle's settings in GUI order. Called once, when the bundle is added to a module. */
    List<Setting<?>> settings();
}
