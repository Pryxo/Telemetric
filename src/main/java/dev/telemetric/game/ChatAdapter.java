package dev.telemetric.game;

import dev.telemetric.Telemetric;
import dev.telemetric.chat.ChatBus;
import dev.telemetric.chat.ChatMessage;
import dev.telemetric.chat.ChatSegment;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MC ADAPTER — turns <b>server system messages</b> into {@link ChatMessage}s for {@link ChatBus}.
 * <p>
 * Only {@code ClientReceiveMessageEvents.GAME} (+ {@code GAME_CANCELED}, so a chat-hiding mod does not blind us) is
 * used: Fabric fires it for {@code ClientboundSystemChatPacket} only. Player chat arrives as
 * {@code ClientboundPlayerChatPacket} / disguised chat ({@code CHAT} event) and is never listened to. Off Telos
 * nothing is dispatched.
 */
public final class ChatAdapter {

    private ChatAdapter() {}

    public static void register() {
        ClientReceiveMessageEvents.GAME.register(ChatAdapter::onSystemMessage);
        ClientReceiveMessageEvents.GAME_CANCELED.register(ChatAdapter::onSystemMessage);
    }

    private static void onSystemMessage(Component component, boolean overlay) {
        if (!ServerGate.onTelos()) return;
        ChatMessage message = convert(component, overlay);
        if (message.segments().isEmpty()) return;
        if (!overlay) Telemetric.debug("Chat: {}", message.describe());
        ChatBus.dispatch(message);
    }

    static ChatMessage convert(Component component, boolean overlay) {
        List<ChatSegment> runs = new ArrayList<>();
        component.visit((style, text) -> {
            runs.add(new ChatSegment(text, style.isBold(), style.isItalic(), style.isUnderlined(),
                    style.isStrikethrough(), style.isObfuscated(),
                    style.getClickEvent() != null || style.getHoverEvent() != null, fontId(style)));
            return Optional.empty();
        }, Style.EMPTY);
        return ChatMessage.of(runs, overlay);
    }

    /** null = default font; resource id otherwise ({@code "?"} for non-resource fonts such as sprites). */
    private static String fontId(Style style) {
        FontDescription font = style.getFont();
        if (font == null || FontDescription.DEFAULT.equals(font)) return null;
        if (font instanceof FontDescription.Resource res) return res.id().toString();
        return "?";
    }
}
