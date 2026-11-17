package dev.telemetric.tracking;

import dev.telemetric.Telemetric;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.ServerInfoAdapter;
import dev.telemetric.game.TelosCharacter;

import java.util.Locale;
import java.util.UUID;

/**
 * Which pity pool the character you play now counts into. Telos shares pity per mode: every <b>normal</b>
 * character shares one pool, every <b>seasonal</b> character another, and each <b>hardcore</b> character has its own.
 * <b>Group</b> characters (ruleset {@code group_type/<colour>} glyphs) are tracked like hardcore ones, one pool each.
 * Scope keys: {@code normal}, {@code seasonal}, {@code hardcore:<id>}, {@code group:<id>}.
 * <p>
 * The mode comes from the Tab "Class" glyph ({@link TelosCharacter}). All hardcore characters share that glyph, so
 * a hardcore character is recognised by its <i>signature</i>: class, level, Fame, Tier Points and the four fragment
 * counts from the Tab list. Those values cannot change while you play another character, so on coming back the
 * signature matches the one stored when you left. While you play, the bound character's signature is kept up to date.
 * <p>
 * Rebinding happens only after a world change (login, character switch, hub, dungeon), once the Tab values have been
 * stable for {@link #SETTLE_MS}: (1) a stored character with exactly this signature, else (2) the character played
 * before the world change if class matches and the level did not go down (fame/fragments may have changed on the
 * way, e.g. a dungeon reward), else (3) a new character. Until then the previous scope stays in use.
 * Known limit: switching straight to a hardcore character the tracker has never seen, of the same class and at
 * least the same level as the one played before, is taken for the same character.
 */
public final class PityScopes {

    public static final String NORMAL = "normal", SEASONAL = "seasonal", HARDCORE_PREFIX = "hardcore:",
            GROUP_PREFIX = "group:";
    /** Signature prefix of group characters (hardcore signatures have none, so stored ones keep matching). */
    private static final String GROUP_SIG = "group|";
    private static final long SETTLE_MS = 2000;
    private static final String[] SIGNATURE_STATS = {"Fame", "Tier Points",
            "Irradiated Fragments", "Gilded Fragments", "Royal Fragments", "Bloodshot Fragments"};

    private static final PityScopes INSTANCE = new PityScopes();

    public static PityScopes get() {
        return INSTANCE;
    }

    /** Stored hardcore character: id, last seen signature, class + level for the continuation check. */
    public record HardcoreChar(String id, String signature, String className, int level, long lastSeen) {}

    private volatile String scope = NORMAL;
    /** Whether the Tab "Class" glyph told the mode on the last tick (false = scope is only the fallback). */
    private volatile boolean modeKnown;
    private TrackingStore store;
    private Object world;
    /** Hardcore character played now (null = not on hardcore or not resolved yet). */
    private HardcoreChar bound;
    /** Character bound before the last world change (continuation candidate), and whether a rebind is pending. */
    private HardcoreChar previous;
    private boolean pending = true;
    private String settleSignature;
    private long settleSince;

    private PityScopes() {}

    /** Pity scope to count into now. */
    public String scope() {
        return scope;
    }

    /** True when the current mode was read from the Tab list, false when {@link #scope()} is only a fallback. */
    public boolean modeKnown() {
        return modeKnown;
    }

    /** Human name of a scope ("Normal", "Seasonal", "Hardcore Knight"). */
    public static String label(String scope, TrackingStore store) {
        if (SEASONAL.equals(scope)) return "Seasonal";
        if (scope != null && scope.startsWith(HARDCORE_PREFIX)) {
            HardcoreChar c = store.hardcoreChar(scope.substring(HARDCORE_PREFIX.length()));
            return c == null || c.className().isEmpty() ? "Hardcore" : "Hardcore " + c.className();
        }
        if (scope != null && scope.startsWith(GROUP_PREFIX)) {
            HardcoreChar c = store.hardcoreChar(scope.substring(GROUP_PREFIX.length()));
            return c == null || c.className().isEmpty() ? "Group" : "Group " + c.className();
        }
        return "Normal";
    }

    /** Client tick (on Telos), before counting: follows mode and hardcore character. */
    public synchronized void tick(TrackingStore current) {
        if (current != store) { // other account: start over
            store = current;
            bound = previous = null;
            pending = true;
            settleSignature = null;
        }
        Object w = PlayerAdapter.worldToken();
        if (w != world) {
            world = w;
            if (bound != null) previous = bound;
            bound = null;
            pending = true;
            settleSignature = null;
        }
        if (w == null) return;
        TelosCharacter ch = TelosCharacter.current().orElse(null);
        modeKnown = ch != null && !ch.mode().isEmpty();
        if (!modeKnown) return; // Tab not there yet / unknown glyph: keep the last scope
        boolean group = "group".equals(ch.mode());
        if (!group && !"hardcore".equals(ch.mode())) {
            setScope(ch.mode()); // normal / seasonal
            bound = previous = null;
            pending = false;
            return;
        }
        String sig = signature(ch, group);
        long now = System.currentTimeMillis();
        if (bound != null && !pending) {
            if (!sig.equals(bound.signature())) bind(new HardcoreChar(bound.id(), sig, ch.className(), ch.level(), now));
            return;
        }
        if (!sig.equals(settleSignature)) {
            settleSignature = sig;
            settleSince = now;
            return;
        }
        if (now - settleSince < SETTLE_MS) return;
        bind(resolve(ch, sig, now));
        pending = false;
        previous = null;
    }

    private HardcoreChar resolve(TelosCharacter ch, String sig, long now) {
        HardcoreChar exact = store.hardcoreBySignature(sig);
        if (exact != null) return new HardcoreChar(exact.id(), sig, ch.className(), ch.level(), now);
        if (previous != null && isGroup(previous) == sig.startsWith(GROUP_SIG)
                && previous.className().equals(ch.className()) && ch.level() >= previous.level()) {
            HardcoreChar p = store.hardcoreChar(previous.id());
            if (p != null) return new HardcoreChar(p.id(), sig, ch.className(), ch.level(), now);
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        Telemetric.debug("Pity: new {} character {} ({} lvl {})", ch.mode(), id, ch.className(), ch.level());
        return new HardcoreChar(id, sig, ch.className(), ch.level(), now);
    }

    private void bind(HardcoreChar c) {
        bound = c;
        store.putHardcoreChar(c);
        setScope((isGroup(c) ? GROUP_PREFIX : HARDCORE_PREFIX) + c.id());
    }

    private static boolean isGroup(HardcoreChar c) {
        return c.signature().startsWith(GROUP_SIG);
    }

    private void setScope(String s) {
        if (!s.equals(scope)) Telemetric.debug("Pity: scope {}", s);
        scope = s;
    }

    private static String signature(TelosCharacter ch, boolean group) {
        StringBuilder b = new StringBuilder(group ? GROUP_SIG : "").append(ch.className()).append('|').append(ch.level());
        for (String label : SIGNATURE_STATS) {
            String v = ServerInfoAdapter.tabStat(label).map(ServerInfoAdapter.TabStat::value).orElse("");
            b.append('|').append(v.strip().toLowerCase(Locale.ROOT));
        }
        return b.toString();
    }
}
