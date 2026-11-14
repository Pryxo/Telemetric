package dev.telemetric.module.impl.player;

import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;

/**
 * Holding jump re-jumps the instant you are back on the ground.
 * <p>
 * Vanilla already re-jumps while jump is held, but after every jump it starts a 10-tick cooldown
 * ({@code LivingEntity#noJumpDelay}). Short hops (low ceilings, landing on a higher block, slabs)
 * land before it runs out, so you stand still for a few ticks. This module clears the cooldown each
 * tick while jump is held, so the next jump happens on the first tick you touch the ground.
 */
public final class AutoJumpModule extends Module {

    public AutoJumpModule() {
        super("Auto Jump", "Hold jump to jump again the instant you land (removes the vanilla jump delay)",
                Category.PLAYER);
    }

    @Override
    public void onTick() {
        if (!PlayerAdapter.isInWorld() || !PlayerAdapter.isJumpKeyHeld()) return;
        if (PlayerAdapter.jumpCooldown() > 0) PlayerAdapter.clearJumpCooldown();
    }
}
