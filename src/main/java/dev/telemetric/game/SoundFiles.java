package dev.telemetric.game;

import dev.telemetric.Telemetric;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MC ADAPTER: plays a sound file from disk (OGG Vorbis or WAV) through the vanilla sound engine, no resource pack
 * needed. Each play gets a stream id {@code telemetric:sounds/file/<n>.ogg}, and {@code SoundBufferLibraryMixin} asks
 * {@link #open} for it. OGG goes through vanilla's own decoder and WAV through Java's. Long files are cut at the given
 * length with a short fade-out.
 */
public final class SoundFiles {

    private static final String STREAM_PREFIX = "sounds/file/", STREAM_SUFFIX = ".ogg";
    private static final float FADE_SECONDS = 0.4f;

    private record Entry(Path file, float maxSeconds, float startSeconds) {}

    private static final Map<String, Entry> STREAMS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT = new AtomicInteger();
    private static SoundInstance current;

    private SoundFiles() {}

    public static boolean isSupported(Path file) {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".ogg") || n.endsWith(".wav");
    }

    /** Plays {@code file} (stopping the previous file sound) at volume 0..1, cut off after {@code maxSeconds}. */
    public static void play(Path file, float volume, float maxSeconds) {
        play(file, volume, maxSeconds, 0f);
    }

    /**
     * Like {@link #play(Path, float, float)}, but starts {@code startSeconds} into the file (the cut-off still counts
     * from the file's start). Used to resume a sound that the game stopped.
     */
    public static void play(Path file, float volume, float maxSeconds, float startSeconds) {
        Minecraft mc = Minecraft.getInstance();
        if (!Files.isRegularFile(file) || !isSupported(file)) return;
        stop();
        String token = String.valueOf(NEXT.incrementAndGet());
        STREAMS.clear(); // only the newest stream is ever opened
        STREAMS.put(token, new Entry(file, maxSeconds, Math.max(0f, startSeconds)));
        current = new FileSound(Identifier.fromNamespaceAndPath(Telemetric.MOD_ID, "file/" + token),
                Math.clamp(volume, 0f, 1f));
        mc.getSoundManager().play(current);
    }

    public static void stop() {
        if (current != null) Minecraft.getInstance().getSoundManager().stop(current);
        current = null;
    }

    /** True for the stream ids made by {@link #play}. */
    public static boolean isFileStream(Identifier id) {
        return token(id) != null;
    }

    /** Opens the stream for an id made by {@link #play}. The sound engine calls this off-thread. */
    public static AudioStream open(Identifier id) throws IOException {
        String token = token(id);
        Entry e = token == null ? null : STREAMS.get(token);
        if (e == null) throw new IOException("Unknown sound file stream " + id);
        String name = e.file().getFileName().toString().toLowerCase(Locale.ROOT);
        AudioStream raw = name.endsWith(".wav") ? wav(e.file()) : new JOrbisAudioStream(Files.newInputStream(e.file()));
        return new Limited(raw, e.maxSeconds(), e.startSeconds());
    }

    private static String token(Identifier id) {
        if (!Telemetric.MOD_ID.equals(id.getNamespace())) return null;
        String p = id.getPath();
        if (!p.startsWith(STREAM_PREFIX) || !p.endsWith(STREAM_SUFFIX)) return null;
        return p.substring(STREAM_PREFIX.length(), p.length() - STREAM_SUFFIX.length());
    }

    // ------------------------------------------------------------------ sound instance

    /** A sound that bypasses sounds.json and resolves to one streamed FILE entry. */
    private static final class FileSound extends AbstractSoundInstance {
        FileSound(Identifier id, float volume) {
            super(id, SoundSource.MASTER, SoundInstance.createUnseededRandom());
            this.volume = volume;
            this.relative = true;
            this.attenuation = Attenuation.NONE;
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager manager) {
            WeighedSoundEvents events = new WeighedSoundEvents(identifier, null);
            ConstantFloat one = ConstantFloat.of(1f);
            sound = new Sound(identifier, one, one, 1, Sound.Type.FILE, true, false, 16);
            events.addSound(sound);
            return events;
        }
    }

    // ------------------------------------------------------------------ streams

    private static AudioStream wav(Path file) throws IOException {
        try {
            AudioInputStream in = AudioSystem.getAudioInputStream(new BufferedInputStream(Files.newInputStream(file)));
            AudioFormat src = in.getFormat();
            AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, src.getSampleRate(), 16,
                    src.getChannels(), src.getChannels() * 2, src.getSampleRate(), false);
            AudioInputStream conv = AudioSystem.getAudioInputStream(pcm, in);
            return new AudioStream() {
                @Override
                public AudioFormat getFormat() {
                    return pcm;
                }

                @Override
                public ByteBuffer read(int size) throws IOException {
                    int frame = pcm.getFrameSize();
                    byte[] buf = new byte[Math.max(frame, size - size % frame)];
                    int n = conv.readNBytes(buf, 0, buf.length);
                    ByteBuffer out = BufferUtils.createByteBuffer(Math.max(0, n));
                    out.put(buf, 0, Math.max(0, n)).flip();
                    return out;
                }

                @Override
                public void close() throws IOException {
                    conv.close();
                }
            };
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("Not a WAV file: " + file.getFileName(), e);
        }
    }

    /**
     * Skips the first {@code startSeconds} and cuts a stream off after {@code maxSeconds} (counted from the file's
     * start), fading 16-bit PCM out over the last {@link #FADE_SECONDS}.
     */
    private static final class Limited implements AudioStream {
        private final AudioStream in;
        private final long limit, fadeFrom, skip;
        private final boolean pcm16;
        private final int frame;
        private long pos;

        Limited(AudioStream in, float maxSeconds, float startSeconds) {
            this.in = in;
            AudioFormat f = in.getFormat();
            this.frame = Math.max(1, f.getFrameSize());
            this.pcm16 = f.getSampleSizeInBits() == 16 && !f.isBigEndian();
            long perSecond = (long) (f.getFrameRate() * frame);
            this.limit = maxSeconds <= 0 || perSecond <= 0 ? Long.MAX_VALUE : (long) (maxSeconds * perSecond) / frame * frame;
            this.fadeFrom = limit == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0, limit - (long) (FADE_SECONDS * perSecond));
            this.skip = perSecond <= 0 ? 0 : (long) (startSeconds * perSecond) / frame * frame;
        }

        @Override
        public AudioFormat getFormat() {
            return in.getFormat();
        }

        @Override
        public ByteBuffer read(int size) throws IOException {
            if (pos >= limit) return BufferUtils.createByteBuffer(0);
            ByteBuffer data = in.read(size);
            while (pos < skip) { // resume: decode and drop everything before the start point
                int got = data.remaining();
                if (got == 0) return data;
                if (pos + got > skip) {
                    data.position(data.position() + (int) (skip - pos));
                    pos = skip;
                    break;
                }
                pos += got;
                data = in.read(size);
            }
            if (pos >= limit) return BufferUtils.createByteBuffer(0);
            int n = data.remaining();
            if (pos + n <= fadeFrom) {
                pos += n;
                return data;
            }
            int keep = (int) Math.min(n, limit - pos);
            ByteBuffer out = BufferUtils.createByteBuffer(keep).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < keep; i++) out.put(data.get(data.position() + i));
            if (pcm16) {
                for (int i = 0; i + 1 < keep; i += 2) {
                    long at = pos + i;
                    if (at < fadeFrom) continue;
                    float gain = Math.clamp((limit - at) / (float) (limit - fadeFrom), 0f, 1f);
                    out.putShort(i, (short) (out.getShort(i) * gain));
                }
            }
            pos += keep;
            out.flip();
            return out;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
