package dev.telemetric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.telemetric.game.AnimatedTextAdapter;
import net.minecraft.network.chat.TextColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Animated text colours ({@link AnimatedTextAdapter}): wraps the one place a glyph's style colour is read while text
 * is prepared for drawing. Chained with other mods' wraps (MixinExtras) and {@code require = 0}: if a font mod
 * replaces this code the game still starts and animated text just stands still.
 */
@Mixin(targets = "net.minecraft.client.gui.Font$PreparedTextBuilder")
public abstract class FontAnimatedColorMixin {

    @WrapOperation(method = "getTextColor", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/chat/TextColor;getValue()I"))
    private int telemetric$animatedColor(TextColor color, Operation<Integer> original) {
        return AnimatedTextAdapter.color(color, original.call(color));
    }
}
