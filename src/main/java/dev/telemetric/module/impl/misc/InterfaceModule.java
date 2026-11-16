package dev.telemetric.module.impl.misc;

import dev.telemetric.Telemetric;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.ActionSetting;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.notification.Toasts;

/** UI-only: behaviour, size and motion of the ClickGUI. */
public final class InterfaceModule extends Module {

    public final NumberSetting uiScale = add(new NumberSetting(
            "UI Scale", "Size of the ClickGUI relative to Minecraft's GUI scale", 1.0, 0.5, 2.0, 0.05).withSuffix("x"));

    public final BooleanSetting fitToScreen = add(new BooleanSetting(
            "Fit to Screen", "Keeps the ClickGUI the same size relative to the screen on every resolution / GUI scale", true));

    public final NumberSetting panelWidth = add(new NumberSetting(
            "Panel Width", "Width of each panel", 116, 90, 180, 1));

    public final NumberSetting maxPanelHeight = add(new NumberSetting(
            "Max Height", "Panels taller than this (percent of screen) scroll", 80, 30, 100, 1).withSuffix("%"));

    public final BooleanSetting animations = add(new BooleanSetting(
            "Animations", "Enable interface animations", true)
            .onChange(Durations::setEnabled));

    public final NumberSetting animationSpeed = add(new NumberSetting(
            "Anim Speed", "Animation speed multiplier (higher = faster)", 1.0, 0.5, 2.0, 0.05)
            .withSuffix("x")
            .visibleWhen(animations::get)
            .onChange(Durations::setSpeed));

    public final NumberSetting backgroundDim = add(new NumberSetting(
            "Background Dim", "How much the world behind the GUI is darkened", 55, 0, 100, 1).withSuffix("%"));

    public final BooleanSetting tooltips = add(new BooleanSetting(
            "Tooltips", "Show descriptions when hovering", true));

    public final BooleanSetting pauseGame = add(new BooleanSetting(
            "Pause Game", "Pause singleplayer while the GUI is open", false));

    public final ActionSetting resetLayout = add(new ActionSetting(
            "Reset Layout", "Re-arrange all panels to their default positions", () -> {
                Telemetric.get().config().getLayout().reset();
                Toasts.info("Panel layout reset");
            }));

    public InterfaceModule() {
        super("Interface", "Scale, motion and behaviour of the ClickGUI", Category.MISC, false);
    }

    @Override
    public void onPostInit() {
        Durations.setEnabled(animations.get());
        Durations.setSpeed(animationSpeed.get());
    }
}
