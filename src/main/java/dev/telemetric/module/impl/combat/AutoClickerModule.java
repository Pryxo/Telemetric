package dev.telemetric.module.impl.combat;

import dev.telemetric.game.CombatAdapter;
import dev.telemetric.game.CombatAdapter.ItemInfo;
import dev.telemetric.game.CombatAdapter.Kind;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.NumberSetting;

/**
 * Auto Clicker: while the attack key is held it swings every tick (Telos fires weapons on the swing), so holding left
 * click attacks as fast as the weapon allows. With a <b>Primed</b> weapon it can instead wait until the next hit is
 * primed (2× damage, see {@link PrimedTracker}).
 */
public final class AutoClickerModule extends Module {

    private final BooleanSetting onlyWhenReady = add(new BooleanSetting("Only When Ready",
            "Skip swings while the held item is on cooldown", true));
    private final BooleanSetting weaponsOnly = add(new BooleanSetting("Weapons Only",
            "Only click while holding a Telos weapon", true));
    private final BooleanSetting waitForPrimed = add(new BooleanSetting("Wait For Primed",
            "With a Primed weapon, only attack once the next hit deals 2x damage", true));
    private final BooleanSetting swapResetsPrimed = add(new BooleanSetting("Swap Resets Primed",
            "Count the Primed wait from when you swapped to the weapon (Primed cancels on swap)", true));
    private final NumberSetting primedDelay = add(new NumberSetting("Primed Delay",
            "Extra ticks to wait after Primed is ready, for high ping (0 = none)", 0, 0, 6, 1));

    public AutoClickerModule() {
        super("Auto Clicker", "Hold attack to keep attacking (one swing per tick)", Category.COMBAT);
        swapResetsPrimed.visibleWhen(waitForPrimed::get);
        primedDelay.visibleWhen(waitForPrimed::get);
    }

    @Override
    public void onTick() {
        if (!PlayerAdapter.isAttackKeyHeld()) return;
        ItemInfo main = CombatAdapter.mainHand();
        if (weaponsOnly.get() && (main == null || main.kind() != Kind.WEAPON)) return;
        if (onlyWhenReady.get() && onCooldown(main)) return;
        if (waitForPrimed.get() && PrimedTracker.hasPrimed(main)
                && !PrimedTracker.get().isPrimed(main, swapResetsPrimed.get(),
                        System.currentTimeMillis() - Math.round(primedDelay.get()) * 50L)) return;
        PlayerAdapter.swingMainHand();
    }

    /** Asks the tracker first (it keeps running across world changes), with vanilla's live cooldown as a fallback. */
    private static boolean onCooldown(ItemInfo main) {
        return (main != null && CooldownTracker.get().get(main.group()) != null) || CombatAdapter.mainHandOnCooldown();
    }
}
