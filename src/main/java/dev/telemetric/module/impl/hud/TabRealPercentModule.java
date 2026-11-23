package dev.telemetric.module.impl.hud;

import dev.telemetric.Telemetric;
import dev.telemetric.game.ServerInfoAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;

/**
 * Shows each stat's real effect (wiki formula, same as Stats Info → Show Real %) right in the Tab list, e.g.
 * "Attack: 50 (17.3%)", so the Stats Info HUD element isn't needed for it.
 */
public final class TabRealPercentModule extends Module {

    private final BooleanSetting left = add(new BooleanSetting("Real % Left",
            "Put the real % in front of the value (\"Attack: (17.3%) 50\") instead of after it", false));

    public TabRealPercentModule() {
        super("Tab Real %", "Shows each stat's real % (wiki formula) next to it in the Tab list", Category.STATS);
        syncOn(this::sync, left);
        sync();
    }

    private void sync() {
        ServerInfoAdapter.setTabExtras(isEnabled() ? TabRealPercentModule::extra : null, left.get(),
                () -> Telemetric.get().theme().palette().textMuted());
    }

    private static String extra(String label, String value) {
        return StatFormulas.supported(label) ? StatFormulas.real(label, value) : null;
    }
}
