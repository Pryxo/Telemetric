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
 * Client entrypoint. It owns the managers and wires up the Fabric events. Keep logic out of here; this class only
 * connects subsystems.
 */
public final class Telemetric implements ClientModInitializer {

    public static final String MOD_ID = "telemetric";
    public static final String NAME = "Telemetric";
    public static final Logger LOGGER = LoggerFactory.getLogger(NAME);
    /** Diagnostic logging (dungeon analysis, Tab stats, stash pop-ups). When off, nothing goes to latest.log. */
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
    private boolean wasOnTelos;

    @Override
    public void onInitializeClient() {
        instance = this;

        ModPaths.migrate(); // first, move the old flat config folder into settings/ saved/ custom/ data/
        TelosData.init(); // shared tables: load from cache or the bundled copy now, one update check per start
        dev.telemetric.api.HeadStatusApi.refreshRealms(); // one live realm list request per start (Realm Selector)
        moduleManager = new ModuleManager();
        moduleManager.registerAll();
        // Add-ons register extra categories, palettes and modules here. This happens before the config loads, so their
        // settings get saved and restored like any other module's.
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
        dev.telemetric.game.ScenePreviewAdapter.register();
        DungeonAdapter.register();
        ChatAdapter.register();
        StashAdapter.register();
        DropAdapter.register();
        BossMenuAdapter.register();
        dev.telemetric.game.RegionSwitchAdapter.register();
        TelemetricCommand.register();
        LootTracker.get().start();
        RealmBossTracker.get().start();

        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
        // once the client and its session services are up, load the settable head statuses (once per start)
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            dev.telemetric.api.HeadStatusApi.refreshStatuses();
            dev.telemetric.api.TelemetricServer.start(); // online-with-the-mod count (Watermark)
            // newest GitHub release (pre-releases count); shown when you join Telos, or right away if already there
            dev.telemetric.api.UpdateChecker.check().thenAccept(r -> client.execute(this::showUpdateNotice));
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            configManager.onShutdown();
            TrackingStore.saveAll();
            dev.telemetric.api.TelemetricServer.stop();
        });

        LOGGER.info("{} {} initialised with {} modules", NAME, version(), moduleManager.getModules().size());
    }

    private void onEndTick(Minecraft client) {
        boolean onTelos = ServerGate.onTelos();
        if (onTelos && !wasOnTelos) {
            // just joined Telos: load your top-25 statuses now, so /telemetric status set has its Tab list right away
            dev.telemetric.api.HeadStatusApi.myTop25().exceptionally(e -> null);
            showUpdateNotice();
        }
        wasOnTelos = onTelos;
        if (!onTelos) {
            Keybinds.consumeOpenGui(); // throw away presses made off Telos
            if (client.screen instanceof ClickGuiScreen || client.screen instanceof HudEditorScreen
                    || client.screen instanceof dev.telemetric.ui.confirm.ConfirmScreen) {
                client.setScreen(null);
            }
            return;
        }
        if (Keybinds.consumeOpenGui() && client.screen == null) {
            client.setScreen(new ClickGuiScreen());
        }
        dev.telemetric.game.ScreenOpener.tick(client); // /tm, /tm edithud
        moduleKeyHandler.tick();
        guardedTick("Dungeon tracker", DungeonTracker.get()::tick);
        guardedTick("Realm boss tracker", RealmBossTracker.get()::tick);
        guardedTick("Loot tracker", LootTracker.get()::tick);
        guardedTick("Wiki stats scan", WikiStatsScan.get()::tick);
        guardedTick("Head status cache", dev.telemetric.api.HeadStatusCache::tick); // loaded players, every 10 s, cached 15 min
        guardedTick("Cooldown tracker", CooldownTracker.get()::tick);
        moduleManager.tick();
    }

    /** Names of the trackers whose tick already failed once (logged once, not every tick). */
    private final java.util.Set<String> tickFailed = new java.util.HashSet<>();

    /** Runs one tracker's tick; a crash there is logged once and never takes the client down. */
    private void guardedTick(String name, Runnable tick) {
        try {
            tick.run();
        } catch (RuntimeException e) {
            if (tickFailed.add(name)) LOGGER.error("{} tick failed (further failures not logged)", name, e);
        }
    }

    private boolean updateShown;

    /** Posts the update block once per start, the first time we're on Telos and a newer release is known. */
    private void showUpdateNotice() {
        var r = dev.telemetric.api.UpdateChecker.newerVersion();
        if (updateShown || r == null || !ServerGate.onTelos()) return;
        updateShown = true;
        for (var line : dev.telemetric.chat.UpdateNotice.lines(
                dev.telemetric.api.UpdateChecker.strip(version()), r.version(), dev.telemetric.api.UpdateChecker.MODRINTH)) {
            ChatAdapter.post(line);
        }
    }

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
