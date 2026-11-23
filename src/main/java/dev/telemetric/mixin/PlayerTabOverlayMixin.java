package dev.telemetric.mixin;

import dev.telemetric.game.ModUserBadgeAdapter;
import dev.telemetric.game.ServerInfoAdapter;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tab list: the Telemetric badge at the right edge of a mod user's entry, in place of the ping icon, and the
 * Tab Real % extras next to Telos stat entries.
 */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {

    @Inject(method = "extractPingIcon", at = @At("HEAD"), cancellable = true)
    private void telemetric$modUserBadge(GuiGraphicsExtractor g, int width, int x, int y, PlayerInfo info,
                                         CallbackInfo ci) {
        if (ModUserBadgeAdapter.tabBadge(g, width, x, y, info)) ci.cancel();
    }

    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void telemetric$tabExtras(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        Component name = cir.getReturnValue();
        Component out = ServerInfoAdapter.decorateTabName(info, name);
        if (out != name) cir.setReturnValue(out);
    }
}
