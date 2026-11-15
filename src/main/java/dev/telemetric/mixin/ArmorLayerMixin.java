package dev.telemetric.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.telemetric.game.EntityVisualsAdapter;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hide Armor: skips worn armor pieces of players. */
@Mixin(HumanoidArmorLayer.class)
public abstract class ArmorLayerMixin {

    @Inject(method = "renderArmorPiece", at = @At("HEAD"), cancellable = true)
    private void telemetric$hideArmor(PoseStack pose, SubmitNodeCollector collector, ItemStack stack, EquipmentSlot slot,
                                      int light, HumanoidRenderState state, CallbackInfo ci) {
        if (state instanceof AvatarRenderState avatar && EntityVisualsAdapter.hideArmorPiece(avatar.id, slot)) ci.cancel();
    }
}
