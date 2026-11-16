package dev.telemetric.game;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.telemetric.Telemetric;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * MC ADAPTER — client-side chat commands ({@code /gc <message>}). A handler returns false when it does not take the
 * command (feature off); then — and always off Telos — the command goes to the server unchanged, so a client command
 * never hides a server command of the same name.
 */
public final class ClientCommandAdapter {

    private static final Map<String, Predicate<String>> COMMANDS = new ConcurrentHashMap<>();
    private static boolean registered;

    private ClientCommandAdapter() {}

    /** Adds {@code /name <text…>}: {@code handler} gets the text (may be empty) and returns true if it handled it. */
    public static synchronized void greedy(String name, Predicate<String> handler) {
        COMMANDS.put(name, handler);
        if (registered) return;
        registered = true;
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (Map.Entry<String, Predicate<String>> e : COMMANDS.entrySet()) {
                String cmd = e.getKey();
                Predicate<String> h = e.getValue();
                dispatcher.register(ClientCommands.literal(cmd)
                        .executes(ctx -> run(cmd, h, ""))
                        .then(ClientCommands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> run(cmd, h, StringArgumentType.getString(ctx, "message")))));
            }
        });
    }

    private static int run(String cmd, Predicate<String> handler, String text) {
        boolean handled = false;
        try {
            handled = ServerGate.onTelos() && handler.test(text);
        } catch (RuntimeException e) {
            Telemetric.LOGGER.error("Client command /{} failed", cmd, e);
        }
        if (!handled) PlayerAdapter.sendCommand(text.isEmpty() ? cmd : cmd + " " + text);
        return 1;
    }
}
