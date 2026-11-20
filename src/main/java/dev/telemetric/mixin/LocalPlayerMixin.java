package dev.telemetric.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.telemetric.game.PlayerAdapter;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * MC ADAPTER (mixin) for Auto Sprint. {@code LocalPlayer#aiStep} reads the sprint key through {@code Input#sprint()}
 * (the only call, checked with javap on 26.1.2). Answering "held" lets vanilla start sprinting under its usual rules
 * (moving forward, not hungry or blind, not using an item). It uses MixinExtras (bundled with Fabric Loader) so other
 * mods can wrap the same call.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {

    @ModifyExpressionValue(method = "aiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Input;sprint()Z"))
    private boolean telemetric$autoSprint(boolean held) {
        return held || PlayerAdapter.autoSprint();
    }
}
