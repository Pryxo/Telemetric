package dev.telemetric.chat;

/**
 * One styled run of a chat message (pure Java, no Minecraft types). Styles are the <b>resolved</b> ones — a run
 * inherits bold/italic/… from its parent components the way the game draws it. Colour is deliberately not kept:
 * chat mods (e.g. ChatNotify highlighting your name) recolour runs, and a system message is already a server one.
 *
 * @param text        the run's text (never empty)
 * @param interactive the run has a click or hover event (player names, links, buttons)
 * @param font        font id ({@code "telos:text"}), or null for the default font
 */
public record ChatSegment(String text, boolean bold, boolean italic, boolean underlined,
                          boolean strikethrough, boolean obfuscated, boolean interactive, String font) {

    /** Same look as {@code other} (everything but the text) — adjacent runs like that are merged. */
    public boolean sameStyle(ChatSegment other) {
        return bold == other.bold && italic == other.italic && underlined == other.underlined
                && strikethrough == other.strikethrough && obfuscated == other.obfuscated
                && interactive == other.interactive && java.util.Objects.equals(font, other.font);
    }

    ChatSegment withText(String newText) {
        return new ChatSegment(newText, bold, italic, underlined, strikethrough, obfuscated, interactive, font);
    }

    @Override
    public String toString() {
        String flags = (bold ? " bold" : "") + (italic ? " italic" : "") + (underlined ? " underlined" : "")
                + (strikethrough ? " strike" : "") + (obfuscated ? " obf" : "") + (interactive ? " interactive" : "")
                + (font != null ? " font=" + font : "");
        return "\"" + text + "\"[" + (flags.isEmpty() ? "plain" : flags.substring(1)) + "]";
    }
}
