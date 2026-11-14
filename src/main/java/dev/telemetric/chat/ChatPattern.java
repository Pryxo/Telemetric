package dev.telemetric.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A <b>trusted</b> server message: plain-text regex + an exact style fingerprint. A message matches only if
 * <ol>
 *   <li>it is a server system message ({@link ChatMessage} is never built from player chat),</li>
 *   <li>it is in the chat (or, for {@link Builder#overlay()} patterns, in the action bar) — not the other one,</li>
 *   <li>its <b>whole</b> plain text matches {@code text} ({@link Matcher#matches()}, so no prefix like a player
 *       name / rank / "[Party]" / whisper arrow can come before it),</li>
 *   <li>it has <b>exactly</b> the listed runs, in order: each run's text matches its regex and
 *       bold/italic/underlined/strikethrough/obfuscated are exactly the given flags,</li>
 *   <li>no run has a click/hover event and no run uses a custom font (unless the run says so).</li>
 * </ol>
 * Colour is <b>not</b> checked: 1 already means the server sent it, and chat mods recolour runs (ChatNotify paints
 * your own name), which made colour checks miss real messages. Runs are split only where bold/italic/… change.
 * A player's line relayed by the server (whisper, party, chat format) has a name / rank / channel prefix, so it
 * cannot pass 3.
 * <p>
 * Every pattern needs at least one run — there is no "text only" pattern. Find the runs of a new message with
 * debug logging on: {@code game/ChatAdapter} logs each system message as {@code Chat: [...]}.
 */
public final class ChatPattern {

    public static final int PLAIN = 0;
    public static final int BOLD = 1;
    public static final int ITALIC = 1 << 1;
    public static final int UNDERLINED = 1 << 2;
    public static final int STRIKETHROUGH = 1 << 3;
    public static final int OBFUSCATED = 1 << 4;

    /** One expected run: text regex (whole run), exact style flags, font (null = default). */
    public record Run(Pattern text, int styles, String font) {
        boolean matches(ChatSegment s) {
            return !s.interactive()
                    && s.bold() == ((styles & BOLD) != 0)
                    && s.italic() == ((styles & ITALIC) != 0)
                    && s.underlined() == ((styles & UNDERLINED) != 0)
                    && s.strikethrough() == ((styles & STRIKETHROUGH) != 0)
                    && s.obfuscated() == ((styles & OBFUSCATED) != 0)
                    && Objects.equals(s.font(), font)
                    && text.matcher(s.text()).matches();
        }
    }

    private final String name;
    private final Pattern text;
    private final List<Run> runs;
    private final boolean overlay;

    private ChatPattern(String name, Pattern text, List<Run> runs, boolean overlay) {
        this.name = name;
        this.text = text;
        this.runs = runs;
        this.overlay = overlay;
    }

    /** @param textRegex regex for the whole plain text; named groups ({@code (?<n>\d+)}) can be read from the match */
    public static Builder builder(String name, String textRegex) {
        return new Builder(name, textRegex);
    }

    public String name() {
        return name;
    }

    /** The text match (groups readable) if {@code message} is this server message, else null. */
    public MatchResult match(ChatMessage message) {
        if (message.overlay() != overlay) return null;
        List<ChatSegment> segments = message.segments();
        if (segments.size() != runs.size()) return null;
        for (int i = 0; i < runs.size(); i++) {
            if (!runs.get(i).matches(segments.get(i))) return null;
        }
        Matcher m = text.matcher(message.text());
        return m.matches() ? m.toMatchResult() : null;
    }

    @Override
    public String toString() {
        return name;
    }

    public static final class Builder {
        private final String name;
        private final Pattern text;
        private final List<Run> runs = new ArrayList<>();
        private boolean overlay;

        private Builder(String name, String textRegex) {
            this.name = name;
            this.text = Pattern.compile(textRegex, Pattern.DOTALL);
        }

        /** Next run: {@code regex} must match its whole text; {@code styles} = flags OR-ed ({@link #PLAIN} = none). */
        public Builder run(String regex, int styles) {
            return run(regex, styles, null);
        }

        /** Same, for a run drawn in a custom font ({@code "telos:text"}). */
        public Builder run(String regex, int styles, String font) {
            runs.add(new Run(Pattern.compile(regex, Pattern.DOTALL), styles, font));
            return this;
        }

        /** The message is shown in the action bar, not the chat. */
        public Builder overlay() {
            overlay = true;
            return this;
        }

        public ChatPattern build() {
            if (runs.isEmpty()) throw new IllegalStateException("ChatPattern '" + name + "' needs at least one run");
            return new ChatPattern(name, text, List.copyOf(runs), overlay);
        }
    }
}
