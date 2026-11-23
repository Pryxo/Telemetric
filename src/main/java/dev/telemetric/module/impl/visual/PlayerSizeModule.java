package dev.telemetric.module.impl.visual;

import dev.telemetric.game.EntityVisualsAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.GroupToggleSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.clickgui.window.PlayerPreview;
import dev.telemetric.ui.clickgui.window.SettingsPreview;

/**
 * Draws players bigger, smaller or squashed, with separate sizes for you and for other players. It's purely visual,
 * so hitboxes and reach don't change. Their Telos nametags move with the head. A negative height flips the player
 * upside down.
 */
public final class PlayerSizeModule extends Module {

    private final GroupToggleSetting me = add(new GroupToggleSetting("Me", "Change your own size", true));
    private final NumberSetting meWidth = add(me.child(size("My Width", "Width", "Scale left/right")));
    private final NumberSetting meHeight = add(me.child(size("My Height", "Height",
            "Scale up/down (negative = upside down)")));
    private final NumberSetting meDepth = add(me.child(size("My Depth", "Depth", "Scale front/back")));

    private final GroupToggleSetting others = add(new GroupToggleSetting("Other Players",
            "Change the size of other players", false));
    private final NumberSetting otherWidth = add(others.child(size("Others Width", "Width", "Scale left/right")));
    private final NumberSetting otherHeight = add(others.child(size("Others Height", "Height",
            "Scale up/down (negative = upside down)")));
    private final NumberSetting otherDepth = add(others.child(size("Others Depth", "Depth", "Scale front/back")));

    public PlayerSizeModule() {
        super("Player Size", "Changes how big players look (visual only)", Category.PLAYER);
        syncOn(() -> EntityVisualsAdapter.setPlayerSize(isEnabled()
                        ? new EntityVisualsAdapter.PlayerSize(
                        me.get() ? scale(meWidth, meHeight, meDepth) : null,
                        others.get() ? scale(otherWidth, otherHeight, otherDepth) : null) : null),
                me, meWidth, meHeight, meDepth, others, otherWidth, otherHeight, otherDepth);
    }

    private static NumberSetting size(String name, String label, String description) {
        return new NumberSetting(name, description, 1, -1, 3, 0.05).withSuffix("x").withLabel(label);
    }

    private static EntityVisualsAdapter.Scale scale(NumberSetting x, NumberSetting y, NumberSetting z) {
        return new EntityVisualsAdapter.Scale(x.getFloat(), y.getFloat(), z.getFloat());
    }

    /** The Settings window shows your player model, which picks this module up live. */
    @Override
    public SettingsPreview createPreview() {
        return new PlayerPreview();
    }
}
