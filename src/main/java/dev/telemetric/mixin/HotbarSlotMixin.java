package dev.telemetric.mixin;

import dev.telemetric.game.ItemRarityAdapter;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Item Rarity: the rarity background behind hotbar (and offhand) items. */
@Mixin(Gui.class)
public abstract class HotbarSlotMixin {

    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void telemetric$rarityBackground(GuiGraphicsExtractor graphics, int x, int y, DeltaTracker delta,
                                             Player player, ItemStack stack, int seed, CallbackInfo ci) {
        ItemRarityAdapter.drawSlot(graphics, stack, x, y, true);
    }
}
