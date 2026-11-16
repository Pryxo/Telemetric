package dev.telemetric.chat;

/**
 * How the chat HUD shows lines (chat tabs, censoring) — set by the Chat module on {@code game/ChatHudAdapter}.
 * Called with every line's plain text, player chat included, so it must only decide <b>display</b>. Pure Java.
 */
public interface ChatView {

    /** Category of a line (computed once per line and cached by the adapter). */
    String category(String plain);

    /** True if lines of {@code category} show right now (the open tab). */
    boolean visible(String category);

    /** Characters of the line to keep before "…" (censored), or -1 to show it whole. */
    int censorAt(String plain, String category);

    /**
     * True while the chat shows the full archive ("All Chat"): every line since the game started, hidden ones
     * included, never trimmed by the history limit or cleared.
     */
    default boolean archive() {
        return false;
    }

    /**
     * A new line arrived (once per line, not on re-layouts). {@code hidden} = a server line kept out of the chat
     * (Hide Utility Messages), only in the archive. Client lines are not reported.
     */
    default void onNewLine(String plain, String category, boolean hidden) {}
}
