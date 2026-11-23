package dev.telemetric.mixin;

import dev.telemetric.game.CustomBagAdapter;
import dev.telemetric.game.DropAdapter;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Loot bag sounds: opening a bag starts the drop scan, and a bag's drop sound gets replaced by the player's own file
 * (and the totem pop sound Telos plays with it is muted). */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void telemetric$bagSounds(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        DropAdapter.onSound(sound.getIdentifier());
        if (CustomBagAdapter.replaceDropSound(sound.getIdentifier()) || CustomBagAdapter.muteTotemSound(sound)) {
            cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
        }
    }
}
