package dev.telemetric.mixin;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * MC ADAPTER (mixin): the chat input box ({@code ChatScreen#input}), so the Emoji Picker can insert an emoji code at
 * the cursor. Only used through {@link dev.telemetric.game.ChatHudAdapter}.
 */
@Mixin(ChatScreen.class)
public interface ChatScreenAccessor {

    @Accessor("input")
    EditBox telemetric$input();
}
