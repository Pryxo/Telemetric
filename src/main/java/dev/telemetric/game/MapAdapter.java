package dev.telemetric.game;

import com.mojang.blaze3d.platform.NativeImage;
import dev.telemetric.Telemetric;
import dev.telemetric.map.BlockLook;
import dev.telemetric.map.MapCoverage;
import dev.telemetric.map.MapProjection;
import dev.telemetric.map.MapTiles;
import dev.telemetric.map.VoxelChunk;
import dev.telemetric.map.VoxelStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MC ADAPTER for the map renderer ({@code map/}): copies loaded chunks into a {@link VoxelStore} (block states turned
 * into {@link BlockLook} ids), works out each block state's look from its model and texture (so the server resource
 * pack's look comes through), and turns baked tiles into textures.
 * <p>
 * Client thread only.
 */
public final class MapAdapter {

    private static final Map<BlockState, Integer> IDS = new IdentityHashMap<>();
    private static final List<BlockLook> LOOKS = new ArrayList<>(List.of(BlockLook.NONE));
    private static BlockLook[] table = {BlockLook.NONE};
    /** Sprite name → its 4×4 colour grid (untinted), or null if the texture can't be read. */
    private static final Map<Identifier, int[]> SPRITES = new HashMap<>();
    private static final Map<String, DynamicTexture> TEXTURES = new HashMap<>();
    private static Object modelSet;
    private static int looksGeneration;
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();

    private MapAdapter() {}

    // ------------------------------------------------------------------ chunks

    /**
     * Bumped after a resource reload: every look id handed out before is stale, so stores copied with them must be
     * cleared ({@link VoxelStore#clear}).
     */
    public static int looksGeneration() {
        checkReload();
        return looksGeneration;
    }

