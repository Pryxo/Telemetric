package dev.telemetric.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.telemetric.game.CustomBagAdapter;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Custom Bags while the totem pop shows a custom bag picture: Picture Size scales it, Animation Speed slows it
 * (fewer counted ticks + a slowed partial tick, so it stays smooth).
 */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {

    @Shadow
    private int itemActivationTicks;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void telemetric$bagSlowTick(CallbackInfo ci) {
        if (!CustomBagAdapter.popTickAdvances(itemActivationTicks > 0)) ci.cancel();
    }

    @ModifyVariable(method = "renderItemActivationAnimation", at = @At("HEAD"), argsOnly = true)
    private float telemetric$bagSlowPartial(float partialTick) {
        return CustomBagAdapter.popPartialTick(partialTick);
    }

    @Inject(method = "renderItemActivationAnimation", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
    private void telemetric$bagScale(PoseStack pose, float partialTick, SubmitNodeCollector collector, CallbackInfo ci) {
        float s = CustomBagAdapter.popScale();
        if (s != 1f) pose.scale(s, s, s);
    }
}
