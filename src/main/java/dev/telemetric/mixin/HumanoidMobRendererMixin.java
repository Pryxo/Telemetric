package dev.telemetric.mixin;

import dev.telemetric.game.EntityVisualsAdapter;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hide Armor → Show True Items: worn skinned armor is drawn with its base piece's armor model while revealing. */
@Mixin(HumanoidMobRenderer.class)
public abstract class HumanoidMobRendererMixin {

    @Inject(method = "getEquipmentIfRenderable", at = @At("HEAD"), cancellable = true)
    private static void telemetric$revealWornArmor(LivingEntity entity, EquipmentSlot slot,
                                                   CallbackInfoReturnable<ItemStack> cir) {
        if (!EntityVisualsAdapter.isRevealing()) return;
        ItemStack real = entity.getItemBySlot(slot);
        ItemStack worn = EntityVisualsAdapter.revealWorn(real);
        if (worn != real) cir.setReturnValue(worn);
    }
}
