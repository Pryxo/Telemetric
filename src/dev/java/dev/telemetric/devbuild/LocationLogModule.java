package dev.telemetric.devbuild;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.telemetric.Telemetric;
import dev.telemetric.game.BossBarAdapter;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.ActionSetting;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TEMP data collector: every new (HUD location, biome, dungeon?) combination seen while playing is appended to
 * {@code config/telemetric/locations.json}, to collect the HUD-box names and biome ids of all dungeons / areas.
 * A combination must hold for {@link #STABLE_POLLS} polls in a row, so the stale HUD name right after a
 * teleport is not recorded with the new place's biome. Remove once the data is collected.
 */
final class LocationLogModule extends Module {

    private static final int POLL_TICKS = 20, STABLE_POLLS = 3;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file = FabricLoader.getInstance().getConfigDir().resolve(Telemetric.MOD_ID).resolve("locations.json");
    private final Map<String, JsonObject> entries = new LinkedHashMap<>();
    private boolean loaded;
    private int ticks, stable;
    private String candidate;

    LocationLogModule() {
        super("Location Log", "TEMP: writes every new HUD location / biome pair to config/telemetric/locations.json",
                DevCategory.DEV);
        add(new ActionSetting("Clear", "Forget all collected entries (empties locations.json)", this::clear));
    }

    @Override
    protected boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onTick() {
        if (!PlayerAdapter.isInWorld() || ++ticks % POLL_TICKS != 0) return;
        load();
        String location = BossBarAdapter.hudLocation();
        String biomeId = PlayerAdapter.biomeId();
        if (biomeId == null) return;
        boolean dungeon = DungeonAdapter.isInDungeon();
        String key = location + "|" + biomeId + "|" + dungeon;
        if (!key.equals(candidate)) {
            candidate = key;
            stable = 1;
            return;
        }
        if (++stable < STABLE_POLLS || entries.containsKey(key)) return;

        JsonObject e = new JsonObject();
        e.addProperty("location", location);
        e.addProperty("biome", PlayerAdapter.biomeName());
        e.addProperty("biomeId", biomeId);
        e.addProperty("dimension", DungeonAdapter.dimensionId());
        e.addProperty("dungeon", dungeon);
        e.addProperty("detectedName", dungeon ? DungeonAdapter.dungeonName() : null);
        e.addProperty("firstSeen", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        entries.put(key, e);
        save();
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        if (!Files.exists(file)) return;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            for (JsonElement el : root.getAsJsonObject().getAsJsonArray("entries")) {
                JsonObject o = el.getAsJsonObject();
                String loc = o.has("location") && !o.get("location").isJsonNull() ? o.get("location").getAsString() : null;
                entries.put(loc + "|" + o.get("biomeId").getAsString() + "|" + o.get("dungeon").getAsBoolean(), o);
            }
        } catch (Exception ex) {
            Telemetric.LOGGER.warn("Location Log: could not read {}: {}", file, ex.toString());
        }
    }

    private void save() {
        JsonArray arr = new JsonArray();
        entries.values().forEach(arr::add);
        JsonObject root = new JsonObject();
        root.add("entries", arr);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Telemetric.LOGGER.warn("Location Log: could not write {}: {}", file, ex.toString());
        }
    }

    private void clear() {
        load();
        entries.clear();
        candidate = null;
        save();
    }
}
