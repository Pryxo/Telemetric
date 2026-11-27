package dev.telemetric.module.impl.player;

import dev.telemetric.game.CursorAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;

/**
 * Keep Cursor: going from one container straight into the next (e.g. clicking through Telos menus) keeps the mouse
 * where it was instead of snapping it to the middle of the screen.
 */
public final class KeepCursorModule extends Module {

    public KeepCursorModule() {
        super("Keep Cursor", "Never snap the mouse to the middle when a menu opens or closes", Category.PLAYER);
        syncOn(() -> CursorAdapter.setEnabled(isEnabled()));
    }

    @Override
    protected boolean isEnabledByDefault() {
        return true;
    }
}
