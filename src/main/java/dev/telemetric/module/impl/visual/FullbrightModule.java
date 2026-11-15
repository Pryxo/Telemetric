package dev.telemetric.module.impl.visual;

import dev.telemetric.game.ViewAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.NumberSetting;

/** Lights up dark places by raising the ambient light (no night-vision tint, no gamma change). */
public final class FullbrightModule extends Module {

    private final NumberSetting strength = add(new NumberSetting("Strength",
            "How bright dark places get (100% = everything fully lit)", 100, 10, 100, 5).withSuffix("%"));

    public FullbrightModule() {
        super("Fullbright", "See in the dark: full ambient light everywhere", Category.VISUAL);
        syncOn(() -> ViewAdapter.setFullbright(isEnabled() ? strength.getFloat() / 100f : 0f), strength);
    }
}
