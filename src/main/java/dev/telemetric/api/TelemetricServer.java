package dev.telemetric.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.telemetric.Telemetric;
import dev.telemetric.game.MojangSessionAdapter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket link to the Telemetric API server ({@value #URL}). On client start it sends {@code hello} with the
 * signed-in account's UUID, name and mod version, then keeps the number of players online with the mod, pushed by the
 * server about once a minute, and the UUIDs of everyone online with the mod ({@code players} → {@link ModUsers}). Reconnects with jittered backoff (2 s doubling to 60 s, reset after a minute up) and
 * stops for good on close code 4002 (protocol no longer supported). Protocol: the server's {@code docs/protocol.md}.
 * No Minecraft imports except through {@link MojangSessionAdapter}.
 */
public final class TelemetricServer {

    public static final String URL = "ws://152.70.3.141:2112/ws";
    private static final int PROTOCOL = 1;
    private static final long BACKOFF_MIN_MS = 2_000, BACKOFF_MAX_MS = 60_000;
    /** A connection that stayed up this long resets the backoff. */
    private static final long STABLE_MS = 60_000;
    private static final int CLOSE_UNSUPPORTED = 4002;

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Telemetric Server");
        t.setDaemon(true);
        return t;
    });

    private static volatile WebSocket socket;
    private static volatile boolean welcomed;
    private static volatile int online = -1;
    private static volatile long onlineAt;
    private static volatile int intervalSecs = 60;
    private static volatile boolean stopped;
    /** Touched only on {@link #EXEC}. */
    private static long backoffMs = BACKOFF_MIN_MS;
    private static long openedAt;

    private TelemetricServer() {}

    /** Opens the connection (once). Call after the client's session services are up. */
    public static void start() {
        EXEC.execute(TelemetricServer::connect);
    }

    /** Closes the socket and stops reconnecting (game shutdown). */
    public static void stop() {
        stopped = true;
        WebSocket ws = socket;
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "client stopping");
    }

    /** True once the server has answered {@code hello} and the socket is still open. */
    public static boolean connected() {
        return welcomed && socket != null;
    }

    /** Players online with the mod (unique accounts, yourself included), or -1 if unknown. */
    public static int online() {
        return connected() ? online : -1;
    }

    /** True when the last count is older than two push intervals (a push was missed). */
    public static boolean stale() {
        return System.currentTimeMillis() - onlineAt > intervalSecs * 2_000L;
    }

    private static void connect() {
        if (stopped || socket != null) return;
        String uuid, name;
        try {
            uuid = MojangSessionAdapter.playerUuid();
            name = MojangSessionAdapter.playerName();
        } catch (RuntimeException e) {
            scheduleReconnect();
            return;
        }
        HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(URL), new Listener())
                .whenCompleteAsync((ws, err) -> {
                    if (err != null) {
                        Telemetric.LOGGER.debug("[Telemetric] API server connect failed: {}", err.toString());
                        scheduleReconnect();
                        return;
                    }
                    if (stopped) {
                        ws.sendClose(WebSocket.NORMAL_CLOSURE, "client stopping");
                        return;
                    }
                    socket = ws;
                    openedAt = System.currentTimeMillis();
                    ws.sendText(hello(uuid, name), true);
                }, EXEC);
    }

    private static String hello(String uuid, String name) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "hello");
        o.addProperty("protocol", PROTOCOL);
        o.addProperty("uuid", uuid);
        o.addProperty("name", name);
        String version = Telemetric.version();
        if (version.length() > 32) version = version.substring(0, 32);
        o.addProperty("mod_version", version);
        return o.toString();
    }

    /** Runs on {@link #EXEC}. */
    private static void onClosed(WebSocket ws, int code, String reason) {
        if (socket != ws) return;
        socket = null;
        welcomed = false;
        ModUsers.clear();
        if (System.currentTimeMillis() - openedAt >= STABLE_MS) backoffMs = BACKOFF_MIN_MS;
        if (code == CLOSE_UNSUPPORTED) {
            stopped = true;
            Telemetric.LOGGER.warn("[Telemetric] API server no longer supports protocol {}; update the mod", PROTOCOL);
            return;
        }
        if (code >= 4000) Telemetric.LOGGER.info("[Telemetric] API server closed the link: {} {}", code, reason);
        scheduleReconnect();
    }

    private static void scheduleReconnect() {
        if (stopped) return;
        long delay = backoffMs + ThreadLocalRandom.current().nextLong(backoffMs / 2 + 1);
        backoffMs = Math.min(backoffMs * 2, BACKOFF_MAX_MS);
        EXEC.schedule(TelemetricServer::connect, delay, TimeUnit.MILLISECONDS);
    }

    private static void onMessage(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String type = o.has("type") ? o.get("type").getAsString() : "";
            if (type.equals("players")) {
                if (!o.has("players") || !o.get("players").isJsonArray()) return;
                java.util.List<String> uuids = new java.util.ArrayList<>();
                for (var e : o.getAsJsonArray("players")) if (e.isJsonPrimitive()) uuids.add(e.getAsString());
                ModUsers.update(uuids);
                return;
            }
            if (!type.equals("welcome") && !type.equals("online")) return; // unknown types are ignored
            if (type.equals("welcome")) {
                welcomed = true;
                if (o.has("interval_secs")) intervalSecs = Math.max(1, o.get("interval_secs").getAsInt());
            }
            if (o.has("online")) {
                online = Math.max(0, o.get("online").getAsInt());
                onlineAt = System.currentTimeMillis();
            }
        } catch (RuntimeException e) {
            Telemetric.LOGGER.debug("[Telemetric] Bad API server message: {}", json);
        }
    }

    private static final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String json = buffer.toString();
                buffer.setLength(0);
                onMessage(json);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            EXEC.execute(() -> onClosed(ws, code, reason));
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            Telemetric.LOGGER.debug("[Telemetric] API server link error: {}", error.toString());
            EXEC.execute(() -> onClosed(ws, -1, String.valueOf(error.getMessage())));
        }
    }
}
