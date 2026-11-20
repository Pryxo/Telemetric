package dev.telemetric.mixin;

import dev.telemetric.game.ChatHudAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Our own clicked chat links ({@code telemetric:link/<n>}) run on the client instead of going to the server. */
@Mixin(Screen.class)
public abstract class ScreenMixin {

    @Inject(method = "defaultHandleGameClickEvent", at = @At("HEAD"), cancellable = true)
    private static void telemetric$ownLinks(ClickEvent event, Minecraft minecraft, Screen screen, CallbackInfo ci) {
        if (ChatHudAdapter.onClick(event)) ci.cancel();
    }
}
