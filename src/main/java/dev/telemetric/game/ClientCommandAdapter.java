package dev.telemetric.game;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.telemetric.Telemetric;
import dev.telemetric.chat.ChatStyle;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

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
 * Client-side chat commands. A {@link #greedy} command goes to the server unchanged when its handler declines it (or
 * when off Telos), so it never hides a server command with the same name. {@link #own} commands work on every server
 * and are never forwarded.
 */
public final class ClientCommandAdapter {

    private static final Map<String, Predicate<String>> COMMANDS = new ConcurrentHashMap<>();
    /** Root, then sub-command ("" for the bare root), then handler. */
    private static final Map<String, Map<String, Consumer<Consumer<String>>>> OWN = new ConcurrentHashMap<>();
    /** Own commands with a text argument: root, then sub-command, then handler. */
    private static final Map<String, Map<String, ArgCommand>> OWN_ARG = new ConcurrentHashMap<>();
    /** Alias to root. {@code /alias …} is built from the same tree as {@code /root …}. */
    private static final Map<String, String> ALIASES = new ConcurrentHashMap<>();
    private static boolean registered;

    private ClientCommandAdapter() {}

    /** Adds {@code /name <text…>}. Returning false from the handler forwards the command to the server. */
    public static synchronized void greedy(String name, Predicate<String> handler) {
        COMMANDS.put(name, handler);
        ensureRegistered();
    }

    /**
     * Adds {@code /root sub} ({@code ""} for the bare {@code /root}). Never forwarded. The reply function prints a chat
     * line and can be called later from any thread.
     */
    public static synchronized void own(String root, String sub, Consumer<Consumer<String>> handler) {
        OWN.computeIfAbsent(root, k -> new ConcurrentHashMap<>()).put(sub, handler);
        ensureRegistered();
    }

    /** Handler for an own command with a text argument. The text may contain spaces. */
    @FunctionalInterface
    public interface ArgHandler {
        void run(String arg, Consumer<String> reply);
    }

    private record ArgCommand(String argName, Supplier<Collection<String>> suggestions, ArgHandler handler) {}

    /**
     * Adds {@code /root sub… <argName>}. {@code sub} may contain spaces. Suggestions are asked on every key press (the
     * list may be empty) and filtered case-insensitively by what was typed.
     */
    public static synchronized void ownArg(String root, String sub, String argName,
                                           Supplier<Collection<String>> suggestions, ArgHandler handler) {
        OWN_ARG.computeIfAbsent(root, k -> new ConcurrentHashMap<>()).put(sub, new ArgCommand(argName, suggestions, handler));
        ensureRegistered();
    }

    /** Alias for a root: same sub-commands and handlers. */
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

    /** Builds {@code /literal …} from the root's own sub-commands. Brigadier merges literals of the same name. */
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
            var arg = ClientCommands.argument(c.argName(), StringArgumentType.greedyString())
                    .suggests((ctx, b) -> {
                        String typed = b.getRemaining().toLowerCase(Locale.ROOT);
                        for (String v : c.suggestions().get()) {
                            if (v.toLowerCase(Locale.ROOT).startsWith(typed)) b.suggest(v);
                        }
                        return b.buildFuture();
                    })
                    .executes(ctx -> runOwnArg(root, c.handler(),
                            StringArgumentType.getString(ctx, c.argName()), ctx.getSource()));
            // an empty sub puts the argument straight under the root (/profile <player>)
            if (s.getKey().isEmpty()) node.then(arg);
            else node.then(chain(s.getKey().split(" "), 0, last -> last.then(arg)));
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
        // Lines replied to synchronously are joined into one message with a single "Telemetric ›" prefix
        // (see chat/ChatStyle). Lines sent later (async results) are separate messages, each with the prefix.
        java.util.List<String> now = new java.util.ArrayList<>();
        boolean[] done = {false};
        Consumer<String> reply = line -> {
            synchronized (now) {
                if (!done[0]) {
                    now.add(line);
                    return;
                }
            }
            ChatAdapter.post(ChatStyle.reply(line));
        };
        try {
            handler.accept(reply);
        } catch (RuntimeException e) {
            Telemetric.LOGGER.error("Client command /{} failed", root, e);
        }
        synchronized (now) {
            done[0] = true;
            if (!now.isEmpty()) ChatAdapter.post(ChatStyle.reply(now));
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
