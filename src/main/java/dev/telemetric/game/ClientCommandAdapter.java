package dev.telemetric.game;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.telemetric.Telemetric;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * MC ADAPTER — client-side chat commands. Two kinds:
 * <ul>
 *   <li>{@link #greedy} ({@code /gc <message>}): a handler returns false when it does not take the command (feature
 *       off); then — and always off Telos — the command goes to the server unchanged, so a client command never hides
 *       a server command of the same name.</li>
 *   <li>{@link #own} ({@code /telemetric update-cache}): the mod's own commands, on every server, never forwarded.</li>
 * </ul>
 */
public final class ClientCommandAdapter {

    private static final Map<String, Predicate<String>> COMMANDS = new ConcurrentHashMap<>();
    /** Own commands: root → (sub-command or "" for the bare root → handler). Work on every server. */
    private static final Map<String, Map<String, Consumer<Consumer<String>>>> OWN = new ConcurrentHashMap<>();
    private static boolean registered;

    private ClientCommandAdapter() {}

    /** Adds {@code /name <text…>}: {@code handler} gets the text (may be empty) and returns true if it handled it. */
    public static synchronized void greedy(String name, Predicate<String> handler) {
        COMMANDS.put(name, handler);
        ensureRegistered();
    }

    /**
     * Adds an own client command {@code /root sub} (sub {@code ""} = bare {@code /root}), on every server, never
     * forwarded. The handler gets a reply function that prints a line to the player's chat; it may be called later
     * from any thread.
     */
    public static synchronized void own(String root, String sub, Consumer<Consumer<String>> handler) {
        OWN.computeIfAbsent(root, k -> new ConcurrentHashMap<>()).put(sub, handler);
        ensureRegistered();
    }

    private static void ensureRegistered() {
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
            for (Map.Entry<String, Map<String, Consumer<Consumer<String>>>> e : OWN.entrySet()) {
                String root = e.getKey();
                LiteralArgumentBuilder<FabricClientCommandSource> node = ClientCommands.literal(root);
                for (Map.Entry<String, Consumer<Consumer<String>>> s : e.getValue().entrySet()) {
                    Consumer<Consumer<String>> h = s.getValue();
                    if (s.getKey().isEmpty()) {
                        node.executes(ctx -> runOwn(root, h, ctx.getSource()));
                    } else {
                        node.then(ClientCommands.literal(s.getKey()).executes(ctx -> runOwn(root, h, ctx.getSource())));
                    }
                }
                dispatcher.register(node);
            }
        });
    }

    private static int runOwn(String root, Consumer<Consumer<String>> handler, FabricClientCommandSource source) {
        Consumer<String> reply = line -> Minecraft.getInstance().execute(() -> source.sendFeedback(Component.literal(line)));
        try {
            handler.accept(reply);
        } catch (RuntimeException e) {
            Telemetric.LOGGER.error("Client command /{} failed", root, e);
        }
        return 1;
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
