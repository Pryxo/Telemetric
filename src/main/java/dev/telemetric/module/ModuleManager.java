package dev.telemetric.module;

import dev.telemetric.module.impl.visual.BossTrackerModule;
import dev.telemetric.module.impl.hud.PityCounterHud;
import dev.telemetric.module.impl.hud.LifetimeStatsHud;
import dev.telemetric.module.impl.hud.SessionStatsHud;
import dev.telemetric.Telemetric;
import dev.telemetric.module.impl.combat.ArmorCooldownsHud;
import dev.telemetric.module.impl.combat.AssassinStacksHud;
import dev.telemetric.module.impl.combat.AutoClickerModule;
import dev.telemetric.module.impl.combat.LowHealthWarningHud;
import dev.telemetric.module.impl.combat.HeldCooldownHud;
import dev.telemetric.module.impl.combat.ItemRangeModule;
import dev.telemetric.module.impl.combat.ReadyAlertsHud;
import dev.telemetric.module.impl.dungeon.WayfinderModule;
import dev.telemetric.module.impl.hud.DpsLogHud;
import dev.telemetric.module.impl.hud.DpsMeterHud;
import dev.telemetric.module.impl.hud.DungeonInfoHud;
import dev.telemetric.module.impl.hud.TabStatsHud;
import dev.telemetric.module.impl.hud.TabRealPercentModule;
import dev.telemetric.module.impl.hud.PerformanceHud;
import dev.telemetric.module.impl.hud.WatermarkHud;
import dev.telemetric.module.impl.misc.ChatColorsModule;
import dev.telemetric.module.impl.misc.ConfigModule;
import dev.telemetric.module.impl.misc.ShareSettingsModule;
import dev.telemetric.module.impl.misc.HudSettingsModule;
import dev.telemetric.module.impl.misc.InterfaceModule;
import dev.telemetric.module.impl.misc.ThemeModule;
import dev.telemetric.module.impl.player.AutoSprintModule;
import dev.telemetric.module.impl.player.KeybindsModule;
import dev.telemetric.module.impl.social.CalloutsModule;
import dev.telemetric.module.impl.social.ChatModule;
import dev.telemetric.module.impl.social.DiscordRpcModule;
import dev.telemetric.module.impl.social.ServerMessagesModule;
import dev.telemetric.module.impl.visual.BossBarScaleModule;
import dev.telemetric.module.impl.visual.TooltipScaleModule;
import dev.telemetric.module.impl.visual.BossStageTrackerModule;
import dev.telemetric.module.impl.visual.CameraModule;
import dev.telemetric.module.impl.visual.CustomBagsModule;
import dev.telemetric.module.impl.visual.FullbrightModule;
import dev.telemetric.module.impl.visual.HealthBarModule;
import dev.telemetric.module.impl.visual.HideArmorModule;
import dev.telemetric.module.impl.visual.HideHudModule;
import dev.telemetric.module.impl.visual.HidePlayersModule;
import dev.telemetric.module.impl.visual.NametagModule;
import dev.telemetric.module.impl.visual.HitboxesModule;
import dev.telemetric.module.impl.visual.ItemRarityModule;
import dev.telemetric.module.impl.visual.PlayerSizeModule;
import dev.telemetric.module.impl.visual.StashOverlayModule;
import dev.telemetric.module.impl.visual.TraitDetailsModule;
import dev.telemetric.module.impl.visual.VoxyModifierModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Owns every module. The registration order is the order inside each panel. */
public final class ModuleManager {

    private final Map<Class<? extends Module>, Module> byClass = new LinkedHashMap<>();
    private final List<Module> modules = new ArrayList<>();

