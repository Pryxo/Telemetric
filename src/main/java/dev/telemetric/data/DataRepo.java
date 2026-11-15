package dev.telemetric.data;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;

/**
 * The shared data repo (<a href="https://github.com/Pryxo/Telemetric-Data">Pryxo/Telemetric-Data</a>, git submodule
 * {@code data/}): base URL and the one HTTP client every download uses. Pure Java, no Minecraft imports.
 */
public final class DataRepo {

    public static final String BASE_URL = "https://raw.githubusercontent.com/Pryxo/Telemetric-Data/main/";

    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

    private DataRepo() {}

    /** GET request for a repo path (e.g. {@code telos/bosses.json}). */
    static HttpRequest get(String path, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(BASE_URL + path)).timeout(timeout)
                .header("User-Agent", "Telemetric").GET().build();
    }
}
