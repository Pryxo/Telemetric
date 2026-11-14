package dev.telemetric.module.impl.hud;

import dev.telemetric.game.ServerInfoAdapter;

/** Frames per second. */
public final class FpsHud extends SimpleStatHud {

    public FpsHud() {
        super("FPS", "Shows your frames per second", "FPS", "144", 1.0, 0.06);
    }

    @Override
    protected String value() {
        return String.valueOf(ServerInfoAdapter.fps());
    }
}
