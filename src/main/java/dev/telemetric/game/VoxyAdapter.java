package dev.telemetric.game;

import dev.telemetric.Telemetric;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
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

    private static java.util.function.BooleanSupplier lockLods = () -> false;

    /** The module tells the ingest mixin whether LODs are locked. */
    public static void setLockLods(java.util.function.BooleanSupplier locked) {
        lockLods = locked;
    }

    /** True = Voxy must not write any LODs (only while on Telos). */
    public static boolean ingestLocked() {
        try {
            return lockLods.getAsBoolean() && ServerGate.onTelos();
        } catch (RuntimeException e) {
            return false;
        }
    }

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
            return migrateLegacyFolder(base, id);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            fail(e);
            return null;
        }
    }

    /** True while the legacy (hashed) folder name is being computed — the mixin then leaves Voxy's hash alone. */
    private static boolean legacyIds;

    /**
     * Called by the mixin from {@code WorldIdentifier.getWorldId(id)}: a readable folder name for our stable stores
     * ({@code telos:hub} seed 0 → {@code hub}, {@code telos:goblin_lair} → {@code goblin_lair}), or null = Voxy's hash.
     */
    public static String readableWorldId(Object worldIdentifier) {
        if (legacyIds || worldIdentifier == null) return null;
        try {
            Class<?> cls = worldIdentifier.getClass();
            if ((long) cls.getField("biomeSeed").get(worldIdentifier) != 0L) return null;
            Object key = cls.getField("key").get(worldIdentifier);
            if (!(key instanceof ResourceKey<?> rk)) return null;
            Identifier id = rk.identifier();
            if (!"telos".equals(id.getNamespace())) return null;
            String name = id.getPath().replaceAll("[^a-z0-9_.-]", "_");
            return name.isEmpty() ? null : name;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    /**
     * {@code <base>/<readable name>} for {@code id}. A store from before 0.20.6 (hashed name) is renamed once, so
     * existing LODs are kept.
     */
    private static Path migrateLegacyFolder(Path base, Object id) throws ReflectiveOperationException {
        Method getWorldId = id.getClass().getMethod("getWorldId");
        Path folder = base.resolve((String) getWorldId.invoke(id));
        if (java.nio.file.Files.exists(folder)) return folder;
        String legacy;
        legacyIds = true;
        try {
            legacy = (String) getWorldId.invoke(id);
        } finally {
            legacyIds = false;
        }
        Path old = base.resolve(legacy);
        if (!old.equals(folder) && java.nio.file.Files.isDirectory(old)) {
            try {
                java.nio.file.Files.move(old, folder);
                Telemetric.LOGGER.info("Voxy: renamed LOD folder {} -> {}", legacy, folder.getFileName());
            } catch (java.io.IOException e) {
                Telemetric.LOGGER.warn("Voxy: could not rename LOD folder {}: {}", legacy, e.toString());
            }
        }
        return folder;
    }

    /** Voxy's current storage base path, or null. */
    private static Path storageBase() throws ReflectiveOperationException {
        Object instance = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon").getMethod("getInstance").invoke(null);
        return instance == null ? null : (Path) instance.getClass().getMethod("getStorageBasePath").invoke(instance);
    }

    /**
     * Renderer hold: on a new Telos level Voxy would build its renderer for the per-instance store, and a moment
     * later {@link #apply} tears it down and builds it again for the shared store (each build = {@code System.gc()},
     * two {@code glFinish()}, shader compile — the lag spike). While a level is held, {@code WorldIdentifier.of}
     * returns null, so Voxy builds no renderer and ingests nothing; {@link #apply} / {@link #release} then build it
     * exactly once.
     */
    private static WeakReference<Level> decidedLevel = new WeakReference<>(null);
    private static WeakReference<Level> heldLevel = new WeakReference<>(null);

    /** Called by the mixin: true = answer null for this level (store not decided yet). */
    public static boolean held(Level level) {
        if (level == null || decidedLevel.get() == level || !sharedPathEnabled.getAsBoolean()) return false;
        try {
            if (!ServerGate.onTelos() || !"telos".equals(level.dimension().identifier().getNamespace())) return false;
        } catch (RuntimeException e) {
            return false;
        }
        if (heldLevel.get() != level) heldLevel = new WeakReference<>(level); // hot path: no allocation per call
        return true;
    }

    /** True while the current level is held (Voxy has no renderer for it yet). */
    public static boolean holding() {
        Level level = Minecraft.getInstance().level;
        return level != null && heldLevel.get() == level && decidedLevel.get() != level;
    }

    /** Ends the hold with Voxy's own store (no shared store for this level): builds the renderer once. */
    public static void release() {
        ClientLevel level = Minecraft.getInstance().level;
        if (!available() || !holding()) return;
        decidedLevel = new WeakReference<>(level);
        try {
            restartRenderer(level);
            queueReingest(level);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            fail(e);
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
            Path base = storageBase();
            if (base != null) migrateLegacyFolder(base, stable); // before Voxy opens the store
            overrideLevel = new WeakReference<>(level);
            overrideId = stable;
            overrideKey = key;
            decidedLevel = new WeakReference<>(level);
            restartRenderer(level);
            queueReingest(level);
            Telemetric.LOGGER.info("Voxy: using shared LOD store {} for {}", key, level.dimension().identifier());
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            fail(e);
            return false;
        }
    }

    /** Level changed: drops the stale override without touching the renderer (the new level may be held). */
    public static void forget() {
        overrideId = null;
        overrideKey = null;
        overrideLevel = new WeakReference<>(null);
    }

    /** Back to Voxy's own per-instance store (only when this level was overridden). */
    public static void clear() {
        if (overrideId == null) {
            release(); // held level without a shared store: Voxy's own store, renderer built now
            return;
        }
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

    /**
     * Live save-folder switch (no rejoin): when Voxy's running instance saves somewhere else than it would pick now
     * ({@code getBasePath()}, through our mixin), restart it the way Voxy's own "enabled" toggle does — renderer
     * off, instance shut down (flushes its stores), new instance, renderer on, loaded chunks re-ingested.
     * True when it restarted.
     */
    public static boolean syncBasePath() {
        if (!available()) return false;
        try {
            Class<?> common = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon");
            Object instance = common.getMethod("getInstance").invoke(null);
            Class<?> clientInstance = Class.forName("me.cortex.voxy.client.VoxyClientInstance");
            if (!clientInstance.isInstance(instance)) return false; // Voxy off / not in a session
            Path current = ((Path) clientInstance.getMethod("getStorageBasePath").invoke(instance)).normalize();
            Method pick = clientInstance.getDeclaredMethod("getBasePath");
            pick.setAccessible(true);
            Path wanted = ((Path) pick.invoke(null)).normalize();
            if (current.equals(wanted)) return false;

            Object renderer = Minecraft.getInstance().levelRenderer;
            Class<?> holder = Class.forName(HOLDER);
            if (holder.isInstance(renderer)) holder.getMethod("voxy$shutdownRenderer").invoke(renderer);
            common.getMethod("shutdownInstance").invoke(null);
            common.getMethod("createInstance").invoke(null);
            ClientLevel level = Minecraft.getInstance().level;
            if (level != null) {
                if (holder.isInstance(renderer)) {
                    holder.getMethod("voxy$setWorld", Level.class).invoke(renderer, level);
                    if (holder.getMethod("voxy$getRenderSystem").invoke(renderer) == null) {
                        holder.getMethod("voxy$createRenderer").invoke(renderer);
                    }
                }
                queueReingest(level);
            }
            Telemetric.LOGGER.info("Voxy: save folder {} -> {}", current, wanted);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            fail(e);
            return false;
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

    /** Chunks per tick for the spread re-ingest (a full render distance takes ~1 s instead of one frame). */
    private static final int REINGEST_PER_TICK = 32;
    private static final ArrayDeque<Long> reingestQueue = new ArrayDeque<>();
    private static WeakReference<ClientLevel> reingestLevel = new WeakReference<>(null);
    private static Method ingestMethod;

    /** Queues the loaded chunks around the player for re-ingest (nearest first); {@link #tick} feeds them to Voxy. */
    private static void queueReingest(ClientLevel level) {
        reingestQueue.clear();
        reingestLevel = new WeakReference<>(level);
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        int cx = player.blockPosition().getX() >> 4, cz = player.blockPosition().getZ() >> 4;
        int r = Minecraft.getInstance().options.getEffectiveRenderDistance() + 1;
        for (int ring = 0; ring <= r; ring++) {
            for (int x = cx - ring; x <= cx + ring; x++) {
                for (int z = cz - ring; z <= cz + ring; z++) {
                    if (Math.max(Math.abs(x - cx), Math.abs(z - cz)) == ring) reingestQueue.add(ChunkPos.pack(x, z));
                }
            }
        }
    }

    /** Called every client tick: feeds a few queued chunks to Voxy's ingest. */
    public static void tick() {
        if (reingestQueue.isEmpty()) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (!available() || level == null || reingestLevel.get() != level) {
            reingestQueue.clear();
            return;
        }
        try {
            if (ingestMethod == null) {
                ingestMethod = Class.forName(INGEST).getMethod("tryAutoIngestChunk", LevelChunk.class);
            }
            for (int i = 0; i < REINGEST_PER_TICK && !reingestQueue.isEmpty(); i++) {
                long pos = reingestQueue.poll();
                var chunk = level.getChunkSource().getChunk(ChunkPos.getX(pos), ChunkPos.getZ(pos),
                        ChunkStatus.FULL, false);
                if (chunk instanceof LevelChunk lc) ingestMethod.invoke(null, lc);
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            reingestQueue.clear();
            fail(e);
        }
    }

    private static void fail(Throwable e) {
        broken = true;
        overrideId = null;
        overrideKey = null;
        Telemetric.LOGGER.error("Voxy bridge failed (incompatible Voxy version?) — Voxy Modifier disabled", e);
    }
}
