package dev.telemetric.module.impl.misc;

import dev.telemetric.Telemetric;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.ActionSetting;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.EnumSetting;
import dev.telemetric.setting.impl.GroupToggleSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.OpenStyle;
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

    public final EnumSetting<OpenStyle> openStyle = add(new EnumSetting<>(
            "Open Style", "How the panels appear when the GUI opens (one after another)", OpenStyle.SLIDE_UP)
            .visibleWhen(animations::get));

    public final BooleanSetting clickRipple = add(new BooleanSetting(
            "Click Ripple", "A ripple spreads from where you click a module or setting row", true)
            .visibleWhen(animations::get));

    public final BooleanSetting bouncyToggles = add(new BooleanSetting(
            "Bouncy Toggles", "Switch knobs overshoot a little and settle", true)
            .visibleWhen(animations::get));

    public final BooleanSetting hoverHighlight = add(new BooleanSetting(
            "Hover Highlight", "Hovered rows get an accent glow sliding in from the left", true));

    public final GroupToggleSetting particles = add(new GroupToggleSetting(
            "Particles", "Slow floating accent-coloured particles behind the panels (right-click to edit)", true));

    public final NumberSetting particleAmount = add(particles.child(new NumberSetting(
            "Amount", "How many particles float behind the panels", 45, 10, 150, 1)));

    public final NumberSetting particleSize = add(particles.child(new NumberSetting(
            "Size", "Size of the particles", 1.0, 0.5, 2.5, 0.05).withSuffix("x")));

    public final NumberSetting backgroundDim = add(new NumberSetting(
            "Background Dim", "How much the world behind the GUI is darkened", 55, 0, 100, 1).withSuffix("%"));

    public final BooleanSetting tooltips = add(new BooleanSetting(
            "Tooltips", "Show descriptions when hovering", true));

    public final BooleanSetting pauseGame = add(new BooleanSetting(
            "Pause Game", "Pause singleplayer while the GUI is open", false));

    public final GroupToggleSetting view3d = add(new GroupToggleSetting("3D View",
            "The 3D preview in module Settings windows (Player Size, Hitboxes, Health Bar, ...). Off: only the settings "
                    + "list; everything the preview can drag is also a setting", true));

    public final BooleanSetting view3dEntities = add(view3d.child(new BooleanSetting("Show Entities",
            "Also draw nearby players, mobs and display models in the 3D view (each one is an extra model render)",
            false)));

    public final NumberSetting view3dMaxEntities = add(view3d.child(new NumberSetting("Max Entities",
            "The most nearby entities drawn in the 3D view (nearest first)", 8, 1, 32, 1)
            .visibleWhen(view3dEntities::get)));

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
