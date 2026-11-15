package dev.telemetric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.telemetric.game.BossBarAdapter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Boss Bar Scale: each bar and its title are drawn under one transform that scales them around the bar's top centre
 * ({@link BossBarAdapter#scaleFor}); bars below a shrunk one move up by the space it saved, so no gap is left.
 * Vanilla order per bar: {@code extractBar(x, y)}, then the title at {@code y - 9}, then {@code y += 19}.
 */
@Mixin(BossHealthOverlay.class)
public abstract class BossHealthOverlayMixin {

    /** Vertical space one bar takes (bar 10 + title 9). */
    @Unique private static final float SLOT = 19f;

    @Unique private float telemetric$scale = 1f;
    @Unique private float telemetric$shift;
    @Unique private int telemetric$barY;

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void telemetric$resetScale(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        telemetric$scale = 1f;
        telemetric$shift = 0f;
    }

    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/BossHealthOverlay;extractBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/world/BossEvent;)V"))
    private void telemetric$scaleBar(BossHealthOverlay self, GuiGraphicsExtractor graphics, int x, int y, BossEvent event,
                                     Operation<Void> original) {
        telemetric$scale = BossBarAdapter.scaleFor(event.getName());
        telemetric$barY = y;
        if (telemetric$scale == 1f && telemetric$shift == 0f) {
            original.call(self, graphics, x, y, event);
            return;
        }
        telemetric$push(graphics);
        original.call(self, graphics, x, y, event);
        graphics.pose().popMatrix();
    }

    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
    private void telemetric$scaleTitle(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int color,
                                       Operation<Void> original) {
        if (telemetric$scale == 1f && telemetric$shift == 0f) {
            original.call(graphics, font, text, x, y, color);
            return;
        }
        telemetric$push(graphics);
        original.call(graphics, font, text, x, y, color);
        graphics.pose().popMatrix();
        telemetric$shift += SLOT * (1f - telemetric$scale);
    }

    /** Scale around the current bar's top centre, then lift by the space earlier shrunk bars saved. */
    @Unique
    private void telemetric$push(GuiGraphicsExtractor graphics) {
        Matrix3x2fStack pose = graphics.pose();
        float cx = graphics.guiWidth() / 2f, top = telemetric$barY;
        pose.pushMatrix();
        pose.translate(cx, top - telemetric$shift);
        pose.scale(telemetric$scale, telemetric$scale);
        pose.translate(-cx, -top);
    }
}
