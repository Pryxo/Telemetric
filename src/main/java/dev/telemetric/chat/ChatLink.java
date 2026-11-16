package dev.telemetric.chat;

/**
 * A clickable part a feature adds to a chat line ({@code game/ChatHudAdapter#addLinker}): characters
 * {@code [start, end)} of the line's plain text get {@code hover} and run {@code click} (left) / {@code rightClick}
 * (right, may be null) <b>on the client</b> — nothing is sent unless the action sends it. Pure Java.
 */
public record ChatLink(int start, int end, String hover, Runnable click, Runnable rightClick) {

    public ChatLink {
        if (start < 0 || end <= start) throw new IllegalArgumentException("empty link " + start + ".." + end);
    }
}
