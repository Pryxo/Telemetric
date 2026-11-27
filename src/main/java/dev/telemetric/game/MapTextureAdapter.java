package dev.telemetric.game;

import com.mojang.blaze3d.platform.NativeImage;
import dev.telemetric.Telemetric;
import dev.telemetric.data.MapImages;
import dev.telemetric.data.MapTable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GPU textures of the Telos Map images ({@link MapImages}). Each image is registered as
 * {@code telemetric:map/<hash>_<level>}: level 0 is the image itself, every further level is half the size
 * (box-filtered), so a zoomed-out map stays smooth instead of shimmering under the nearest-pixel sampler.
 * Decoding and downscaling run off-thread, the upload on the render thread. Textures not drawn for
 * {@link #IDLE_MS} are released again ({@link #releaseIdle}).
 */
public final class MapTextureAdapter {

    /** Downscaled copies (1/2, 1/4, 1/8). */
    private static final int LEVELS = 4;
    private static final long IDLE_MS = 120_000;

    private static final class Entry {
        volatile List<Identifier> ids;   // null while loading
        volatile boolean failed;
        volatile long lastUsed = System.currentTimeMillis();
        int width, height;
    }

    private static final Map<String, Entry> TEXTURES = new ConcurrentHashMap<>();

    private MapTextureAdapter() {}

    private static String key(MapTable.Tile t) {
        String h = t.sha256().isEmpty() ? Integer.toHexString(t.file().hashCode()) : t.sha256().substring(0, Math.min(12, t.sha256().length()));
        return h.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The texture id to draw {@code tile} with, or null while it's still downloading / loading (that starts here).
     *
     * @param blocksPerPixel how many world blocks one screen pixel covers right now; picks the downscaled level
     */
    public static String texture(MapTable.Tile tile, double blocksPerPixel) {
        String k = key(tile);
        Entry e = TEXTURES.get(k);
        if (e == null) {
            Path file = MapImages.path(tile);
            if (file == null) return null;
            Entry fresh = new Entry();
            if (TEXTURES.putIfAbsent(k, fresh) == null) load(k, fresh, file);
            return null;
        }
        List<Identifier> ids = e.ids;
        if (ids == null || ids.isEmpty()) return null;
        e.lastUsed = System.currentTimeMillis();
        double texelsPerPixel = blocksPerPixel * e.width / (double) tile.width();
        int level = texelsPerPixel <= 1.5 ? 0 : (int) Math.floor(Math.log(texelsPerPixel / 1.5) / Math.log(2)) + 1;
        return ids.get(Math.clamp(level, 0, ids.size() - 1)).toString();
    }

    /** True if the tile's image couldn't be downloaded or decoded. */
    public static boolean failed(MapTable.Tile tile) {
        Entry e = TEXTURES.get(key(tile));
        return e != null && e.failed || MapImages.status(tile) == MapImages.Status.FAILED;
    }

    /** Lets failed images load again (called when the map opens). */
    public static void retryFailed() {
        TEXTURES.values().removeIf(e -> e.failed);
        MapImages.retryFailed();
    }

    private static void load(String key, Entry entry, Path file) {
        CompletableFuture.supplyAsync(() -> {
            List<NativeImage> levels = new ArrayList<>(LEVELS);
            try (InputStream in = Files.newInputStream(file)) {
                NativeImage img = NativeImage.read(in);
                levels.add(img);
                for (int i = 1; i < LEVELS; i++) {
                    NativeImage prev = levels.getLast();
                    if (prev.getWidth() < 64 || prev.getHeight() < 64) break;
                    levels.add(half(prev));
                }
                return levels;
            } catch (Exception ex) {
                for (NativeImage n : levels) n.close();
                throw new RuntimeException(ex);
            }
        }).whenComplete((levels, err) -> {
            if (err != null) {
                Telemetric.LOGGER.warn("Map image {} not decoded: {}", file.getFileName(), err.toString());
                entry.failed = true;
                return;
            }
            Minecraft.getInstance().execute(() -> upload(key, entry, levels));
        });
    }

    private static void upload(String key, Entry entry, List<NativeImage> levels) {
        if (TEXTURES.get(key) != entry) {
            for (NativeImage n : levels) n.close();
            return;
        }
        var manager = Minecraft.getInstance().getTextureManager();
        List<Identifier> ids = new ArrayList<>(levels.size());
        entry.width = levels.getFirst().getWidth();
        entry.height = levels.getFirst().getHeight();
        for (int i = 0; i < levels.size(); i++) {
            Identifier id = Identifier.fromNamespaceAndPath(Telemetric.MOD_ID, "map/" + key + "_" + i);
            manager.register(id, new DynamicTexture(id::toString, levels.get(i)));
            ids.add(id);
        }
        entry.lastUsed = System.currentTimeMillis();
        entry.ids = List.copyOf(ids);
    }

    /** Half-size copy, each pixel the average of a 2×2 block (alpha-weighted so transparent edges don't darken). */
    private static NativeImage half(NativeImage src) {
        int w = Math.max(1, src.getWidth() / 2), h = Math.max(1, src.getHeight() / 2);
        NativeImage out = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                long a = 0, c0 = 0, c1 = 0, c2 = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int p = src.getPixel(Math.min(src.getWidth() - 1, x * 2 + dx), Math.min(src.getHeight() - 1, y * 2 + dy));
                        int pa = p >>> 24;
                        a += pa;
                        c0 += (long) ((p >>> 16) & 0xFF) * pa;
                        c1 += (long) ((p >>> 8) & 0xFF) * pa;
                        c2 += (long) (p & 0xFF) * pa;
                    }
                }
                int pixel = a == 0 ? 0 : (int) (a / 4) << 24 | (int) (c0 / a) << 16 | (int) (c1 / a) << 8 | (int) (c2 / a);
                out.setPixel(x, y, pixel);
            }
        }
        return out;
    }

    /** Frees textures no map has drawn for a while (called every client tick). */
    public static void releaseIdle() {
        long now = System.currentTimeMillis();
        TEXTURES.entrySet().removeIf(en -> {
            Entry e = en.getValue();
            List<Identifier> ids = e.ids;
            if (ids == null || now - e.lastUsed < IDLE_MS) return false;
            var manager = Minecraft.getInstance().getTextureManager();
            for (Identifier id : ids) manager.release(id);
            return true;
        });
    }
}
