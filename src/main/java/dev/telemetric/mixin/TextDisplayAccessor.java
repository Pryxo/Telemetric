package dev.telemetric.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * MC ADAPTER (mixin): exposes the private {@code Display.TextDisplay#getText()} (the synced text component). Only
 * used through {@link dev.telemetric.game.DamageNumberAdapter} and {@link dev.telemetric.game.BossStageAdapter}.
 */
@Mixin(Display.TextDisplay.class)
public interface TextDisplayAccessor {

    @Invoker("getText")
    Component telemetric$getText();
}
