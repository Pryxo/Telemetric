package dev.telemetric.mixin;

import dev.telemetric.game.ViewAdapter;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fullbright: once the vanilla values are extracted, raises the lightmap's ambient colour towards white. */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapMixin {

    @Inject(method = "extract", at = @At("RETURN"))
    private void telemetric$fullbright(LightmapRenderState state, float partialTick, CallbackInfo ci) {
        float s = ViewAdapter.fullbright();
        if (s <= 0 || !state.needsUpdate || state.ambientColor == null) return;
        Vector3f c = new Vector3f(state.ambientColor);
        state.ambientColor = c.lerp(LightmapRenderStateExtractor.WHITE, s);
    }
}
