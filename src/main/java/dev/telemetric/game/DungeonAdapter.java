package dev.telemetric.game;

import dev.telemetric.dungeon.Cell;
import dev.telemetric.dungeon.ChunkCells;
import dev.telemetric.dungeon.DungeonMap;
import dev.telemetric.dungeon.Point;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * MC ADAPTER — everything the dungeon features read from the game: which dimension we are in,
 * chunk scanning into {@link ChunkCells}, and glowing "treasure" entities.
 * <p>
 * <b>Scanning cost.</b> Chunks are queued when they load (and the area around the player is re-queued
 * now and then, for doors/gates that open). {@link #scanPending} drains the queue under a time budget
 * on the client thread. Per chunk only non-empty sections are read, each block state is classified once
 * (identity cache), and the result is a few shorts per block column.
 */
public final class DungeonAdapter {

    private static final String DUNGEON_NAMESPACE = "telos";
    private static final String DUNGEON_PREFIX = "dungeon/";
    /** Named instance worlds that are dungeons too (dimension path prefixes under {@code telos:}). */
    private static final List<String> INSTANCE_PREFIXES = List.of("neo_eden/");

    private static final LinkedHashSet<Long> PENDING = new LinkedHashSet<>();
    private static final Map<BlockState, Byte> CLASS_CACHE = new IdentityHashMap<>();
    /** A block emitting this many yellow dust packets is a jump pad (one stray puff is not). */
    private static final int PAD_MIN_HITS = 3;
    /** Yellow dust packets per source block (packed block pos; client thread only). */
    private static final Map<Long, Integer> PAD_HITS = new java.util.HashMap<>();
    /** Pads confirmed since the last {@link #drainJumpPads} (packed {@link Cell} positions). */
    private static final List<Long> PAD_NEW = new ArrayList<>();
    private static boolean active;
    private static boolean registered;

    private DungeonAdapter() {}

    /** Hooks chunk-load events (call once at init). */
    public static void register() {
        if (registered) return;
        registered = true;
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            if (active) enqueue(chunk.getPos().x(), chunk.getPos().z());
        });
    }

    // ------------------------------------------------------------------ location

    private static ClientLevel level() {
        return Minecraft.getInstance().level;
    }

    /** Dimension id ("telos:dungeon/1"), or null outside a world. */
    public static String dimensionId() {
        ClientLevel level = level();
        return level == null ? null : level.dimension().identifier().toString();
    }

    /**
     * True inside a Telos dungeon instance: {@code telos:dungeon/<n>}, or a named instance world in
     * {@link #INSTANCE_PREFIXES} ({@code telos:neo_eden/<n>}).
     */
    public static boolean isInDungeon() {
        ClientLevel level = level();
        if (level == null || Minecraft.getInstance().player == null) return false;
        Identifier id = level.dimension().identifier();
        if (!DUNGEON_NAMESPACE.equals(id.getNamespace())) return false;
        String path = id.getPath();
        if (path.startsWith(DUNGEON_PREFIX)) return true;
        for (String prefix : INSTANCE_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Plain custom names of visible-named armor stands within {@code range} blocks of the player (server
     * nameplates like the renown count "(75/110)"). Empty outside a world.
     */
    public static List<String> nameplates(double range) {
        ClientLevel level = level();
        LocalPlayer self = Minecraft.getInstance().player;
        List<String> out = new ArrayList<>();
        if (level == null || self == null) return out;
        double r2 = range * range;
        for (Entity e : level.entitiesForRendering()) {
            if (!(e instanceof net.minecraft.world.entity.decoration.ArmorStand stand) || stand.isRemoved()) continue;
            if (!stand.isCustomNameVisible() || stand.getCustomName() == null) continue;
            if (stand.distanceToSqr(self) > r2) continue;
            out.add(stand.getCustomName().getString());
        }
        return out;
    }

    /** Opaque token that changes whenever a new world/instance is joined (even with the same dimension id). */
    public static Object levelToken() {
        return level();
    }

    /**
     * Readable name of the current dungeon, or null: the location name ({@link PlayerAdapter#locationName()} —
     * HUD box made part-specific by {@link TelosLocations}, else the biome name).
     */
    public static String dungeonName() {
        return PlayerAdapter.locationName();
    }

    /** The HUD box location right now (may still be the previous place for a moment after a teleport), or null. */
    public static String hudLocation() {
        return BossBarAdapter.hudLocation();
    }

    /** Player feet position, or null. */
    public static Point playerPos() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null ? null : new Point(p.getX(), p.getY(), p.getZ());
    }

    /**
     * The dungeon's return portal (where every Telos dungeon starts), or null if none is loaded. Its anchor is an
     * invisible evoker wearing orange stained glass, standing still on the portal (verified in Midnight Stronghold,
     * 2026-11-14: at the spawn (8, 100, 8) together with a parrot; the fire models around the player are the
     * player's own effects and follow them). The one nearest the player wins.
     */
    public static Point returnPortalPos() {
        ClientLevel level = level();
        LocalPlayer self = Minecraft.getInstance().player;
        if (level == null || self == null) return null;
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : level.entitiesForRendering()) {
            if (e.isRemoved() || e.getType() != net.minecraft.world.entity.EntityType.EVOKER || !e.isInvisible()) continue;
            if (!(e instanceof net.minecraft.world.entity.LivingEntity living)) continue;
            if (!living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)
                    .is(net.minecraft.world.item.Items.ORANGE_STAINED_GLASS)) continue;
            double d = e.distanceToSqr(self);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best == null ? null : new Point(best.getX(), best.getY(), best.getZ());
    }

    // ------------------------------------------------------------------ scanning

    /** Starts/stops listening for chunk loads. Starting queues every chunk already loaded around the player. */
    public static void setActive(boolean on) {
        if (on == active) return;
        active = on;
        PENDING.clear();
        PAD_HITS.clear();
        PAD_NEW.clear();
        CLASS_CACHE.clear(); // block tags (climbable) come from the server and may differ per server
        if (on) enqueueAround(Minecraft.getInstance().options.getEffectiveRenderDistance() + 1);
    }

    public static boolean isActive() {
        return active;
    }

    /** Queues the loaded chunks within {@code radius} chunks of the player (rescans already known ones). */
    public static void enqueueAround(int radius) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        int pcx = p.getBlockX() >> 4, pcz = p.getBlockZ() >> 4;
        // nearest first so the route around the player is ready quickly
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == r) enqueue(pcx + dx, pcz + dz);
                }
            }
        }
    }

    private static void enqueue(int chunkX, int chunkZ) {
        PENDING.add(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
    }

    public static int pendingCount() {
        return PENDING.size();
    }

    /** Scans queued chunks into the map until the time budget is used. Returns chunks scanned. */
    public static int scanPending(DungeonMap map, long budgetNanos) {
        ClientLevel level = level();
        if (level == null || PENDING.isEmpty()) return 0;
        long deadline = System.nanoTime() + budgetNanos;
        int done = 0;
        Iterator<Long> it = PENDING.iterator();
        while (it.hasNext() && System.nanoTime() < deadline) {
            long key = it.next();
            it.remove();
            int cx = (int) (key >> 32), cz = (int) key;
            LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            map.put(scan(chunk, cx, cz));
            done++;
        }
        return done;
    }

    private static ChunkCells scan(LevelChunk chunk, int cx, int cz) {
        LevelChunkSection[] sections = chunk.getSections();
        int first = -1, last = -1;
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] != null && !sections[i].hasOnlyAir()) {
                if (first < 0) first = i;
                last = i;
            }
        }
        byte[][] classes = new byte[256][];
        if (first < 0) return ChunkCells.build(cx, cz, 0, classes); // empty chunk: all open, no floor
        int baseY = (chunk.getMinSectionY() + first) << 4;
        int height = (last - first + 1) * 16 + 3; // +3: open head room above the top section
        for (int col = 0; col < 256; col++) classes[col] = new byte[height]; // 0 == OPEN
        for (int s = first; s <= last; s++) {
            LevelChunkSection section = sections[s];
            if (section == null || section.hasOnlyAir()) continue;
            int off = (s - first) << 4;
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        BlockState state = section.getBlockState(lx, ly, lz);
                        if (state.isAir()) continue;
                        classes[(lz << 4) | lx][off + ly] = classify(state);
                    }
                }
            }
        }
        return ChunkCells.build(cx, cz, baseY, classes);
    }

    private static byte classify(BlockState state) {
        Byte cached = CLASS_CACHE.get(state);
        if (cached != null) return cached;
        byte c = computeClass(state);
        CLASS_CACHE.put(state, c);
        return c;
    }

    private static byte computeClass(BlockState state) {
        Fluid fluid = state.getFluidState().getType();
        if (fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA) return ChunkCells.HAZARD;
        if (state.getBlock() instanceof BaseFireBlock) return ChunkCells.HAZARD;
        if (state.is(BlockTags.CLIMBABLE)) return ChunkCells.CLIMB;
        // open trapdoors are thin side panels (3/16): Telos lines drop shafts with them (e.g. under a pond)
        if (state.getBlock() instanceof TrapDoorBlock && state.getValue(TrapDoorBlock.OPEN)) return ChunkCells.OPEN;
        VoxelShape shape;
        try {
            shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException e) {
            return ChunkCells.SOLID;
        }
        if (shape.isEmpty()) return ChunkCells.OPEN;
        if (isSlipThrough(shape)) return ChunkCells.OPEN;
        double top = shape.max(Direction.Axis.Y);
        if (top <= 0.5) return ChunkCells.LOW;
        if (top > 1.0) return ChunkCells.TALL;
        return ChunkCells.SOLID;
    }

    /** Max footprint width (narrow axis) that leaves ≥ 0.6 (player width) free between two such blocks side by side. */
    private static final double SLIP_WIDTH = 0.4;

    /**
     * A thin post that spans the block on neither horizontal axis — unconnected iron bars / panes / fences, or one
     * arm only (end of a line). Placed side by side (Telos places them unconnected) the gaps between them are
     * wider than the player, so routing treats the cell as open. Lines (spanning a side to side), corners and
     * wall posts (0.5 wide) still block.
     */
    private static boolean isSlipThrough(VoxelShape shape) {
        double minX = shape.min(Direction.Axis.X), maxX = shape.max(Direction.Axis.X);
        double minZ = shape.min(Direction.Axis.Z), maxZ = shape.max(Direction.Axis.Z);
        boolean spansX = minX <= 0.01 && maxX >= 0.99, spansZ = minZ <= 0.01 && maxZ >= 0.99;
        return !spansX && !spansZ && Math.min(maxX - minX, maxZ - minZ) <= SLIP_WIDTH;
    }

    // ------------------------------------------------------------------ treasures

    // ------------------------------------------------------------------ jump pads

    /**
     * Called by {@code ClientPacketListenerMixin} for every server particle packet (client thread).
     * Telos jump pads are invisible: only a fountain of {@code minecraft:dust} marks them. Colour differs per
     * dungeon (verified in game): yellow in Treasure Cave (base ≈ (0.95, 0.9, 0.53), ~1.5 above the floor),
     * white in Frozen Ruins (spread over ~3×3 blocks, 0..2.5 above the floor).
     */
    public static void onParticles(ParticleOptions options, double x, double y, double z) {
        if (!active || !(options instanceof DustParticleOptions dust)) return;
        Vector3f c = dust.getColor();
        if (!isPadColour(c.x(), c.y(), c.z())) return;
        long key = Cell.pack((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        int hits = PAD_HITS.merge(key, 1, Integer::sum);
        if (hits == PAD_MIN_HITS) PAD_NEW.add(key);
    }

    /** Jump pads confirmed since the last call (particle source block, {@link Cell#pack}). */
    public static List<Long> drainJumpPads() {
        if (PAD_NEW.isEmpty()) return List.of();
        List<Long> out = new ArrayList<>(PAD_NEW);
        PAD_NEW.clear();
        return out;
    }

    /** Jump pad dust: yellow or white / light grey. */
    static boolean isPadColour(float r, float g, float b) {
        return isPadYellow(r, g, b) || isPadWhite(r, g, b);
    }

    /** Yellow: red and green strong and close, blue clearly lower (not white, orange, lime or red). */
    static boolean isPadYellow(float r, float g, float b) {
        return r >= 0.6f && g >= 0.5f && Math.abs(r - g) <= 0.3f && b <= Math.min(r, g) * 0.75f;
    }

    /** White / light grey: all channels bright and nearly equal (no tint). */
    static boolean isPadWhite(float r, float g, float b) {
        float lo = Math.min(r, Math.min(g, b)), hi = Math.max(r, Math.max(g, b));
        return lo >= 0.55f && hi - lo <= 0.15f;
    }

    /**
     * A player for the dungeon minimap.
     *
     * @param skin skin texture id ({@code "namespace:path"}, 64×64; face at 8,8 and hat layer at 40,8)
     * @param yaw  body yaw in degrees (0 = south / +Z)
     */
    public record PlayerMarker(String skin, double x, double z, float yaw, boolean self) {}

    /** Every player in the current level (the local player included, flagged {@code self}). */
    public static List<PlayerMarker> players() {
        ClientLevel level = level();
        LocalPlayer self = Minecraft.getInstance().player;
        List<PlayerMarker> out = new ArrayList<>();
        if (level == null) return out;
        for (net.minecraft.client.player.AbstractClientPlayer p : level.players()) {
            if (p.isRemoved() || p.isSpectator()) continue;
            String skin = p.getSkin().body().texturePath().toString();
            out.add(new PlayerMarker(skin, p.getX(), p.getZ(), p.getYRot(), p == self));
        }
        return out;
    }

    /**
     * Positions (feet) of entities that glow with a yellow/gold outline — Telos marks treasure with it.
     * The glow colour is the entity's team colour (or a display entity's glow override).
     */
    public static List<Point> yellowGlowingEntities() {
        ClientLevel level = level();
        LocalPlayer self = Minecraft.getInstance().player;
        List<Point> out = new ArrayList<>();
        if (level == null) return out;
        for (Entity e : level.entitiesForRendering()) {
            if (e == self || e.isRemoved() || !e.isCurrentlyGlowing()) continue;
            // dropped loot can share the glow colour — only the treasure entity itself counts
            if (e instanceof net.minecraft.world.entity.item.ItemEntity) continue;
            if (!isYellow(e.getTeamColor())) continue;
            out.add(new Point(e.getX(), e.getY(), e.getZ()));
        }
        return out;
    }

    /** A mob nameplate found in the level: the text display's entity id and position. */
    public record NamePlate(int id, Point pos) {}

    /**
     * Nameplates (text displays) whose text spells {@code name} — {@code name} normalised like
     * {@link #normalizeName}. Telos draws elite/goal mob names in a custom font: {@code A}–{@code Z} =
     * U+16004–U+1601D, space U+15FF9, brackets U+1601F/U+16020 (verified: "Keeper of Dusk", Midnight Stronghold);
     * plain Latin letters are read too.
     */
    public static List<NamePlate> namePlates(String name) {
        ClientLevel level = level();
        List<NamePlate> out = new ArrayList<>();
        if (level == null || name == null || name.isEmpty()) return out;
        for (Entity e : level.entitiesForRendering()) {
            if (e.isRemoved() || !(e instanceof net.minecraft.world.entity.Display.TextDisplay td)) continue;
            net.minecraft.network.chat.Component text = ((dev.telemetric.mixin.TextDisplayAccessor) td).telemetric$getText();
            if (text != null && name.equals(plateLetters(text.getString()))) {
                out.add(new NamePlate(e.getId(), new Point(e.getX(), e.getY(), e.getZ())));
            }
        }
        return out;
    }

    /** True while the chunk holding block column (x, z) is loaded on the client. */
    public static boolean isLoaded(double x, double z) {
        ClientLevel level = level();
        return level != null && level.getChunkSource().hasChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
    }

    /** Lower-case letters/digits only ("Keeper of Dusk" → "keeperofdusk"). */
    public static String normalizeName(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static final int PLATE_A = 0x16004, PLATE_Z = PLATE_A + 25;

    /** Nameplate text → lower-case letters (Telos plate font decoded, everything else dropped). */
    static String plateLetters(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (cp >= PLATE_A && cp <= PLATE_Z) b.append((char) ('a' + (cp - PLATE_A)));
            else if (cp < 0x80 && Character.isLetterOrDigit(cp)) b.append(Character.toLowerCase((char) cp));
            i += Character.charCount(cp);
        }
        return b.toString();
    }

    /**
     * Yellow/gold hues: red and green both strong, blue clearly lower. Telos treasure chests glow
     * {@code #C3CA22} (verified in game, Treasure Cave); gold {@code #FFAA00} and yellow {@code #FFFF55}
     * also pass, orange/red/lime/white do not.
     */
    static boolean isYellow(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return r >= 150 && g >= 130 && b <= Math.min(r, g) * 0.6;
    }
}
