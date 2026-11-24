package dev.telemetric.mixin;

import dev.telemetric.ui.chat.ChatScreenHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.CharacterEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Typed characters in the chat input go to the Emoji Picker's search box while the picker is open. */
@Mixin(EditBox.class)
public abstract class EditBoxMixin {

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void telemetric$emojiSearch(CharacterEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (!(Minecraft.getInstance().screen instanceof ChatScreen chat)) return;
        if (((ChatScreenAccessor) chat).telemetric$input() != (Object) this) return;
        if (ChatScreenHooks.charTyped(event.codepoint())) cir.setReturnValue(true);
    }
}
