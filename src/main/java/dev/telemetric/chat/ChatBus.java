package dev.telemetric.chat;

import dev.telemetric.Telemetric;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.MatchResult;

/**
 * Where features listen for server messages. Only {@link ChatPattern trusted patterns} can be subscribed — there is
 * deliberately no "every message" listener, so no feature can react to text a player could type.
 * <p>
 * {@code game/ChatAdapter} feeds it system messages on the client thread, on Telos only; listeners run there too.
 * A feature can also {@link #hide} a matching message from the chat (it is still dispatched to listeners).
 */
public final class ChatBus {

    /** Cancels a subscription (safe to call more than once). */
    public interface Subscription {
        void cancel();
    }

    private record Entry(ChatPattern pattern, Consumer<MatchResult> listener) {}

    private record Hide(ChatPattern pattern, Predicate<MatchResult> when, boolean replaced) {}

    /** What happens to a system message before it is shown. */
    public enum Visibility {
        /** Shown normally. */
        SHOW,
        /** Kept out of the chat; All Chat (the archive) still shows it. */
        HIDE,
        /** Replaced by a Telemetric line: gone everywhere, All Chat included (the replacement is shown instead). */
        REPLACE
    }

    private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();
    private static final List<Hide> HIDES = new CopyOnWriteArrayList<>();

    private ChatBus() {}

    /** Calls {@code listener} with the text match every time a message matching {@code pattern} arrives. */
    public static Subscription on(ChatPattern pattern, Consumer<MatchResult> listener) {
        Entry entry = new Entry(pattern, listener);
        ENTRIES.add(entry);
        return () -> ENTRIES.remove(entry);
    }

    /**
     * Keeps messages matching {@code pattern} out of the chat when {@code when} says so for the match (e.g. a module
     * setting is on and the line is about you). Listeners still receive them.
     */
    public static Subscription hide(ChatPattern pattern, Predicate<MatchResult> when) {
        return add(new Hide(pattern, when, false));
    }

    /**
     * Like {@link #hide}, for a message a feature shows its own line for instead: it is also kept out of All Chat,
     * so the chat never shows both. Listeners still receive it.
     */
    public static Subscription replace(ChatPattern pattern, Predicate<MatchResult> when) {
        return add(new Hide(pattern, when, true));
    }

    private static Subscription add(Hide hide) {
        HIDES.add(hide);
        return () -> HIDES.remove(hide);
    }

    /** True if any feature hides messages (else {@code game/ChatAdapter} skips the check). */
    public static boolean hasHideRules() {
        return !HIDES.isEmpty();
    }

    /** Called by {@code game/ChatAdapter} before a system message is shown (a replace rule wins over a hide rule). */
    public static Visibility visibility(ChatMessage message) {
        Visibility out = Visibility.SHOW;
        for (Hide h : HIDES) {
            try {
                MatchResult m = h.pattern().match(message);
                if (m == null || !h.when().test(m)) continue;
                if (h.replaced()) return Visibility.REPLACE;
                out = Visibility.HIDE;
            } catch (RuntimeException ex) {
                Telemetric.LOGGER.error("Chat hide rule for '{}' failed", h.pattern().name(), ex);
            }
        }
        return out;
    }

    /** Called by {@code game/ChatAdapter} for each received system message. */
    public static void dispatch(ChatMessage message) {
        for (Entry e : ENTRIES) {
            MatchResult m = e.pattern().match(message);
            if (m == null) continue;
            Telemetric.debug("Chat matched '{}': {}", e.pattern().name(), message.text());
            try {
                e.listener().accept(m);
            } catch (RuntimeException ex) {
                Telemetric.LOGGER.error("Chat listener for '{}' failed", e.pattern().name(), ex);
            }
        }
    }
}
