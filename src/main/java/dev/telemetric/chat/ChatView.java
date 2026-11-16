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
}
