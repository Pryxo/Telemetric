package dev.telemetric.chat;

/**
 * How the chat HUD shows lines (chat tabs, censoring). The Chat module sets it on {@code game/ChatHudAdapter}.
 * It gets every line's plain text, player chat included, so it may only decide how things are displayed.
 */
public interface ChatView {

    /** Category of a line. The adapter computes it once per line and caches it. */
    String category(String plain);

    /** True if lines of {@code category} are showing right now (in the open tab). */
    boolean visible(String category);

    /** How many characters of the line to keep before "…" when it's censored, or -1 to show it whole. */
    int censorAt(String plain, String category);

    /**
     * True while the chat shows the full archive ("All Chat"): every line since the game started, hidden ones
     * included. The archive is never trimmed by the history limit and never cleared.
     */
    default boolean archive() {
        return false;
    }

    /**
     * A new line arrived (called once per line, not on re-layouts). {@code hidden} means a server line that was kept
     * out of the chat (Hide Utility Messages) and only lives in the archive. Client lines aren't reported.
     */
    default void onNewLine(String plain, String category, boolean hidden) {}
}
