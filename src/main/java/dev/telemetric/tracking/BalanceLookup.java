package dev.telemetric.tracking;

import dev.telemetric.chat.ChatBus;
import dev.telemetric.chat.ChatPattern;
import dev.telemetric.game.PlayerAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Other players' fame and fragments, read from Telos' {@code /balance <user>} in the background (Visual → Player
 * Currency). One command at a time, at most one per {@link #GAP_MS}, and each player at most once per
 * {@link #CACHE_MS}, so the server's spam limit is never hit. The replies to our own requests never reach the chat
 * (not even All Chat); a {@code /balance} you type yourself still shows.
 * <p>
 * Reply: {@code "zPryxo has ❖16,652,699 normal and ❖1,632,163.3 seasonal balance.\nFragments:
 * 0/120 𖆲 + 93/120 𖃰 + 39/120 𕵁 + 69/120 𖆱"} (glyphs in {@code telos:material}); unknown player:
 * {@code "Sorry! player 'Notch' is not online or not on this server."}
 */
public final class BalanceLookup {

    /** A fragment count from the reply: {@code have}/{@code max} and its glyph. In reply order (Irradiated, Gilded, Royal, Bloodshot). */
    public record Fragment(String have, String max, String glyph) {}

    /**
     * @param fame by lowercase mode ({@code "normal"}, {@code "seasonal"}), the number as Telos writes it ("1,632,163.3")
     */
    public record Balance(Map<String, String> fame, List<Fragment> fragments) {}

    /** How long a reply is used before it's asked again. */
    public static final long CACHE_MS = 3 * 60_000L;
    /** Unknown player / no reply: ask again after this. */
    private static final long MISS_MS = 60_000L;
    /** At least this long between two commands. */
    public static final long GAP_MS = 1_000L;
    /** A request without a reply after this counts as a miss. */
    private static final long TIMEOUT_MS = 5_000L;
    /** Entries nobody asked for in this long are dropped. */
    private static final long FORGET_MS = 15 * 60_000L;

    private static final ChatPattern REPLY = ChatPattern.builder("Balance reply",
                    "(?<name>[A-Za-z0-9_]{1,16}) has (?<fame>.*?) balance\\.(?:\\n(?:Fragments: )?(?<frags>.*))?")
            .anyRuns().build(); // fame / fragment layout differs by mode (ironman) — only the text is checked
    private static final ChatPattern MISSING = ChatPattern.builder("Balance unknown player",
                    "Sorry! player '(?<name>[A-Za-z0-9_]{1,16})' is not online or not on this server\\.")
            .run(".+", ChatPattern.PLAIN).build();

    private static final Pattern FAME = Pattern.compile("❖([\\d,.]+) (\\w+)");
    private static final Pattern FRAGMENT = Pattern.compile("(\\d[\\d,]*)/(\\d[\\d,]*) (\\S+)");

    private record Entry(Balance balance, long at) {}

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static volatile String pending; // lowercase name of the request in flight, or null
    private static long sentAt, lastSent;
    private static boolean registered;

    private BalanceLookup() {}

    /** Hooks the chat listeners once. */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        ChatBus.replace(REPLY, m -> isPending(m.group("name")));
        ChatBus.replace(MISSING, m -> isPending(m.group("name")));
        ChatBus.on(REPLY, m -> {
            if (isPending(m.group("name"))) finish(parse(m));
        });
        ChatBus.on(MISSING, m -> {
            if (isPending(m.group("name"))) finish(null);
        });
    }

    /** The last balance read for {@code name}, or null if there's none (yet). */
    public static Balance get(String name) {
        Entry e = name == null ? null : CACHE.get(name.toLowerCase(Locale.ROOT));
        return e == null ? null : e.balance();
    }

    /**
     * Call every tick while the feature is on: sends the next {@code /balance} if the gap allows it, for the first of
     * {@code names} (nearest first) without a fresh reply.
     */
    public static void tick(List<String> names) {
        long now = System.currentTimeMillis();
        if (pending != null) {
            if (now - sentAt < TIMEOUT_MS) return;
            finish(null); // no reply
        }
        if (now - lastSent < GAP_MS) return;
        CACHE.values().removeIf(e -> now - e.at() > FORGET_MS);
        for (String name : names) {
            String key = name.toLowerCase(Locale.ROOT);
            Entry e = CACHE.get(key);
            if (e != null && now - e.at() < (e.balance() == null ? MISS_MS : CACHE_MS)) continue;
            pending = key;
            sentAt = lastSent = now;
            PlayerAdapter.sendCommand("balance " + name);
            return;
        }
    }

    /** Stops waiting for a reply (feature turned off, world left). Cached replies are kept. */
    public static void cancel() {
        pending = null;
    }

    private static boolean isPending(String name) {
        String p = pending;
        return p != null && p.equalsIgnoreCase(name);
    }

    private static void finish(Balance balance) {
        String key = pending;
        pending = null;
        if (key == null) return;
        Entry old = CACHE.get(key);
        // a failed refresh keeps the old numbers on screen; only the next try is sooner
        if (balance == null && old != null && old.balance() != null) {
            CACHE.put(key, new Entry(old.balance(), System.currentTimeMillis() - CACHE_MS + MISS_MS));
        } else {
            CACHE.put(key, new Entry(balance, System.currentTimeMillis()));
        }
    }

    private static Balance parse(MatchResult m) {
        Map<String, String> fame = new HashMap<>();
        Matcher f = FAME.matcher(m.group("fame"));
        while (f.find()) fame.put(f.group(2).toLowerCase(Locale.ROOT), f.group(1));
        List<Fragment> fragments = new ArrayList<>();
        String frags = m.group("frags");
        if (frags != null) {
            Matcher g = FRAGMENT.matcher(frags);
            while (g.find()) fragments.add(new Fragment(g.group(1), g.group(2), g.group(3)));
        }
        return new Balance(Map.copyOf(fame), List.copyOf(fragments));
    }
}
