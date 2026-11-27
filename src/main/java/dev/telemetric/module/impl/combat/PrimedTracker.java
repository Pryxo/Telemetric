package dev.telemetric.module.impl.combat;

import dev.telemetric.data.TelosData;
import dev.telemetric.data.TraitTable;
import dev.telemetric.game.CombatAdapter;
import dev.telemetric.game.CombatAdapter.ItemInfo;
import dev.telemetric.game.CombatAdapter.ItemTrait;
import dev.telemetric.game.CombatAdapter.Kind;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks the weapon trait <b>Primed</b>: the first hit after not attacking for {@code factor × weapon cooldown} deals
 * 2× damage (cancelled by swapping). The factor per tier comes from {@code tables/traits.json} (Primed, value
 * {@code {0}}, e.g. S = {@code "1.85x"}).
 * <p>
 * An attack is a weapon cooldown start seen by {@link CooldownTracker} (the server only sends it when a shot really
 * fires), so swings that hit nothing don't reset the wait. The cooldown length is the one from that packet, with the
 * lore's {@code Cooldown » 1s} as a fallback before the first shot. Swapping to a Primed weapon restarts its wait.
 * <p>
 * Ticked every client tick after {@link CooldownTracker}, so it keeps up even while Auto Clicker is off.
 */
public final class PrimedTracker {

    public static final String TRAIT = "Primed";
    private static final double FALLBACK_FACTOR = 2.0; // safe upper bound if the table has no value

    /** Last seen attack of one cooldown group. */
    private record Attack(long startMs, long endMs, long durationMs) {}

    private static final PrimedTracker INSTANCE = new PrimedTracker();

    private final Map<String, Attack> attacks = new HashMap<>();
    private String heldGroup;
    private long swapMs;

    private PrimedTracker() {}

    public static PrimedTracker get() {
        return INSTANCE;
    }

    public void tick() {
        ItemInfo main = CombatAdapter.mainHand();
        String group = main != null && main.kind() == Kind.WEAPON ? main.group() : null;
        if (group != null && !group.equals(heldGroup)) swapMs = System.currentTimeMillis();
        heldGroup = group;

        for (CooldownTracker.Cooldown c : CooldownTracker.get().active()) {
            if (!c.is(Kind.WEAPON)) continue;
            Attack last = attacks.get(c.group());
            if (last != null && last.startMs() == c.startMs() && last.endMs() == c.endMs()) continue;
            if (last == null || last.startMs() != c.startMs()) {
                attacks.put(c.group(), new Attack(c.startMs(), c.endMs(), c.endMs() - c.startMs()));
            } else {
                // a re-sent cooldown kept the old start (CooldownTracker resync), so the shot was at end - duration
                attacks.put(c.group(), new Attack(Math.max(c.startMs(), c.endMs() - last.durationMs()),
                        c.endMs(), last.durationMs()));
            }
        }
    }

    /** True if the item has the Primed trait. */
    public static boolean hasPrimed(ItemInfo info) {
        return info != null && info.trait(TRAIT) != null;
    }

    /** The wait factor of the item's Primed tier (e.g. 1.85), or NaN if it has no Primed. */
    public static double factor(ItemInfo info) {
        ItemTrait t = info == null ? null : info.trait(TRAIT);
        if (t == null) return Double.NaN;
        TraitTable table = TelosData.TRAITS.get();
        TraitTable.Trait trait = table.get(TRAIT);
        double f = trait == null ? Double.NaN : trait.number(0, table.tier(t.tier()));
        return Double.isNaN(f) || f <= 0 ? FALLBACK_FACTOR : f;
    }

    /** How long to stay idle with this weapon before the next hit is primed, in ms; or -1 if it has no Primed. */
    public long waitMs(ItemInfo info) {
        if (!hasPrimed(info)) return -1;
        Attack a = attacks.get(info.group());
        long cooldownMs = a != null && a.durationMs() > 0 ? a.durationMs()
                : info.cooldown() > 0 ? Math.round(info.cooldown() * 1000.0) : 0;
        return Math.round(factor(info) * cooldownMs);
    }

    /**
     * When the next hit with {@code info} will be primed (wall clock ms), or -1 if it has no Primed. Counted from its
     * last attack, or from when it was swapped to if that is later ({@code swapResets}).
     */
    public long readyAtMs(ItemInfo info, boolean swapResets) {
        long wait = waitMs(info);
        if (wait < 0) return -1;
        Attack a = attacks.get(info.group());
        long from = a == null ? 0 : a.startMs();
        if (swapResets && info.group().equals(heldGroup)) from = Math.max(from, swapMs);
        return from + wait;
    }

    /** True if the next hit with {@code info} is primed (false if it has no Primed). */
    public boolean isPrimed(ItemInfo info, boolean swapResets, long now) {
        long at = readyAtMs(info, swapResets);
        return at >= 0 && now >= at;
    }
}
