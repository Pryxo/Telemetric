package dev.telemetric.chat;

import dev.telemetric.data.CalloutTable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Boss and portal call-outs in player chat. Both the format Telemetric sends and the one it reads live here, so they
 * stay in sync:
 * <ul>
 *   <li>{@code Teleport for <boss> - <hp>% HP} / {@code Teleport for <portal> - <n>s left} (realm),</li>
 *   <li>{@code <boss> is at <hp>% HP - <phase or area>} / {@code <a> & <b> are at <x>%/<y>% HP - …} (dungeon),</li>
 *   <li>{@code Currently in <area>} (dungeon, no boss bar).</li>
 * </ul>
 * Call-outs are <b>player text</b>, so they never trigger anything on their own. We only remember who called what in
 * which realm (for the public cooldown and cross-realm teleports) and add a link the user can click. Pure Java.
 */
public final class Callouts {

    /** A player chat line {@code [<tag>] <rank / glyphs> <name>: <message>}. */
    private static final Pattern LINE = Pattern.compile("^\\[([^\\]\\n]+)\\][^\\n]*?\\s([A-Za-z0-9_]{1,16}):\\s+(.*)$",
            Pattern.DOTALL);
    private static final Pattern TELEPORT = Pattern.compile("^Teleport for (.+?) - (?:(\\d+)%(?:/\\d+%)? HP|\\d+s left)");
    private static final Pattern BOSS_HP = Pattern.compile("^(.+?) (?:is|are) at (\\d+)%(?:/\\d+%)? HP - .+");
    private static final Pattern DUNGEON = Pattern.compile("^Currently in (.+)");

    private static final long REALM_MEMORY_MS = 60 * 60_000L, CALL_MEMORY_MS = 60_000L;

    /**
     * A player chat line.
     *
     * @param tag          text inside the leading brackets ({@code Astra}, {@code Guild}, or {@code EU, Astra} for shouts)
     * @param prefixEnd    index just past the closing {@code ]}
     * @param contentStart index where the message starts, after {@code "name: "}
     */
    public record Line(String tag, String player, int prefixEnd, int contentStart) {

        /** Guild or group channel line (not public, no realm). */
        public boolean isChannel() {
            return tag.equalsIgnoreCase("Guild") || tag.equalsIgnoreCase("Group");
        }

        /** A shout ({@code [region, realm]}), which was sent from another realm. */
        public boolean isShout() {
            return tag.indexOf(',') >= 0;
        }

        /** The realm the sender is in (lower case), or null for guild and group lines. */
        public String realm() {
            if (isChannel()) return null;
            int comma = tag.lastIndexOf(',');
            return (comma < 0 ? tag : tag.substring(comma + 1)).trim().toLowerCase(Locale.ROOT);
        }
    }

    /** A call-out: what was called ({@code target}) and its HP % (-1 for portals and "Currently in"). */
    public record Call(Line line, String target, int hp) {}

    private record Seen(String realm, long time, int hp) {}

    private final Map<String, Seen> realmOfPlayer = new HashMap<>();
    private final Map<String, Seen> publicCalls = new HashMap<>();

    // ------------------------------------------------------------------ reading

    /** The player chat line in {@code plain}, or null for anything else (system lines, plugins). */
    public static Line line(String plain) {
        if (plain == null || plain.isEmpty() || plain.charAt(0) != '[') return null;
        Matcher m = LINE.matcher(plain);
        if (!m.find()) return null;
        return new Line(m.group(1).trim(), m.group(2), m.end(1) + 1, m.start(3));
    }

    /** The call-out in {@code plain}, or null. */
    public static Call parse(String plain) {
        Line line = line(plain);
        if (line == null) return null;
        String content = plain.substring(line.contentStart());
        Matcher m = TELEPORT.matcher(content);
        if (m.find()) return new Call(line, m.group(1), m.group(2) == null ? -1 : Integer.parseInt(m.group(2)));
        m = BOSS_HP.matcher(content);
        if (m.find()) return new Call(line, m.group(1), Integer.parseInt(m.group(2)));
        m = DUNGEON.matcher(content);
        if (m.find()) return new Call(line, m.group(1).trim(), -1);
        return null;
    }

    public static boolean isCallout(String plain) {
        return parse(plain) != null;
    }

    // ------------------------------------------------------------------ writing

    public static String teleport(String boss, int hp) {
        return "Teleport for " + boss + " - " + hp + "% HP";
    }

    public static String portal(String portal, int secondsLeft) {
        return "Teleport for " + portal + " - " + secondsLeft + "s left";
    }

    public static String bossAt(String boss, int hp, String where) {
        return boss + " is at " + hp + "% HP - " + where;
    }

    public static String duoAt(String name, int hpA, int hpB, String where) {
        return name + " are at " + hpA + "%/" + hpB + "% HP - " + where;
    }

    public static String currentlyIn(String area) {
        return "Currently in " + area;
    }

    // ------------------------------------------------------------------ memory

    /** Remembers the sender's realm and, for public lines, that {@code call.target} was just called there. */
    public void remember(Call call, long now) {
        String realm = call.line().realm();
        if (realm == null) return; // guild / group: the realm is unknown, and these never block public calls
        realmOfPlayer.put(call.line().player().toLowerCase(Locale.ROOT), new Seen(realm, now, -1));
        publicCalls.put(callKey(realm, call.target()), new Seen(realm, now, call.hp()));
        prune(now);
    }

    /** Remembers that {@code player} spoke in {@code realm}, e.g. when a realm prefix was clicked. */
    public void rememberRealm(String player, String realm, long now) {
        realmOfPlayer.put(player.toLowerCase(Locale.ROOT), new Seen(realm.toLowerCase(Locale.ROOT), now, -1));
    }

    /** The last realm {@code player} called something out in (within the last hour), or null. */
    public String realmOf(String player, long now) {
        Seen s = realmOfPlayer.get(player.toLowerCase(Locale.ROOT));
        return s == null || now - s.time() > REALM_MEMORY_MS ? null : s.realm();
    }

    /**
     * Milliseconds until {@code target} can be called out publicly in {@code realm} again (0 means now). This is
     * non-zero when someone called it within the cooldown, and the wait gets shorter once its HP has moved by
     * {@code hpStep} % since then.
     */
    public long cooldownLeft(String realm, String target, int hp, long now, CalloutTable.Cooldown cd) {
        Seen last = publicCalls.get(callKey(realm, target));
        if (last == null) return 0;
        boolean hpMoved = hp >= 0 && last.hp() >= 0 && Math.abs(last.hp() - hp) >= cd.hpStep();
        long wait = (hpMoved ? cd.hpChangedSeconds() : cd.seconds()) * 1000L;
        return Math.max(0, wait - (now - last.time()));
    }

    /** Records your own public call. */
    public void called(String realm, String target, int hp, long now) {
        publicCalls.put(callKey(realm, target), new Seen(realm, now, hp));
    }

    private static String callKey(String realm, String target) {
        return realm.toLowerCase(Locale.ROOT) + '|' + target.toLowerCase(Locale.ROOT);
    }

    private void prune(long now) {
        prune(realmOfPlayer, now, REALM_MEMORY_MS);
        prune(publicCalls, now, CALL_MEMORY_MS);
    }

    private static void prune(Map<String, Seen> map, long now, long maxAge) {
        for (Iterator<Seen> it = map.values().iterator(); it.hasNext(); ) {
            if (now - it.next().time() > maxAge) it.remove();
        }
    }
}
