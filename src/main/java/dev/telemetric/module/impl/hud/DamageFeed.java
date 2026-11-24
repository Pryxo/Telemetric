package dev.telemetric.module.impl.hud;

import dev.telemetric.game.DamageNumberAdapter;
import dev.telemetric.game.DamageNumberAdapter.DamageNumber;
import dev.telemetric.module.ModuleManager;

import java.util.List;

/**
 * The DPS Meter's and DPS Log's view of {@link DamageNumberAdapter#thisTick}: one shared poll per client tick (the
 * pity tracker reads the same batch). Plain-text numbers (Telos countdowns) never count.
 */
final class DamageFeed {

    private DamageFeed() {}

    /** The damage numbers that showed up this tick (the same list for every caller in one tick). */
    static List<DamageNumber> thisTick() {
        return DamageNumberAdapter.thisTick(ModuleManager.tickCount());
    }

}
