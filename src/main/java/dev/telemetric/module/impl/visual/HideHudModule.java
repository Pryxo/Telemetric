package dev.telemetric.module.impl.visual;

import dev.telemetric.game.HudHideAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;

/**
 * Hides HUD elements in chosen situations. "During Black Screen" hides every Telemetric HUD element (vanilla HUD stays)
 * while Telos shows its full-screen black fade title, e.g. right after a boss kill. Game access goes through {@link HudHideAdapter}.
 */
public final class HideHudModule extends Module {

    private final BooleanSetting blackScreen = add(new BooleanSetting("During Black Screen",
            "Hide Telemetric's HUD elements while the server's black fade covers the screen", true));

    public HideHudModule() {
        super("Hide HUD", "Hides Telemetric's HUD elements in chosen situations, like the black screen after a boss kill",
                Category.WORLD);
        syncOn(() -> HudHideAdapter.setDuringBlackFade(isEnabled() && blackScreen.get()), blackScreen);
    }
}
