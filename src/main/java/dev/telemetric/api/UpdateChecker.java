package dev.telemetric.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.telemetric.Telemetric;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Newest Telemetric release on GitHub ({@code Pryxo/Telemetric}), pre-releases included, checked once per start.
 * {@link #newerVersion()} is set when that release is newer than the running jar. No Minecraft imports.
 */
public final class UpdateChecker {

    public static final String REPO = "Pryxo/Telemetric";
    public static final String MODRINTH = "https://modrinth.com/mod/telemetric";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases?per_page=30";

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    /** Newer release: tag without the leading {@code v} and its page. Null if none (or not checked yet). */
    public record Release(String version, String url) {}

    private static volatile Release newer;
    private static volatile boolean started;

    private UpdateChecker() {}

    public static Release newerVersion() {
        return newer;
    }

    /** Starts the one check of this session (later calls do nothing). Completes with the newer release or null. */
    public static CompletableFuture<Release> check() {
        if (started) return CompletableFuture.completedFuture(newer);
        started = true;
        HttpRequest req = HttpRequest.newBuilder(URI.create(API)).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Telemetric-Mod").GET().build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(res -> {
            if (res.statusCode() != 200) {
                Telemetric.LOGGER.warn("Update check: GitHub answered {}", res.statusCode());
                return null;
            }
            Release best = newest(JsonParser.parseString(res.body()).getAsJsonArray());
            String current = strip(Telemetric.version());
            if (best != null && compare(best.version(), current) > 0) {
                newer = best;
                Telemetric.LOGGER.info("Update available: {} -> {}", current, best.version());
            }
            return newer;
        }).exceptionally(e -> {
            Telemetric.LOGGER.warn("Update check failed: {}", e.toString());
            return null;
        });
    }

    /** Highest version among the non-draft releases (GitHub's order is by date, not version). */
    static Release newest(JsonArray releases) {
        Release best = null;
        for (JsonElement el : releases) {
            JsonObject r = el.getAsJsonObject();
            if (r.has("draft") && r.get("draft").getAsBoolean()) continue;
            if (!r.has("tag_name")) continue;
            String v = strip(r.get("tag_name").getAsString());
            String url = r.has("html_url") ? r.get("html_url").getAsString() : null;
            if (best == null || compare(v, best.version()) > 0) best = new Release(v, url);
        }
        return best;
    }

    /** Version without a leading {@code v}. */
    public static String strip(String v) {
        v = v.trim();
        return v.startsWith("v") || v.startsWith("V") ? v.substring(1) : v;
    }

    /**
     * Compares {@code 1.2.3[-suffix]} versions by their numbers; with equal numbers the one without a suffix
     * (the full release) is higher. {@code +build} metadata is ignored.
     */
    static int compare(String a, String b) {
        a = a.split("\\+", 2)[0];
        b = b.split("\\+", 2)[0];
        String[] as = a.split("-", 2), bs = b.split("-", 2);
        String[] an = as[0].split("\\."), bn = bs[0].split("\\.");
        for (int i = 0; i < Math.max(an.length, bn.length); i++) {
            int x = i < an.length ? num(an[i]) : 0, y = i < bn.length ? num(bn[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        boolean aPre = as.length > 1, bPre = bs.length > 1;
        if (aPre != bPre) return aPre ? -1 : 1;
        return aPre ? as[1].compareTo(bs[1]) : 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
