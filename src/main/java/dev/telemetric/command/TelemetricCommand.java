package dev.telemetric.command;

import dev.telemetric.Telemetric;
import dev.telemetric.api.HeadStatusApi;
import dev.telemetric.api.HeadStatusCache;
import dev.telemetric.data.SharedData;
import dev.telemetric.game.ClientCommandAdapter;
import dev.telemetric.dungeon.Point;
import dev.telemetric.game.MojangSessionAdapter;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.ScreenOpener;
import dev.telemetric.game.ServerGate;
import dev.telemetric.game.ServerInfoAdapter;

import java.util.Locale;
import java.util.Map;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * {@code /telemetric} (alias {@code /tm}), the mod's own client command. It works on every server. Every reply is one
 * chat message with the {@code Telemetric ›} prefix only once (see {@code ClientCommandAdapter#runOwn}); the help list
 * follows Melinoe's layout ({@code > /tm ping - Shows your ping}).
 * <ul>
 *   <li>{@code /telemetric} opens the ClickGUI on Telos (elsewhere it prints the help).</li>
 *   <li>{@code /telemetric help} lists the sub-commands.</li>
 *   <li>{@code /telemetric edithud} opens the HUD editor (Telos only).</li>
 *   <li>{@code /telemetric ping | fps | tps | coords} print your ping (Tab list), FPS, the server TPS estimated from
 *       the world-time packets, and your coordinates + location.</li>
 *   <li>{@code /telemetric version} prints the mod version.</li>
 *   <li>{@code /telemetric update-cache} downloads every shared data table again (Boss / pity loot, rarities,
 *       traits, chat, realms, …), saves it to the cache and reloads it right away.</li>
 *   <li>{@code /telemetric status get} shows your current HeadStatus head status.</li>
 *   <li>{@code /telemetric status set <name>} sets it. Names are the display names from the API's status list
 *       ("Luna Kills") and get sent to the API as their ID ({@code STATUS-LUNA}). Only statuses you're in the top 25
 *       of are offered (Tab) and accepted.</li>
 *   <li>{@code /telemetric status clear} removes it.</li>
 *   <li>{@code /telemetric status refresh} loads the status list again (it's loaded once on start).</li>
 * </ul>
 */
public final class TelemetricCommand {

    private static final String ROOT = "telemetric";
    private static final String ALIAS = "tm";
    private static final AtomicBoolean UPDATING = new AtomicBoolean();

    private TelemetricCommand() {}

    public static void register() {
        ClientCommandAdapter.own(ROOT, "", TelemetricCommand::openGui);
        ClientCommandAdapter.own(ROOT, "help", TelemetricCommand::help);
        ClientCommandAdapter.own(ROOT, "edithud", TelemetricCommand::editHud);
        ClientCommandAdapter.own(ROOT, "ping", TelemetricCommand::ping);
        ClientCommandAdapter.own(ROOT, "fps", TelemetricCommand::fps);
        ClientCommandAdapter.own(ROOT, "tps", TelemetricCommand::tps);
        ClientCommandAdapter.own(ROOT, "coords", TelemetricCommand::coords);
        ClientCommandAdapter.own(ROOT, "version", TelemetricCommand::version);
        ClientCommandAdapter.own(ROOT, "update-cache", TelemetricCommand::updateCache);
        ClientCommandAdapter.own(ROOT, "status", TelemetricCommand::statusHelp);
        ClientCommandAdapter.own(ROOT, "status get", TelemetricCommand::statusGet);
        ClientCommandAdapter.own(ROOT, "status clear", TelemetricCommand::statusClear);
        ClientCommandAdapter.own(ROOT, "status refresh", TelemetricCommand::statusRefresh);
        ClientCommandAdapter.ownArg(ROOT, "status set", "status",
                () -> HeadStatusApi.cachedTop25().values(), TelemetricCommand::statusSet);
        ClientCommandAdapter.alias(ROOT, ALIAS);
    }

    // ---------------------------------------------------------------- help / utility

    /** One help line in Melinoe's layout: grey bold {@code >}, gold command, grey {@code -}, grey description. */
    private static String line(String command, String description) {
        return "§8§l> §r§6" + command + " §8- §7" + description;
    }

    private static void help(Consumer<String> reply) {
        reply.accept("Command Help §8(v" + Telemetric.version() + ")§7:");
        reply.accept(line("/tm§8, §6/telemetric", "Opens the ClickGUI"));
        reply.accept(line("/tm help", "Shows this list"));
        reply.accept(line("/tm edithud", "Opens the HUD editor"));
        reply.accept(line("/tm ping", "Shows your ping"));
        reply.accept(line("/tm fps", "Shows your FPS"));
        reply.accept(line("/tm tps", "Shows the server's TPS"));
        reply.accept(line("/tm coords", "Shows your coordinates and location"));
        reply.accept(line("/tm version", "Shows the mod version"));
        reply.accept(line("/tm update-cache", "Downloads and reloads all shared data"));
        statusLines(reply);
    }

    private static void openGui(Consumer<String> reply) {
        if (!ServerGate.onTelos()) {
            help(reply);
            return;
        }
        ScreenOpener.clickGui();
    }

    private static void editHud(Consumer<String> reply) {
        if (!ServerGate.onTelos()) {
            reply.accept("§cThe HUD editor only works on Telos.");
            return;
        }
        ScreenOpener.hudEditor();
    }

    private static void ping(Consumer<String> reply) {
        int ping = ServerInfoAdapter.ping();
        reply.accept(ping < 0 ? "§cPing unknown §7(not on a server)." : "§6Ping: §7" + ping + "ms");
    }

    private static void fps(Consumer<String> reply) {
        reply.accept("§6FPS: §7" + ServerInfoAdapter.fps());
    }

    private static void tps(Consumer<String> reply) {
        double tps = ServerInfoAdapter.tps();
        reply.accept(tps < 0 ? "§cTPS unknown §7(wait a few seconds after joining)."
                : "§6TPS: §7" + String.format(Locale.ROOT, "%.1f", tps));
    }

    private static void coords(Consumer<String> reply) {
        Point p = PlayerAdapter.feet(1f);
        if (p == null) {
            reply.accept("§cNot in a world.");
            return;
        }
        String where = PlayerAdapter.locationName();
        reply.accept("§6Coords: §7" + (int) Math.floor(p.x()) + ", " + (int) Math.floor(p.y()) + ", "
                + (int) Math.floor(p.z()) + (where == null ? "" : " §8(§7" + where + "§8)"));
    }

    private static void version(Consumer<String> reply) {
        reply.accept("§6Version: §7" + Telemetric.version());
    }

    // ---------------------------------------------------------------- status

    private static void statusHelp(Consumer<String> reply) {
        reply.accept("Status Commands:");
        statusLines(reply);
    }

    private static void statusLines(Consumer<String> reply) {
        reply.accept(line("/tm status get", "Shows your head status"));
        reply.accept(line("/tm status set <status>", "Sets your head status (Tab lists your top 25)"));
        reply.accept(line("/tm status clear", "Removes your head status"));
        reply.accept(line("/tm status refresh", "Reloads the status list"));
    }

    private static void statusGet(Consumer<String> reply) {
        String uuid;
        try {
            uuid = MojangSessionAdapter.playerUuid();
        } catch (RuntimeException e) {
            reply.accept("No signed-in account.");
            return;
        }
        HeadStatusApi.status(uuid).whenComplete((st, e) -> {
            if (e != null) {
                reply.accept("Could not get your status: §f" + HeadStatusApi.rootMessage(e));
            } else if (st.isEmpty()) {
                reply.accept("You have no head status.");
            } else {
                HeadStatusApi.HeadStatus s = st.get();
                StringBuilder msg = new StringBuilder("Your status is §f")
                        .append(HeadStatusApi.statusName(s.text()));
                if (s.count() > 0) msg.append(" §7(§f").append(String.format(Locale.ROOT, "%,d", s.count())).append("§7)");
                if (s.position() > 0) msg.append(" §8- §f#").append(s.position());
                reply.accept(msg.toString());
            }
        });
    }

    private static void statusSet(String name, Consumer<String> reply) {
        if (!HeadStatusApi.statusesLoaded()) {
            reply.accept("Status list not loaded yet. Try §f/tm status refresh§7.");
            return;
        }
        String id = findStatus(HeadStatusApi.statuses(), name);
        if (id == null) {
            reply.accept("§f" + name + " §7is not a status. Use Tab to see the list.");
            return;
        }
        String display = HeadStatusApi.statusName(id);
        HeadStatusApi.myTop25().whenComplete((top, te) -> {
            if (te != null) {
                reply.accept("Could not load your top 25: §f" + HeadStatusApi.rootMessage(te));
                return;
            }
            if (findStatus(top, name) == null) {
                reply.accept("You are not top 25 in §f" + display + "§7. Use Tab to see yours.");
                return;
            }
            HeadStatusApi.setStatus(id).whenComplete((v, e) -> {
                dropOwnCache();
                if (e != null) reply.accept("Could not set your status: §f" + HeadStatusApi.rootMessage(e));
                else reply.accept("Status set to §f" + display + "§7.");
            });
        });
    }

    /** Status ID for a display name ("luna kills") or an ID ("STATUS-LUNA"), ignoring case. Null if there's no match. */
    private static String findStatus(Map<String, String> statuses, String name) {
        for (Map.Entry<String, String> e : statuses.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name) || e.getKey().equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    /** Makes the next scan (within 10 s) ask for your head status again, instead of waiting out the 15 min cache. */
    private static void dropOwnCache() {
        try {
            HeadStatusCache.invalidate(MojangSessionAdapter.playerUuid());
        } catch (RuntimeException ignored) {
            // no account
        }
    }

    private static void statusClear(Consumer<String> reply) {
        HeadStatusApi.clearStatus().whenComplete((v, e) -> {
            dropOwnCache();
            if (e != null) reply.accept("Could not remove your status: §f" + HeadStatusApi.rootMessage(e));
            else reply.accept("Head status removed.");
        });
    }

    private static void statusRefresh(Consumer<String> reply) {
        reply.accept("Loading the status list…");
        HeadStatusApi.refreshStatuses().whenComplete((list, e) -> {
            if (e != null) reply.accept("Status list failed: §f" + HeadStatusApi.rootMessage(e));
            else reply.accept("§f" + list.size() + " §7statuses loaded.");
        });
    }

    private static void updateCache(Consumer<String> reply) {
        if (!UPDATING.compareAndSet(false, true)) {
            reply.accept("Data update already running…");
            return;
        }
        reply.accept("Updating shared data…");
        SharedData.refreshAll().whenComplete((r, e) -> {
            UPDATING.set(false);
            if (e != null) {
                Telemetric.LOGGER.error("Shared data update failed", e);
                reply.accept("Data update failed, see the log.");
                return;
            }
            StringBuilder msg = new StringBuilder("§f").append(r.downloaded()).append('/').append(r.total())
                    .append(" §7tables updated and reloaded");
            if (r.overridden() > 0) msg.append(", §f").append(r.overridden()).append(" §7from local overrides");
            if (r.failed() > 0) msg.append(", §f").append(r.failed()).append(" §7failed (kept old data)");
            reply.accept(msg.append('.').toString());
        });
    }
}
