package dev.telemetric.mixin;

import dev.telemetric.game.ItemRarityAdapter;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Item Rarity: the rarity background behind each container slot's item. */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerSlotMixin {

    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void telemetric$rarityBackground(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY,
                                             CallbackInfo ci) {
        ItemRarityAdapter.drawSlot(graphics, slot.getItem(), slot.x, slot.y, false);
    }
}
