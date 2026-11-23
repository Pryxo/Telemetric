package dev.telemetric.game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

/**
 * MC ADAPTER: the blocks around you as textured faces, for the Settings window's 3D previews (the "World" option).
 * Every block model quad (same quads, UVs, tint and face shading as the world) in a box of {@code radius} blocks
 * around your feet, {@link #BELOW} levels down and {@link #ABOVE} up. Faces hidden by a full opaque neighbour inside
 * the box are left out; the box's outer faces are kept, so it reads as a small diorama. Built at most once per
 * {@link #REFRESH_MS} (or when you move to another block / change the radius), drawn every frame by the preview.
 */
public final class WorldPreviewAdapter {

    /** Levels below / above your feet block. */
    public static final int BELOW = 3, ABOVE = 2;
    private static final long REFRESH_MS = 1000;
    private static final int MAX_FACES = 12_000;

    /**
     * One face: 4 corners {x, y, z} relative to {@link Snapshot}'s origin block (blocks), the atlas UVs per corner,
     * the colour (tint × face shade, opaque) and the face normal. {@code ghost} = above your feet (drawn faded).
     */
    public record Face(float[] pos, float[] uv, int argb, float nx, float ny, float nz, boolean ghost) {}

    /** The faces and the block their coordinates start from. */
    public record Snapshot(int ox, int oy, int oz, List<Face> faces) {
        static final Snapshot EMPTY = new Snapshot(0, 0, 0, List.of());
    }

    private static Snapshot cache = Snapshot.EMPTY;
    private static long builtAt;
    private static BlockPos builtAround;
    private static int builtRadius = -1;
    private static ClientLevel builtLevel;

    private WorldPreviewAdapter() {}

    /** Your feet right now (interpolated), in world coordinates, or null outside a world. */
    public static double[] feet() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        Vec3 p = mc.player.getPosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        return new double[] {p.x, p.y, p.z};
    }

    /** The block atlas id ("minecraft:textures/atlas/blocks.png"), for {@code Render2D#blockFaces}. */
    public static net.minecraft.resources.Identifier atlas() {
        return TextureAtlas.LOCATION_BLOCKS;
    }

    /** The faces around you within {@code radius} blocks (cached), or an empty snapshot outside a world. */
    public static Snapshot around(int radius) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            cache = Snapshot.EMPTY;
            builtLevel = null;
            return cache;
        }
        BlockPos at = mc.player.blockPosition();
        long now = System.currentTimeMillis();
        if (level == builtLevel && at.equals(builtAround) && radius == builtRadius && now - builtAt < REFRESH_MS) {
            return cache;
        }
        builtLevel = level;
        builtAround = at;
        builtRadius = radius;
        builtAt = now;
        try {
            cache = build(mc, level, at, radius, mc.player.getY());
        } catch (RuntimeException e) {
            cache = Snapshot.EMPTY; // a model or tint source we can't read: no world rather than a crash
        }
        return cache;
    }

    private static Snapshot build(Minecraft mc, ClientLevel level, BlockPos at, int radius, double feetY) {
        int ghostFrom = (int) Math.ceil(feetY - 0.01);
        List<Face> out = new ArrayList<>();
        List<BlockStateModelPart> parts = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        for (int dy = -BELOW; dy <= ABOVE; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    pos.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) continue;
                    BlockStateModel model = mc.getModelManager().getBlockStateModelSet().get(state);
                    if (model == null) continue;
                    parts.clear();
                    model.collectParts(RandomSource.create(state.getSeed(pos)), parts);
                    boolean ghost = pos.getY() >= ghostFrom;
                    for (BlockStateModelPart part : parts) {
                        for (Direction d : Direction.values()) {
                            if (hidden(level, next, pos, d, at, radius)) continue;
                            for (BakedQuad q : part.getQuads(d)) add(out, mc, level, state, pos, at, q, ghost);
                        }
                        for (BakedQuad q : part.getQuads(null)) add(out, mc, level, state, pos, at, q, ghost);
                    }
                    if (out.size() > MAX_FACES) return new Snapshot(at.getX(), at.getY(), at.getZ(), out);
                }
            }
        }
        return new Snapshot(at.getX(), at.getY(), at.getZ(), out);
    }

    /** A face toward {@code d} is hidden when the neighbour there is a full opaque block inside the box. */
    private static boolean hidden(ClientLevel level, BlockPos.MutableBlockPos next, BlockPos pos, Direction d,
                                  BlockPos at, int radius) {
        next.setWithOffset(pos, d);
        if (Math.abs(next.getX() - at.getX()) > radius || Math.abs(next.getZ() - at.getZ()) > radius) return false;
        int dy = next.getY() - at.getY();
        if (dy < -BELOW || dy > ABOVE) return false;
        return level.getBlockState(next).isSolidRender();
    }

    private static void add(List<Face> out, Minecraft mc, ClientLevel level, BlockState state, BlockPos pos,
                            BlockPos origin, BakedQuad q, boolean ghost) {
        BakedQuad.MaterialInfo mi = q.materialInfo();
        TextureAtlasSprite sprite = mi.sprite();
        if (sprite == null || !TextureAtlas.LOCATION_BLOCKS.equals(sprite.atlasLocation())) return;
        float ox = pos.getX() - origin.getX(), oy = pos.getY() - origin.getY(), oz = pos.getZ() - origin.getZ();
        float[] p = new float[12];
        float[] uv = new float[8];
        for (int i = 0; i < 4; i++) {
            Vector3fc v = q.position(i);
            p[i * 3] = ox + v.x();
            p[i * 3 + 1] = oy + v.y();
            p[i * 3 + 2] = oz + v.z();
            long packed = q.packedUV(i);
            uv[i * 2] = UVPair.unpackU(packed);
            uv[i * 2 + 1] = UVPair.unpackV(packed);
        }
        int rgb = 0xFFFFFF;
        if (mi.isTinted()) {
            BlockTintSource tint = mc.getBlockColors().getTintSource(state, mi.tintIndex());
            if (tint != null) rgb = tint.colorInWorld(state, level, pos) & 0xFFFFFF;
        }
        Direction d = q.direction();
        float shade = !mi.shade() ? 1f : switch (d) {
            case UP -> 1f;
            case DOWN -> 0.5f;
            case NORTH, SOUTH -> 0.8f;
            case EAST, WEST -> 0.6f;
        };
        int r = Math.round(((rgb >> 16) & 0xFF) * shade), g = Math.round(((rgb >> 8) & 0xFF) * shade),
                b = Math.round((rgb & 0xFF) * shade);
        out.add(new Face(p, uv, 0xFF000000 | r << 16 | g << 8 | b, d.getStepX(), d.getStepY(), d.getStepZ(), ghost));
    }
}
