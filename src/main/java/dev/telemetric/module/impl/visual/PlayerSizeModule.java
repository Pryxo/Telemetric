package dev.telemetric.module.impl.visual;

import dev.telemetric.game.EntityVisualsAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.module.common.PlayerTarget;
import dev.telemetric.setting.impl.EnumSetting;
import dev.telemetric.setting.impl.NumberSetting;

/**
 * Draws players bigger, smaller or squashed (purely visual — hitboxes and reach are unchanged). Their Telos
 * nametags move with the head. A negative height flips the player upside down.
 */
public final class PlayerSizeModule extends Module {

    private final EnumSetting<PlayerTarget> players = add(new EnumSetting<>("Players",
            "Whose size is changed", PlayerTarget.SELF));
    private final NumberSetting width = add(new NumberSetting("Width", "Scale left/right", 1, -1, 3, 0.05)
            .withSuffix("x"));
    private final NumberSetting height = add(new NumberSetting("Height",
            "Scale up/down (negative = upside down)", 1, -1, 3, 0.05).withSuffix("x"));
    private final NumberSetting depth = add(new NumberSetting("Depth", "Scale front/back", 1, -1, 3, 0.05)
            .withSuffix("x"));

    public PlayerSizeModule() {
        super("Player Size", "Changes how big players look (visual only)", Category.VISUAL);
        syncOn(() -> EntityVisualsAdapter.setPlayerSize(isEnabled()
                ? new EntityVisualsAdapter.PlayerSize(players.get().target(), width.getFloat(), height.getFloat(),
                depth.getFloat()) : null), players, width, height, depth);
    }
}
