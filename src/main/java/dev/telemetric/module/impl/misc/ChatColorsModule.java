package dev.telemetric.module.impl.misc;

import dev.telemetric.game.AnimatedTextAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;

/**
 * UI-only: animated colours in Telemetric's own chat lines (e.g. the shiny Pity drop line and its hovers). Off = the
 * same colours as a still gradient. Speed follows Interface → Animation Speed; Interface → Animations off stops it.
 */
public final class ChatColorsModule extends Module {

    public ChatColorsModule() {
        super("Chat Colors", "Animates the shiny colours in Telemetric's chat lines and their hover texts", Category.MISC);
        syncOn(() -> AnimatedTextAdapter.setAnimated(isEnabled()));
    }

    @Override
    protected boolean isEnabledByDefault() {
        return true;
    }
}
