package dev.telemetric.module.impl.combat;

import dev.telemetric.game.CombatAdapter;
import dev.telemetric.game.CombatAdapter.ItemInfo;
import dev.telemetric.game.CombatAdapter.Kind;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;

/**
 * Auto Clicker: while the attack key is held, swings every tick (Telos fires weapons on the swing), so holding
 * left click attacks as fast as the weapon allows.
 */
public final class AutoClickerModule extends Module {

    private final BooleanSetting onlyWhenReady = add(new BooleanSetting("Only When Ready",
            "Skip swings while the held item is on cooldown", true));
    private final BooleanSetting weaponsOnly = add(new BooleanSetting("Weapons Only",
            "Only click while holding a Telos weapon", true));

    public AutoClickerModule() {
        super("Auto Clicker", "Hold attack to keep attacking (one swing per tick)", Category.COMBAT);
    }

    @Override
    public void onTick() {
        if (!PlayerAdapter.isAttackKeyHeld()) return;
        ItemInfo main = CombatAdapter.mainHand();
        if (weaponsOnly.get() && (main == null || main.kind() != Kind.WEAPON)) return;
        if (onlyWhenReady.get() && onCooldown(main)) return;
        PlayerAdapter.swingMainHand();
    }

    /** Tracker first (keeps running across world changes), vanilla's live cooldown as a fallback. */
    private static boolean onCooldown(ItemInfo main) {
        return (main != null && CooldownTracker.get().get(main.group()) != null) || CombatAdapter.mainHandOnCooldown();
    }
}
