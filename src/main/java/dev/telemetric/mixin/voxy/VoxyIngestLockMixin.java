package dev.telemetric.mixin.voxy;

import dev.telemetric.game.VoxyAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Voxy (optional): every LOD write (chunk ingest, block-change ingest) asks
 * {@code VoxyClientInstance#isIngestEnabled(WorldIdentifier)} first. While {@link VoxyAdapter#ingestLocked()} is on
 * we answer false, so saved LODs are only read and never updated.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.VoxyClientInstance", remap = false)
public abstract class VoxyIngestLockMixin {

    @Inject(method = "isIngestEnabled(Lme/cortex/voxy/commonImpl/WorldIdentifier;)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void telemetric$lockLods(CallbackInfoReturnable<Boolean> cir) { // no args here, because WorldIdentifier isn't on our classpath
        if (VoxyAdapter.ingestLocked()) cir.setReturnValue(false);
    }
}
