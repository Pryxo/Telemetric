package dev.telemetric.mixin;

import dev.telemetric.game.EntityVisualsAdapter;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hide Armor: Telos helmets are items on the head, cleared from the render state like a hidden helmet. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("TAIL"))
    private void telemetric$hideHeadItem(LivingEntity entity, LivingEntityRenderState state, float partialTick, CallbackInfo ci) {
        if (EntityVisualsAdapter.hideHeadItem(entity)) state.headItem.clear();
    }
}
