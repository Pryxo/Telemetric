package dev.telemetric.game;

import dev.telemetric.config.ModPaths;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.telemetric.Telemetric;
import dev.telemetric.data.TelosData;
import dev.telemetric.data.TelosData.Bag;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * MC ADAPTER for Custom Bags: the player's own picture (PNG/JPG/GIF) and sound (OGG/WAV) for a loot bag drop.
 * <ul>
 *   <li><b>Files</b> live in {@code config/telemetric/custom/bags/<key>.<ext>} (the key comes from the bags in
 *       {@code data/tables/rarities.json}). They're imported through a native file picker, so players never have to
 *       find the folder.</li>
 *   <li><b>Picture</b>: Telos shows a drop as a totem pop of {@code telos:<totem>}. {@code GameRendererMixin} hands the
 *       floating stack to {@link #onDropAnimation}, which switches it to {@code telemetric:bags/<model>} (the bag's
 *       banner plus a flat screen) and writes the picture into the screen's sprite ({@code telemetric:mob/bags/image})
 *       in every atlas that holds it. GIF frames get swapped each tick while the pop lasts. With Shiny Effect the
 *       picture gets the Telos shiny pouch look each tick ({@link ShinyEffect}).</li>
 *   <li><b>Sound</b>: {@code SoundEngineMixin} asks {@link #replaceDropSound} about each
 *       {@code noise:player.bags.create_<sound>}, and a custom file plays instead if there is one (via
 *       {@link SoundFiles}).</li>
 * </ul>
 * Decoding happens on a background thread and the result is cached until the file changes.
 */
public final class CustomBagAdapter {

    public static final Path DIR = ModPaths.BAGS;
    private static final Identifier SCREEN_SPRITE = Identifier.fromNamespaceAndPath(Telemetric.MOD_ID, "mob/bags/image");
    private static final String DROP_SOUND = "noise:player.bags.create_";
    private static final String[] IMAGE_EXT = {"gif", "png", "jpg", "jpeg"}, SOUND_EXT = {"ogg", "wav"};
    private static final long MAX_FILE_BYTES = 15L << 20, ANIM_MS = 4000, PREVIEW_REOPEN_TICKS = 40;
    private static final String IMAGE_ONLY_MODEL = "bags/image_only";

    /**
     * Module settings. Null means off. {@code scale} = size of the drop pop while it shows a custom picture (1 = normal),
     * {@code maxSoundSeconds} = where a custom sound gets cut off, {@code stopOnWorldChange} false = a custom sound the
     * game stopped on a world / dimension change picks up where it was.
     */
    public record Settings(float volume, boolean showBanner, float scale, float maxSoundSeconds, boolean stopOnWorldChange,
                           Shiny shiny, float shinyStrength, float animSpeed, float thickness) {}

    /** Which pictures get the animated shiny effect ({@link ShinyEffect}). */
    public enum Shiny { OFF, SHINY_BAG, ALL_BAGS }

    private static final String SHINY_KEY = "shiny";

    private static volatile Settings settings;
    /** Scale for the pop on screen right now (1 unless it shows a custom picture). */
    private static volatile float popScale = 1f;
    /** Depth of the custom picture (1 = model default), applied as a Z scale on top of popScale. */
    private static volatile float popDepth = 1f;
    /** Pop playback speed right now (1 unless it shows a custom picture) and the tick progress carried between ticks. */
    private static volatile float popSpeed = 1f, popTickAcc = 0f;

    /** The custom sound playing right now (for resuming it after a world change), or null. */
    private record Playing(Path file, long startMs, float volume, float maxSeconds) {}

    private static Playing playing;
    private static Object lastLevel;

    private record Decoded(FileTime mtime, List<ImageFrames.Frame> frames) {}

    private static final Map<String, Decoded> CACHE = new HashMap<>();
    private static final Set<String> DECODING = new HashSet<>();

    /** The pop on screen right now: GIF frames and/or the shiny effect ({@code shiny} = strength, 0 = off). */
    private record Anim(List<ImageFrames.Frame> frames, long start, int total, float shiny, long durationMs) {}

    private static Anim anim;
    private static boolean previewing, ticking;
    private static Screen reopen;
    private static int reopenIn;

    private CustomBagAdapter() {}

    public static void setSettings(Settings s) {
        settings = s;
        if (s != null) {
            ensureTicker();
            for (Bag bag : TelosData.BAGS.get().list()) {
                Path img = file(bag.key(), IMAGE_EXT);
                if (img != null) warm(bag.key(), img);
            }
        }
    }

    // ================================================================== files

    /** The bag's custom file with one of these extensions, or null. */
    private static Path file(String key, String[] exts) {
        for (String ext : exts) {
            Path p = DIR.resolve(key + "." + ext);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    public static boolean hasImage(String key) {
        return file(key, IMAGE_EXT) != null;
    }

    public static boolean hasSound(String key) {
        return file(key, SOUND_EXT) != null;
    }

    public static boolean isProcessing(String key) {
        synchronized (DECODING) {
            return DECODING.contains(key);
        }
    }

    /** Opens a native file picker (off the render thread), then copies the chosen file in as the bag's picture. */
    public static void pickImage(Bag bag, Consumer<String> result) {
        pick("Picture for the " + bag.label() + " bag", new String[]{"*.png", "*.jpg", "*.jpeg", "*.gif"},
                "Images (PNG, JPG, GIF)", path -> result.accept(importFile(bag.key(), path, IMAGE_EXT)));
    }

    /** Opens a native file picker, then copies the chosen file in as the bag's drop sound. */
    public static void pickSound(Bag bag, Consumer<String> result) {
        pick("Drop sound for the " + bag.label() + " bag", new String[]{"*.ogg", "*.wav"}, "Audio (OGG, WAV)",
                path -> result.accept(importFile(bag.key(), path, SOUND_EXT)));
    }

    /** Deletes the bag's picture or sound. */
    public static boolean clear(Bag bag, boolean image) {
        boolean any = false;
        for (String ext : image ? IMAGE_EXT : SOUND_EXT) {
            try {
                any |= Files.deleteIfExists(DIR.resolve(bag.key() + "." + ext));
            } catch (IOException e) {
                Telemetric.LOGGER.warn("Custom bag file not deleted: {}", e.toString());
            }
        }
        if (image) dropCache(bag.key());
        return any;
    }

    /** @return null on success, otherwise an error message for the player */
    private static String importFile(String key, Path source, String[] exts) {
        try {
            String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
            String ext = name.substring(name.lastIndexOf('.') + 1);
            if (!List.of(exts).contains(ext)) return "Unsupported file type ." + ext;
            if (Files.size(source) > MAX_FILE_BYTES) return "File is larger than 15 MB";
            byte[] data = Files.readAllBytes(source); // read it first, because the source may be one of the files deleted below
            Files.createDirectories(DIR);
            for (String e : exts) Files.deleteIfExists(DIR.resolve(key + "." + e));
            Path target = DIR.resolve(key + "." + (ext.equals("jpeg") ? "jpg" : ext));
            Files.write(target, data);
            if (exts == IMAGE_EXT) {
                dropCache(key);
                warm(key, target);
            }
            return null;
        } catch (IOException | RuntimeException e) {
            Telemetric.LOGGER.warn("Custom bag file not imported from {}: {}", source, e.toString());
            return "Could not use that file";
        }
    }

    private static void pick(String title, String[] patterns, String description, Consumer<Path> chosen) {
        Thread t = new Thread(() -> {
            String path;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(patterns.length);
                for (String p : patterns) filters.put(stack.UTF8(p));
                filters.flip();
                path = TinyFileDialogs.tinyfd_openFileDialog(title, "", filters, description, false);
            }
            if (path != null) chosen.accept(Path.of(path)); // copy on this thread, not the render thread
        }, "telemetric-file-picker");
        t.setDaemon(true);
        t.start();
    }

    // ================================================================== picture

    private static void dropCache(String key) {
        Decoded old;
        synchronized (CACHE) {
            old = CACHE.remove(key);
        }
        if (old != null) Minecraft.getInstance().execute(() -> close(old)); // it may still be on screen this frame
    }

    private static void close(Decoded d) {
        if (anim != null && anim.frames() == d.frames()) {
            anim = null;
            ShinyEffect.release();
        }
        for (ImageFrames.Frame f : d.frames()) f.image().close();
    }

    /** The cached frames if they're decoded and still fresh. Otherwise starts decoding and returns null. */
    private static Decoded frames(String key, Path file) {
        FileTime mtime;
        try {
            mtime = Files.getLastModifiedTime(file);
        } catch (IOException e) {
            return null;
        }
        synchronized (CACHE) {
            Decoded d = CACHE.get(key);
            if (d != null && d.mtime().equals(mtime)) return d;
        }
        warm(key, file);
        return null;
    }

    /** Decodes on a background thread, one at a time per bag. */
    private static void warm(String key, Path file) {
        synchronized (DECODING) {
            if (!DECODING.add(key)) return;
        }
        Thread t = new Thread(() -> {
            try {
                FileTime mtime = Files.getLastModifiedTime(file);
                synchronized (CACHE) {
                    Decoded d = CACHE.get(key);
                    if (d != null && d.mtime().equals(mtime)) return;
                }
                List<ImageFrames.Frame> frames = ImageFrames.decode(file);
                if (frames.isEmpty()) return;
                Decoded fresh = new Decoded(mtime, frames), old;
                synchronized (CACHE) {
                    old = CACHE.put(key, fresh);
                }
                if (old != null) Minecraft.getInstance().execute(() -> close(old));
            } catch (IOException | RuntimeException e) {
                Telemetric.LOGGER.warn("Custom bag picture {} not decoded: {}", key, e.toString());
            } finally {
                synchronized (DECODING) {
                    DECODING.remove(key);
                }
            }
        }, "telemetric-bag-decode");
        t.setDaemon(true);
        t.start();
    }

    /** {@code GameRendererMixin}: a totem pop is about to show {@code stack}. */
    public static void onDropAnimation(ItemStack stack) {
        popScale = 1f;
        popDepth = 1f;
        popSpeed = 1f;
        popTickAcc = 0f;
        Settings s = settings;
        if (s == null || !ServerGate.onTelos() && !previewing) return;
        Identifier model = stack.get(DataComponents.ITEM_MODEL);
        Bag bag = model == null ? null : TelosData.BAGS.get().byTotem().get(model.getPath());
        Path file = bag == null ? null : file(bag.key(), IMAGE_EXT);
        if (file == null) return;
        Decoded d = frames(bag.key(), file);
        if (d == null) return; // still decoding, so the default totem shows this time
        try {
            Shiny mode = s.shiny();
            float shiny = mode == Shiny.ALL_BAGS || mode == Shiny.SHINY_BAG && bag.key().equals(SHINY_KEY)
                    ? s.shinyStrength() : 0f;
            NativeImage first = d.frames().getFirst().image();
            if (!upload(shiny > 0 ? ShinyEffect.apply(first, 0, shiny) : first)) return;
            stack.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath(Telemetric.MOD_ID,
                    s.showBanner() ? "bags/" + bag.model() : IMAGE_ONLY_MODEL));
            popScale = s.scale();
            popDepth = Math.max(0.02f, s.thickness());
            popSpeed = Math.clamp(s.animSpeed(), 0.05f, 1f);
            int total = 0;
            for (ImageFrames.Frame f : d.frames()) total += f.delayMs();
            anim = d.frames().size() > 1 || shiny > 0
                    ? new Anim(d.frames(), System.currentTimeMillis(), total, shiny, (long) (ANIM_MS / popSpeed)) : null;
            if (anim == null) ShinyEffect.release();
        } catch (RuntimeException e) {
            Telemetric.LOGGER.warn("Custom bag picture not shown: {}", e.toString());
        }
    }

    /** Writes {@code image} over the screen sprite (every mip level) in each atlas that holds it. */
    private static boolean upload(NativeImage image) {
        List<TextureAtlasSprite> sprites = new ArrayList<>();
        List<TextureAtlas> atlases = new ArrayList<>();
        Minecraft.getInstance().getAtlasManager().forEach((id, atlas) -> {
            TextureAtlasSprite s = atlas.getSprite(SCREEN_SPRITE);
            if (s.contents().name().equals(SCREEN_SPRITE)) {
                sprites.add(s);
                atlases.add(atlas);
            }
        });
        if (sprites.isEmpty()) {
            Telemetric.LOGGER.warn("Custom bag sprite {} is in no atlas", SCREEN_SPRITE);
            return false;
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        for (int i = 0; i < sprites.size(); i++) {
            TextureAtlasSprite s = sprites.get(i);
            GpuTexture tex = atlases.get(i).getTexture();
            int w = s.contents().width(), h = s.contents().height();
            for (int mip = 0; mip < tex.getMipLevels(); mip++) {
                int mw = w >> mip, mh = h >> mip;
                if (mw <= 0 || mh <= 0) break;
                try (NativeImage scaled = new NativeImage(mw, mh, false)) {
                    resample(image, scaled);
                    encoder.writeToTexture(tex, scaled, mip, 0, s.getX() >> mip, s.getY() >> mip, mw, mh, 0, 0);
                }
            }
        }
        return true;
    }

    /**
     * Box-filter resize (alpha-weighted) that fills every target pixel. The vanilla resize left a dark last
     * column/row, which showed as a black line on the right and bottom of the picture.
     */
    private static void resample(NativeImage src, NativeImage dst) {
        int sw = src.getWidth(), sh = src.getHeight(), dw = dst.getWidth(), dh = dst.getHeight();
        int[] px = src.getPixels(); // ARGB
        for (int y = 0; y < dh; y++) {
            int y0 = y * sh / dh, y1 = Math.max(y0 + 1, (y + 1) * sh / dh);
            for (int x = 0; x < dw; x++) {
                int x0 = x * sw / dw, x1 = Math.max(x0 + 1, (x + 1) * sw / dw);
                long a = 0, r = 0, g = 0, b = 0;
                int n = 0;
                for (int sy = y0; sy < y1; sy++) {
                    for (int sx = x0; sx < x1; sx++) {
                        int p = px[sy * sw + sx];
                        int pa = p >>> 24;
                        a += pa;
                        r += (long) ((p >> 16) & 0xFF) * pa;
                        g += (long) ((p >> 8) & 0xFF) * pa;
                        b += (long) (p & 0xFF) * pa;
                        n++;
                    }
                }
                int out = 0;
                if (a > 0) out = (int) (a / n) << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
                dst.setPixel(x, y, out);
            }
        }
    }

    private static void ensureTicker() {
        if (ticking) return;
        ticking = true;
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
    }

    /** {@code ScreenEffectRendererMixin}: how much to scale the totem pop on screen. */
    public static float popScale() {
        return popScale;
    }

    /** {@code ScreenEffectRendererMixin}: extra Z scale (Picture Thickness) for the totem pop. */
    public static float popDepth() {
        return popDepth;
    }

    /**
     * {@code ScreenEffectRendererMixin} (pop tick): true = let vanilla count this tick down. At Animation Speed below
     * 100 % only every 1/speed-th tick counts, so the pop stays on screen longer.
     */
    public static boolean popTickAdvances(boolean active) {
        if (!active) {
            popSpeed = 1f;
            popTickAcc = 0f;
            return true;
        }
        float sp = popSpeed;
        if (sp >= 1f) return true;
        float acc = popTickAcc + sp;
        if (acc < 1f) {
            popTickAcc = acc;
            return false;
        }
        popTickAcc = acc - 1f;
        return true;
    }

    /** {@code ScreenEffectRendererMixin}: the partial tick for the pop, slowed by Animation Speed. */
    public static float popPartialTick(float partialTick) {
        float sp = popSpeed;
        return sp >= 1f ? partialTick : popTickAcc + partialTick * sp;
    }

    /** Swaps GIF frames while the pop shows, reopens the screen that a preview closed, resumes stopped sounds. */
    private static void tick() {
        tickWorldChange();
        Anim a = anim;
        if (a != null) {
            long elapsed = System.currentTimeMillis() - a.start();
            if (elapsed > a.durationMs()) {
                anim = null;
                ShinyEffect.release();
            } else {
                NativeImage frame = a.frames().getFirst().image();
                if (a.frames().size() > 1 && a.total() > 0) {
                    long t = elapsed % a.total(), acc = 0;
                    frame = a.frames().getLast().image();
                    for (ImageFrames.Frame f : a.frames()) {
                        acc += f.delayMs();
                        if (t < acc) {
                            frame = f.image();
                            break;
                        }
                    }
                }
                try {
                    upload(a.shiny() > 0 ? ShinyEffect.apply(frame, elapsed, a.shiny()) : frame);
                } catch (RuntimeException e) {
                    anim = null;
                    ShinyEffect.release();
                }
            }
        }
        if (reopen != null && --reopenIn <= 0) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen == null) mc.setScreen(reopen);
            reopen = null;
        }
    }

    // ================================================================== sound

    /** {@code SoundEngineMixin}: true if the custom file plays instead of this vanilla sound. */
    public static boolean replaceDropSound(Identifier soundId) {
        Settings s = settings;
        if (s == null || !soundId.toString().startsWith(DROP_SOUND)) return false;
        if (!ServerGate.onTelos() && !previewing) return false;
        Bag bag = TelosData.BAGS.get().bySound().get(soundId.toString().substring(DROP_SOUND.length()));
        Path file = bag == null ? null : file(bag.key(), SOUND_EXT);
        if (file == null) return false;
        SoundFiles.play(file, s.volume(), s.maxSoundSeconds());
        playing = new Playing(file, System.currentTimeMillis(), s.volume(), s.maxSoundSeconds());
        return true;
    }

    /**
     * The game stops every sound when the world / dimension changes. With Stop On World Change off, a custom sound
     * that was still inside its length cap starts again where it was.
     */
    private static void tickWorldChange() {
        Object level = Minecraft.getInstance().level;
        if (level == lastLevel) return;
        boolean switched = lastLevel != null && level != null;
        lastLevel = level;
        Playing p = playing;
        if (p == null) return;
        float elapsed = (System.currentTimeMillis() - p.startMs()) / 1000f;
        Settings s = settings;
        if (!switched || s == null || s.stopOnWorldChange() || elapsed >= p.maxSeconds()) {
            playing = null;
            SoundFiles.stop();
            return;
        }
        SoundFiles.play(p.file(), p.volume(), p.maxSeconds(), elapsed);
    }

    // ================================================================== preview

    /** Plays the bag's drop pop and sound right now (closes the open screen for 2 s). */
    public static void preview(Bag bag) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath("telos", bag.totem()));
        reopen = mc.screen;
        reopenIn = (int) PREVIEW_REOPEN_TICKS;
        mc.setScreen(null);
        previewing = true;
        try {
            mc.gameRenderer.displayItemActivation(stack);
            Identifier sound = Identifier.parse(DROP_SOUND + bag.sound());
            if (!replaceDropSound(sound)) SoundAdapter.play(sound.toString(), 1f, 1f);
        } finally {
            previewing = false;
        }
    }

    /** True while {@link #preview} is running. Drop counters have to ignore it. */
    public static boolean isPreviewing() {
        return previewing;
    }
}
