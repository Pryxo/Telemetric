package dev.telemetric.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.telemetric.Telemetric;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Telos player data through our own server ({@link TelemetricServer}'s host): {@code /telos/profile},
 * {@code /telos/character} and the item / trait definitions. The mod never talks to Telos itself and holds no token.
 * <p>
 * One request per user action, no polling. A {@code 202 loading} answer (Telos still busy) is asked again after its
 * {@code Retry-After} for up to about a minute; a {@code 429} is asked again once after its {@code Retry-After}.
 * Answers are kept in memory until the server's {@code expires_at} (a {@code STALE} copy only until its
 * {@code Retry-After}); the definitions once per game session. Everything runs off the render thread (async HTTP);
 * callers hop back to the client thread before touching a screen. See {@code docs/profile.md}.
 */
public final class TelosApi {

    /** {@code ws://host:port/ws} → {@code http://host:port} ({@code wss} → {@code https}). */
    public static final String BASE = TelemetricServer.URL.replaceFirst("^ws", "http").replaceFirst("/ws$", "");

    /** The server answers within ~15 s (with 202 if Telos is slower), so this only catches a dead connection. */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** 202 repeats before giving up: ~5 s apart, the server itself stops waiting for Telos after 60 s. */
    private static final int MAX_LOADING_TRIES = 12;
    /** Longest {@code Retry-After} we wait out automatically on a 429; longer ones are shown to the player. */
    private static final int MAX_AUTO_WAIT_S = 30;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private static final Map<String, Cached> PROFILES = new ConcurrentHashMap<>();
    private static final Map<String, Cached> CHARACTERS = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<JsonObject>> DEFINITIONS = new ConcurrentHashMap<>();

    private TelosApi() {}

    /**
     * A failed call: {@code error} is the server's code ({@code not_found}, {@code rate_limited}, {@code loading}, …),
     * {@code network} or {@code internal}. {@code retryAfter} (seconds, 0 = none) counts from {@code receivedAt}.
     */
    public static final class TelosException extends RuntimeException {
        public final int status;
        public final String error;
        public final int retryAfter;
        public final long receivedAt = System.currentTimeMillis();

        TelosException(int status, String error, String message, int retryAfter) {
            super(message);
            this.status = status;
            this.error = error;
            this.retryAfter = retryAfter;
        }

        /** Whole seconds until the server's {@code Retry-After} has passed (0 = ask now). */
        public int secondsLeft() {
            long left = receivedAt + retryAfter * 1000L - System.currentTimeMillis();
            return left <= 0 ? 0 : (int) ((left + 999) / 1000);
        }
    }

    /**
     * A successful answer: the JSON body, when the server fetched it, whether it is an old copy
     * ({@code X-Cache: STALE}) and, for a stale one, the {@code Retry-After} seconds until the server tries Telos again.
     */
    public record Answer(JsonObject body, long fetchedAt, boolean stale, int retryAfter) {}

    private record Cached(Answer answer, long expiresAtMs) {
        boolean fresh() { return System.currentTimeMillis() < expiresAtMs; }
    }

    /** {@code GET /telos/profile/{player}} (a name or UUID). {@code fresh} skips our in-memory copy (Retry / Refresh). */
    public static CompletableFuture<Answer> profile(String player, boolean fresh) {
        return cached(PROFILES, player.toLowerCase(Locale.ROOT), "/telos/profile/" + encode(player), fresh);
    }

    /** {@code GET /telos/character/{id}}. */
    public static CompletableFuture<Answer> character(String id, boolean fresh) {
        return cached(CHARACTERS, id.toLowerCase(Locale.ROOT), "/telos/character/" + encode(id), fresh);
    }

    /** The {@code definitions} object of {@code items} or {@code item-traits}, fetched once per game session. */
    public static CompletableFuture<JsonObject> definitions(String kind) {
        CompletableFuture<JsonObject> f = DEFINITIONS.computeIfAbsent(kind, k -> get("/telos/definitions/" + k)
                .thenApply(a -> {
                    JsonElement d = a.body().get("definitions");
                    return d != null && d.isJsonObject() ? d.getAsJsonObject() : new JsonObject();
                }));
        // a failed load is retried on the next use instead of failing for the whole session
        f.whenComplete((v, e) -> { if (e != null) DEFINITIONS.remove(kind, f); });
        return f;
    }

