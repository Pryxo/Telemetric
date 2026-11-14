package dev.telemetric.module.impl.hud;

import dev.telemetric.game.ServerInfoAdapter;

/** Your latency to the server (Tab-list "Ping" as fallback). */
public final class PingHud extends SimpleStatHud {

    public PingHud() {
        super("Ping", "Shows your latency to the server", "Ping", "20ms", 1.0, 0.11);
    }

    @Override
    protected String value() {
        int ping = ServerInfoAdapter.ping();
        if (ping > 0) return ping + "ms";
        return ServerInfoAdapter.tabStat("Ping")
                .map(s -> s.value().endsWith("ms") ? s.value() : s.value() + "ms")
                .orElse(ping == 0 ? "0ms" : null);
    }
}
