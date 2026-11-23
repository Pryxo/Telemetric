package dev.telemetric.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes vanilla's private line-pipeline snippet so a see-through variant can reuse its shaders and format. */
@Mixin(RenderPipelines.class)
public interface RenderPipelinesAccessor {

    @Accessor("LINES_SNIPPET")
    static RenderPipeline.Snippet telemetric$linesSnippet() {
        throw new AssertionError();
    }

    @Accessor("GUI_TEXTURED_SNIPPET")
    static RenderPipeline.Snippet telemetric$guiTexturedSnippet() {
        throw new AssertionError();
    }
}
