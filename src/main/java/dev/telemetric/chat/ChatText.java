package dev.telemetric.chat;

import java.util.ArrayList;
import java.util.List;

/**
 * A client-side chat line built by a feature (pure Java): coloured spans, optionally bold / underlined, with a
 * hover text and a click that puts a command / message into the chat box. {@code game/ChatAdapter#post} shows it.
 * <pre>{@code
 * ChatText.of().add("Dropped ", 0xAAAAAA).add(item, rgb, ChatText.BOLD).hover(ChatText.of().add("Pity 42", -1))
 * }</pre>
 */
public final class ChatText {

    public static final int BOLD = 1, UNDERLINED = 2;

    /** Minecraft's chat colours (0xRRGGBB) — the look players know from server lines. */
    public static final int GRAY = 0xAAAAAA, DARK_GRAY = 0x555555, WHITE = 0xFFFFFF, YELLOW = 0xFFFF55,
            GOLD = 0xFFAA00, GREEN = 0x55FF55, RED = 0xFF5555, AQUA = 0x55FFFF;

    /**
     * @param rgb     0xRRGGBB, or -1 for the chat default
     * @param hover   shown while hovering, or null
     * @param suggest put into the chat box on click (not sent), or null
     * @param font    font id ({@code "minecraft:default"} = null), for server-pack glyphs
     */
    public record Span(String text, int rgb, int styles, ChatText hover, String suggest, String font) {}

    private final List<Span> spans = new ArrayList<>();

    private ChatText() {}

    public static ChatText of() {
        return new ChatText();
    }

    public ChatText add(String text, int rgb) {
        return add(text, rgb, 0);
    }

    public ChatText add(String text, int rgb, int styles) {
        spans.add(new Span(text, rgb, styles, null, null, null));
        return this;
    }

    /** A glyph from a resource-pack font (e.g. a Telos loot icon). */
    public ChatText glyph(String text, String font) {
        spans.add(new Span(text, 0xFFFFFF, 0, null, null, font));
        return this;
    }

    /** Hover text for the last span. */
    public ChatText hover(ChatText hover) {
        return replaceLast(s -> new Span(s.text(), s.rgb(), s.styles(), hover, s.suggest(), s.font()));
    }

    /** Click on the last span puts {@code text} into the chat box. */
    public ChatText suggest(String text) {
        return replaceLast(s -> new Span(s.text(), s.rgb(), s.styles(), s.hover(), text, s.font()));
    }

    /** Appends another text's spans. */
    public ChatText append(ChatText other) {
        spans.addAll(other.spans);
        return this;
    }

    public List<Span> spans() {
        return List.copyOf(spans);
    }

    public boolean isEmpty() {
        return spans.isEmpty();
    }

    /** Plain text (logs, tests). */
    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        for (Span s : spans) b.append(s.text());
        return b.toString();
    }

    private ChatText replaceLast(java.util.function.UnaryOperator<Span> f) {
        if (spans.isEmpty()) throw new IllegalStateException("no span yet");
        spans.set(spans.size() - 1, f.apply(spans.getLast()));
        return this;
    }
}
