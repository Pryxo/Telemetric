package dev.telemetric.game;

import dev.telemetric.Telemetric;
import dev.telemetric.chat.ChatBus;
import dev.telemetric.chat.ChatMessage;
import dev.telemetric.chat.ChatSegment;
import dev.telemetric.chat.ChatText;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.objects.AtlasSprite;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MC ADAPTER — turns <b>server system messages</b> into {@link ChatMessage}s for {@link ChatBus}, hides the ones a
 * feature asked to hide ({@link ChatBus#hide}) and shows client-side lines ({@link #post}).
 * <p>
 * Only {@code ClientReceiveMessageEvents.GAME} (+ {@code GAME_CANCELED}, so a chat-hiding mod does not blind us) is
 * used: Fabric fires it for {@code ClientboundSystemChatPacket} only. Player chat arrives as
 * {@code ClientboundPlayerChatPacket} / disguised chat ({@code CHAT} event) and is never listened to. Off Telos
 * nothing is dispatched.
 */
public final class ChatAdapter {

    private ChatAdapter() {}

    public static void register() {
        ClientReceiveMessageEvents.ALLOW_GAME.register(ChatAdapter::allow);
        ClientReceiveMessageEvents.GAME.register(ChatAdapter::onSystemMessage);
        ClientReceiveMessageEvents.GAME_CANCELED.register(ChatAdapter::onSystemMessage);
    }

    private static boolean allow(Component component, boolean overlay) {
        if (!ServerGate.onTelos() || !ChatBus.hasHideRules()) return true;
        ChatMessage message = convert(component, overlay);
        return message.segments().isEmpty() || !ChatBus.shouldHide(message);
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

    // ------------------------------------------------------------------ client lines

    /** Shows a client-side line in the chat (never sent to the server). Safe from any thread. */
    public static void post(ChatText text) {
        if (text == null || text.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.gui == null) return;
            ChatText.Tag tag = text.tag();
            if (tag == null) mc.gui.getChat().addClientSystemMessage(toComponent(text));
            else mc.gui.getChat().addPlayerMessage(toComponent(text), null,
                    new GuiMessageTag(tag.rgb(), null, tag.text() == null ? null : toComponent(tag.text()), tag.logTag()));
        });
    }

    static Component toComponent(ChatText text) {
        MutableComponent out = Component.empty();
        for (ChatText.Span s : text.spans()) {
            Style style = Style.EMPTY;
            if (s.rgb() >= 0) style = style.withColor(TextColor.fromRgb(s.rgb()));
            if ((s.styles() & ChatText.BOLD) != 0) style = style.withBold(true);
            if ((s.styles() & ChatText.UNDERLINED) != 0) style = style.withUnderlined(true);
            if (s.font() != null) {
                style = style.withFont(new FontDescription.Resource(Identifier.parse(s.font())));
            }
            if (s.hover() != null) style = style.withHoverEvent(new HoverEvent.ShowText(toComponent(s.hover())));
            if (s.suggest() != null) style = style.withClickEvent(new ClickEvent.SuggestCommand(s.suggest()));
            Identifier sprite = s.sprite() == null ? null : Identifier.tryParse(s.sprite());
            if (sprite != null) {
                out.append(Component.object(new AtlasSprite(AtlasSprite.DEFAULT_ATLAS, sprite), Component.literal(s.text()))
                        .withStyle(style));
            } else {
                out.append(Component.literal(s.text()).withStyle(style));
            }
        }
        return out;
    }
}
