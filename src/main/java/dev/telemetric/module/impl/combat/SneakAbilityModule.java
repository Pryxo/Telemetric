package dev.telemetric.module.impl.combat;

import dev.telemetric.game.SneakAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.module.ModuleManager;
import dev.telemetric.setting.impl.KeybindSetting;
import dev.telemetric.setting.impl.ModeSetting;
import dev.telemetric.setting.impl.NumberSetting;

import java.util.ArrayDeque;

/**
 * Combat → Sneak Ability: one key for Telos abilities that need "sneak N times in quick succession" (e.g. Shadow Silk
 * leggings, 3 sneaks). Each sneak is shift on + shift off, sent through {@link SneakAdapter} in one of three ways:
 * <ul>
 *   <li>Packet Burst: every shift toggle as its own input packet in the same moment (fastest, no crouch).</li>
 *   <li>Packet Ticks: one shift toggle per client tick (2 × Sneaks ticks, no crouch).</li>
 *   <li>Sneak Key: the vanilla sneak key is held / released for one tick each (you really crouch).</li>
 * </ul>
 * Every sequence ends on the shift state it started with, so vanilla's own input stays in sync.
 */
public final class SneakAbilityModule extends Module {

    public static final String BURST = "Packet Burst", TICKS = "Packet Ticks", KEY = "Sneak Key";

    private final KeybindSetting key = add(new KeybindSetting("Ability Key",
            "Sneaks the set number of times to trigger a sneak ability", KeybindSetting.NONE).onPress(this::activate));
    private final NumberSetting sneaks = add(new NumberSetting("Sneaks",
            "How many sneaks the ability needs (Shadow Silk: 3)", 3, 1, 5, 1));
    private final ModeSetting method = add(new ModeSetting("Method",
            "Packet Burst: all at once (fastest). Packet Ticks: one toggle per tick. Sneak Key: really crouches, one tick each",
            BURST, BURST, TICKS, KEY));

    /** Shift states still to apply, one per tick (Packet Ticks / Sneak Key). */
    private final ArrayDeque<Boolean> queue = new ArrayDeque<>();
    private boolean keyMode;
    private long lastStepTick = -1;

    public SneakAbilityModule() {
        super("Sneak Ability", "One key for abilities that need several sneaks in a row", Category.COMBAT);
    }

    /** Runs the sneak sequence with the current Method (ignored while one is still running). */
    public void activate() {
        if (!queue.isEmpty() || !SneakAdapter.ready()) return;
        boolean base = SneakAdapter.sentShift();
        int n = sneaks.getInt();
        if (method.is(BURST)) {
            for (int i = 0; i < n; i++) {
                SneakAdapter.sendShift(!base);
                SneakAdapter.sendShift(base);
            }
            return;
        }
        for (int i = 0; i < n; i++) {
            queue.add(!base);
            queue.add(base);
        }
        keyMode = method.is(KEY);
        lastStepTick = -1;
        step();
    }

    @Override
    public void onTick() {
        if (!queue.isEmpty()) step();
        else if (keyMode) {
            keyMode = false;
            SneakAdapter.forceShiftKey(null);
        }
    }

    private void step() {
        long tick = ModuleManager.tickCount();
        if (tick == lastStepTick) return;
        lastStepTick = tick;
        boolean shift = queue.poll();
        if (keyMode) SneakAdapter.forceShiftKey(shift);
        else SneakAdapter.sendShift(shift);
    }

    @Override
    protected void onDisable() {
        queue.clear();
        keyMode = false;
        SneakAdapter.forceShiftKey(null);
    }
}
