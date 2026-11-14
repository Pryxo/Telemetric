package dev.telemetric.module.impl.hud;

import dev.telemetric.game.ServerInfoAdapter;
import dev.telemetric.game.ServerInfoAdapter.TabStat;

/** Server ticks per second, as published in the Tab list. Hidden on servers that don't publish it. */
public final class TpsHud extends SimpleStatHud {

    public TpsHud() {
        super("TPS", "Shows the server's ticks per second (from the Tab list)", "TPS", "20", 1.0, 0.16);
    }

    @Override
    protected String value() {
        return ServerInfoAdapter.tabStat("TPS").map(TabStat::value).orElse(null);
    }
}
