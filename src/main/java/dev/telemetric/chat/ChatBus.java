package dev.telemetric.chat;

import dev.telemetric.Telemetric;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.MatchResult;

/**
 * Where features listen for server messages. Only {@link ChatPattern trusted patterns} can be subscribed — there is
 * deliberately no "every message" listener, so no feature can react to text a player could type.
 * <p>
 * {@code game/ChatAdapter} feeds it system messages on the client thread, on Telos only; listeners run there too.
 */
public final class ChatBus {

    /** Cancels a subscription (safe to call more than once). */
    public interface Subscription {
        void cancel();
    }

    private record Entry(ChatPattern pattern, Consumer<MatchResult> listener) {}

    private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();

    private ChatBus() {}

    /** Calls {@code listener} with the text match every time a message matching {@code pattern} arrives. */
    public static Subscription on(ChatPattern pattern, Consumer<MatchResult> listener) {
        Entry entry = new Entry(pattern, listener);
        ENTRIES.add(entry);
        return () -> ENTRIES.remove(entry);
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
