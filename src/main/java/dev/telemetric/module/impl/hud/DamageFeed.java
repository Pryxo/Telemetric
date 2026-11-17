package dev.telemetric.module.impl.hud;

import dev.telemetric.game.DamageNumberAdapter;
import dev.telemetric.game.DamageNumberAdapter.DamageNumber;
import dev.telemetric.module.ModuleManager;

import java.util.List;

/**
 * Shares one {@link DamageNumberAdapter#poll} per client tick between the DPS Meter and the DPS Log (the adapter
 * reports each number only once, so two modules polling it would steal hits from each other). Plain-text
 * numbers (Telos countdowns) are never counted.
 */
final class DamageFeed {

    private static long polledTick = -1;
    private static List<DamageNumber> batch = List.of();

    private DamageFeed() {}

    /** The damage numbers that appeared this tick (same list for every caller in one tick). */
    static List<DamageNumber> thisTick() {
        long tick = ModuleManager.tickCount();
        if (tick != polledTick) {
            polledTick = tick;
            batch = DamageNumberAdapter.poll(false);
        }
        return batch;
    }

}
