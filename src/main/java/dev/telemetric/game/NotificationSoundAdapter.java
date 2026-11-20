package dev.telemetric.game;

import dev.telemetric.config.ModPaths;
import dev.telemetric.Telemetric;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * MC ADAPTER: the sound that Social → Chat → Notifications plays ({@code config/telemetric/custom/sounds/notification.ogg|wav}).
 * Handles the native file picker (off the render thread, like Custom Bags), copying, and playback through {@link SoundFiles}.
 */
public final class NotificationSoundAdapter {

    public static final Path DIR = ModPaths.SOUNDS;
    private static final String NAME = "notification";
    private static final String[] EXT = {"ogg", "wav"};
    private static final long MAX_FILE_BYTES = 15L * 1024 * 1024;
    private static final float MAX_SECONDS = 10f;

    private NotificationSoundAdapter() {}

    /** The uploaded sound, or null. */
    public static Path file() {
        for (String ext : EXT) {
            Path p = DIR.resolve(NAME + "." + ext);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    public static boolean has() {
        return file() != null;
    }

    /** Plays the uploaded sound (volume 0..1, cut off after 10 s). False if nothing is uploaded. */
    public static boolean play(float volume) {
        Path f = file();
        if (f == null) return false;
        Minecraft.getInstance().execute(() -> SoundFiles.play(f, volume, MAX_SECONDS)); // render thread only
        return true;
    }

    /** Native file picker. {@code result} gets null on success or an error message for the player (on the picker thread). */
    public static void pick(Consumer<String> result) {
        Thread t = new Thread(() -> {
            String path;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(2);
                filters.put(stack.UTF8("*.ogg"));
                filters.put(stack.UTF8("*.wav"));
                filters.flip();
                path = TinyFileDialogs.tinyfd_openFileDialog("Notification sound", "", filters, "Audio (OGG, WAV)", false);
            }
            if (path != null) result.accept(importFile(Path.of(path)));
        }, "telemetric-file-picker");
        t.setDaemon(true);
        t.start();
    }

    private static String importFile(Path source) {
        try {
            String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
            String ext = name.substring(name.lastIndexOf('.') + 1);
            if (!ext.equals("ogg") && !ext.equals("wav")) return "Unsupported file type ." + ext;
            if (Files.size(source) > MAX_FILE_BYTES) return "File is larger than 15 MB";
            byte[] data = Files.readAllBytes(source);
            Files.createDirectories(DIR);
            for (String e : EXT) Files.deleteIfExists(DIR.resolve(NAME + "." + e));
            Files.write(DIR.resolve(NAME + "." + ext), data);
            return null;
        } catch (IOException | RuntimeException e) {
            Telemetric.LOGGER.warn("Notification sound not imported from {}: {}", source, e.toString());
            return "Could not use that file";
        }
    }
}
