package dev.telemetric.chat;

/**
 * A clickable part that a feature adds to a chat line ({@code game/ChatHudAdapter#addLinker}). Characters
 * {@code [start, end)} of the line's plain text get {@code hover}, and clicking runs {@code click} (left) or
 * {@code rightClick} (right, may be null) <b>on the client</b>. Nothing is sent unless the action itself sends it.
 * Pure Java.
 */
public record ChatLink(int start, int end, String hover, Runnable click, Runnable rightClick) {

    public ChatLink {
        if (start < 0 || end <= start) throw new IllegalArgumentException("empty link " + start + ".." + end);
    }
}
