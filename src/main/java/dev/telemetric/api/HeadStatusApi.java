package dev.telemetric.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.telemetric.Telemetric;
import dev.telemetric.game.MojangSessionAdapter;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.UnaryOperator;

/**
 * Client for the HeadStatus API ({@value #BASE}). It handles the live realm list (cached for {@link #REALM_TTL_MS})
 * and the player session: challenge, Mojang join, then a 24 h bearer token, with a new session on any 401.
 * No Minecraft imports except through {@link MojangSessionAdapter}. The Minecraft access token only ever goes to
 * Mojang, never to this API.
 */
public final class HeadStatusApi {

    public static final String BASE = "https://headstatus.rasmus.zip/api/v1";
    /** How long the realm list is cached. Opening the selector asks again only after this; nothing refreshes in between. */
    public static final long REALM_TTL_MS = 30_000L;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    /** One thread for auth and authorized calls, so two callers never race to make a new session. */
    private static final ExecutorService AUTH = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Telemetric HeadStatus");
        t.setDaemon(true);
        return t;
    });

    private HeadStatusApi() {}

    // ---------------------------------------------------------------- realms

    /**
     * One live server from {@code /realms}.
     *
     * @param region  API region id ({@code de} = EU, {@code ny} = NA, {@code sg} = SG)
     * @param name    lower-case realm / hub id ({@code astra}, {@code hub-1})
     * @param players online players
     */
    public record LiveRealm(String region, String name, int players) {}

    private static volatile List<LiveRealm> realms = List.of();
    private static volatile long realmsAt; // 0 means never loaded
    private static CompletableFuture<List<LiveRealm>> realmsLoad;

    /** Last loaded realm list (empty until the first request succeeds). */
    public static List<LiveRealm> realms() {
        return realms;
    }

    /** True once a realm list has loaded at least once. */
    public static boolean realmsLoaded() {
        return realmsAt != 0;
    }

    /** True while a realm request is running. */
    public static synchronized boolean realmsLoading() {
        return realmsLoad != null && !realmsLoad.isDone();
    }

    /** Asks for the realm list again, but only if the cache is older than {@link #REALM_TTL_MS} or was never loaded. */
    public static CompletableFuture<List<LiveRealm>> refreshRealmsIfStale() {
        if (realmsAt != 0 && System.currentTimeMillis() - realmsAt < REALM_TTL_MS) {
            return CompletableFuture.completedFuture(realms);
        }
        return refreshRealms();
    }

    /** Requests the realm list now, or joins a request that's already running. A failure keeps the old list. */
    public static synchronized CompletableFuture<List<LiveRealm>> refreshRealms() {
        if (realmsLoad != null && !realmsLoad.isDone()) return realmsLoad;
        HttpRequest req = request("/realms").GET().build();
        realmsLoad = HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(res -> {
            if (res.statusCode() != 200) throw new IllegalStateException("realms returned " + res.statusCode());
            List<LiveRealm> list = parseRealms(res.body());
            realms = list;
            realmsAt = System.currentTimeMillis();
            return list;
        }).exceptionally(e -> {
            Telemetric.LOGGER.warn("[Telemetric] HeadStatus realm list failed: {}", e.getMessage());
            return realms;
        });
        return realmsLoad;
    }

    private static List<LiveRealm> parseRealms(String body) {
        List<LiveRealm> out = new ArrayList<>();
        for (JsonElement el : JsonParser.parseString(body).getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (!o.has("name") || !o.get("name").isJsonPrimitive()) continue;
            String full = o.get("name").getAsString().trim().toLowerCase(Locale.ROOT);
            int colon = full.indexOf(':');
            if (colon <= 0 || colon == full.length() - 1) continue;
            int players = o.has("players") && o.get("players").isJsonPrimitive() ? o.get("players").getAsInt() : 0;
            out.add(new LiveRealm(full.substring(0, colon), full.substring(colon + 1), Math.max(0, players)));
        }
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------- head statuses

    /**
     * A player's current head status from {@code /status/{uuid}}.
     *
     * @param id       leaderboard ID ({@code STATUS-CHARACTER-SLOTS}); looked up in {@code statusIcons.json}
     * @param position leaderboard position ({@code 0} if missing)
     * @param count    the counted value, e.g. kills ({@code 0} if missing)
     */
    public record HeadStatus(String id, int position, long count) {}

    /** Every status, ID to display name ({@code STATUS-LUNA} → {@code Luna Kills}), from {@code /status-list}. */
    private static volatile Map<String, String> statusNames = Map.of();
    private static volatile boolean statusesLoaded;
    private static CompletableFuture<Map<String, String>> statusLoad;

    /** Every status, ID to display name, in API order (empty until loaded). */
    public static Map<String, String> statuses() {
        return statusNames;
    }

    /** True once {@code /status-list} has answered at least once. */
    public static boolean statusesLoaded() {
        return statusesLoaded;
    }

    /** Display name for a status ID ("Luna Kills" for {@code STATUS-LUNA}), or the ID itself if unknown. */
    public static String statusName(String id) {
        String n = statusNames.get(id);
        return n != null ? n : id;
    }

    /**
     * Loads {@code /status-list} (every status, ID to display name). Runs once on start and on
     * {@code /telemetric status refresh}, and joins a load that's already running. A failure keeps the old list.
     */
    public static synchronized CompletableFuture<Map<String, String>> refreshStatuses() {
        if (statusLoad != null && !statusLoad.isDone()) return statusLoad;
        statusLoad = HTTP.sendAsync(request("/status-list").GET().build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(res -> {
                    if (res.statusCode() != 200) throw new IllegalStateException(errorText(res));
                    Map<String, String> list = parseStatusList(res.body());
                    statusNames = list;
                    statusesLoaded = true;
                    return list;
                });
        statusLoad.whenComplete((m, e) -> {
            if (e != null) Telemetric.LOGGER.warn("[Telemetric] HeadStatus status list failed: {}", rootMessage(e));
        });
        return statusLoad;
    }

    /**
     * The statuses this player is in the top 25 of, as ID to display name ({@code /my-status-list}, authorized; the
     * API caches it for 60 s per player). It asks fresh on every call and keeps the answer for {@link #cachedTop25()}.
     */
    public static CompletableFuture<Map<String, String>> myTop25() {
        top25Asked = System.currentTimeMillis();
        return authorized("/my-status-list", HttpRequest.Builder::GET).thenApply(res -> {
            if (res.statusCode() != 200) throw new IllegalStateException(errorText(res));
            Map<String, String> list = parseStatusList(res.body());
            top25 = list;
            return list;
        });
    }

    private static volatile Map<String, String> top25 = Map.of();
    private static volatile long top25Asked;

    /**
     * Last known top-25 statuses, for Tab completion, which has to answer right away. If the last request is older than
     * 60 s it asks the API again in the background, so the next Tab press gets the fresh list.
     */
    public static Map<String, String> cachedTop25() {
        if (System.currentTimeMillis() - top25Asked > 60_000) {
            myTop25().exceptionally(e -> null);
        }
        return top25;
    }

    /** Sets the player's head status to {@code id} (an ID from the status list). */
    public static CompletableFuture<Void> setStatus(String id) {
        String path = "/setstatus/" + URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
        return authorized(path, b -> b.POST(HttpRequest.BodyPublishers.noBody())).thenAccept(res -> {
            if (res.statusCode() / 100 != 2) throw new IllegalStateException(errorText(res));
        });
    }

    /** Removes the player's head status. */
    public static CompletableFuture<Void> clearStatus() {
        return authorized("/setstatus", HttpRequest.Builder::DELETE).thenAccept(res -> {
            if (res.statusCode() / 100 != 2) throw new IllegalStateException(errorText(res));
        });
    }

    /** The current head status of {@code uuid}, empty if the player has none (404). No auth needed. */
    public static CompletableFuture<Optional<HeadStatus>> status(String uuid) {
        HttpRequest req = request("/status/" + uuid).GET().build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(res -> {
            if (res.statusCode() == 404) return Optional.<HeadStatus>empty();
            if (res.statusCode() != 200) throw new IllegalStateException(errorText(res));
            return Optional.ofNullable(parseStatus(JsonParser.parseString(res.body()).getAsJsonObject()));
        });
    }

    /** The most UUIDs a single {@code /status-bulk} request can carry. */
    public static final int BULK_MAX = 100;

    /**
     * Current head statuses of up to {@link #BULK_MAX} players ({@code POST /status-bulk}, no auth). Players without a
     * status are left out of the result. Keys are the UUIDs in the form the API sends back.
     */
    public static CompletableFuture<Map<String, HeadStatus>> statusBulk(List<String> uuids) {
        if (uuids.size() > BULK_MAX) throw new IllegalArgumentException("at most " + BULK_MAX + " uuids");
        JsonObject body = new JsonObject();
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        uuids.forEach(arr::add);
        body.add("uuids", arr);
        HttpRequest req = request("/status-bulk").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(res -> {
            if (res.statusCode() != 200) throw new IllegalStateException(errorText(res));
            Map<String, HeadStatus> out = new LinkedHashMap<>();
            JsonElement root = JsonParser.parseString(res.body());
            if (!root.isJsonObject()) return out;
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                HeadStatus st = parseStatus(e.getValue().getAsJsonObject());
                if (st != null) out.put(e.getKey(), st);
            }
            return out;
        });
    }

    private static HeadStatus parseStatus(JsonObject o) {
        String id = str(o, "id"); // the API's "text" is ignored: names come from statusIcons.json
        if (id == null || id.isBlank()) return null;
        id = id.trim();
        int pos = o.has("position") && o.get("position").isJsonPrimitive() ? o.get("position").getAsInt() : 0;
        long count = o.has("count") && o.get("count").isJsonPrimitive() ? o.get("count").getAsLong() : 0;
        return new HeadStatus(id, pos, count);
    }

    /**
     * Parses a status list. The docs say it's {@code ["ID: display name", …]}, but {@code /status-list} currently sends
     * {@code {"ID": "display name"}}, so both work. Keeps API order.
     */
    private static Map<String, String> parseStatusList(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonElement root = JsonParser.parseString(body);
        if (root.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonPrimitive()) put(out, e.getKey(), e.getValue().getAsString());
            }
        } else if (root.isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray()) {
                if (!el.isJsonPrimitive()) continue;
                String s = el.getAsString();
                int colon = s.indexOf(':');
                if (colon <= 0) put(out, s, s);
                else put(out, s.substring(0, colon), s.substring(colon + 1));
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static void put(Map<String, String> out, String id, String name) {
        id = id.trim();
        name = name.trim();
        if (!id.isEmpty()) out.put(id, name.isEmpty() ? id : name);
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    /** The API's {@code {"error": …}} text, or else the status code. */
    private static String errorText(HttpResponse<String> res) {
        try {
            JsonElement el = JsonParser.parseString(res.body());
            if (el.isJsonObject()) {
                String err = str(el.getAsJsonObject(), "error");
                if (err != null && !err.isBlank()) return err;
            }
        } catch (RuntimeException ignored) {
            // not JSON
        }
        return "HTTP " + res.statusCode();
    }

    /** The innermost exception message (unwraps CompletionException and other wrapper layers). */
    public static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t
                && (t instanceof java.util.concurrent.CompletionException || t.getMessage() == null)) {
            t = t.getCause();
        }
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    // ---------------------------------------------------------------- session / auth

    private record Challenge(String serverId, String name) {}

    private static String token;          // only touched on the AUTH thread
    private static long tokenExpiresAt;

    /** Gets a challenge serverId from the API and joins it with Mojang on this client. Blocks. */
    private static Challenge joinChallenge() {
        try {
            HttpRequest req = request("/auth-challenge").GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new IllegalStateException("challenge returned " + res.statusCode());
            String serverId = JsonParser.parseString(res.body()).getAsJsonObject().get("serverId").getAsString();
            if (!serverId.matches("[0-9a-f]{1,64}")) throw new IllegalStateException("bad serverId from challenge");
            // the access token only goes to Mojang's session server
            String name = MojangSessionAdapter.join(serverId);
            return new Challenge(serverId, name);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("join interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("join failed: " + e.getMessage(), e);
        }
    }

    /** Challenge, Mojang join, then {@code POST /session}. Stores the bearer token. Blocks, AUTH thread only. */
    private static String newSession() {
        Challenge c = joinChallenge();
        try {
            HttpRequest req = request("/session").header("X-Player-Name", c.name()).header("X-Server-Id", c.serverId())
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new IllegalStateException("session returned " + res.statusCode());
            JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
            long expiresIn = o.has("expiresIn") ? o.get("expiresIn").getAsLong() : 86_400L;
            token = o.get("token").getAsString();
            // renew a minute early so a call never goes out with a token that just ran out
            tokenExpiresAt = System.currentTimeMillis() + Math.max(0, expiresIn - 60) * 1000L;
            return token;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("session interrupted", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("session failed: " + e.getMessage(), e);
        }
    }

    private static String currentToken() {
        if (token == null || System.currentTimeMillis() >= tokenExpiresAt) return newSession();
        return token;
    }

    /** The bearer token. Makes a new session if it's missing or expired. */
    public static CompletableFuture<String> session() {
        return CompletableFuture.supplyAsync(HeadStatusApi::currentToken, AUTH);
    }

    /** Drops the token so the next authorized call makes a new session. */
    public static void invalidateSession() {
        AUTH.execute(() -> token = null);
    }

    /**
     * Sends an authorized request ({@code Authorization: Bearer}). On a 401 (the token expired or the API restarted)
     * it makes a new session once and sends again.
     *
     * @param path   API path after {@code /api/v1}, e.g. {@code /my-status-list}
     * @param method sets the method and body on the builder, e.g. {@code b -> b.GET()} or {@code b -> b.DELETE()}
     */
    public static CompletableFuture<HttpResponse<String>> authorized(String path, UnaryOperator<HttpRequest.Builder> method) {
        return CompletableFuture.supplyAsync(() -> {
            HttpResponse<String> res = sendWithToken(path, method, currentToken());
            if (res.statusCode() == 401) {
                token = null;
                res = sendWithToken(path, method, newSession());
            }
            return res;
        }, AUTH);
    }

    private static HttpResponse<String> sendWithToken(String path, UnaryOperator<HttpRequest.Builder> method, String bearer) {
        try {
            HttpRequest req = method.apply(request(path).header("Authorization", "Bearer " + bearer)).build();
            return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("request interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("request failed: " + e.getMessage(), e);
        }
    }

    private static HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(BASE + path)).timeout(TIMEOUT).header("User-Agent", "Telemetric");
    }
}
