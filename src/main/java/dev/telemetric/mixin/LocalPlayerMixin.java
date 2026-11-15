package dev.telemetric.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.telemetric.game.PlayerAdapter;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * MC ADAPTER (mixin) — Auto Sprint: {@code LocalPlayer#aiStep} reads the sprint key through {@code Input#sprint()}
 * (the only call, verified javap 26.1.2); answering "held" lets vanilla start sprinting under its usual rules
 * (moving forward, not hungry / blind / using an item). MixinExtras (bundled with Fabric Loader) so other mods can
 * wrap the same call.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {

    @ModifyExpressionValue(method = "aiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Input;sprint()Z"))
    private boolean telemetric$autoSprint(boolean held) {
        return held || PlayerAdapter.autoSprint();
    }
}
