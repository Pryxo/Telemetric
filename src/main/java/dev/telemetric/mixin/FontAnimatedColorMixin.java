package dev.telemetric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.telemetric.game.AnimatedTextAdapter;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Animated text ({@link AnimatedTextAdapter}). It wraps the one place where a glyph's style colour is read while
 * text is prepared for drawing, and the one place where a glyph gets placed (for the wave bob). It chains with other
 * mods' wraps (MixinExtras) and uses {@code require = 0}, so if a font mod replaces this code the game still starts
 * and animated text just stands still.
 */
@Mixin(targets = "net.minecraft.client.gui.Font$PreparedTextBuilder")
public abstract class FontAnimatedColorMixin {

    @WrapOperation(method = "getTextColor", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/chat/TextColor;getValue()I"))
    private int telemetric$animatedColor(TextColor color, Operation<Integer> original) {
        return AnimatedTextAdapter.color(color, original.call(color));
    }

    @WrapOperation(method = "accept(ILnet/minecraft/network/chat/Style;Lnet/minecraft/client/gui/font/glyphs/BakedGlyph;)Z",
            require = 0,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/font/glyphs/BakedGlyph;createGlyph(FFIILnet/minecraft/network/chat/Style;FF)Lnet/minecraft/client/gui/font/TextRenderable$Styled;"))
    private TextRenderable.Styled telemetric$waveGlyph(BakedGlyph glyph, float x, float y, int color, int shadowColor,
                                                       Style style, float boldOffset, float shadowOffset,
                                                       Operation<TextRenderable.Styled> original) {
        return original.call(glyph, x, y + AnimatedTextAdapter.waveY(style), color, shadowColor, style,
                boldOffset, shadowOffset);
    }
}
