package dev.telemetric.mixin;

import dev.telemetric.game.StashAdapter;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MC ADAPTER (mixin): while the stash overlay replaces the Telos stash, the container's own drawing (slots,
 * labels/texture glyph, carried item, tooltip) is skipped, and the overlay draws everything in
 * {@code ScreenEvents.afterExtract}. {@code hoveredSlot} is cleared so the vanilla slot keys (Q, 1–9, F) do nothing.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class StashScreenMixin {

    @Shadow
    protected Slot hoveredSlot;

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void telemetric$hideForStash(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta,
                                         CallbackInfo ci) {
        if (StashAdapter.replacesScreen((Object) this)) {
            hoveredSlot = null;
            ci.cancel();
        }
    }
}
