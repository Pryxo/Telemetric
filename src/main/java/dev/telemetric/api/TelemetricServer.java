package dev.telemetric.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.telemetric.Telemetric;
import dev.telemetric.game.MojangSessionAdapter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket link to the Telemetric API server ({@value #URL}). On client start it answers the server's
 * {@code challenge} (Mojang join, so the server can verify the account) with {@code hello}: the signed-in account's
 * UUID, name, mod version and the challenge id; then keeps the number of players online with the mod, pushed by the
 * server about once a minute, and the UUIDs of everyone online with the mod ({@code players} → {@link ModUsers}).
 * Reconnects with jittered backoff (2 s doubling to 60 s, reset after a minute up) and stops for good on close code
 * 4002 (protocol no longer supported). Protocol: the server's {@code docs/protocol.md}.
 * No Minecraft imports except through {@link MojangSessionAdapter}.
 */
public final class TelemetricServer {

    public static final String URL = "ws://152.70.3.141:2112/ws";
    private static final int PROTOCOL = 1;
    private static final long BACKOFF_MIN_MS = 2_000, BACKOFF_MAX_MS = 60_000;
    /** A connection that stayed up this long resets the backoff. */
    private static final long STABLE_MS = 60_000;
    private static final int CLOSE_UNSUPPORTED = 4002;
    /** How often the link is checked for silence and account switches. */
    private static final long WATCHDOG_MS = 15_000;
    /** The server pings every 30 s and pushes {@code online} every interval; this much silence means a dead link. */
    private static final long SILENCE_MIN_MS = 90_000;
    /** No {@code challenge} this long after connecting (older server): send a plain hello. */
    private static final long CHALLENGE_WAIT_MS = 3_000;
    /** Mojang join bound; the server's hello timeout is 10 s. */
    private static final long JOIN_TIMEOUT_MS = 6_000;

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Telemetric Server");
        t.setDaemon(true);
        return t;
    });

    private static volatile WebSocket socket;
    private static volatile boolean welcomed;
    /** The server's {@code welcome} said Mojang confirmed our account. */
    private static volatile boolean verified;
    private static volatile int online = -1;
    private static volatile int intervalSecs = 60;
    private static volatile boolean stopped;
    /** Last frame (text, ping or pong) from the server. */
    private static volatile long lastRxAt;
    /** Touched only on {@link #EXEC}. */
    private static long backoffMs = BACKOFF_MIN_MS;
    private static long openedAt;
    private static boolean connecting, watching;
    /** Account the open socket said {@code hello} as. */
    private static String helloUuid;

    private TelemetricServer() {}

    /** Opens the connection (once). Call after the client's session services are up. */
    public static void start() {
        EXEC.execute(() -> {
            if (!watching) {
                watching = true;
                // an exception escaping a fixed-delay task cancels all later runs, so the watchdog never throws
                EXEC.scheduleWithFixedDelay(() -> {
                    try {
                        watchdog();
                    } catch (RuntimeException e) {
                        Telemetric.LOGGER.warn("[Telemetric] API watchdog failed: {}", e.toString());
                    }
                }, WATCHDOG_MS, WATCHDOG_MS, TimeUnit.MILLISECONDS);
            }
            connect();
        });
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

    /** True while connected and the server verified the account with Mojang. */
    public static boolean verified() {
        return connected() && verified;
    }

    /** Players online with the mod (unique accounts, yourself included), or -1 if unknown. */
    public static int online() {
        return connected() ? online : -1;
    }

    private static void connect() {
        if (stopped || socket != null || connecting) return;
        String uuid, name;
        try {
            uuid = MojangSessionAdapter.playerUuid();
            name = MojangSessionAdapter.playerName();
        } catch (RuntimeException e) {
            scheduleReconnect();
            return;
        }
        connecting = true;
        Listener listener = new Listener(uuid, name);
        HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(URL), listener)
                .whenCompleteAsync((ws, err) -> {
                    connecting = false;
                    if (err != null) {
                        Telemetric.LOGGER.debug("[Telemetric] API server connect failed: {}", err.toString());
                        scheduleReconnect();
                        return;
                    }
                    if (stopped) {
                        ws.sendClose(WebSocket.NORMAL_CLOSURE, "client stopping");
                        return;
                    }
                    if (ws.isInputClosed() || ws.isOutputClosed()) {
                        // Closed before this ran: its onClosed was skipped (socket was not set yet).
                        ws.abort();
                        scheduleReconnect();
                        return;
                    }
                    socket = ws;
                    welcomed = false;
                    verified = false;
                    helloUuid = uuid;
                    openedAt = lastRxAt = System.currentTimeMillis();
                    String challenge = listener.challenge;
                    if (challenge != null) {
                        answerChallenge(ws, listener, challenge);
                    } else {
                        // Servers from before verification send no challenge: say a plain hello after a short wait.
                        EXEC.schedule(() -> {
                            if (socket == ws && listener.challenge == null) sendHello(ws, listener, null);
                        }, CHALLENGE_WAIT_MS, TimeUnit.MILLISECONDS);
                    }
                }, EXEC);
    }

    /**
     * Runs on {@link #EXEC}. Joins the server's challenge with Mojang (the access token only goes to Mojang) off this
     * thread, then sends {@code hello} with {@code server_id}. The id is echoed even when the join failed (offline
     * account, expired token): the server then counts us as unverified, and knows this mod can verify.
     */
    private static void answerChallenge(WebSocket ws, Listener listener, String challenge) {
        if (socket != ws || listener.joining || listener.helloSent) return;
        listener.joining = true;
        CompletableFuture.runAsync(() -> {
                    try {
                        MojangSessionAdapter.join(challenge);
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                })
                .orTimeout(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .whenCompleteAsync((v, err) -> {
                    if (err != null) Telemetric.LOGGER.debug("[Telemetric] Mojang join for the API server failed: {}", err.toString());
                    if (socket == ws) sendHello(ws, listener, challenge);
                }, EXEC);
    }

    /** Runs on {@link #EXEC}. Sends this socket's one {@code hello}. */
    private static void sendHello(WebSocket ws, Listener listener, String serverId) {
        if (listener.helloSent) return;
        listener.helloSent = true;
        ws.sendText(hello(listener.uuid, listener.name, serverId), true);
    }

    /**
     * Runs on {@link #EXEC}. The JDK socket has no read timeout, so a link that died without a close (PC slept, Wi-Fi /
     * VPN / IP change) would otherwise look connected forever while the server has already dropped us. Also re-joins
     * when the signed-in account changed (account switcher), so the server lists the account actually playing.
     */
    private static void watchdog() {
        WebSocket ws = socket;
        if (ws == null || stopped) return;
        long silent = System.currentTimeMillis() - lastRxAt;
        if (silent > Math.max(SILENCE_MIN_MS, intervalSecs * 2_500L)) {
            Telemetric.LOGGER.info("[Telemetric] API server silent for {} s, reconnecting", silent / 1000);
            ws.abort();
            onClosed(ws, -1, "silent");
            return;
        }
        String now;
        try {
            now = MojangSessionAdapter.playerUuid();
        } catch (RuntimeException e) {
            return;
        }
        if (!now.equals(helloUuid)) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "account changed");
            backoffMs = BACKOFF_MIN_MS;
            onClosed(ws, -1, "account changed");
        }
    }

    private static String hello(String uuid, String name, String serverId) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "hello");
        o.addProperty("protocol", PROTOCOL);
        o.addProperty("uuid", uuid);
        o.addProperty("name", name);
        String version = Telemetric.version();
        if (version.length() > 32) version = version.substring(0, 32);
        o.addProperty("mod_version", version);
        if (serverId != null) o.addProperty("server_id", serverId);
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

    private static void onMessage(WebSocket ws, Listener listener, String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String type = o.has("type") ? o.get("type").getAsString() : "";
            if (type.equals("challenge")) {
                if (!o.has("server_id") || listener.challenge != null) return;
                String id = o.get("server_id").getAsString();
                if (id.isEmpty() || id.length() > 64 || !id.chars().allMatch(c -> c < 128 && Character.isLetterOrDigit(c))) return;
                listener.challenge = id;
                // Before the connect callback has run, socket != ws here; that callback answers it then.
                EXEC.execute(() -> answerChallenge(ws, listener, id));
                return;
            }
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
                verified = o.has("verified") && o.get("verified").getAsBoolean();
                if (o.has("interval_secs")) intervalSecs = Math.max(1, o.get("interval_secs").getAsInt());
            }
            if (o.has("online")) {
                online = Math.max(0, o.get("online").getAsInt());
            }
        } catch (RuntimeException e) {
            Telemetric.LOGGER.debug("[Telemetric] Bad API server message: {}", json);
        }
    }

    /** One per connection attempt; also holds that socket's hello state. */
    private static final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();
        private final String uuid, name;
        /** The server's challenge id, once it arrived. */
        private volatile String challenge;
        /** Touched only on {@link #EXEC}. */
        private boolean joining, helloSent;

        Listener(String uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            lastRxAt = System.currentTimeMillis();
            buffer.append(data);
            if (last) {
                String json = buffer.toString();
                buffer.setLength(0);
                onMessage(ws, this, json);
            }
            ws.request(1);
            return null;
        }

        /** The JDK answers the server's pings with a pong by itself; this only notes that the link is alive. */
        @Override
        public CompletionStage<?> onPing(WebSocket ws, java.nio.ByteBuffer message) {
            lastRxAt = System.currentTimeMillis();
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket ws, java.nio.ByteBuffer message) {
            lastRxAt = System.currentTimeMillis();
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
