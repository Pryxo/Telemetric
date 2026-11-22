package dev.telemetric.chat;

/**
 * The look of the mod's own chat lines (pure Java): the gold-to-black gradient, the {@code Telemetric ›} prefix in
 * front of every command reply, grey body text and the gold chat tag. {@code game/ChatAdapter#post} shows the result.
 */
public final class ChatStyle {

    /** Gold → black gradient stops (0xRRGGBB). The last stop stays a little above black so it's readable in chat. */
    public static final int[] GOLD_TO_BLACK = {0xFFE58A, 0xFFB300, 0xC77A00, 0x7A4700, 0x3A2200};

    /** Body text. */
    public static final int TEXT = ChatText.GRAY;
    /** Values inside a sentence (names, numbers, commands): white, not a colour. */
    public static final int VALUE = ChatText.WHITE;
    public static final int SEPARATOR = ChatText.DARK_GRAY;

    /** The bar to the left of every Telemetric line, with its hover. */
    public static final ChatText.Tag TAG = new ChatText.Tag(0xFFB300,
            ChatText.of().add("Message from Telemetric", TEXT), "Telemetric");

    private ChatStyle() {}

    /** {@code Telemetric ›} — the mod name bold in the gold-to-black gradient, a grey bold separator. */
    public static ChatText prefix() {
        return gradient(ChatText.of(), "Telemetric", GOLD_TO_BLACK, ChatText.BOLD)
                .add(" › ", SEPARATOR, ChatText.BOLD);
    }

    /** A command reply: prefix + {@code line} in grey. {@code §f…§7} inside {@code line} marks values. */
    public static ChatText reply(String line) {
        return prefix().add(line, TEXT).tag(TAG);
    }

    /**
     * One chat message for several reply lines: the prefix only in front of the first, the others below it (each its
     * own span, so {@code §} colours never run over into the next line).
     */
    public static ChatText reply(java.util.List<String> lines) {
        ChatText out = prefix();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.add("\n", TEXT);
            out.add(lines.get(i), TEXT);
        }
        return out.tag(TAG);
    }

    /** Adds {@code text} to {@code out}, one span per character, coloured along {@code stops} from left to right. */
    public static ChatText gradient(ChatText out, String text, int[] stops, int styles) {
        int n = text.codePointCount(0, text.length());
        int i = 0;
        for (int off = 0; off < text.length(); ) {
            int cp = text.codePointAt(off);
            String ch = new String(Character.toChars(cp));
            out.add(ch, colorAt(stops, n <= 1 ? 0 : (double) i / (n - 1)), styles);
            off += Character.charCount(cp);
            i++;
        }
        return out;
    }

    /** Colour at {@code t} (0..1) along the stops. */
    public static int colorAt(int[] stops, double t) {
        if (stops.length == 1) return stops[0];
        double p = Math.max(0, Math.min(1, t)) * (stops.length - 1);
        int a = Math.min((int) p, stops.length - 2);
        double f = p - a;
        int c0 = stops[a], c1 = stops[a + 1];
        int r = (int) Math.round(((c0 >> 16) & 0xFF) + (((c1 >> 16) & 0xFF) - ((c0 >> 16) & 0xFF)) * f);
        int g = (int) Math.round(((c0 >> 8) & 0xFF) + (((c1 >> 8) & 0xFF) - ((c0 >> 8) & 0xFF)) * f);
        int b = (int) Math.round((c0 & 0xFF) + ((c1 & 0xFF) - (c0 & 0xFF)) * f);
        return (r << 16) | (g << 8) | b;
    }
}
