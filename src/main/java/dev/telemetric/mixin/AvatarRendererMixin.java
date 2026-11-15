package dev.telemetric.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.telemetric.game.EntityVisualsAdapter;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Player Size: marks scaled players while extracting, then scales their model (and vanilla name tag). */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("HEAD"))
    private void telemetric$markScaled(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        boolean scaled = EntityVisualsAdapter.shouldScalePlayer(avatar);
        if (scaled || state.getData(EntityVisualsAdapter.SCALED) != null) state.setData(EntityVisualsAdapter.SCALED, scaled);
    }

    @Inject(method = "scale(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At("HEAD"))
    private void telemetric$scaleModel(AvatarRenderState state, PoseStack pose, CallbackInfo ci) {
        if (Boolean.TRUE.equals(state.getData(EntityVisualsAdapter.SCALED))) EntityVisualsAdapter.scalePlayer(pose);
    }

    @Inject(method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", shift = At.Shift.AFTER),
            require = 0)
    private void telemetric$scaleNameTag(AvatarRenderState state, PoseStack pose, SubmitNodeCollector collector,
                                         CameraRenderState camera, CallbackInfo ci) {
        if (Boolean.TRUE.equals(state.getData(EntityVisualsAdapter.SCALED))) EntityVisualsAdapter.scalePlayer(pose);
    }
}
