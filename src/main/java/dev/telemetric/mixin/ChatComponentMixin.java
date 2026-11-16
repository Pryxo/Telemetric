package dev.telemetric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.telemetric.game.ChatHudAdapter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Chat HUD hooks for {@code game/ChatHudAdapter}: links on new lines, tab filter, censoring, longer history. */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {

    @ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Component telemetric$decorate(Component message) {
        return ChatHudAdapter.decorate(message);
    }

    /** Lines of other tabs stay in the history (allMessages) but are not shown. */
    @Inject(method = "addMessageToDisplayQueue", at = @At("HEAD"), cancellable = true)
    private void telemetric$filter(GuiMessage message, CallbackInfo ci) {
        if (!ChatHudAdapter.isVisible(message)) ci.cancel();
    }

    @WrapOperation(method = "addMessageToDisplayQueue", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/chat/GuiMessage;splitLines(Lnet/minecraft/client/gui/Font;I)Ljava/util/List;"))
    private List<FormattedCharSequence> telemetric$censor(GuiMessage message, Font font, int width,
                                                          Operation<List<FormattedCharSequence>> original) {
        return ChatHudAdapter.split(message, font, width, m -> original.call(m, font, width));
    }

    @ModifyConstant(method = "addMessageToQueue", constant = @Constant(intValue = 100))
    private int telemetric$history(int vanilla) {
        return ChatHudAdapter.history();
    }

    @ModifyConstant(method = "addMessageToDisplayQueue", constant = @Constant(intValue = 100))
    private int telemetric$displayLimit(int vanilla) {
        return ChatHudAdapter.displayLimit();
    }

    /** Every new line (not re-layouts): All Chat archive + unread counts. */
    @Inject(method = "addMessageToQueue", at = @At("HEAD"))
    private void telemetric$stored(GuiMessage message, CallbackInfo ci) {
        ChatHudAdapter.onStored(message);
    }

    /** All Chat open: the visible lines come from the archive instead of the trimmed history. */
    @Inject(method = "refreshTrimmedMessages", at = @At("HEAD"), cancellable = true)
    private void telemetric$refresh(CallbackInfo ci) {
        if (ChatHudAdapter.refreshFromArchive((ChatComponent) (Object) this)) ci.cancel();
    }

    /** The archive survives a chat clear (reconnect, F3 + D); All Chat shows it again. */
    @Inject(method = "clearMessages", at = @At("TAIL"))
    private void telemetric$cleared(boolean history, CallbackInfo ci) {
        ChatHudAdapter.afterClear();
    }
}
