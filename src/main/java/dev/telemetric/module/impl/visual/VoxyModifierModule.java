package dev.telemetric.module.impl.visual;

import dev.telemetric.data.SharedVoxyMaps;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.game.TelosLocations;
import dev.telemetric.game.VoxyAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.module.impl.dungeon.DungeonTracker;
import dev.telemetric.setting.impl.ActionSetting;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.MultiChoiceSetting;
import dev.telemetric.ui.notification.Toasts;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.Objects;

/**
 * Voxy (optional mod): makes every instance of a whitelisted dungeon share one LOD store named
 * {@code telos:<dungeon_name>}. The name comes from the HUD location box, not the dimension id. Only meant for dungeons
 * whose layout never changes (Telos places every instance at the same coordinates). Game access goes through
 * {@link VoxyAdapter}.
 */
public final class VoxyModifierModule extends Module {

    /** Ticks in a new level before we trust the HUD box (it can still show the previous place). */
    private static final int SETTLE_TICKS = 60;
    /** How many ticks the HUD name has to stay the same before it's used. */
    private static final int STABLE_TICKS = 40;
    /** How many ticks a HUD name that differs from the previous place has to stay the same (the fast path). */
    private static final int FRESH_STABLE_TICKS = 5;

    private final MultiChoiceSetting whitelist = add(new MultiChoiceSetting("Whitelist",
            "Dungeons with a fixed layout whose LODs are shared between instances (all others are left alone)",
            this::options));
    private final ActionSetting whitelistCurrent = add(new ActionSetting("Whitelist Current",
            "Add or remove the dungeon you are in", this::toggleCurrent));

    private final BooleanSetting downloadMaps = add(new BooleanSetting("Download Maps",
            "Fetch prebuilt LODs for whitelisted dungeons from the Telemetric-Data repo (never replaces your own)", true));

    private final BooleanSetting realmAndHub = add(new BooleanSetting("Realm & Hub",
            "Keep one LOD save for the realm and hub (other non-dungeon Telos worlds too), even when the server's seed or instance number changes",
            true));

    private final BooleanSetting lockLods = add(new BooleanSetting("Lock LODs",
            "Never update saved LODs on Telos (Voxy only shows what is already saved; downloads still install)", false));

    /** Keys whose shared map was checked this session (only before Voxy first opens that store). */
    private final Map<String, CompletableFuture<Boolean>> prepared = new HashMap<>();

    private Object levelToken;
    private int levelTicks;
    private String lastName;
    private int stableTicks;
    private String placeName; // the last settled HUD name (any level)
    private String staleName; // what placeName was when this level started

    /** The longest renderer hold on a new level (map download, slow HUD) before we fall back to Voxy's own store. */
    private static final int HOLD_MAX_TICKS = 600;

    public VoxyModifierModule() {
        super("Voxy Modifier",
                "Voxy tweaks: one save folder for all Telos regions, shared LODs per dungeon/realm/hub",
                Category.VISUAL);
        VoxyAdapter.setSharedPathEnabled(this::isEnabled);
        VoxyAdapter.setLockLods(() -> isEnabled() && lockLods.get());
    }

    @Override
    public void onTick() {
        if (!VoxyAdapter.available()) return;
        Object token = DungeonAdapter.levelToken();
        if (token != levelToken) {
            levelToken = token;
            levelTicks = 0;
            staleName = placeName; // what the HUD box showed on the old level
            lastName = null;
            stableTicks = 0;
            VoxyAdapter.forget(); // the old level is gone, so only drop the stale override (and keep the new level's hold)
        }
        levelTicks++;
        if (levelTicks > SETTLE_TICKS && DungeonAdapter.hudLocation() != null) placeName = DungeonAdapter.dungeonName();
        VoxyAdapter.tick();
        if (levelTicks > HOLD_MAX_TICKS && VoxyAdapter.holding()) VoxyAdapter.release(); // never hide the LODs for long
        if (levelTicks % 20 == 1 && VoxyAdapter.syncBasePath()) {
            Toasts.info("Voxy: now saving to the shared Telos folder");
        }
        if (!DungeonAdapter.isInDungeon()) {
            String key = realmAndHub.get() ? worldKey(DungeonAdapter.dimensionId()) : null;
            if (key == null) VoxyAdapter.clear();
            else if (!key.equals(VoxyAdapter.activeKey()) && sharedMapReady(key)) VoxyAdapter.apply(key);
            return;
        }
        String name = DungeonAdapter.hudLocation() == null ? null : DungeonAdapter.dungeonName();
        if (!Objects.equals(name, lastName)) {
            lastName = name;
            stableTicks = 0;
        } else {
            stableTicks++;
        }
        if (name == null) return;
        // Fast path: right after the teleport the HUD box may still show the previous place. A name that's different from
        // it is the new dungeon, so a short check is enough. The same name as before keeps the slow wait.
        boolean fresh = !name.equals(staleName) && stableTicks >= FRESH_STABLE_TICKS;
        if (!fresh && (levelTicks < SETTLE_TICKS || stableTicks < STABLE_TICKS)) return;
        applyDungeon(name);
    }

