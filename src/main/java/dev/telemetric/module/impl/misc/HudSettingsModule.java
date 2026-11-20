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

import java.util.function.Consumer;

/**
 * UI-only: the HUD editor, and the look of all HUD elements at once. Background, Bg Opacity, Accent Bar and Stat Icons
 * are universal: changing one here copies it onto every element's own HUD Settings (each element can then differ).
 */
public final class HudSettingsModule extends Module {

    public final ActionSetting openEditor = add(new ActionSetting(
            "Edit HUD", "Open the HUD editor: move and resize every active HUD element", HudEditorScreen::openFromGui));

    public final BooleanSetting background = add(new BooleanSetting(
            "Background", "Draw a panel behind every HUD element (sets each element's own Background)", true));

    public final NumberSetting backgroundOpacity = add(new NumberSetting(
            "Bg Opacity", "Background opacity of every HUD element (sets each element's own)", 70, 0, 100, 1)
            .withSuffix("%")
            .visibleWhen(background::get));

    public final BooleanSetting accentBar = add(new BooleanSetting(
            "Accent Bar", "Thin accent-coloured edge on every HUD element (sets each element's own)", true)
            .visibleWhen(background::get));

    public final BooleanSetting serverColors = add(new BooleanSetting(
            "Server Colors", "Use the colours the server uses in the Tab list for stats", true));

    public final BooleanSetting statIcons = add(new BooleanSetting(
            "Stat Icons", "Icons in front of HUD rows on every element (sets each element's own Icons)", true));

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
        background.onChange(on -> forEachHud(h -> h.applyGlobalBackground(on)));
        backgroundOpacity.onChange(v -> forEachHud(h -> h.applyGlobalBgOpacity(v)));
        accentBar.onChange(on -> forEachHud(h -> h.applyGlobalAccentBar(on)));
        statIcons.onChange(on -> forEachHud(h -> h.applyGlobalIcons(on)));
    }

    private static void forEachHud(Consumer<HudModule> action) {
        for (var module : Telemetric.get().modules().getModules()) if (module instanceof HudModule hud) action.accept(hud);
    }
}
