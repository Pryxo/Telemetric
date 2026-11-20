package dev.telemetric.mixin.voxy;

import dev.telemetric.game.VoxyAdapter;
import java.nio.file.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Voxy (optional): Voxy saves LODs per server address ({@code .voxy/saves/<ip>/}), so every Telos region address
 * would get its own copy. On Telos (any address, {@code ServerGate}) we use one folder instead:
 * {@code .voxy/saves/telosrealms/}. It's read once when Voxy's instance is created (on join).
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.VoxyClientInstance", remap = false)
public abstract class VoxyBasePathMixin {

    @Inject(method = "getBasePath()Ljava/nio/file/Path;", at = @At("RETURN"), cancellable = true, require = 0)
    private static void telemetric$sharedTelosPath(CallbackInfoReturnable<Path> cir) {
        Path shared = VoxyAdapter.basePathOverride();
        if (shared != null) cir.setReturnValue(shared);
    }
}
