package dev.telemetric.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A <b>trusted</b> server message: a plain-text regex plus an exact style fingerprint. A message only matches if
 * <ol>
 *   <li>it's a server system message ({@link ChatMessage} is never built from player chat),</li>
 *   <li>it's in the chat, or in the action bar for {@link Builder#overlay()} patterns, and not the other one,</li>
 *   <li>its <b>whole</b> plain text matches {@code text} ({@link Matcher#matches()}), so nothing like a player
 *       name, rank, "[Party]" or whisper arrow can come before it,</li>
 *   <li>it has <b>exactly</b> the listed runs, in order: each run's text matches its regex and
 *       bold/italic/underlined/strikethrough/obfuscated are exactly the given flags,</li>
 *   <li>no run has a click/hover event and no run uses a custom font (unless the run allows it).</li>
 * </ol>
 * Colour is <b>not</b> checked. Point 1 already proves the server sent it, and chat mods recolour runs (ChatNotify
 * paints your own name), which made colour checks miss real messages. Runs are only split where bold/italic/…
 * change. A player's line relayed by the server (whisper, party, chat format) has a name, rank or channel prefix,
 * so it can't pass point 3.
 * <p>
 * Every pattern needs at least one run, or an explicit {@link Builder#anyRuns()} for a message whose run layout
 * hasn't been read live yet. Points 1 to 3 still apply then, 4 is skipped, and so is 5 with
 * {@link Builder#allowInteractive()}. Once you've seen the message, replace {@code anyRuns()} with the real runs:
 * turn debug logging on and {@code game/ChatAdapter} logs each system message as {@code Chat: [...]}.
 */
public final class ChatPattern {

    public static final int PLAIN = 0;
    public static final int BOLD = 1;
    public static final int ITALIC = 1 << 1;
    public static final int UNDERLINED = 1 << 2;
    public static final int STRIKETHROUGH = 1 << 3;
    public static final int OBFUSCATED = 1 << 4;

    /** One expected run: a text regex (whole run), exact style flags and a font (null for the default). */
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
    private final boolean overlay, anyRuns, interactive;

    private ChatPattern(String name, Pattern text, List<Run> runs, boolean overlay, boolean anyRuns,
                        boolean interactive) {
        this.name = name;
        this.text = text;
        this.runs = runs;
        this.overlay = overlay;
        this.anyRuns = anyRuns;
        this.interactive = interactive;
    }

    /** @param textRegex regex for the whole plain text; named groups ({@code (?<n>\d+)}) can be read from the match */
    public static Builder builder(String name, String textRegex) {
        return new Builder(name, textRegex);
    }

    public String name() {
        return name;
    }

    /** The text match (with readable groups) if {@code message} is this server message, otherwise null. */
    public MatchResult match(ChatMessage message) {
        if (message.overlay() != overlay) return null;
        List<ChatSegment> segments = message.segments();
        if (anyRuns) {
            if (!interactive) for (ChatSegment s : segments) if (s.interactive()) return null;
        } else {
            if (segments.size() != runs.size()) return null;
            for (int i = 0; i < runs.size(); i++) {
                if (!runs.get(i).matches(segments.get(i))) return null;
            }
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
        private boolean overlay, anyRuns, interactive;

        private Builder(String name, String textRegex) {
            this.name = name;
            this.text = Pattern.compile(textRegex, Pattern.DOTALL);
        }

        /** Next run: {@code regex} must match its whole text, and {@code styles} are the flags OR-ed together ({@link #PLAIN} for none). */
        public Builder run(String regex, int styles) {
            return run(regex, styles, null);
        }

        /** Same, but for a run drawn in a custom font ({@code "telos:text"}). */
        public Builder run(String regex, int styles, String font) {
            runs.add(new Run(Pattern.compile(regex, Pattern.DOTALL), styles, font));
            return this;
        }

        /**
         * The run layout hasn't been read live yet, so any runs, styles and fonts pass and only the whole-text match
         * decides. Interactive runs are still refused unless {@link #allowInteractive()} is set.
         */
        public Builder anyRuns() {
            anyRuns = true;
            return this;
        }

        /** Use with {@link #anyRuns()}: runs with a click or hover event (player names, item tooltips) are allowed. */
        public Builder allowInteractive() {
            interactive = true;
            return this;
        }

        /** The message is shown in the action bar, not the chat. */
        public Builder overlay() {
            overlay = true;
            return this;
        }

        public ChatPattern build() {
            if (runs.isEmpty() && !anyRuns) {
                throw new IllegalStateException("ChatPattern '" + name + "' needs at least one run (or anyRuns())");
            }
            if (!runs.isEmpty() && anyRuns) throw new IllegalStateException("ChatPattern '" + name + "': runs or anyRuns");
            if (interactive && !anyRuns) throw new IllegalStateException("ChatPattern '" + name + "': allowInteractive needs anyRuns");
            return new ChatPattern(name, text, List.copyOf(runs), overlay, anyRuns, interactive);
        }
    }
}
