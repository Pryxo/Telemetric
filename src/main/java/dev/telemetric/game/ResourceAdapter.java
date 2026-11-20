package dev.telemetric.game;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * MC ADAPTER: asks the client's resource manager whether a resource exists, e.g. to check whether a server resource
 * pack (and so its font glyphs) is loaded right now. Results are cached for {@link #TTL_MS} ms, so packs applied or
 * removed while playing get picked up.
 */
public final class ResourceAdapter {

    private static final long TTL_MS = 5_000;
    private static final Map<String, Boolean> cache = new HashMap<>();
    private static long cacheTime;

    private ResourceAdapter() {}

    /** @param id full resource id, e.g. {@code "telos:textures/icon/mastery/0.png"} */
    public static boolean exists(String id) {
        long now = System.currentTimeMillis();
        if (now - cacheTime > TTL_MS) {
            cache.clear();
            cacheTime = now;
        }
        return cache.computeIfAbsent(id, ResourceAdapter::lookup);
    }

    private static boolean lookup(String id) {
        try {
            Identifier identifier = Identifier.tryParse(id);
            return identifier != null
                    && Minecraft.getInstance().getResourceManager().getResource(identifier).isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
