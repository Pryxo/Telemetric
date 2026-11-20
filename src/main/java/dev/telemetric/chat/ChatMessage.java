package dev.telemetric.chat;

import java.util.ArrayList;
import java.util.List;

/**
 * A <b>server system message</b> as received (pure Java). Only {@code game/ChatAdapter} builds these, and only from
 * system chat packets, so player chat (signed or disguised) never becomes a {@code ChatMessage}.
 *
 * @param text     plain text (all segments joined)
 * @param segments styled runs, with empty runs dropped and neighbouring runs of the same style merged
 * @param overlay  shown above the hotbar (action bar) instead of in the chat
 */
public record ChatMessage(String text, List<ChatSegment> segments, boolean overlay) {

    /** Builds a message from raw runs, dropping empty runs and merging neighbours with the same style. */
    public static ChatMessage of(List<ChatSegment> raw, boolean overlay) {
        List<ChatSegment> merged = new ArrayList<>(raw.size());
        StringBuilder text = new StringBuilder();
        for (ChatSegment s : raw) {
            if (s.text().isEmpty()) continue;
            text.append(s.text());
            int last = merged.size() - 1;
            if (last >= 0 && merged.get(last).sameStyle(s)) {
                merged.set(last, merged.get(last).withText(merged.get(last).text() + s.text()));
            } else {
                merged.add(s);
            }
        }
        return new ChatMessage(text.toString(), List.copyOf(merged), overlay);
    }

    /** Debug dump: every run with its style. */
    public String describe() {
        return (overlay ? "[overlay] " : "") + segments;
    }
}
