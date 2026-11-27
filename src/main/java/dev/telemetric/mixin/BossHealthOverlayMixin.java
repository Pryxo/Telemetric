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
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Boss Bar Scale: each bar and its title are drawn under one transform that scales them around the bar's top centre
 * ({@link BossBarAdapter#scaleFor}). Bars below a shrunk one move up by the space it saved, so no gap is left.
 * Vanilla's order per bar is {@code extractBar(x, y)}, then the title at {@code y - 9}, then {@code y += 19}.
 */
@Mixin(BossHealthOverlay.class)
public abstract class BossHealthOverlayMixin {

    /** The vertical space one bar takes (bar 10 + title 9). */
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

    // ---- Boss Stage Tracker → Color Boss Bar ----

    @Unique private int telemetric$tint;
    @Unique private int telemetric$barTint, telemetric$part, telemetric$blit;
    @Unique private static final int CLEAN_BACK = 0xC0101010;

    /** Remembers the tint of the bar being drawn (only its progress part gets coloured). */
    @Inject(method = "extractBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/world/BossEvent;)V",
            at = @At("HEAD"))
    private void telemetric$tintStart(GuiGraphicsExtractor graphics, int x, int y, BossEvent event, CallbackInfo ci) {
        telemetric$barTint = BossBarAdapter.tintFor(event.getName());
        telemetric$part = 0;
        telemetric$tint = 0;
    }

    /** The first inner call draws the background (left alone), the second the progress (tinted). */
    @WrapOperation(method = "extractBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/world/BossEvent;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/BossHealthOverlay;extractBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/world/BossEvent;I[Lnet/minecraft/resources/Identifier;[Lnet/minecraft/resources/Identifier;)V"))
    private void telemetric$tintPart(BossHealthOverlay self, GuiGraphicsExtractor graphics, int x, int y, BossEvent event,
                                     int width, Identifier[] bar, Identifier[] overlay, Operation<Void> original) {
        telemetric$tint = ++telemetric$part == 2 ? telemetric$barTint : 0;
        telemetric$blit = 0;
        if (telemetric$barTint != 0 && BossBarAdapter.cleanBar()) {
            // Clean Boss Bar: dark track (part 1) and a flat stage-colour fill (part 2), no sprite / notches
            if (width > 0) graphics.fill(x, y, x + width, y + 5, telemetric$part == 2 ? telemetric$barTint : CLEAN_BACK);
            telemetric$tint = 0;
            return;
        }
        try {
            original.call(self, graphics, x, y, event, width, bar, overlay);
        } finally {
            telemetric$tint = 0;
        }
    }

    /** Tinted bars use the white sprite so the stage colour shows true instead of mixed with the bar colour. */
    @WrapOperation(method = "extractBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/world/BossEvent;I[Lnet/minecraft/resources/Identifier;[Lnet/minecraft/resources/Identifier;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/BossEvent;getColor()Lnet/minecraft/world/BossEvent$BossBarColor;"))
    private BossEvent.BossBarColor telemetric$tintColor(BossEvent event, Operation<BossEvent.BossBarColor> original) {
        return telemetric$tint != 0 ? BossEvent.BossBarColor.WHITE : original.call(event);
    }

    @WrapOperation(method = "extractBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/world/BossEvent;I[Lnet/minecraft/resources/Identifier;[Lnet/minecraft/resources/Identifier;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIIIII)V"))
    private void telemetric$tintSprite(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite,
                                       int texW, int texH, int u, int v, int x, int y, int w, int h,
                                       Operation<Void> original) {
        if (telemetric$tint == 0) original.call(graphics, pipeline, sprite, texW, texH, u, v, x, y, w, h);
        else graphics.blitSprite(pipeline, sprite, texW, texH, u, v, x, y, w, h, telemetric$tint);
    }

    /** Scales around the current bar's top centre, then lifts it by the space that earlier shrunk bars saved. */
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
