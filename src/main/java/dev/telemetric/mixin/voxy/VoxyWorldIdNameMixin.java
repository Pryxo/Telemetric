package dev.telemetric.mixin.voxy;

import dev.telemetric.game.VoxyAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Voxy (optional): the LOD folder name is {@code WorldIdentifier.getWorldId(id)} (a sha256 hash, used as
 * {@code {world_identifier}} in Voxy's storage path). For our stable Telos stores ({@code telos:hub}, seed 0) return a
 * readable name instead ({@code hub}). {@code @Pseudo}: skipped when Voxy is not installed.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.commonImpl.WorldIdentifier", remap = false)
public abstract class VoxyWorldIdNameMixin {

    @Inject(method = "getWorldId(Lme/cortex/voxy/commonImpl/WorldIdentifier;)Ljava/lang/String;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void telemetric$readableName(@org.spongepowered.asm.mixin.injection.Coerce Object id,
                                                CallbackInfoReturnable<String> cir) {
        String name = VoxyAdapter.readableWorldId(id);
        if (name != null) cir.setReturnValue(name);
    }
}
