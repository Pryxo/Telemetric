package dev.telemetric.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.telemetric.game.EntityVisualsAdapter;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.DisplayEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Player Size: Telos nametags (text displays) follow their scaled player. */
@Mixin(DisplayRenderer.class)
public abstract class DisplayRendererMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Display;Lnet/minecraft/client/renderer/entity/state/DisplayEntityRenderState;F)V",
            at = @At("RETURN"))
    private void telemetric$markNametag(Display display, DisplayEntityRenderState state, float partialTick, CallbackInfo ci) {
        EntityVisualsAdapter.Scale scale = display instanceof Display.TextDisplay text
                ? EntityVisualsAdapter.nametagScale(text) : null;
        if (scale != null || state.getData(EntityVisualsAdapter.SCALED_TAG) != null) {
            state.setData(EntityVisualsAdapter.SCALED_TAG, scale != null ? scale : EntityVisualsAdapter.Scale.NONE);
        }
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/DisplayEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD"))
    private void telemetric$scaleNametag(DisplayEntityRenderState state, PoseStack pose, SubmitNodeCollector collector,
                                         CameraRenderState camera, CallbackInfo ci) {
        // The dispatcher pushes and pops the pose around each entity, so this transform ends with the entity.
        EntityVisualsAdapter.scaleNametag(pose, state.getData(EntityVisualsAdapter.SCALED_TAG));
    }
}