    /** The one place where modules are listed. Add new modules here. */
    public void registerAll() {
        register(new ThemeModule());
        register(new InterfaceModule());
        register(new HudSettingsModule());
        register(new ChatColorsModule());
        register(new ConfigModule());
        register(new ShareSettingsModule());

        // Movement & Controls
        register(new AutoSprintModule());
        register(new KeybindsModule());

        // Combat (cooldowns come from the shared CooldownTracker; these HUD elements are listed in the Combat panel)
        register(HeldCooldownHud.weapon());
        register(HeldCooldownHud.ability());
        register(new ArmorCooldownsHud());
        register(new ReadyAlertsHud());
        ItemRangeModule abilityRange = ItemRangeModule.ability();
        register(ItemRangeModule.weapon(abilityRange));
        register(abilityRange);
        register(new AssassinStacksHud());
        register(new LowHealthWarningHud());
        register(new AutoClickerModule());

        // Character / World & Rendering / Inventory & Loot / Dungeons & Bosses (former Visual modules)
        register(new StashOverlayModule());
        register(new VoxyModifierModule());
        register(new BossStageTrackerModule());
        register(new BossTrackerModule()); // realm bosses: HUD list and waypoints
        register(new FullbrightModule());
        register(new CameraModule());
        register(new BossBarScaleModule());
        register(new TooltipScaleModule());
        register(new HitboxesModule());
        register(new PlayerSizeModule());
        register(new HideArmorModule());
        NametagModule nametag = register(new NametagModule()); // every name tag edit: Status Title, Player Currency
        register(nametag.colorsHud()); // hidden HUD element, toggled by Show Status Colors
        register(new HidePlayersModule());
        register(new HideHudModule());
        register(new ItemRarityModule());
        register(new TraitDetailsModule());
        register(new HealthBarModule());
        register(new CustomBagsModule());

        // Dungeons & Bosses (Wayfinder and map only active in telos:dungeon/<n>)
        register(new WayfinderModule());

        // Social (chat, call-outs, Discord)
        register(new ChatModule());
        register(new CalloutsModule());
        register(new ServerMessagesModule());
        register(new DiscordRpcModule());

        // HUD elements (drawn in-game; layout edited through Misc → HUD → Edit HUD). Most sit in Stats;
        // Watermark → World, Character Info → Player, Dungeon Info → Combat,
        // Pity Counter → Inventory.
        WatermarkHud watermark = register(new WatermarkHud());
        register(watermark.connectionHud()); // hidden HUD element, toggled by Watermark → Show Connection
        PerformanceHud performance = register(new PerformanceHud());
        performance.parts().forEach(this::register);
        register(TabStatsHud.generalInfo());
        register(TabStatsHud.characterInfo());
        register(TabStatsHud.statsInfo());
        register(new TabRealPercentModule());
        register(new DpsMeterHud());
        register(new DpsLogHud());
        register(new DungeonInfoHud());
        register(new PityCounterHud());
        register(new LifetimeStatsHud());
        register(new SessionStatsHud());
    }

    public <M extends Module> M register(M module) {
        for (Module existing : modules) {
            if (existing == module) {
                throw new IllegalStateException("Module registered twice: " + module.getName());
            }
            if (existing.getName().equalsIgnoreCase(module.getName())) {
                throw new IllegalStateException("Duplicate module name: " + module.getName());
            }
        }
        // Several instances of one class are allowed (e.g. the Performance HUD parts). Looking up by class gets the first.
        byClass.putIfAbsent(module.getClass(), module);
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

    private static long tickCount;

    /** How many times {@link #tick()} has been called so far, so several modules can share one per-tick poll. */
    public static long tickCount() {
        return tickCount;
    }

    /** Ticks the enabled toggleable modules and all settings-group modules. */
    public void tick() {
        tickCount++;
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

    /** The modules of one category, sorted alphabetically by name (the ClickGUI panel order). */
    public List<Module> getByCategory(Category category) {
        List<Module> list = new ArrayList<>();
        for (Module module : modules) if (module.getCategory() == category) list.add(module);
        list.sort(Comparator.comparing(Module::getName, String.CASE_INSENSITIVE_ORDER));
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
