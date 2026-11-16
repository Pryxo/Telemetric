package dev.telemetric.module.impl.visual;

import dev.telemetric.game.BossBarAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.NumberSetting;

/**
 * Shrinks the boss bar of real bosses (listed in {@code data/Extractor/bossbars.json}); HUD widget bars and the dungeon
 * name bar keep their size. The bars below move up so no gap is left.
 */
public final class BossBarScaleModule extends Module {

    private final NumberSetting scale = add(new NumberSetting("Bar Scale",
            "Size of a boss's bar compared to vanilla", 70, 25, 100, 5).withSuffix("%"));

    public BossBarScaleModule() {
        super("Boss Bar Scale", "Makes boss bars smaller so they cover less of the screen", Category.VISUAL);
        syncOn(() -> BossBarAdapter.setBossBarScale(isEnabled() ? scale.getFloat() / 100f : 1f), scale);
    }
}
