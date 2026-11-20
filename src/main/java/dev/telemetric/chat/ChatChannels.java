package dev.telemetric.chat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * The server's chat mode ({@code /chat default|guild|group}), plus a queue that sends a message in another mode:
 * switch, wait for the server to confirm, send, then switch back. Chat tabs use it (the Guild tab means guild mode),
 * and so do the Toggle Guild / Group keys and {@code /ac /gc /grc}. Telos rate-limits chat, so sends are spaced by
 * {@link #SEND_GAP_MS}. A switch the server never confirms is dropped after {@link #TIMEOUT_MS}.
 * Pure Java; the commands go out through {@link Server}, and the Chat module ticks it.
 */
public final class ChatChannels {

    public static final String DEFAULT = "default";
    static final long SEND_GAP_MS = 1100, TIMEOUT_MS = 3000;

    /** Where commands and messages go (the game). */
    public interface Server {
        void command(String command);

        void chat(String message);
    }

    private enum State { IDLE, SWITCHING, SENDING, RESTORING }

    private record Queued(String channel, String message) {}

    private final Server server;
    private final Deque<Queued> queue = new ArrayDeque<>();
    private String mode = DEFAULT;
    private State state = State.IDLE;
    private String restoreTo = DEFAULT;
    private long stateSince, lastSent;
    /** Mode switches we asked for that still owe us a confirmation line (hidden from chat). */
    private int ownSwitches;
    private long ownSwitchUntil;

    public ChatChannels(Server server) {
        this.server = server;
    }

    /** Last mode the server confirmed. */
    public String mode() {
        return mode;
    }

    public boolean isDefault() {
        return DEFAULT.equals(mode);
    }

    /** Switches the server to {@code channel}. Does nothing if it's already there. */
    public void switchTo(String channel, long now) {
        String c = normalize(channel);
        if (c.isEmpty() || c.equals(mode)) return;
        requestSwitch(c, now);
    }

    /** Guild or group key: switch to {@code channel}, or back to default if we're already in it. */
    public void toggle(String channel, long now) {
        String c = normalize(channel);
        requestSwitch(c.equals(mode) ? DEFAULT : c, now);
    }

    /** Sends {@code message} in {@code channel} without leaving the current mode. */
    public void enqueue(String channel, String message) {
        if (message == null || message.isBlank()) return;
        queue.addLast(new Queued(normalize(channel), message));
    }

    /** True while a queued message is being sent. Messages typed meanwhile should queue behind it. */
    public boolean isBusy() {
        return state != State.IDLE || !queue.isEmpty();
    }

    /** The mode a message typed now will end up in once the queue is done. */
    public String typingMode() {
        return state == State.IDLE ? mode : restoreTo;
    }

    /** A message you sent yourself. Keeps the rate gap intact. */
    public void sentByUser(long now) {
        lastSent = now;
    }

    /** True while a mode confirmation would be the answer to a switch we made (its chat line gets hidden). */
    public boolean expectsConfirmation(long now) {
        return state == State.SWITCHING || state == State.RESTORING || (ownSwitches > 0 && now <= ownSwitchUntil);
    }

    /**
     * The server confirmed a mode switch. Returns true if the confirmation line answers a switch we made, in which
     * case the caller hides it.
     */
    public boolean confirmed(String newMode, long now) {
        mode = normalize(newMode);
        boolean own = false;
        if (state == State.SWITCHING) {
            state = State.SENDING;
            stateSince = now;
            own = true;
        } else if (state == State.RESTORING) {
            state = State.IDLE;
            own = true;
        }
        if (ownSwitches > 0 && now <= ownSwitchUntil) {
            ownSwitches--;
            own = true;
        }
        return own;
    }

    public void tick(long now) {
        if (state != State.IDLE && now - stateSince > TIMEOUT_MS) state = State.IDLE; // the server never answered
        if (ownSwitches > 0 && now > ownSwitchUntil) ownSwitches = 0;
        switch (state) {
            case IDLE -> {
                Queued next = queue.peekFirst();
                if (next == null) return;
                if (next.channel().equals(mode)) {
                    if (now - lastSent < SEND_GAP_MS) return;
                    queue.pollFirst();
                    server.chat(next.message());
                    lastSent = now;
                } else {
                    restoreTo = mode;
                    state = State.SWITCHING;
                    stateSince = now;
                    server.command("chat " + next.channel());
                }
            }
            case SENDING -> {
                if (now - lastSent < SEND_GAP_MS) return;
                Queued next = queue.pollFirst();
                if (next != null) {
                    server.chat(next.message());
                    lastSent = now;
                }
                Queued after = queue.peekFirst();
                if (after != null && after.channel().equals(mode)) {
                    stateSince = now; // more queued for this mode, so stay
                    return;
                }
                state = State.RESTORING;
                stateSince = now;
                server.command("chat " + restoreTo);
            }
            default -> { } // waiting for the server
        }
    }

    private void requestSwitch(String channel, long now) {
        ownSwitches++;
        ownSwitchUntil = now + TIMEOUT_MS;
        server.command("chat " + channel);
    }

    private static String normalize(String channel) {
        String c = channel == null ? "" : channel.trim().toLowerCase(Locale.ROOT);
        return c.equals("all") ? DEFAULT : c;
    }
}
