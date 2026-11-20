package dev.telemetric.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * MC ADAPTER (mixin accessor): the tooltip queued with {@code setTooltipForNextFrame}. Vanilla draws it in
 * {@code extractDeferredElements}, which has already run by the time {@code ScreenEvents.afterExtract} fires, so
 * {@link dev.telemetric.ui.render.Render2D#flushTooltip()} draws it itself.
 */
@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsExtractorAccessor {

    @Accessor("deferredTooltip")
    Runnable telemetric$getDeferredTooltip();

    @Accessor("deferredTooltip")
    void telemetric$setDeferredTooltip(Runnable tooltip);
}
