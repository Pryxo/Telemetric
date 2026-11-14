package dev.telemetric.devbuild;

import dev.telemetric.addon.TelemetricAddon;
import dev.telemetric.module.ModuleManager;
import dev.telemetric.ui.theme.ThemeManager;

/**
 * Dev-build entrypoint. Everything under {@code src/dev} ships only in the dev jar
 * ({@code telemetric-<version> Dev.jar}); the normal jar contains none of it. See docs/dev-build.md.
 */
public final class DevAddon implements TelemetricAddon {

    @Override
    public void register(ModuleManager modules) {
        ThemeManager.registerFixedPalette(DevCategory.DEV, DevPalette.RED_BLACK);
        // Dev modules: modules.register(new MyDevModule());  (category DevCategory.DEV)
        modules.register(new LocationLogModule()); // TEMP: location / biome data collector
        modules.register(new PacketLogModule());   // TEMP: received-packet dump (puzzle research)
    }
}
