package dev.telemetric.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The container screen's (protected) position and size, used to place the stash overlay. */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {

    @Accessor("leftPos")
    int telemetric$leftPos();

    @Accessor("topPos")
    int telemetric$topPos();

    @Accessor("imageWidth")
    int telemetric$imageWidth();

    @Accessor("imageHeight")
    int telemetric$imageHeight();
}
