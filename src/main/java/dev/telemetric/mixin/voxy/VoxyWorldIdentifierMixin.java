package dev.telemetric.mixin.voxy;

import dev.telemetric.game.VoxyAdapter;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Voxy (optional): every lookup of a level's world identifier goes through {@code WorldIdentifier.of(Level)}
 * (renderer, chunk ingest, commands). While {@link VoxyAdapter} has a stable dungeon identifier for this level, we
 * return that instead. {@code @Pseudo}: skipped when Voxy isn't installed.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.commonImpl.WorldIdentifier", remap = false)
public abstract class VoxyWorldIdentifierMixin {

    @Inject(method = "of(Lnet/minecraft/world/level/Level;)Lme/cortex/voxy/commonImpl/WorldIdentifier;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void telemetric$stableDungeonId(Level level, CallbackInfoReturnable<Object> cir) {
        if (VoxyAdapter.held(level)) { // the store isn't decided yet, so no renderer and no ingest (see VoxyAdapter#held)
            cir.setReturnValue(null);
            return;
        }
        Object id = VoxyAdapter.override(level);
        if (id != null) cir.setReturnValue(id);
    }
}
