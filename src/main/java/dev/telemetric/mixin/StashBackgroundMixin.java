package dev.telemetric.mixin;

import dev.telemetric.game.StashAdapter;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** MC ADAPTER (mixin): skips the chest texture and background while the stash overlay replaces the stash. */
@Mixin(ContainerScreen.class)
public abstract class StashBackgroundMixin {

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void telemetric$hideBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta,
                                           CallbackInfo ci) {
        if (StashAdapter.replacesScreen((Object) this)) ci.cancel();
    }
}
