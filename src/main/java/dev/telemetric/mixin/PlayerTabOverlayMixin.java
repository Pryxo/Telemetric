package dev.telemetric.mixin;

import dev.telemetric.game.ModUserBadgeAdapter;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tab list: the Telemetric badge at the right edge of a mod user's entry, in place of the ping icon. */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {

    @Inject(method = "extractPingIcon", at = @At("HEAD"), cancellable = true)
    private void telemetric$modUserBadge(GuiGraphicsExtractor g, int width, int x, int y, PlayerInfo info,
                                         CallbackInfo ci) {
        if (ModUserBadgeAdapter.tabBadge(g, width, x, y, info)) ci.cancel();
    }
}