    /** Copies loaded chunk (cx, cz) into {@code store}. False if it isn't loaded. */
    public static boolean capture(VoxelStore store, int cx, int cz) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return false;
        LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null) return false;
        checkReload();
        int before = LOOKS.size();
        VoxelChunk copy = copy(level, chunk, cx, cz);
        if (LOOKS.size() != before) table = LOOKS.toArray(new BlockLook[0]);
        store.setLooks(table);
        store.put(copy);
        return true;
    }

    /** True if chunk (cx, cz) is loaded on the client. */
    public static boolean isLoaded(int cx, int cz) {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) != null;
    }

    private static VoxelChunk copy(ClientLevel level, LevelChunk chunk, int cx, int cz) {
        LevelChunkSection[] sections = chunk.getSections();
        int first = -1, last = -1;
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] != null && !sections[i].hasOnlyAir()) {
                if (first < 0) first = i;
                last = i;
            }
        }
        if (first < 0) return VoxelChunk.empty(cx, cz);
        int minY = (chunk.getMinSectionY() + first) << 4;
        int height = (last - first + 1) << 4;
        short[] ids = new short[height << 8];
        BlockState lastState = null;
        int lastId = 0;
        int bx = cx << 4, bz = cz << 4;
        int topLayer = -1; // highest layer with a shown block (invisible ones like barriers / light blocks don't count)
        for (int s = first; s <= last; s++) {
            LevelChunkSection section = sections[s];
            if (section == null || section.hasOnlyAir()) continue;
            int off = (s - first) << 4;
            int sy = minY + off;
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        BlockState state = section.getBlockState(lx, ly, lz);
                        if (state.isAir()) continue;
                        int id;
                        if (state == lastState) id = lastId;
                        else {
                            id = lookId(level, state, bx + lx, sy + ly, bz + lz);
                            lastState = state;
                            lastId = id;
                        }
                        if (id != 0) {
                            ids[((off + ly) << 8) | (lz << 4) | lx] = (short) id;
                            topLayer = Math.max(topLayer, off + ly);
                        }
                    }
                }
            }
        }
        if (topLayer < 0) return VoxelChunk.empty(cx, cz);
        int shown = ((topLayer >> 4) + 1) << 4; // whole sections up to the highest shown block
        if (shown < height) {
            height = shown;
            ids = java.util.Arrays.copyOf(ids, height << 8);
        }
        return new VoxelChunk(cx, cz, minY, height, ids);
    }

    private static int lookId(ClientLevel level, BlockState state, int x, int y, int z) {
        Integer id = IDS.get(state);
        if (id != null) return id;
        BlockLook look;
        try {
            look = computeLook(level, state, POS.set(x, y, z));
        } catch (RuntimeException e) {
            Telemetric.LOGGER.warn("Map: no look for {}", state, e);
            look = BlockLook.NONE;
        }
        int newId = look.kind() == BlockLook.AIR ? 0 : LOOKS.size();
        if (newId != 0) {
            if (newId >= Short.MAX_VALUE) newId = 0; // table full (never seen in practice)
            else LOOKS.add(look);
        }
        IDS.put(state, newId);
        return newId;
    }

    private static void checkReload() {
        Object current = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        if (current == modelSet) return;
        modelSet = current;
        IDS.clear();
        LOOKS.clear();
        LOOKS.add(BlockLook.NONE);
        table = new BlockLook[]{BlockLook.NONE};
        SPRITES.clear();
        looksGeneration++;
    }

    // ------------------------------------------------------------------ looks

    private static BlockLook computeLook(ClientLevel level, BlockState state, BlockPos pos) {
        FluidState fluid = state.getFluidState();
        if (state.getBlock() instanceof LiquidBlock) {
            if (fluid.getType().isSame(Fluids.LAVA)) {
                int[] grid = sprite(Identifier.withDefaultNamespace("block/lava_still"));
                return grid == null ? BlockLook.flat(0xFFD0601A, 0xFFB04A10, BlockLook.SOLID, 15)
                        : new BlockLook(grid, grid, BlockLook.SOLID, 15);
            }
            int tint = 0xFF3F76E4;
            try {
                tint = 0xFF000000 | level.getBiome(pos).value().getWaterColor();
            } catch (RuntimeException ignored) {
            }
            int[] grid = sprite(Identifier.withDefaultNamespace("block/water_still"));
            if (grid == null) return BlockLook.flat(tint, tint, BlockLook.WATER, 0);
            int[] t = tint(grid, tint);
            return new BlockLook(t, t, BlockLook.WATER, 0);
        }
        if (state.getRenderShape() == RenderShape.INVISIBLE) return BlockLook.NONE; // barriers, light blocks
        byte kind = kind(level, state, pos);
        if (kind == BlockLook.AIR) return BlockLook.NONE;

        BlockStateModelSet models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        BlockStateModel model = models.get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(42L), parts);
        int[] top = face(level, state, pos, parts, Direction.UP);
        int[] side = face(level, state, pos, parts, Direction.SOUTH);
        if (top == null || side == null) {
            int[] particle = sprite(model.particleMaterial().sprite().contents().name());
            if (top == null) top = side != null ? side : particle;
            if (side == null) side = top;
        }
        if (top == null) { // unreadable texture: vanilla map colour
            MapColor mc = state.getMapColor(level, pos);
            int c = mc == null ? 0xFF808080 : 0xFF000000 | mc.col;
            return BlockLook.flat(c, darker(c), kind, state.getLightEmission());
        }
        if (kind == BlockLook.SOLID) {
            top = opaque(top);
            side = opaque(side);
        }
        return new BlockLook(top, side, kind, state.getLightEmission());
    }

    private static byte kind(ClientLevel level, BlockState state, BlockPos pos) {
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) return BlockLook.DECOR;
        AABB box = shape.bounds();
        // posts, bars, panes and chains: drawn as a light tint, not as walls
        if (box.getXsize() < 0.5 && box.getZsize() < 0.5) return BlockLook.DECOR;
        if ((box.getXsize() < 0.3 || box.getZsize() < 0.3) && !state.canOcclude()) return BlockLook.DECOR;
        if (box.getYsize() < 0.2) return BlockLook.DECOR; // carpets, pressure plates
        if (!state.canOcclude() && isTranslucent(state)) return BlockLook.GLASS;
        return BlockLook.SOLID;
    }

    private static boolean isTranslucent(BlockState state) {
        try {
            TextureAtlasSprite s = Minecraft.getInstance().getModelManager().getBlockStateModelSet()
                    .getParticleMaterial(state).sprite();
            return s.transparency().hasTranslucent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The 4×4 grid of the first quad facing {@code dir} (tinted), or null. */
    private static int[] face(ClientLevel level, BlockState state, BlockPos pos, List<BlockStateModelPart> parts,
                              Direction dir) {
        BakedQuad quad = null;
        for (BlockStateModelPart part : parts) {
            List<BakedQuad> qs = part.getQuads(dir);
            if (!qs.isEmpty()) {
                quad = qs.getFirst();
                break;
            }
        }
        if (quad == null) {
            for (BlockStateModelPart part : parts) {
                for (BakedQuad q : part.getQuads(null)) {
                    if (q.direction() == dir) {
                        quad = q;
                        break;
                    }
                }
                if (quad != null) break;
            }
        }
        if (quad == null) return null;
        int[] grid = sprite(quad.materialInfo().sprite().contents().name());
        if (grid == null) return null;
        if (quad.materialInfo().isTinted()) {
            int tint = tintColor(level, state, pos, quad.materialInfo().tintIndex());
            if (tint != -1) grid = tint(grid, tint);
        }
        return grid;
    }

    private static int tintColor(ClientLevel level, BlockState state, BlockPos pos, int index) {
        try {
            BlockTintSource src = Minecraft.getInstance().getBlockColors().getTintSource(state, index);
            if (src == null) return -1;
            return src.colorInWorld(state, level, pos);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Reads a block texture from the resources (server pack included) and averages it into a 4×4 grid. */
    private static int[] sprite(Identifier name) {
        if (SPRITES.containsKey(name)) return SPRITES.get(name);
        int[] grid = null;
        Identifier file = Identifier.fromNamespaceAndPath(name.getNamespace(), "textures/" + name.getPath() + ".png");
        Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(file);
        if (res.isPresent()) {
            try (InputStream in = res.get().open(); NativeImage img = NativeImage.read(in)) {
                grid = average(img);
            } catch (Exception e) {
                Telemetric.debug("Map: can't read texture {}: {}", file, e.toString());
            }
        }
        SPRITES.put(name, grid);
        return grid;
    }

    private static int[] average(NativeImage img) {
        int w = img.getWidth();
        int h = Math.min(img.getHeight(), w); // animated strips: first frame
        int[] out = new int[16];
        for (int gy = 0; gy < 4; gy++) {
            for (int gx = 0; gx < 4; gx++) {
                int x0 = gx * w / 4, x1 = Math.max(x0 + 1, (gx + 1) * w / 4);
                int y0 = gy * h / 4, y1 = Math.max(y0 + 1, (gy + 1) * h / 4);
                long r = 0, g = 0, b = 0, a = 0;
                int n = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int c = img.getPixel(x, y);
                        int ca = c >>> 24;
                        n++;
                        if (ca == 0) continue;
                        r += (long) ((c >> 16) & 0xFF) * ca;
                        g += (long) ((c >> 8) & 0xFF) * ca;
                        b += (long) (c & 0xFF) * ca;
                        a += ca;
                    }
                }
                if (a == 0) continue;
                int alpha = (int) Math.min(255, a / Math.max(1, n));
                out[gy * 4 + gx] = alpha << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
            }
        }
        return out;
    }

    private static int[] tint(int[] grid, int tint) {
        int tr = (tint >> 16) & 0xFF, tg = (tint >> 8) & 0xFF, tb = tint & 0xFF;
        int[] out = new int[16];
        for (int i = 0; i < 16; i++) {
            int c = grid[i];
            out[i] = (c & 0xFF000000) | (((c >> 16) & 0xFF) * tr / 255) << 16 | (((c >> 8) & 0xFF) * tg / 255) << 8
                    | ((c & 0xFF) * tb / 255);
        }
        return out;
    }

    /** Cutout textures (leaves) on solid blocks: holes get the average of the opaque cells. */
    private static int[] opaque(int[] grid) {
        long r = 0, g = 0, b = 0;
        int n = 0;
        for (int c : grid) {
            if ((c >>> 24) < 16) continue;
            r += (c >> 16) & 0xFF;
            g += (c >> 8) & 0xFF;
            b += c & 0xFF;
            n++;
        }
        int fill = n == 0 ? 0xFF808080 : 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
        int[] out = new int[16];
        for (int i = 0; i < 16; i++) out[i] = (grid[i] >>> 24) < 16 ? fill : grid[i] | 0xFF000000;
        return out;
    }

    private static int darker(int c) {
        return (c & 0xFF000000) | (((c >> 16) & 0xFF) * 3 / 4) << 16 | (((c >> 8) & 0xFF) * 3 / 4) << 8 | ((c & 0xFF) * 3 / 4);
    }

    // ------------------------------------------------------------------ textures

    /** Uploads finished tiles (a few per frame) and releases evicted ones. Call while drawing. */
    public static void syncTextures(MapTiles tiles, int maxPerFrame) {
        for (String id : tiles.drainReleased()) release(id);
        for (MapTiles.Upload u : tiles.pollUploads(maxPerFrame)) {
            upload(u.textureId(), MapProjection.TILE_PX, u.argb());
            MapCoverage.put(u.textureId(), u.argb(), MapProjection.TILE_PX);
        }
    }

    /** Creates or updates texture {@code id} ({@code namespace:path}) from ARGB pixels. */
    public static void upload(String id, int size, int[] argb) {
        DynamicTexture tex = TEXTURES.get(id);
        if (tex == null) {
            NativeImage img = new NativeImage(size, size, false);
            tex = new DynamicTexture(() -> id, img);
            Identifier ident = Identifier.tryParse(id);
            if (ident == null) {
                tex.close();
                return;
            }
            Minecraft.getInstance().getTextureManager().register(ident, tex);
            TEXTURES.put(id, tex);
        }
        NativeImage img = tex.getPixels();
        if (img == null) return;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) img.setPixel(x, y, argb[y * size + x]);
        }
        tex.upload();
    }

    public static void release(String id) {
        MapCoverage.remove(id);
        DynamicTexture tex = TEXTURES.remove(id);
        if (tex == null) return;
        Identifier ident = Identifier.tryParse(id);
        if (ident != null) Minecraft.getInstance().getTextureManager().release(ident);
        else tex.close();
    }

    /** A texture drawn with linear filtering (smooth when stretched), e.g. the low-res map fog. */
    private static final class SmoothTexture extends DynamicTexture {
        SmoothTexture(String id, int w, int h) {
            super(() -> id, w, h, false);
            this.sampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache()
                    .getClampToEdge(com.mojang.blaze3d.textures.FilterMode.LINEAR);
        }
    }

    /**
     * Writes ARGB pixels ({@code w}×{@code h}) into the top-left corner of the smooth texture {@code id} (created at
     * {@code size}×{@code size} on first use).
     */
    public static void uploadSmooth(String id, int size, int w, int h, int[] argb) {
        DynamicTexture tex = TEXTURES.get(id);
        if (tex == null) {
            Identifier ident = Identifier.tryParse(id);
            if (ident == null) return;
            tex = new SmoothTexture(id, size, size);
            Minecraft.getInstance().getTextureManager().register(ident, tex);
            TEXTURES.put(id, tex);
        }
        NativeImage img = tex.getPixels();
        if (img == null) return;
        int cw = Math.min(w, size), ch = Math.min(h, size);
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) img.setPixel(x, y, argb[y * w + x]);
        }
        tex.upload();
    }

    // ------------------------------------------------------------------ PNG tiles (shared world maps)

    private record LoadedPng(String id, NativeImage image, byte[] coverage) {}

    private static final java.util.Set<String> PNG_PENDING = new java.util.HashSet<>();
    private static final java.util.concurrent.ConcurrentLinkedQueue<LoadedPng> PNG_DONE = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** True if texture {@code id} is registered (uploaded tile, loaded PNG). */
    public static boolean hasTexture(String id) {
        return TEXTURES.containsKey(id);
    }

    /**
     * Reads a PNG off the client thread and registers it as texture {@code id} on a later {@link #pumpPngs} call.
     * Does nothing if it's already loaded or loading.
     */
    public static void loadPng(String id, java.nio.file.Path file) {
        if (TEXTURES.containsKey(id) || !PNG_PENDING.add(id)) return;
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            NativeImage img = null;
            byte[] cov = null;
            try (InputStream in = java.nio.file.Files.newInputStream(file)) {
                img = NativeImage.read(in);
                NativeImage im = img;
                cov = MapCoverage.grid(Math.min(im.getWidth(), im.getHeight()), (x, y) -> im.getPixel(x, y) >>> 24);
            } catch (java.io.IOException | RuntimeException e) {
                Telemetric.LOGGER.warn("Map tile {} not readable: {}", file, e.toString());
            }
            PNG_DONE.add(new LoadedPng(id, img, cov));
        });
    }

    /** Registers up to {@code max} PNGs read since the last call. Client thread, while drawing. */
    public static void pumpPngs(int max) {
        LoadedPng p;
        while (max-- > 0 && (p = PNG_DONE.poll()) != null) {
            boolean wanted = PNG_PENDING.remove(p.id());
            if (p.image() == null) continue;
            Identifier ident = Identifier.tryParse(p.id());
            if (!wanted || ident == null || TEXTURES.containsKey(p.id())) {
                p.image().close();
                continue;
            }
            String id = p.id();
            DynamicTexture tex = new DynamicTexture(() -> id, p.image());
            Minecraft.getInstance().getTextureManager().register(ident, tex);
            tex.upload();
            TEXTURES.put(id, tex);
            if (p.coverage() != null) MapCoverage.put(id, p.coverage());
        }
    }

    /** Forgets a PNG still loading (its result gets closed) or releases the loaded texture. */
    public static void releasePng(String id) {
        PNG_PENDING.remove(id);
        release(id);
    }

    /** Writes ARGB pixels as a PNG (map export). Any thread. */
    public static void writePng(java.nio.file.Path file, int size, int[] argb) throws java.io.IOException {
        try (NativeImage img = new NativeImage(size, size, false)) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) img.setPixel(x, y, argb[y * size + x]);
            }
            java.nio.file.Files.createDirectories(file.getParent());
            img.writeToFile(file);
        }
    }

    /** The client's render distance in chunks. */
    public static int viewChunks() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance();
    }
}
