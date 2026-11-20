package dev.telemetric.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * MC ADAPTER (mixin): exposes {@code LivingEntity#noJumpDelay}, the vanilla 10-tick cooldown that {@code aiStep}
 * sets after every ground jump. Only used through {@link dev.telemetric.game.PlayerAdapter}.
 */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {

    @Accessor("noJumpDelay")
    int telemetric$getNoJumpDelay();

    @Accessor("noJumpDelay")
    void telemetric$setNoJumpDelay(int ticks);
}
