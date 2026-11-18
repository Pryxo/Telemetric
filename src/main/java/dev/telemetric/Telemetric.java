package dev.telemetric;

import dev.telemetric.command.TelemetricCommand;
import dev.telemetric.data.TelosData;
import dev.telemetric.game.BossMenuAdapter;
import dev.telemetric.game.DropAdapter;
import dev.telemetric.tracking.LootTracker;
import dev.telemetric.tracking.WikiStatsScan;
import dev.telemetric.tracking.RealmBossTracker;
import dev.telemetric.tracking.TrackingStore;
import dev.telemetric.config.ModPaths;
import dev.telemetric.addon.TelemetricAddon;
import dev.telemetric.config.ConfigManager;
import dev.telemetric.game.ChatAdapter;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.game.ServerGate;
import dev.telemetric.game.StashAdapter;
import dev.telemetric.game.WorldRenderAdapter;
import dev.telemetric.module.impl.combat.CooldownTracker;
import dev.telemetric.module.impl.dungeon.DungeonTracker;
import dev.telemetric.ui.hud.HudEditorScreen;
import dev.telemetric.input.Keybinds;
import dev.telemetric.input.ModuleKeyHandler;
import dev.telemetric.module.ModuleManager;
import dev.telemetric.ui.clickgui.ClickGuiScreen;
import dev.telemetric.ui.hud.HudOverlay;
import dev.telemetric.ui.theme.ThemeManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint. Owns the managers and wires Fabric events. Keep logic out of here —
 * it only connects subsystems.
 */
public final class Telemetric implements ClientModInitializer {

    public static final String MOD_ID = "telemetric";
    public static final String NAME = "Telemetric";
    public static final Logger LOGGER = LoggerFactory.getLogger(NAME);
    /** Diagnostic logging (dungeon analysis, Tab stats, stash pop-ups). Off: nothing goes to latest.log. */
    public static boolean debugLog = false;

    /** Logs at info level only while {@link #debugLog} is on. */
    public static void debug(String format, Object... args) {
        if (debugLog) LOGGER.info(format, args);
    }

    private static Telemetric instance;

    private ModuleManager moduleManager;
    private ConfigManager configManager;
    private ThemeManager themeManager;
    private ModuleKeyHandler moduleKeyHandler;

    @Override
    public void onInitializeClient() {
        instance = this;

        ModPaths.migrate(); // first: old flat config folder -> settings/ saved/ custom/ data/
        TelosData.init(); // shared tables: cache / bundled copy now, one update check per start
        moduleManager = new ModuleManager();
        moduleManager.registerAll();
        // Add-ons register extra categories, palettes and modules here,
        // before the config loads so their settings are saved and restored like any other module.
        for (TelemetricAddon addon : FabricLoader.getInstance()
                .getEntrypoints(TelemetricAddon.ENTRYPOINT, TelemetricAddon.class)) {
            addon.register(moduleManager);
        }

        themeManager = new ThemeManager();
        configManager = new ConfigManager(moduleManager);
        configManager.init();
        moduleManager.postInit();

        moduleKeyHandler = new ModuleKeyHandler(moduleManager);
        Keybinds.register();
        HudOverlay.register();
        WorldRenderAdapter.register();
        DungeonAdapter.register();
        ChatAdapter.register();
        StashAdapter.register();
        DropAdapter.register();
        BossMenuAdapter.register();
        TelemetricCommand.register();
        LootTracker.get().start();
        RealmBossTracker.get().start();

        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            configManager.onShutdown();
            TrackingStore.saveAll();
        });

        LOGGER.info("{} {} initialised with {} modules", NAME, version(), moduleManager.getModules().size());
    }

    private void onEndTick(Minecraft client) {
        if (!ServerGate.onTelos()) {
            Keybinds.consumeOpenGui(); // drop presses made off Telos
            if (client.screen instanceof ClickGuiScreen || client.screen instanceof HudEditorScreen) {
                client.setScreen(null);
            }
            return;
        }
        if (Keybinds.consumeOpenGui() && client.screen == null) {
            client.setScreen(new ClickGuiScreen());
        }
        moduleKeyHandler.tick();
        DungeonTracker.get().tick();
        RealmBossTracker.get().tick();
        LootTracker.get().tick();
        WikiStatsScan.get().tick();
        try {
            CooldownTracker.get().tick();
        } catch (RuntimeException e) {
            LOGGER.error("Cooldown tracker tick failed", e);
        }
        moduleManager.tick();
    }

    // ------------------------------------------------------------------ access

    public static Telemetric get() {
        if (instance == null) throw new IllegalStateException("Telemetric not initialised yet");
        return instance;
    }

    public ModuleManager modules() { return moduleManager; }
    public ConfigManager config() { return configManager; }
    public ThemeManager theme() { return themeManager; }

    public static String version() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("dev");
    }
}
