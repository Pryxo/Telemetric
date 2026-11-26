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
 * Turns server system messages into {@link ChatMessage}s for {@link ChatBus}, hides the ones a feature asked to hide
 * ({@link ChatBus#hide}) and shows client-side lines ({@link #post}).
 * <p>
 * Only {@code ClientReceiveMessageEvents.GAME} is used, plus {@code GAME_CANCELED} so a chat-hiding mod can't blind us.
 * Fabric fires GAME only for {@code ClientboundSystemChatPacket}; player chat is never listened to. Off Telos nothing
 * is dispatched.
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
        if (message.segments().isEmpty()) return true;
        ChatBus.Visibility visibility = ChatBus.visibility(message);
        if (visibility == ChatBus.Visibility.SHOW) return true;
        // All Chat keeps hidden lines. Replaced lines are dropped and their Telemetric line shows instead.
        if (!overlay && visibility == ChatBus.Visibility.HIDE) ChatHudAdapter.onHidden(component);
        return false;
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

    /** Resource id of the font, null for the default font, "?" for fonts that aren't resources (sprites). */
    private static String fontId(Style style) {
        FontDescription font = style.getFont();
        if (font == null || FontDescription.DEFAULT.equals(font)) return null;
        if (font instanceof FontDescription.Resource res) return res.id().toString();
        return "?";
    }

    /** Shows a client-side line in chat. Never sent to the server; safe from any thread. */
    public static void post(ChatText text) {
        if (text == null || text.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.gui == null) return;
            ChatText.Tag tag = text.tag();
            Component line = text.dividerStops() != null ? divider(mc, text.dividerStops()) : toComponent(text);
            if (text.centered() && text.dividerStops() == null) line = centered(mc, line);
            if (tag == null) mc.gui.getChat().addClientSystemMessage(line);
            else mc.gui.getChat().addPlayerMessage(line, null,
                    new GuiMessageTag(tag.rgb(), null, tag.text() == null ? null : toComponent(tag.text()), tag.logTag()));
        });
    }

    /** Wrap width in unscaled chat pixels, at the current chat scale and width. */
    private static int chatWidth(Minecraft mc) {
        double scale = Math.max(0.01, mc.options.chatScale().get());
        return (int) Math.floor(net.minecraft.client.gui.components.ChatComponent.getWidth(
                mc.options.chatWidth().get()) / scale);
    }

    /** Line of strikethrough spaces filling the chat width, with a bright middle and dark ends. */
    private static Component divider(Minecraft mc, int[] stops) {
        Style strike = Style.EMPTY.withStrikethrough(true);
        int space = Math.max(1, mc.font.width(Component.literal(" ").withStyle(strike)));
        Component mid = Component.literal(" ✦ ").withStyle(Style.EMPTY.withBold(true).withColor(TextColor.fromRgb(stops[0])));
        int free = chatWidth(mc) - mc.font.width(mid) - space; // one space of slack so it never wraps
        int side = Math.max(3, free / 2 / space);
        MutableComponent out = Component.empty();
        for (int i = 0; i < side; i++) {
            double t = side <= 1 ? 1 : (double) i / (side - 1);
            out.append(Component.literal(" ").withStyle(strike.withColor(TextColor.fromRgb(
                    dev.telemetric.chat.ChatStyle.colorAt(stops, 1 - t)))));
        }
        out.append(mid);
        for (int i = 0; i < side; i++) {
            double t = side <= 1 ? 1 : (double) i / (side - 1);
            out.append(Component.literal(" ").withStyle(strike.withColor(TextColor.fromRgb(
                    dev.telemetric.chat.ChatStyle.colorAt(stops, t)))));
        }
        return out;
    }

    /** Pads {@code line} with leading spaces to centre it, using the width chat wraps at. */
    private static Component centered(Minecraft mc, Component line) {
        int free = chatWidth(mc) - mc.font.width(line);
        int space = Math.max(1, mc.font.width(" "));
        if (free <= space * 2) return line;
        return Component.empty().append(Component.literal(" ".repeat(free / 2 / space))).append(line);
    }

    static Component toComponent(ChatText text) {
        MutableComponent out = Component.empty();
        int shinyChars = 0; // shiny chars so far: the colour cycle carries on along the line
        for (ChatText.Span s : text.spans()) {
            Style style = Style.EMPTY;
            boolean shiny = (s.styles() & ChatText.SHINY) != 0 && s.sprite() == null;
            if (!shiny && s.rgb() >= 0) style = style.withColor(TextColor.fromRgb(s.rgb()));
            if ((s.styles() & ChatText.BOLD) != 0) style = style.withBold(true);
            if ((s.styles() & ChatText.UNDERLINED) != 0) style = style.withUnderlined(true);
            if (s.font() != null) {
                style = style.withFont(new FontDescription.Resource(Identifier.parse(s.font())));
            }
            if (s.hover() != null) style = style.withHoverEvent(new HoverEvent.ShowText(toComponent(s.hover())));
            if (s.url() != null && s.url().startsWith("https://")) {
                style = style.withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(s.url())));
            } else if (s.suggest() != null) {
                style = style.withClickEvent(new ClickEvent.SuggestCommand(s.suggest()));
            }
            Identifier sprite = s.sprite() == null ? null : Identifier.tryParse(s.sprite());
            if (sprite != null) {
                out.append(Component.object(new AtlasSprite(AtlasSprite.DEFAULT_ATLAS, sprite), Component.literal(s.text()))
                        .withStyle(style));
            } else if (shiny) {
                // one component per character, each with its marker colour (see AnimatedTextAdapter)
                MutableComponent run = Component.empty().withStyle(style);
                String t = s.text();
                for (int c = 0; c < t.length(); ) {
                    int n = Character.charCount(t.codePointAt(c));
                    run.append(Component.literal(t.substring(c, c + n))
                            .withStyle(Style.EMPTY.withColor(AnimatedTextAdapter.shiny(shinyChars++))));
                    c += n;
                }
                out.append(run);
            } else {
                out.append(Component.literal(s.text()).withStyle(style));
            }
        }
        return out;
    }
}
