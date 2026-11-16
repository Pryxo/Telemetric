package dev.telemetric.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** Chat HUD internals for {@code game/ChatHudAdapter} (which line is under the mouse). */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {

    @Accessor("trimmedMessages")
    List<GuiMessage.Line> telemetric$getTrimmedMessages();

    @Accessor("chatScrollbarPos")
    int telemetric$getScrollPos();

    @Invoker("getScale")
    double telemetric$getScale();

    @Invoker("getWidth")
    int telemetric$getWidth();

    @Invoker("addMessageToDisplayQueue")
    void telemetric$addToDisplay(GuiMessage message);
}
