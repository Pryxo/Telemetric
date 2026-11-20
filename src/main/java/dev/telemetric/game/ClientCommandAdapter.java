package dev.telemetric.game;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.telemetric.Telemetric;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * MC ADAPTER: client-side chat commands. There are two kinds:
 * <ul>
 *   <li>{@link #greedy} ({@code /gc <message>}): the handler returns false when it doesn't take the command (feature
 *       off). In that case, and always off Telos, the command goes to the server unchanged, so a client command
 *       never hides a server command of the same name.</li>
 *   <li>{@link #own} ({@code /telemetric update-cache}): the mod's own commands. They work on every server and are
 *       never forwarded.</li>
 * </ul>
 */
public final class ClientCommandAdapter {

    private static final Map<String, Predicate<String>> COMMANDS = new ConcurrentHashMap<>();
    /** Own commands: root to (sub-command, or "" for the bare root, to handler). They work on every server. */
    private static final Map<String, Map<String, Consumer<Consumer<String>>>> OWN = new ConcurrentHashMap<>();
    /** Own commands that take a text argument: root to (sub path to handler). */
    private static final Map<String, Map<String, ArgCommand>> OWN_ARG = new ConcurrentHashMap<>();
    /** Alias to root. {@code /alias …} is built from the same tree as {@code /root …}. */
    private static final Map<String, String> ALIASES = new ConcurrentHashMap<>();
    private static boolean registered;

    private ClientCommandAdapter() {}

    /** Adds {@code /name <text…>}. {@code handler} gets the text (may be empty) and returns true if it handled it. */
    public static synchronized void greedy(String name, Predicate<String> handler) {
        COMMANDS.put(name, handler);
        ensureRegistered();
    }

    /**
     * Adds one of our own client commands, {@code /root sub} (sub {@code ""} means bare {@code /root}). It works on
     * every server and is never forwarded. The handler gets a reply function that prints a line to the player's chat;
     * that can be called later from any thread.
     */
    public static synchronized void own(String root, String sub, Consumer<Consumer<String>> handler) {
        OWN.computeIfAbsent(root, k -> new ConcurrentHashMap<>()).put(sub, handler);
        ensureRegistered();
    }

    /** Handler for one of our commands with a text argument. It gets the text (may contain spaces) and a reply function. */
    @FunctionalInterface
    public interface ArgHandler {
        void run(String arg, Consumer<String> reply);
    }

    private record ArgCommand(String argName, Supplier<Collection<String>> suggestions, ArgHandler handler) {}

    /**
     * Adds {@code /root sub… <argName>}, and the rest of the line goes to {@code handler}. {@code sub} can contain spaces
     * ({@code "status set"} gives {@code /root status set <text>}). {@code suggestions} supplies the tab-complete values
     * (asked on every key press, may be empty), which get filtered by what was typed, ignoring case.
     */
    public static synchronized void ownArg(String root, String sub, String argName,
                                           Supplier<Collection<String>> suggestions, ArgHandler handler) {
        OWN_ARG.computeIfAbsent(root, k -> new ConcurrentHashMap<>()).put(sub, new ArgCommand(argName, suggestions, handler));
        ensureRegistered();
    }

    /** Makes {@code /alias …} work exactly like {@code /root …} (same sub-commands, same handlers). */
    public static synchronized void alias(String root, String alias) {
        ALIASES.put(alias, root);
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
            Set<String> roots = new HashSet<>(OWN.keySet());
            roots.addAll(OWN_ARG.keySet());
            for (String root : roots) {
                dispatcher.register(ownTree(root, root));
            }
            for (Map.Entry<String, String> a : ALIASES.entrySet()) {
                if (roots.contains(a.getValue())) dispatcher.register(ownTree(a.getKey(), a.getValue()));
            }
        });
    }

    /** Builds {@code /literal …} with every one of {@code root}'s own sub-commands. Brigadier merges literals with the same name. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> ownTree(String literal, String root) {
        LiteralArgumentBuilder<FabricClientCommandSource> node = ClientCommands.literal(literal);
        for (Map.Entry<String, Consumer<Consumer<String>>> s : OWN.getOrDefault(root, Map.of()).entrySet()) {
            Consumer<Consumer<String>> h = s.getValue();
            if (s.getKey().isEmpty()) {
                node.executes(ctx -> runOwn(root, h, ctx.getSource()));
            } else {
                node.then(chain(s.getKey().split(" "), 0,
                        last -> last.executes(ctx -> runOwn(root, h, ctx.getSource()))));
            }
        }
        for (Map.Entry<String, ArgCommand> s : OWN_ARG.getOrDefault(root, Map.of()).entrySet()) {
            ArgCommand c = s.getValue();
            node.then(chain(s.getKey().split(" "), 0, last -> last.then(
                    ClientCommands.argument(c.argName(), StringArgumentType.greedyString())
                            .suggests((ctx, b) -> {
                                String typed = b.getRemaining().toLowerCase(Locale.ROOT);
                                for (String v : c.suggestions().get()) {
                                    if (v.toLowerCase(Locale.ROOT).startsWith(typed)) b.suggest(v);
                                }
                                return b.buildFuture();
                            })
                            .executes(ctx -> runOwnArg(root, c.handler(),
                                    StringArgumentType.getString(ctx, c.argName()), ctx.getSource())))));
        }
        return node;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> chain(
            String[] parts, int i, UnaryOperator<LiteralArgumentBuilder<FabricClientCommandSource>> leaf) {
        LiteralArgumentBuilder<FabricClientCommandSource> n = ClientCommands.literal(parts[i]);
        return i == parts.length - 1 ? leaf.apply(n) : n.then(chain(parts, i + 1, leaf));
    }

    private static int runOwnArg(String root, ArgHandler handler, String arg, FabricClientCommandSource source) {
        return runOwn(root, reply -> handler.run(arg.trim(), reply), source);
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
