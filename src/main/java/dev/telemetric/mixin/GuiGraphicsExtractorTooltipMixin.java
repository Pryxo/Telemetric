package dev.telemetric.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.telemetric.game.TooltipAdapter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Tooltip Scale: the whole {@code tooltip(...)} draw runs under {@code pose.scale(s)}, with the mouse position and the
 * screen size divided by {@code s}, so the vanilla positioner still keeps the tooltip on screen.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsExtractorTooltipMixin {

    @Unique private float telemetric$tipScale = 1f;

    @WrapMethod(method = "tooltip")
    private void telemetric$scaleTooltip(Font font, List<ClientTooltipComponent> lines, int x, int y,
                                         ClientTooltipPositioner positioner, Identifier style, Operation<Void> original) {
        float s = TooltipAdapter.tooltipScale();
        if (s == 1f) {
            original.call(font, lines, x, y, positioner, style);
            return;
        }
        GuiGraphicsExtractor self = (GuiGraphicsExtractor) (Object) this;
        self.pose().pushMatrix();
        self.pose().scale(s, s);
        telemetric$tipScale = s;
        try {
            original.call(font, lines, Math.round(x / s), Math.round(y / s), positioner, style);
        } finally {
            telemetric$tipScale = 1f;
            self.pose().popMatrix();
        }
    }

    @WrapOperation(method = "tooltip", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;guiWidth()I"))
    private int telemetric$scaledWidth(GuiGraphicsExtractor self, Operation<Integer> original) {
        return Math.round(original.call(self) / telemetric$tipScale);
    }

    @WrapOperation(method = "tooltip", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;guiHeight()I"))
    private int telemetric$scaledHeight(GuiGraphicsExtractor self, Operation<Integer> original) {
        return Math.round(original.call(self) / telemetric$tipScale);
    }
}
