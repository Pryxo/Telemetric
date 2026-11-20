package dev.telemetric.command;

import dev.telemetric.Telemetric;
import dev.telemetric.api.HeadStatusApi;
import dev.telemetric.api.HeadStatusCache;
import dev.telemetric.data.SharedData;
import dev.telemetric.game.ClientCommandAdapter;
import dev.telemetric.game.MojangSessionAdapter;

import java.util.Locale;
import java.util.Map;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * {@code /telemetric} (alias {@code /tm}), the mod's own client command. It works on every server.
 * <ul>
 *   <li>{@code /telemetric} lists the sub-commands.</li>
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
        ClientCommandAdapter.own(ROOT, "", TelemetricCommand::help);
        ClientCommandAdapter.own(ROOT, "update-cache", TelemetricCommand::updateCache);
        ClientCommandAdapter.own(ROOT, "status", TelemetricCommand::statusHelp);
        ClientCommandAdapter.own(ROOT, "status get", TelemetricCommand::statusGet);
        ClientCommandAdapter.own(ROOT, "status clear", TelemetricCommand::statusClear);
        ClientCommandAdapter.own(ROOT, "status refresh", TelemetricCommand::statusRefresh);
        ClientCommandAdapter.ownArg(ROOT, "status set", "status",
                () -> HeadStatusApi.cachedTop25().values(), TelemetricCommand::statusSet);
        ClientCommandAdapter.alias(ROOT, ALIAS);
    }

    private static void help(Consumer<String> reply) {
        reply.accept("§b" + Telemetric.NAME + " §7" + Telemetric.version());
        reply.accept("§f/telemetric update-cache §7— download and reload all shared data (pity / loot tables, …)");
        statusHelp(reply);
        reply.accept("§7(§f/tm §7works the same as §f/telemetric§7)");
    }

    // ---------------------------------------------------------------- status

    private static void statusHelp(Consumer<String> reply) {
        reply.accept("§f/telemetric status get §7— show your head status");
        reply.accept("§f/telemetric status set <status> §7— set your head status (Tab lists your top 25 statuses)");
        reply.accept("§f/telemetric status clear §7— remove your head status");
        reply.accept("§f/telemetric status refresh §7— reload the status list");
    }

    private static void statusGet(Consumer<String> reply) {
        String uuid;
        try {
            uuid = MojangSessionAdapter.playerUuid();
        } catch (RuntimeException e) {
            reply.accept("§cTelemetric: no signed-in account.");
            return;
        }
        HeadStatusApi.status(uuid).whenComplete((st, e) -> {
            if (e != null) {
                reply.accept("§cTelemetric: could not get your status — " + HeadStatusApi.rootMessage(e));
            } else if (st.isEmpty()) {
                reply.accept("§7Telemetric: you have no head status.");
            } else {
                HeadStatusApi.HeadStatus s = st.get();
                StringBuilder msg = new StringBuilder("§7Telemetric: your status is §b")
                        .append(HeadStatusApi.statusName(s.text()));
                if (s.count() > 0) msg.append(" §7(§f").append(String.format(Locale.ROOT, "%,d", s.count())).append("§7)");
                if (s.position() > 0) msg.append(" §7— §f#").append(s.position());
                reply.accept(msg.toString());
            }
        });
    }

    private static void statusSet(String name, Consumer<String> reply) {
        if (!HeadStatusApi.statusesLoaded()) {
            reply.accept("§eTelemetric: status list not loaded yet — try §f/telemetric status refresh§e.");
            return;
        }
        String id = findStatus(HeadStatusApi.statuses(), name);
        if (id == null) {
            reply.accept("§cTelemetric: \"" + name + "\" is not a status. §7Use Tab to see the list.");
            return;
        }
        String display = HeadStatusApi.statusName(id);
        HeadStatusApi.myTop25().whenComplete((top, te) -> {
            if (te != null) {
                reply.accept("§cTelemetric: could not load your top 25 — " + HeadStatusApi.rootMessage(te));
                return;
            }
            if (findStatus(top, name) == null) {
                reply.accept("§cTelemetric: you are not top 25 in §b" + display + "§c. §7Use Tab to see yours.");
                return;
            }
            HeadStatusApi.setStatus(id).whenComplete((v, e) -> {
                dropOwnCache();
                if (e != null) reply.accept("§cTelemetric: could not set your status — " + HeadStatusApi.rootMessage(e));
                else reply.accept("§aTelemetric: status set to §b" + display + "§a.");
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
            if (e != null) reply.accept("§cTelemetric: could not remove your status — " + HeadStatusApi.rootMessage(e));
            else reply.accept("§aTelemetric: head status removed.");
        });
    }

    private static void statusRefresh(Consumer<String> reply) {
        reply.accept("§7Telemetric: loading the status list…");
        HeadStatusApi.refreshStatuses().whenComplete((list, e) -> {
            if (e != null) reply.accept("§cTelemetric: status list failed — " + HeadStatusApi.rootMessage(e));
            else reply.accept("§aTelemetric: " + list.size() + " statuses loaded.");
        });
    }

    private static void updateCache(Consumer<String> reply) {
        if (!UPDATING.compareAndSet(false, true)) {
            reply.accept("§eTelemetric: data update already running…");
            return;
        }
        reply.accept("§7Telemetric: updating shared data…");
        SharedData.refreshAll().whenComplete((r, e) -> {
            UPDATING.set(false);
            if (e != null) {
                Telemetric.LOGGER.error("Shared data update failed", e);
                reply.accept("§cTelemetric: data update failed — see the log.");
                return;
            }
            StringBuilder msg = new StringBuilder(r.failed() == 0 ? "§a" : "§e")
                    .append("Telemetric: ").append(r.downloaded()).append('/').append(r.total())
                    .append(" tables updated and reloaded");
            if (r.overridden() > 0) msg.append(", ").append(r.overridden()).append(" from local overrides");
            if (r.failed() > 0) msg.append(", §c").append(r.failed()).append(" failed (kept old data)");
            reply.accept(msg.append('.').toString());
        });
    }
}
