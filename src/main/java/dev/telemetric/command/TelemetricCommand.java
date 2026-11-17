package dev.telemetric.command;

import dev.telemetric.Telemetric;
import dev.telemetric.data.SharedData;
import dev.telemetric.game.ClientCommandAdapter;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * {@code /telemetric} — the mod's own client command (works on every server).
 * <ul>
 *   <li>{@code /telemetric} — lists the sub-commands.</li>
 *   <li>{@code /telemetric update-cache} — re-downloads every shared data table (Boss / pity loot, rarities, traits,
 *       chat, realms, …), saves it to the cache and reloads it right away.</li>
 * </ul>
 */
public final class TelemetricCommand {

    private static final String ROOT = "telemetric";
    private static final AtomicBoolean UPDATING = new AtomicBoolean();

    private TelemetricCommand() {}

    public static void register() {
        ClientCommandAdapter.own(ROOT, "", TelemetricCommand::help);
        ClientCommandAdapter.own(ROOT, "update-cache", TelemetricCommand::updateCache);
    }

    private static void help(Consumer<String> reply) {
        reply.accept("§b" + Telemetric.NAME + " §7" + Telemetric.version());
        reply.accept("§f/telemetric update-cache §7— download and reload all shared data (pity / loot tables, …)");
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
