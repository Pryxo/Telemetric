package dev.telemetric.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The tooltip queued with {@code setTooltipForNextFrame}. Vanilla draws it in
 * {@code extractDeferredElements}, which has already run by the time {@code ScreenEvents.afterExtract} fires, so
 * {@link dev.telemetric.ui.render.Render2D#flushTooltip()} draws it itself. Also the GUI render state, so the 3D
 * preview can add its own picture-in-picture state ({@code ScenePreviewAdapter}).
 */
@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsExtractorAccessor {

    @Accessor("deferredTooltip")
    Runnable telemetric$getDeferredTooltip();

    @Accessor("deferredTooltip")
    void telemetric$setDeferredTooltip(Runnable tooltip);

    @Accessor("guiRenderState")
    GuiRenderState telemetric$guiRenderState();
}