    private static CompletableFuture<Answer> cached(Map<String, Cached> cache, String key, String path, boolean fresh) {
        Cached c = cache.get(key);
        if (!fresh && c != null && c.fresh()) return CompletableFuture.completedFuture(c.answer());
        return get(path).thenApply(a -> {
            long now = System.currentTimeMillis();
            long expires = a.body().has("expires_at") ? a.body().get("expires_at").getAsLong() * 1000L : now + 60_000L;
            // a stale copy is only kept until the server tries Telos again, so reopening after that can be fresh
            if (a.stale()) expires = now + Math.max(a.retryAfter(), 60) * 1000L;
            cache.put(key, new Cached(a, expires));
            return a;
        });
    }

    /** One request, plus the repeats a {@code 202 loading} (up to {@link #MAX_LOADING_TRIES}) or one {@code 429} ask for. */
    private static CompletableFuture<Answer> get(String path) {
        return get(path, 1, false);
    }

    private static CompletableFuture<Answer> get(String path, int tries, boolean rateRetried) {
        return send(path).handle((a, t) -> {
            if (t == null) return CompletableFuture.completedFuture(a);
            TelosException e = unwrap(t);
            boolean again = e.status == 202 && tries < MAX_LOADING_TRIES
                    || e.status == 429 && !rateRetried && e.retryAfter <= MAX_AUTO_WAIT_S;
            if (!again) {
                if (e.status == 202) {
                    return CompletableFuture.<Answer>failedFuture(new TelosException(504, "loading_timeout",
                            "Telos is taking too long to answer. Try again in a minute.", 0));
                }
                return CompletableFuture.<Answer>failedFuture(e);
            }
            int wait = Math.max(1, e.retryAfter > 0 ? e.retryAfter : 5);
            return CompletableFuture.supplyAsync(() -> null, CompletableFuture.delayedExecutor(wait, TimeUnit.SECONDS))
                    .thenCompose(v -> get(path, e.status == 202 ? tries + 1 : tries, rateRetried || e.status == 429));
        }).thenCompose(f -> f);
    }

    private static CompletableFuture<Answer> send(String path) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(TIMEOUT)
                    .header("User-Agent", "Telemetric").header("Accept", "application/json").GET().build();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(new TelosException(0, "internal", "Bad request URL.", 0));
        }
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((res, t) -> {
                    if (t != null) {
                        Telemetric.LOGGER.debug("Telos API {} failed", path, t);
                        throw new TelosException(0, "network", "Could not reach the Telemetric server.", 0);
                    }
                    return parse(res);
                });
    }

    private static Answer parse(HttpResponse<String> res) {
        JsonObject body;
        try {
            JsonElement el = JsonParser.parseString(res.body());
            body = el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            body = new JsonObject();
        }
        int retry = res.headers().firstValue("Retry-After").map(TelosApi::parseInt).orElse(0);
        if (res.statusCode() != 200) {
            String error = str(body, "error", "http_" + res.statusCode());
            String message = str(body, "message", "Server answered " + res.statusCode() + ".");
            throw new TelosException(res.statusCode(), error, message, retry);
        }
        long fetched = body.has("fetched_at") ? body.get("fetched_at").getAsLong() * 1000L : System.currentTimeMillis();
        boolean stale = res.headers().firstValue("X-Cache").map("STALE"::equalsIgnoreCase).orElse(false);
        return new Answer(body, fetched, stale, stale ? retry : 0);
    }

    /** The {@link TelosException} behind a future's failure, or a generic one. */
    public static TelosException unwrap(Throwable t) {
        while (t instanceof CompletionException && t.getCause() != null) t = t.getCause();
        if (t instanceof TelosException te) return te;
        return new TelosException(0, "internal", "Failed to load: " + t.getMessage(), 0);
    }

    private static String str(JsonObject o, String key, String fallback) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
    }

    private static int parseInt(String s) {
        try {
            return Math.max(0, Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
