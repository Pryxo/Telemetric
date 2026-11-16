package dev.telemetric.discord;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.telemetric.Telemetric;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Minimal Discord Rich Presence client over Discord's local IPC (no native library): a named pipe
 * {@code \\.\pipe\discord-ipc-N} on Windows, a Unix socket {@code discord-ipc-N} in the runtime / temp dir elsewhere
 * (incl. Flatpak / Snap Discord). Frames are {@code int32 LE opcode, int32 LE length, UTF-8 JSON}: handshake
 * (op 0) then {@code SET_ACTIVITY} commands (op 1). Runs on its own daemon thread: every {@link #PERIOD_S} s it asks
 * {@code activity} for the presence and sends it only when it changed (Discord allows ~5 updates / 20 s);
 * reconnects every {@link #RETRY_S} s while Discord is closed. Pure Java.
 */
public final class DiscordIpc {

    static final int PERIOD_S = 5, RETRY_S = 15;
    private static final int OP_HANDSHAKE = 0, OP_FRAME = 1, OP_CLOSE = 2, OP_PING = 3, OP_PONG = 4;
    private static final int MAX_FRAME = 64 * 1024;

    private final String clientId;
    private final Supplier<JsonObject> activity;
    private final ScheduledExecutorService thread;
    private Pipe pipe;
    private String lastSent;
    private long nextConnect;

    /** @param activity the presence to show (called on the IPC thread; null clears it) */
    public DiscordIpc(String clientId, Supplier<JsonObject> activity) {
        this.clientId = clientId;
        this.activity = activity;
        this.thread = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Telemetric Discord RPC");
            t.setDaemon(true);
            return t;
        });
        thread.scheduleWithFixedDelay(this::update, 0, PERIOD_S, TimeUnit.SECONDS);
    }

    /** Clears the presence and disconnects (the thread ends). */
    public void close() {
        thread.execute(() -> {
            if (pipe != null) {
                try {
                    send(OP_FRAME, command(JsonNull.INSTANCE));
                    send(OP_CLOSE, new JsonObject());
                } catch (IOException ignored) {
                    // closing anyway
                }
            }
            disconnect();
        });
        thread.shutdown();
    }

    private void update() {
        try {
            JsonObject now = activity.get();
            String json = now == null ? "null" : now.toString();
            if (pipe == null && !connect()) return;
            if (json.equals(lastSent)) return;
            send(OP_FRAME, command(now == null ? JsonNull.INSTANCE : now));
            readReply();
            lastSent = json;
        } catch (IOException | RuntimeException e) {
            Telemetric.debug("Discord RPC: {}", e.toString());
            disconnect();
        }
    }

    private boolean connect() throws IOException {
        long now = System.currentTimeMillis();
        if (now < nextConnect) return false;
        nextConnect = now + RETRY_S * 1000L;
        pipe = Pipe.open();
        if (pipe == null) return false; // Discord not running
        JsonObject hello = new JsonObject();
        hello.addProperty("v", 1);
        hello.addProperty("client_id", clientId);
        send(OP_HANDSHAKE, hello);
        JsonObject ready = readReply();
        if (ready == null || !"READY".equals(str(ready, "evt"))) {
            Telemetric.LOGGER.warn("Discord RPC: handshake refused ({})", ready);
            disconnect();
            return false;
        }
        lastSent = null;
        Telemetric.LOGGER.info("Discord RPC connected");
        return true;
    }

    private JsonObject command(JsonElement activity) {
        JsonObject args = new JsonObject();
        args.addProperty("pid", ProcessHandle.current().pid());
        args.add("activity", activity);
        JsonObject cmd = new JsonObject();
        cmd.addProperty("cmd", "SET_ACTIVITY");
        cmd.add("args", args);
        cmd.addProperty("nonce", UUID.randomUUID().toString());
        return cmd;
    }

    private void send(int op, JsonObject payload) throws IOException {
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer frame = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        frame.putInt(op).putInt(body.length).put(body).flip();
        pipe.write(frame);
    }

    /** Reads frames until the reply to our last frame (answers pings); null when Discord closed the pipe. */
    private JsonObject readReply() throws IOException {
        for (int guard = 0; guard < 8; guard++) {
            ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            pipe.readFully(header);
            int op = header.getInt(0), len = header.getInt(4);
            if (len < 0 || len > MAX_FRAME) throw new IOException("bad frame length " + len);
            ByteBuffer body = ByteBuffer.allocate(len);
            pipe.readFully(body);
            JsonObject json = JsonParser.parseString(new String(body.array(), StandardCharsets.UTF_8)).getAsJsonObject();
            switch (op) {
                case OP_PING -> send(OP_PONG, json);
                case OP_CLOSE -> {
                    Telemetric.LOGGER.warn("Discord RPC closed by Discord: {}", json);
                    disconnect();
                    return null;
                }
                default -> {
                    if ("ERROR".equals(str(json, "evt"))) Telemetric.LOGGER.warn("Discord RPC error: {}", json);
                    return json;
                }
            }
        }
        return null;
    }

    private void disconnect() {
        if (pipe != null) {
            try {
                pipe.close();
            } catch (IOException ignored) {
                // already gone
            }
        }
        pipe = null;
        lastSent = null;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    // ------------------------------------------------------------------ transport

    /** Windows named pipe or Unix domain socket. */
    private interface Pipe extends Closeable {
        void write(ByteBuffer data) throws IOException;

        void readFully(ByteBuffer into) throws IOException;

        static Pipe open() {
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            for (int i = 0; i < 10; i++) {
                try {
                    if (windows) return new WindowsPipe(new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + i, "rw"));
                    for (Path dir : unixDirs()) {
                        Path socket = dir.resolve("discord-ipc-" + i);
                        if (Files.exists(socket)) {
                            return new UnixPipe(SocketChannel.open(UnixDomainSocketAddress.of(socket)));
                        }
                    }
                } catch (IOException | RuntimeException ignored) {
                    // not this one
                }
            }
            return null;
        }

        private static List<Path> unixDirs() {
            List<Path> dirs = new ArrayList<>();
            for (String env : new String[]{"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
                String v = System.getenv(env);
                if (v != null && !v.isBlank()) dirs.add(Path.of(v));
            }
            dirs.add(Path.of("/tmp"));
            List<Path> out = new ArrayList<>();
            for (Path d : dirs) {
                out.add(d);
                out.add(d.resolve("app/com.discordapp.Discord")); // Flatpak
                out.add(d.resolve("snap.discord"));               // Snap
            }
            return out;
        }
    }

    private record WindowsPipe(RandomAccessFile file) implements Pipe {
        @Override
        public void write(ByteBuffer data) throws IOException {
            file.write(data.array(), data.position(), data.remaining());
        }

        @Override
        public void readFully(ByteBuffer into) throws IOException {
            file.readFully(into.array(), 0, into.capacity());
        }

        @Override
        public void close() throws IOException {
            file.close();
        }
    }

    private record UnixPipe(SocketChannel channel) implements Pipe {
        @Override
        public void write(ByteBuffer data) throws IOException {
            while (data.hasRemaining()) channel.write(data);
        }

        @Override
        public void readFully(ByteBuffer into) throws IOException {
            while (into.hasRemaining()) {
                if (channel.read(into) < 0) throw new IOException("Discord closed the socket");
            }
            into.flip();
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
