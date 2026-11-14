package dev.telemetric.module;

import dev.telemetric.Telemetric;
import dev.telemetric.module.impl.dungeon.DungeonMapHud;
import dev.telemetric.module.impl.dungeon.WayfinderModule;
import dev.telemetric.module.impl.hud.DpsMeterHud;
import dev.telemetric.module.impl.hud.DungeonInfoHud;
import dev.telemetric.module.impl.hud.FpsHud;
import dev.telemetric.module.impl.hud.PingHud;
import dev.telemetric.module.impl.hud.TabStatsHud;
import dev.telemetric.module.impl.hud.TpsHud;
import dev.telemetric.module.impl.hud.WatermarkHud;
import dev.telemetric.module.impl.misc.ConfigModule;
import dev.telemetric.module.impl.misc.HudSettingsModule;
import dev.telemetric.module.impl.misc.InterfaceModule;
import dev.telemetric.module.impl.misc.ThemeModule;
import dev.telemetric.module.impl.player.AutoJumpModule;
import dev.telemetric.module.impl.visual.StashOverlayModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Owns every module. Registration order = order inside its panel. */
public final class ModuleManager {

    private final Map<Class<? extends Module>, Module> byClass = new LinkedHashMap<>();
    private final List<Module> modules = new ArrayList<>();

    /** Single place where modules are listed. Add new modules here. */
    public void registerAll() {
        // ---- Misc (UI-only settings) ----
        register(new ThemeModule());
        register(new InterfaceModule());
        register(new HudSettingsModule());
        register(new ConfigModule());

        // ---- Features, grouped by category ----
        // Player
        register(new AutoJumpModule());

        // Visual
        register(new StashOverlayModule());

        // Dungeon (active only in telos:dungeon/<n>)
        register(new WayfinderModule());
        register(new DungeonMapHud()); // HUD element listed in the Dungeon panel

        // HUD (elements drawn in-game; layout edited via Misc → HUD → Edit HUD)
        register(new WatermarkHud());
        register(new FpsHud());
        register(new PingHud());
        register(new TpsHud());
        register(TabStatsHud.generalInfo());
        register(TabStatsHud.characterInfo());
        register(TabStatsHud.statsInfo());
        register(new DpsMeterHud());
        register(new DungeonInfoHud());
    }

    public <M extends Module> M register(M module) {
        if (byClass.containsKey(module.getClass())) {
            throw new IllegalStateException("Module registered twice: " + module.getName());
        }
        for (Module existing : modules) {
            if (existing.getName().equalsIgnoreCase(module.getName())) {
                throw new IllegalStateException("Duplicate module name: " + module.getName());
            }
        }
        byClass.put(module.getClass(), module);
        modules.add(module);
        return module;
    }

    public void postInit() {
        for (Module module : modules) {
            try {
                module.onPostInit();
            } catch (RuntimeException e) {
                Telemetric.LOGGER.error("postInit failed for {}", module.getName(), e);
            }
        }
    }

    /** Ticks enabled toggleable modules and all settings-group modules. */
    public void tick() {
        for (Module module : modules) {
            if (module.isToggleable() && !module.isEnabled()) continue;
            try {
                module.onTick();
            } catch (RuntimeException e) {
                Telemetric.LOGGER.error("Tick failed for {}", module.getName(), e);
            }
        }
    }

    public List<Module> getModules() {
        return Collections.unmodifiableList(modules);
    }

    public List<Module> getByCategory(Category category) {
        List<Module> list = new ArrayList<>();
        for (Module module : modules) if (module.getCategory() == category) list.add(module);
        return list;
    }

    @SuppressWarnings("unchecked")
    public <M extends Module> M get(Class<M> type) {
        Module module = byClass.get(type);
        if (module == null) throw new IllegalArgumentException("Module not registered: " + type.getSimpleName());
        return (M) module;
    }

    public Optional<Module> find(String name) {
        for (Module module : modules) if (module.getName().equalsIgnoreCase(name)) return Optional.of(module);
        return Optional.empty();
    }
}
