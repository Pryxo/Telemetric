package dev.telemetric.mixin;

import dev.telemetric.game.CustomBagAdapter;
import dev.telemetric.game.DropAdapter;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Telos shows a loot bag drop as a totem pop: the bag trackers read its model first (Lifetime Stats), then Custom
 * Bags may swap its picture before it starts.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "displayItemActivation", at = @At("HEAD"))
    private void telemetric$bagPop(ItemStack stack, CallbackInfo ci) {
        DropAdapter.onTotemPop(stack);
        CustomBagAdapter.onDropAnimation(stack);
    }
}
