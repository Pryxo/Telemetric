package dev.telemetric.module.impl.misc;

import dev.telemetric.Telemetric;
import dev.telemetric.hud.HudModule;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.ActionSetting;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.hud.HudEditorScreen;
import dev.telemetric.ui.notification.Toasts;

/** UI-only: shared look of all HUD elements + the entry point to the HUD editor. */
public final class HudSettingsModule extends Module {

    public final ActionSetting openEditor = add(new ActionSetting(
            "Edit HUD", "Open the HUD editor: move and resize every active HUD element", HudEditorScreen::openFromGui));

    public final BooleanSetting background = add(new BooleanSetting(
            "Background", "Draw a panel behind HUD elements", true));

    public final NumberSetting backgroundOpacity = add(new NumberSetting(
            "Bg Opacity", "Opacity of the HUD element background", 70, 0, 100, 1)
            .withSuffix("%")
            .visibleWhen(background::get));

    public final BooleanSetting accentBar = add(new BooleanSetting(
            "Accent Bar", "Thin accent-coloured edge on HUD elements", true)
            .visibleWhen(background::get));

    public final BooleanSetting serverColors = add(new BooleanSetting(
            "Server Colors", "Use the colours the server uses in the Tab list for stats", true));

    public final BooleanSetting statIcons = add(new BooleanSetting(
            "Stat Icons", "Show the server resource pack's icons in front of Tab-list stats", true));

    public final BooleanSetting mirrorRight = add(new BooleanSetting(
            "Mirror Right Align", "Right-aligned elements mirror their rows (\"Arcanist :Class <icon>\"); off keeps \"<icon> Class: Arcanist\"", true));

    public final BooleanSetting snapping = add(new BooleanSetting(
            "Snapping", "Snap to screen edges, centre and other elements in the editor (hold Shift to bypass)", true));

    public final ActionSetting resetPositions = add(new ActionSetting(
            "Reset HUD", "Move every HUD element back to its default position and size", () -> {
                for (var module : Telemetric.get().modules().getModules()) {
                    if (module instanceof HudModule hud) hud.resetPosition();
                }
                Toasts.info("HUD positions reset");
            }));

    public HudSettingsModule() {
        super("HUD", "Look of HUD elements and the HUD editor", Category.MISC, false);
    }
}
