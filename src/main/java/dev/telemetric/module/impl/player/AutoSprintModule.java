package dev.telemetric.module.impl.player;

import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;

/**
 * Auto Sprint: acts as if the sprint key were always held. Vanilla still decides when sprinting is possible
 * (moving forward, enough hunger, not blind / using an item), so it behaves exactly like holding the key.
 */
public final class AutoSprintModule extends Module {

    public AutoSprintModule() {
        super("Auto Sprint", "Always sprint while moving forward (like holding the sprint key)", Category.PLAYER);
    }

    @Override
    protected void onEnable() {
        PlayerAdapter.setAutoSprint(true);
    }

    @Override
    protected void onDisable() {
        PlayerAdapter.setAutoSprint(false);
    }
}
