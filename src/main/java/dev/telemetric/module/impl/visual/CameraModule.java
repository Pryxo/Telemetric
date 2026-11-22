package dev.telemetric.module.impl.visual;

import dev.telemetric.game.ViewAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.NumberSetting;

/** Camera perspective helpers: no front view, and an optional automatic third person while looking down. */
public final class CameraModule extends Module {

    private final BooleanSetting noFront = add(new BooleanSetting("No Front Camera",
            "The perspective key (F5) only switches between first and third person", true));
    private final BooleanSetting auto = add(new BooleanSetting("Auto Perspective",
            "Third person while you look down, first person otherwise (F5 inverts it)", false));
    private final NumberSetting pitch = add(new NumberSetting("Pitch Threshold",
            "How far down you have to look before the camera switches", 15, 0, 90, 1)
            .withSuffix("°").visibleWhen(auto::get));

    public CameraModule() {
        super("Camera", "No front camera; optional automatic third person while looking down", Category.WORLD);
        syncOn(() -> ViewAdapter.setCameraRules(isEnabled()
                ? new ViewAdapter.CameraRules(noFront.get(), auto.get(), pitch.getFloat()) : null), noFront, auto, pitch);
    }
}
