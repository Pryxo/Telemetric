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
 * {@code telos:<dungeon_name>} — the name comes from the HUD location box, not the dimension id. Only for dungeons
 * whose layout never changes (Telos places every instance at the same coordinates). Game access:
 * {@link VoxyAdapter}.
 */
public final class VoxyModifierModule extends Module {

    /** Ticks in a new level before the HUD box is trusted (it can still show the previous place). */
    private static final int SETTLE_TICKS = 60;
    /** Ticks the HUD name must stay the same before it is used. */
    private static final int STABLE_TICKS = 40;

    private final MultiChoiceSetting whitelist = add(new MultiChoiceSetting("Whitelist",
            "Dungeons with a fixed layout whose LODs are shared between instances (all others are left alone)",
            this::options));
    private final ActionSetting whitelistCurrent = add(new ActionSetting("Whitelist Current",
            "Add or remove the dungeon you are in", this::toggleCurrent));

    private final BooleanSetting downloadMaps = add(new BooleanSetting("Download Maps",
            "Fetch prebuilt LODs for whitelisted dungeons from the Telemetric-Data repo (never replaces your own)", true));

    /** Keys whose shared map was checked this session (only before Voxy first opens that store). */
    private final Map<String, CompletableFuture<Boolean>> prepared = new HashMap<>();

    private Object levelToken;
    private int levelTicks;
    private String lastName;
    private int stableTicks;

    public VoxyModifierModule() {
        super("Voxy Modifier",
                "Voxy: all instances of a whitelisted dungeon share one LOD save (telos:<dungeon name>)",
                Category.VISUAL);
        VoxyAdapter.setSharedPathEnabled(this::isEnabled);
    }

    @Override
    public void onTick() {
        if (!VoxyAdapter.available()) return;
        Object token = DungeonAdapter.levelToken();
        if (token != levelToken) {
            levelToken = token;
            levelTicks = 0;
            lastName = null;
            stableTicks = 0;
            VoxyAdapter.clear(); // old level is gone: only drops the stale override
        }
        levelTicks++;
        if (!DungeonAdapter.isInDungeon()) {
            VoxyAdapter.clear();
            return;
        }
        String name = DungeonAdapter.hudLocation() == null ? null : DungeonAdapter.dungeonName();
        if (!Objects.equals(name, lastName)) {
            lastName = name;
            stableTicks = 0;
        } else {
            stableTicks++;
        }
        if (name == null || levelTicks < SETTLE_TICKS || stableTicks < STABLE_TICKS) return;
        String key = isWhitelisted(name) ? VoxyAdapter.stableKey(name) : null;
        if (key == null) {
            VoxyAdapter.clear();
        } else if (!key.equals(VoxyAdapter.activeKey()) && sharedMapReady(key) && VoxyAdapter.apply(key)) {
            Toasts.info("Voxy: shared LODs for " + name);
        }
    }

    /** Downloads the shared map once per session before the store is first opened; true when done (or skipped). */
    private boolean sharedMapReady(String key) {
        CompletableFuture<Boolean> job = prepared.get(key);
        if (job == null) {
            Path folder = downloadMaps.get() ? VoxyAdapter.storeFolder(key) : null;
            job = folder == null ? CompletableFuture.completedFuture(false) : SharedVoxyMaps.prepare(key, folder);
            prepared.put(key, job);
        }
        if (!job.isDone()) return false;
        if (Boolean.TRUE.equals(job.getNow(false))) {
            prepared.put(key, CompletableFuture.completedFuture(false)); // toast once
            Toasts.success("Voxy: downloaded shared map");
        }
        return true;
    }

    @Override
    protected void onDisable() {
        VoxyAdapter.clear();
        levelToken = null;
    }

    private List<String> options() {
        List<String> options = new ArrayList<>(TelosLocations.DUNGEONS);
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
            for (String option : TelosLocations.DUNGEONS) {
                if (DungeonTracker.normalize(option).equals(DungeonTracker.normalize(name))) entry = option;
            }
            whitelist.toggle(entry);
            Toasts.info(name + " added to the Voxy whitelist");
        }
    }
}
