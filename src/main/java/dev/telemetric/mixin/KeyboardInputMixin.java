package dev.telemetric.mixin;

import dev.telemetric.game.SneakAdapter;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MC ADAPTER (mixin) for Sneak Ability's "Sneak Key" method: after {@code KeyboardInput#tick} built this tick's
 * {@code keyPresses}, replace its shift flag while {@link SneakAdapter#forcedShift()} is set. Injecting at TAIL (not
 * into the {@code keyShift.isDown()} call) keeps working next to other mods that wrap that call (e.g. Freecam).
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void telemetric$forceShift(CallbackInfo ci) {
        Boolean forced = SneakAdapter.forcedShift();
        if (forced == null) return;
        ClientInput self = (ClientInput) (Object) this;
        Input k = self.keyPresses;
        if (k.shift() == forced) return;
        self.keyPresses = new Input(k.forward(), k.backward(), k.left(), k.right(), k.jump(), forced, k.sprint());
    }
}
