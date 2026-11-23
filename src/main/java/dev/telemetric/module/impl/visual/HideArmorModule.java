package dev.telemetric.module.impl.visual;

import dev.telemetric.game.EntityVisualsAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.module.common.PlayerTarget;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.EnumSetting;
import dev.telemetric.setting.impl.KeybindSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.clickgui.window.PlayerPreview;
import dev.telemetric.ui.clickgui.window.SettingsPreview;
import dev.telemetric.ui.notification.Toasts;

/**
 * Hides worn armor pieces (models and Telos helmet items) so skins and capes show. There are two action keys: one
 * pauses the hiding, the other briefly shows everyone's real item models under their skins ("true items").
 */
public final class HideArmorModule extends Module {

    private final EnumSetting<PlayerTarget> players = add(new EnumSetting<>("Players",
            "Whose armor is hidden", PlayerTarget.EVERYONE));
    private final BooleanSetting helmet = add(new BooleanSetting("Helmet", "Hide helmets", true));
    private final BooleanSetting chestplate = add(new BooleanSetting("Chestplate", "Hide chestplates", true));
    private final BooleanSetting leggings = add(new BooleanSetting("Leggings", "Hide leggings", true));
    private final BooleanSetting boots = add(new BooleanSetting("Boots", "Hide boots", true));
    private final KeybindSetting toggleKey = add(new KeybindSetting("Toggle Hiding",
            "Key that shows / hides armor again without turning the module off", KeybindSetting.NONE)
            .onPress(this::toggleHiding));
    private final KeybindSetting revealKey = add(new KeybindSetting("Show True Items",
            "Key that briefly shows everyone's real item and armor models under their skins", KeybindSetting.NONE)
            .onPress(this::reveal));
    private final NumberSetting revealTime = add(new NumberSetting("Reveal Time",
            "How long Show True Items lasts", 3, 1, 10, 0.5).withSuffix("s"));

    /** Paused with the Toggle Hiding key (session only). */
    private boolean hiding = true;

    public HideArmorModule() {
        super("Hide Armor", "Hides worn armor; a key reveals real item models under skins", Category.PLAYER);
        syncOn(this::sync, players, helmet, chestplate, leggings, boots);
    }

    private void sync() {
        int slots = (helmet.get() ? 1 : 0) | (chestplate.get() ? 2 : 0) | (leggings.get() ? 4 : 0) | (boots.get() ? 8 : 0);
        EntityVisualsAdapter.setArmorHiding(isEnabled() && hiding && slots != 0
                ? new EntityVisualsAdapter.ArmorHiding(slots, players.get().target()) : null);
        if (!isEnabled()) EntityVisualsAdapter.stopReveal();
    }

    @Override
    protected void onEnable() {
        hiding = true;
    }

    private void toggleHiding() {
        hiding = !hiding;
        sync();
        Toasts.info(hiding ? "Armor hidden" : "Armor visible");
    }

    private void reveal() {
        EntityVisualsAdapter.revealTrueItems(Math.round(revealTime.get() * 1000));
        Toasts.info("Showing true items for " + revealTime.get() + "s");
    }

    /** The Settings window shows your player model, which picks this module up live. */
    @Override
    public SettingsPreview createPreview() {
        return new PlayerPreview();
    }
}
