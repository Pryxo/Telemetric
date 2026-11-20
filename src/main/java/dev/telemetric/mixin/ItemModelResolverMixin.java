package dev.telemetric.mixin;

import dev.telemetric.game.EntityVisualsAdapter;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Hide Armor → Show True Items: while revealing, skinned items are drawn with their base item's model. */
@Mixin(ItemModelResolver.class)
public abstract class ItemModelResolverMixin {

    @ModifyVariable(method = "updateForTopItem", at = @At("HEAD"), argsOnly = true)
    private ItemStack telemetric$revealTrueItem(ItemStack stack) {
        return EntityVisualsAdapter.revealItem(stack);
    }
}