    private void applyDungeon(String name) {
        String key = isWhitelisted(name) ? VoxyAdapter.stableKey(name) : null;
        if (key == null) {
            VoxyAdapter.clear();
        } else if (!key.equals(VoxyAdapter.activeKey()) && sharedMapReady(key) && VoxyAdapter.apply(key)) {
            Toasts.info("Voxy: shared LODs for " + name);
        }
    }

    /** Downloads the shared map once per session, before the store is first opened. True when done (or skipped). */
    private boolean sharedMapReady(String key) {
        CompletableFuture<Boolean> job = prepared.get(key);
        if (job == null) {
            Path folder = downloadMaps.get() ? VoxyAdapter.storeFolder(key) : null;
            job = folder == null ? CompletableFuture.completedFuture(false) : SharedVoxyMaps.prepare(key, folder);
            prepared.put(key, job);
        }
        if (!job.isDone()) return false;
        if (Boolean.TRUE.equals(job.getNow(false))) {
            prepared.put(key, CompletableFuture.completedFuture(false)); // show the toast only once
            Toasts.success("Voxy: downloaded shared map");
        }
        return true;
    }

    /**
     * The stable key for a Telos world that isn't a dungeon (realm, hub): the dimension id without its instance number
     * ({@code telos:realm}, {@code telos:hub/2} → {@code telos:hub}). Null outside Telos dimensions.
     */
    static String worldKey(String dimensionId) {
        if (dimensionId == null || !dimensionId.startsWith("telos:")) return null;
        String key = dimensionId.replaceFirst("/\\d+$", "");
        return key.length() > "telos:".length() ? key : null;
    }

    @Override
    protected void onEnable() {
        levelToken = null; // re-run the folder check and overrides on the next tick
    }

    @Override
    protected void onDisable() {
        VoxyAdapter.clear();
        VoxyAdapter.syncBasePath(); // back to Voxy's own per-address folder, without rejoining
        levelToken = null;
    }

    private List<String> options() {
        List<String> options = new ArrayList<>(TelosLocations.dungeons());
        for (String entry : whitelist.get()) {
            if (options.stream().noneMatch(o -> DungeonTracker.normalize(o).equals(DungeonTracker.normalize(entry)))) {
                options.add(entry);
            }
        }
        return options;
    }

    private boolean isWhitelisted(String dungeon) {
        String key = DungeonTracker.normalize(dungeon);
        for (String entry : whitelist.get()) if (DungeonTracker.normalize(entry).equals(key)) return true;
        return false;
    }

    private void toggleCurrent() {
        String name = DungeonAdapter.isInDungeon() && DungeonAdapter.hudLocation() != null
                ? DungeonAdapter.dungeonName() : null;
        if (name == null) {
            Toasts.error("Not in a dungeon");
            return;
        }
        if (isWhitelisted(name)) {
            String key = DungeonTracker.normalize(name);
            for (String e : new ArrayList<>(whitelist.get())) if (DungeonTracker.normalize(e).equals(key)) whitelist.toggle(e);
            Toasts.info(name + " removed from the Voxy whitelist");
        } else {
            String entry = name;
            for (String option : TelosLocations.dungeons()) {
                if (DungeonTracker.normalize(option).equals(DungeonTracker.normalize(name))) entry = option;
            }
            whitelist.toggle(entry);
            Toasts.info(name + " added to the Voxy whitelist");
        }
    }
}
