package dev.telemetric.module.impl.visual;

import dev.telemetric.game.TooltipAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.NumberSetting;

/** Scales every item/text tooltip. Tooltips still stay on screen (positioned in the scaled space). */
public final class TooltipScaleModule extends Module {

    private final NumberSetting scale = new NumberSetting("Scale", "Size of tooltips compared to vanilla",
            75, 25, 200, 5).withSuffix("%");

    public TooltipScaleModule() {
        super("Tooltip Scaling", "Makes tooltips smaller or bigger", Category.INVENTORY);
        syncOn(() -> TooltipAdapter.setTooltipScale(isEnabled() ? scale.getFloat() / 100f : 1f), scale);
    }
}
