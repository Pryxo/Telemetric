package dev.telemetric.addon;

import dev.telemetric.module.ModuleManager;

/**
 * Hook for add-on code that is not part of this mod. Implementations are listed under the
 * {@value #ENTRYPOINT} entrypoint in {@code fabric.mod.json}; this mod declares none itself.
 */
public interface TelemetricAddon {

    String ENTRYPOINT = "telemetric-addon";

    /**
     * Called once at start-up, right after the built-in modules are registered and before the config
     * loads. Register categories ({@code Category.register}), fixed palettes
     * ({@code ThemeManager.registerFixedPalette}) and modules ({@code modules.register}) here.
     */
    void register(ModuleManager modules);
}
