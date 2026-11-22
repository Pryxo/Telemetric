package dev.telemetric.chat;

import java.util.ArrayList;
import java.util.List;

/**
 * A client-side chat line built by a feature (pure Java): coloured spans, optionally bold or underlined, with hover
 * text and a click that puts a command or message into the chat box. {@code game/ChatAdapter#post} shows it.
 * <pre>{@code
 * ChatText.of().add("Dropped ", 0xAAAAAA).add(item, rgb, ChatText.BOLD).hover(ChatText.of().add("Pity 42", -1))
 * }</pre>
 */
public final class ChatText {

    public static final int BOLD = 1, UNDERLINED = 2;
    /**
     * Style flag: draw the span in the shiny colours, one step per character along the line ({@code rgb} is ignored).
     * It animates while Misc → Chat Colors is on and is a still gradient otherwise.
     */
    public static final int SHINY = 4;

    /** Minecraft's chat colours (0xRRGGBB), the ones players know from server lines. */
    public static final int GRAY = 0xAAAAAA, DARK_GRAY = 0x555555, WHITE = 0xFFFFFF, YELLOW = 0xFFFF55,
            GOLD = 0xFFAA00, GREEN = 0x55FF55, RED = 0xFF5555, AQUA = 0x55FFFF;

    /**
     * @param rgb     0xRRGGBB, or -1 for the chat default
     * @param hover   shown while hovering, or null
     * @param suggest put into the chat box on click (not sent), or null
     * @param font    font id (null means {@code "minecraft:default"}), for server-pack glyphs
     * @param sprite  block-atlas sprite id drawn instead of {@code text} ({@code "telos:material/pet/onyx"}), or null;
     *                {@code text} is then the fallback and copy text
     * @param url     opened on click (Minecraft's "open link?" prompt), or null; wins over {@code suggest}
     */
    public record Span(String text, int rgb, int styles, ChatText hover, String suggest, String font, String sprite,
                       String url) {
        public Span(String text, int rgb, int styles, ChatText hover, String suggest, String font, String sprite) {
            this(text, rgb, styles, hover, suggest, font, sprite, null);
        }

        public Span(String text, int rgb, int styles, ChatText hover, String suggest, String font) {
            this(text, rgb, styles, hover, suggest, font, null, null);
        }
    }

    /**
     * The chat-side tag of the whole line: the coloured bar to its left, with {@code text} on hover, like the server's
     * system tag. Null uses Minecraft's default for client lines.
     *
     * @param rgb 0xRRGGBB bar colour
     */
    public record Tag(int rgb, ChatText text, String logTag) {}

    private Tag tag;

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

    /** A picture from the block atlas, one line tall. Used for the server pack's item textures, e.g. a drop's model id. */
    public ChatText sprite(String spriteId, String fallback) {
        spans.add(new Span(fallback, 0xFFFFFF, 0, null, null, null, spriteId));
        return this;
    }

    /** Sets the line's chat tag (the indicator bar and its hover). */
    public ChatText tag(Tag tag) {
        this.tag = tag;
        return this;
    }

    public Tag tag() {
        return tag;
    }

    private boolean centered;

    /** Centres the line in the chat box (padded with spaces when it's posted, for the chat width at that moment). */
    public ChatText center() {
        centered = true;
        return this;
    }

    public boolean centered() {
        return centered;
    }

    /** A glyph from a resource-pack font, such as a Telos loot icon. */
    public ChatText glyph(String text, String font) {
        spans.add(new Span(text, 0xFFFFFF, 0, null, null, font));
        return this;
    }

    /** Hover text for the last span. */
    public ChatText hover(ChatText hover) {
        return replaceLast(s -> new Span(s.text(), s.rgb(), s.styles(), hover, s.suggest(), s.font(), s.sprite(), s.url()));
    }

    /** Clicking the last span opens {@code url} (https only). */
    public ChatText url(String url) {
        return replaceLast(s -> new Span(s.text(), s.rgb(), s.styles(), s.hover(), s.suggest(), s.font(), s.sprite(), url));
    }

    /** Clicking the last span puts {@code text} into the chat box. */
    public ChatText suggest(String text) {
        return replaceLast(s -> new Span(s.text(), s.rgb(), s.styles(), s.hover(), text, s.font(), s.sprite(), s.url()));
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

    /** Plain text, for logs and tests. */
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
