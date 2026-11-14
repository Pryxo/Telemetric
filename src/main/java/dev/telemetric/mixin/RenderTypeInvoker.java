package dev.telemetric.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the package-private {@code RenderType.create} factory. */
@Mixin(RenderType.class)
public interface RenderTypeInvoker {

    @Invoker("create")
    static RenderType telemetric$create(String name, RenderSetup setup) {
        throw new AssertionError();
    }
}
