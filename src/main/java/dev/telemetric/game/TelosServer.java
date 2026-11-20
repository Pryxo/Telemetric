package dev.telemetric.game;

import java.util.Locale;

/**
 * The server you're on, from the Tab list "Server" value {@code [<region>, <realm>]} ({@code [<name>]} on servers
 * without a region). It's the one parser for every feature that needs the region or realm (Dungeon ID, Callouts,
 * Realm Selector, Discord RPC). Pure parsing; {@link #current()} reads the Tab list through {@link ServerInfoAdapter}.
 *
 * @param region the region part ("" if the value has none)
 * @param realm  realm / hub / dungeon server name, in the form {@code /joinq} takes
 */
public record TelosServer(String region, String realm) {

    /** Parses a Tab "Server" value. Null for null or blank input. */
    public static TelosServer parse(String value) {
        if (value == null) return null;
        String s = value.trim();
        if (s.startsWith("[")) s = s.substring(1);
        if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
        s = s.trim();
        if (s.isEmpty()) return null;
        int comma = s.indexOf(',');
        if (comma < 0) return new TelosServer("", s);
        return new TelosServer(s.substring(0, comma).trim(), s.substring(comma + 1).trim());
    }

    /** The current server from the Tab list, or null before the Tab list has been filled. */
    public static TelosServer current() {
        return parse(ServerInfoAdapter.tabStat("Server").map(ServerInfoAdapter.TabStat::value).orElse(null));
    }

    /** Lower-case realm name, for comparing with realm tags in chat. */
    public String realmKey() {
        return realm.toLowerCase(Locale.ROOT);
    }

    /** {@code "NA, Ashburn"} / {@code "Ashburn"}. */
    public String display() {
        return region.isEmpty() ? realm : region + ", " + realm;
    }
}
