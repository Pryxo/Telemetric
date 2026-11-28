package dev.telemetric.command;

import dev.telemetric.game.ClientCommandAdapter;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.ProfileAvatarAdapter;
import dev.telemetric.game.ScreenOpener;
import dev.telemetric.ui.profile.ProfileScreen;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code /profile [player]} (alias {@code /pv}): opens the Telos profile viewer. No argument = yourself. Runs on any
 * server (the data comes from our own server, see {@code api/TelosApi}). Suggestions: players you looked up this
 * session, then your tab list.
 */
public final class ProfileCommand {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private ProfileCommand() {}

    public static void register() {
        ClientCommandAdapter.own("profile", "", reply -> open(PlayerAdapter.name(), reply));
        ClientCommandAdapter.ownArg("profile", "", "player", ProfileCommand::suggestions, ProfileCommand::open);
        ClientCommandAdapter.alias("profile", "pv");
    }

    private static void open(String name, java.util.function.Consumer<String> reply) {
        String n = name == null ? "" : name.trim();
        if (!NAME.matcher(n).matches() && !n.matches("\\p{XDigit}{32}|[\\p{XDigit}-]{36}")) {
            reply.accept("Usage: /profile <username>");
            return;
        }
        ScreenOpener.profile(n);
    }

    private static Set<String> suggestions() {
        Set<String> out = new LinkedHashSet<>(ProfileScreen.recent());
        List<String> online = ProfileAvatarAdapter.onlineNames();
        out.addAll(online);
        return out;
    }
}
