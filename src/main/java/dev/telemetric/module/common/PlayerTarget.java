package dev.telemetric.module.common;

import dev.telemetric.game.EntityVisualsAdapter;
import dev.telemetric.setting.impl.EnumSetting;

/** "Whose …" choice for per-player visuals (Player Size, Hide Armor): {@code new EnumSetting<>("Players", …, SELF)}. */
public enum PlayerTarget implements EnumSetting.Nameable {
    SELF("Only Me", EntityVisualsAdapter.Target.SELF),
    OTHERS("Other Players", EntityVisualsAdapter.Target.OTHERS),
    EVERYONE("Everyone", EntityVisualsAdapter.Target.EVERYONE);

    private final String label;
    private final EntityVisualsAdapter.Target target;

    PlayerTarget(String label, EntityVisualsAdapter.Target target) {
        this.label = label;
        this.target = target;
    }

    public EntityVisualsAdapter.Target target() {
        return target;
    }

    @Override
    public String displayName() {
        return label;
    }
}
