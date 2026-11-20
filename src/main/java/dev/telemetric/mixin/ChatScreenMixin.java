package dev.telemetric.mixin;

import dev.telemetric.ui.chat.ChatScreenHooks;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Chat screen hooks for {@code ui/chat/ChatScreenHooks}: the tab bar, tab keys, right-click actions and queued sends. */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {

    /** Drawn before the command suggestions so they stay on top. */
    @Inject(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/CommandSuggestions;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V"))
    private void telemetric$tabBar(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ChatScreenHooks.render(graphics, mouseX, mouseY);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void telemetric$click(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (ChatScreenHooks.mouseClicked(event.x(), event.y(), event.button())) cir.setReturnValue(true);
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void telemetric$scroll(double mouseX, double mouseY, double scrollX, double scrollY, CallbackInfoReturnable<Boolean> cir) {
        if (ChatScreenHooks.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) cir.setReturnValue(true);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void telemetric$key(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (ChatScreenHooks.keyPressed(event.key(), event.modifiers())) cir.setReturnValue(true);
    }

    @Inject(method = "handleChatInput", at = @At("HEAD"), cancellable = true)
    private void telemetric$send(String message, boolean addToHistory, CallbackInfo ci) {
        if (ChatScreenHooks.onSend(message, addToHistory)) ci.cancel();
    }
}
