package dev.telemetric.module.impl.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.telemetric.Telemetric;
import dev.telemetric.data.TelosData;
import dev.telemetric.data.TelosData.DiscordInfo;
import dev.telemetric.discord.DiscordIpc;
import dev.telemetric.game.BossBarAdapter;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.TelosCharacter;
import dev.telemetric.game.TelosServer;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.TextSetting;

import java.util.ArrayList;
import java.util.List;

/**
 * Social → Discord RPC: your Telos status as Discord Rich Presence —
 * server + location, what you fight, your character. Application id, art keys, texts and area lines come from
 * {@code manual/discord.json}; the connection is {@link DiscordIpc} (no native library). The game state is
 * snapshotted each tick on Telos; when ticks stop (you left Telos) the presence switches to the "off Telos" text.
 */
public class DiscordRpcModule extends Module {

    /** No snapshot for this long = not on Telos any more. */
    private static final long STALE_MS = 3000;

    private final BooleanSetting showServer = add(new BooleanSetting("Show Server", "Region and realm you are on", true));
    private final BooleanSetting showLocation = add(new BooleanSetting("Show Location", "Area / dungeon you are in", true));
    private final BooleanSetting showActivity = add(new BooleanSetting("Show Activity",
            "The boss you fight, else a line about the area", true));
    private final BooleanSetting showCharacter = add(new BooleanSetting("Show Character",
            "Level, mode and class on the small picture", true));
    private final TextSetting customText = add(new TextSetting("Custom Text",
            "Extra text after your location (empty = none)", "", 64));

    /** What the presence shows, captured on the client thread. */
    private record Snapshot(String server, String area, String boss, boolean dungeon, TelosCharacter character, long time) {}

    private volatile Snapshot snapshot;
    private DiscordIpc ipc;
    private long startSeconds;
    private boolean everOnTelos;

    public DiscordRpcModule() {
        super("Discord RPC", "Shows your Telos status on your Discord profile", Category.SOCIAL);
    }

    @Override
    protected void onEnable() {
        String app = TelosData.DISCORD.get().applicationId();
        if (app.isBlank()) {
            Telemetric.LOGGER.warn("Discord RPC: no applicationId in manual/discord.json");
            return;
        }
        startSeconds = System.currentTimeMillis() / 1000;
        ipc = new DiscordIpc(app, this::activity);
    }

    @Override
    protected void onDisable() {
        if (ipc != null) ipc.close();
        ipc = null;
        snapshot = null;
    }

    @Override
    public void onTick() {
        if (!PlayerAdapter.isInWorld()) return;
        everOnTelos = true;
        TelosServer server = TelosServer.current();
        List<String> bosses = BossBarAdapter.knownBosses();
        snapshot = new Snapshot(server == null ? null : server.display(), PlayerAdapter.locationName(),
                bosses.isEmpty() ? null : bosses.getFirst(), DungeonAdapter.isInDungeon(),
                TelosCharacter.current().orElse(null), System.currentTimeMillis());
    }

    /** The presence JSON (IPC thread). */
    private JsonObject activity() {
        DiscordInfo info = TelosData.DISCORD.get();
        String mod = Telemetric.NAME;
        Snapshot s = snapshot;
        boolean onTelos = s != null && System.currentTimeMillis() - s.time() < STALE_MS;

        JsonObject a = new JsonObject();
        a.addProperty("type", 0); // Playing
        JsonObject time = new JsonObject();
        time.addProperty("start", startSeconds);
        a.add("timestamps", time);

        JsonObject assets = new JsonObject();
        put(assets, "large_image", info.largeImage());
        put(assets, "large_text", "Telos Realms");
        String small = info.smallImage(), smallText = info.text("mod", "mod", mod);
        if (!onTelos) {
            put(a, "details", info.text(everOnTelos ? "offTelos" : "connecting"));
        } else {
            List<String> first = new ArrayList<>(3);
            if (showServer.get() && s.server() != null) first.add(s.server());
            if (showLocation.get() && s.area() != null) first.add(s.area());
            if (!customText.get().isBlank()) first.add(customText.get().strip());
            put(a, "details", first.isEmpty() ? info.text("exploring") : String.join(" | ", first));
            if (showActivity.get()) put(a, "state", activityLine(info, s));
            TelosCharacter c = s.character();
            if (showCharacter.get() && c != null && !c.className().isEmpty()) {
                String mode = info.modeNames().getOrDefault(c.mode(), "");
                small = info.modeImages().getOrDefault(c.mode(), small);
                smallText = info.text("character", "level", c.level() < 0 ? "?" : Integer.toString(c.level()),
                        "mode", mode, "class", c.className()).replaceAll(" {2,}", " ").strip();
            }
        }
        put(assets, "small_image", small);
        put(assets, "small_text", smallText);
        a.add("assets", assets);

        if (!info.buttons().isEmpty()) {
            JsonArray buttons = new JsonArray();
            for (DiscordInfo.Button b : info.buttons()) {
                JsonObject o = new JsonObject();
                o.addProperty("label", b.label());
                o.addProperty("url", b.url());
                buttons.add(o);
            }
            a.add("buttons", buttons);
        }
        return a;
    }

    private static String activityLine(DiscordInfo info, Snapshot s) {
        if (s.boss() != null) return info.text("fighting", "boss", s.boss());
        String area = s.area() == null ? null : info.areaTexts().get(s.area());
        if (area != null) return area;
        return s.dungeon() ? info.text("dungeon") : "";
    }

    /** Adds a text field if it is long enough for Discord (2..128 chars). */
    private static void put(JsonObject o, String key, String value) {
        if (value == null) return;
        String v = value.strip();
        if (v.length() < 2) return;
        o.addProperty(key, v.length() > 128 ? v.substring(0, 127) + "…" : v);
    }
}
