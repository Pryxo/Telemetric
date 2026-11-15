package dev.telemetric.mixin;

import dev.telemetric.game.CustomBagAdapter;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Custom Bags: Telos shows a loot bag drop as a totem pop — swap its picture before it starts. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "displayItemActivation", at = @At("HEAD"))
    private void telemetric$customBag(ItemStack stack, CallbackInfo ci) {
        CustomBagAdapter.onDropAnimation(stack);
    }
}
