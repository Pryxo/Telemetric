package dev.telemetric.mixin;

import dev.telemetric.game.CustomBagAdapter;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Custom Bags: a loot bag's drop sound is replaced by the player's own file. */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void telemetric$customBagSound(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (CustomBagAdapter.replaceDropSound(sound.getIdentifier())) cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
    }
}
