package dev.telemetric.game;

import dev.telemetric.Telemetric;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Optional Voxy bridge (soft dependency, all reflection — Telemetric does not compile against Voxy).
 * <p>
 * Voxy stores LODs per world under {@code sha256(biomeSeed + dimensionKey)[0..32]} (see
 * {@code me.cortex.voxy.commonImpl.WorldIdentifier#getWorldId}). Telos dimensions carry an instance number
 * ({@code telos:neo_eden/1}), so every instance of a dungeon gets a new, empty LOD store. This adapter swaps the
 * identifier of the current level for a stable one ({@code telos:<dungeon_name>}, seed 0) — every instance of that
 * dungeon then shares one store. {@code mixin/voxy/VoxyWorldIdentifierMixin} routes {@code WorldIdentifier.of(level)}
 * through {@link #override}.
 * <p>
 * Verified against Voxy 0.2.18-beta (26.1.2): {@code WorldIdentifier(ResourceKey<Level>, long, ResourceKey<DimensionType>)},
 * public field {@code dimension}, {@code IVoxyRenderSystemHolder#voxy$setWorld(Level)} / {@code voxy$createRenderer()}
 * on {@code LevelRenderer}, static {@code VoxelIngestService.tryAutoIngestChunk(LevelChunk)}.
 */
public final class VoxyAdapter {

    private static final String WORLD_ID = "me.cortex.voxy.commonImpl.WorldIdentifier";
    private static final String HOLDER = "me.cortex.voxy.client.core.IVoxyRenderSystemHolder";
    private static final String INGEST = "me.cortex.voxy.common.world.service.VoxelIngestService";

    private static Boolean loaded;
    private static boolean broken;

    /** Level whose identifier is overridden (weak, so a closed level is never kept alive). */
    private static WeakReference<Level> overrideLevel = new WeakReference<>(null);
    private static Object overrideId;
    private static String overrideKey;

    private VoxyAdapter() {}

    /** True when Voxy is installed and the bridge has not failed. */
    public static boolean available() {
        if (loaded == null) loaded = FabricLoader.getInstance().isModLoaded("voxy");
        return loaded && !broken;
    }

    /** Telos-wide Voxy save folder name (instead of one per region address). */
    public static final String SHARED_FOLDER = "telosrealms";
    private static java.util.function.BooleanSupplier sharedPathEnabled = () -> false;

    /** The module tells the base-path mixin whether the shared Telos folder is wanted. */
    public static void setSharedPathEnabled(java.util.function.BooleanSupplier enabled) {
        sharedPathEnabled = enabled;
    }

    /**
     * Called by the mixin when Voxy picks its save folder (on join): {@code .voxy/saves/telosrealms} on any Telos
     * address ({@link ServerGate}), else null = Voxy's own per-address folder.
     */
    public static Path basePathOverride() {
        try {
            if (!sharedPathEnabled.getAsBoolean() || !ServerGate.onTelos()) return null;
            return Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy").resolve("saves")
                    .resolve(SHARED_FOLDER).toAbsolutePath();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Voxy's folder for the stable store {@code key} ({@code <save base>/<world id>}), or null without Voxy. */
    public static Path storeFolder(String key) {
        if (!available()) return null;
        Identifier path = Identifier.tryParse(key);
        if (path == null) return null;
        try {
            Object instance = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon").getMethod("getInstance").invoke(null);
            if (instance == null) return null;
            Path base = (Path) instance.getClass().getMethod("getStorageBasePath").invoke(instance);
            Class<?> cls = Class.forName(WORLD_ID);
            Object id = cls.getConstructor(ResourceKey.class, long.class, ResourceKey.class)
                    .newInstance(ResourceKey.create(Registries.DIMENSION, path), 0L, null);
            return base.resolve((String) cls.getMethod("getWorldId").invoke(id));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            fail(e);
            return null;
        }
    }

    /** Called by the mixin from {@code WorldIdentifier.of(level)}: the stable identifier, or null = Voxy's own. */
    public static Object override(Level level) {
        Object id = overrideId;
        return id != null && level != null && overrideLevel.get() == level ? id : null;
    }

    /** The stable key in use right now ("telos:goblin_lair"), or null. */
    public static String activeKey() {
        return overrideId != null && overrideLevel.get() == Minecraft.getInstance().level ? overrideKey : null;
    }

    /** "Rustborn Kingdom: Part I" → "telos:rustborn_kingdom_part_i". */
    public static String stableKey(String dungeonName) {
        String path = dungeonName.toLowerCase(Locale.ROOT).replace("'", "")
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return path.isEmpty() ? null : "telos:" + path;
    }

    /**
     * Points Voxy at the stable store {@code key} for the current level, restarts its renderer and re-ingests the
     * loaded chunks (they were ingested into the instance store before). No-op when already active.
     */
    public static boolean apply(String key) {
        if (!available()) return false;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return false;
        if (key.equals(activeKey())) return true;
        Identifier path = Identifier.tryParse(key);
        if (path == null) return false;
        try {
            Class<?> cls = Class.forName(WORLD_ID);
            Object original = cls.getMethod("of", Level.class).invoke(null, level); // only for its dimension type
            Object dimType = original == null ? null : cls.getField("dimension").get(original);
            Constructor<?> ctor = cls.getConstructor(ResourceKey.class, long.class, ResourceKey.class);
            Object stable = ctor.newInstance(ResourceKey.create(Registries.DIMENSION, path), 0L, dimType);
            overrideLevel = new WeakReference<>(level);
            overrideId = stable;
            overrideKey = key;
            restartRenderer(level);
            reingestLoaded(level);
            Telemetric.LOGGER.info("Voxy: using shared LOD store {} for {}", key, level.dimension().identifier());
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            fail(e);
            return false;
        }
    }

    /** Back to Voxy's own per-instance store (only when this level was overridden). */
    public static void clear() {
        if (overrideId == null) return;
        Level level = overrideLevel.get();
        overrideId = null;
        overrideKey = null;
        overrideLevel = new WeakReference<>(null);
        if (available() && level != null && level == Minecraft.getInstance().level) {
            try {
                restartRenderer(level);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                fail(e);
            }
        }
    }

    private static void restartRenderer(Level level) throws ReflectiveOperationException {
        Object renderer = Minecraft.getInstance().levelRenderer;
        Class<?> holder = Class.forName(HOLDER);
        if (!holder.isInstance(renderer)) return;
        holder.getMethod("voxy$setWorld", Level.class).invoke(renderer, level); // shuts down when the id changed
        Method getter = holder.getMethod("voxy$getRenderSystem");
        if (getter.invoke(renderer) == null) holder.getMethod("voxy$createRenderer").invoke(renderer);
    }

    private static void reingestLoaded(ClientLevel level) throws ReflectiveOperationException {
        Method ingest = Class.forName(INGEST).getMethod("tryAutoIngestChunk", LevelChunk.class);
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        int cx = player.blockPosition().getX() >> 4, cz = player.blockPosition().getZ() >> 4;
        int r = Minecraft.getInstance().options.getEffectiveRenderDistance() + 1;
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk instanceof LevelChunk lc) ingest.invoke(null, lc);
            }
        }
    }

    private static void fail(Throwable e) {
        broken = true;
        overrideId = null;
        overrideKey = null;
        Telemetric.LOGGER.error("Voxy bridge failed (incompatible Voxy version?) — Voxy Modifier disabled", e);
    }
}
