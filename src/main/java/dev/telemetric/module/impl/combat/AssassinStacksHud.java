package dev.telemetric.module.impl.combat;

import dev.telemetric.game.CombatAdapter;
import dev.telemetric.game.CombatAdapter.ParticleKind;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.ServerInfoAdapter;
import dev.telemetric.hud.LinesHudModule;
import dev.telemetric.module.Category;
import dev.telemetric.setting.impl.BooleanSetting;

import java.util.List;
import java.util.Locale;

/**
 * Assassin Stacks: your current stack count as a HUD row, read from the stack particles around you
 * ({@link AssassinStackCounter}). Only shown while your class (Tab list) is Assassin, and hidden at 0 stacks.
 * <p>
 * With <b>HP Drain Detection</b> on, every stack above {@value #DRAIN_FREE_STACKS} drains {@value #HP_PER_STACK} HP
 * as a health drop without a hit (hurtTime 0), so such a drop gives the exact count:
 * {@code 16 + drop / 0.05}. The particle count is used until the first drain drop arrives, and again once no
 * drain drop came for {@value #DRAIN_TIMEOUT_MS} ms (back at ≤ 16 stacks).
 */
public final class AssassinStacksHud extends LinesHudModule {

    /** Server particles show where the player was 1–2 ticks ago, so we step the player back this many ticks. */
    private static final double LAG_TICKS = 1.5, LAG_TICKS_Y = 0.2;
    private static final int DRAIN_FREE_STACKS = 16;
    private static final double HP_PER_STACK = 0.05;
    private static final long DRAIN_TIMEOUT_MS = 2500;

    private final BooleanSetting hpDrain = add(new BooleanSetting("HP Drain Detection",
            "Above 16 stacks, count stacks from the HP they drain (0.05 HP per stack over 16) — exact at high stacks",
            true));

    private final AssassinStackCounter counter = new AssassinStackCounter();
    private volatile boolean assassin;
    private float lastHealth = Float.NaN;
    private volatile int drainStacks;
    private volatile long drainTime;

    public AssassinStacksHud() {
        super("Assassin Stacks", "Your Assassin stack count (only while playing Assassin)", Category.COMBAT,
                0.0, 0.62, false);
        CombatAdapter.addParticleListener(this::onParticle);
    }

    @Override
    public void onTick() {
        boolean now = ServerInfoAdapter.tabStat("Class")
                .map(s -> s.value().toLowerCase(Locale.ROOT).contains("assassin")).orElse(false);
        if (!now && assassin) resetAll();
        assassin = now;
        trackDrain();
    }

    /** A health drop with no hit animation = stack drain; its size gives the stacks above 16. */
    private void trackDrain() {
        PlayerAdapter.Health h = PlayerAdapter.health();
        if (h == null || !assassin) {
            lastHealth = Float.NaN;
            return;
        }
        float drop = lastHealth - h.current();
        lastHealth = h.current();
        if (!hpDrain.get() || !(drop > HP_PER_STACK / 2) || PlayerAdapter.hurtTime() > 0) return;
        drainStacks = DRAIN_FREE_STACKS + (int) Math.round(drop / HP_PER_STACK);
        drainTime = System.currentTimeMillis();
    }

    @Override
    protected void onDisable() {
        assassin = false;
        resetAll();
    }

    private void resetAll() {
        counter.reset();
        lastHealth = Float.NaN;
        drainStacks = 0;
    }

    private void onParticle(ParticleKind kind, double x, double y, double z) {
        if (!assassin || !isEnabled()) return;
        PlayerAdapter.Motion m = PlayerAdapter.motion();
        if (m == null) return;
        counter.accept(kind == ParticleKind.ELECTRIC_SPARK,
                x - (m.x() - m.vx() * LAG_TICKS), y - (m.y() - m.vy() * LAG_TICKS_Y), z - (m.z() - m.vz() * LAG_TICKS),
                System.currentTimeMillis());
    }

    private int stacks(long now) {
        if (!assassin) return 0;
        // drain value only while drain drops keep coming (> 16 stacks); otherwise the particle count
        if (hpDrain.get() && drainStacks > 0 && now - drainTime <= DRAIN_TIMEOUT_MS) return drainStacks;
        return counter.stacks(now);
    }

    @Override
    protected List<Line> lines(boolean preview) {
        int stacks = stacks(System.currentTimeMillis());
        if (stacks > 0) return List.of(new Line("Stacks", Integer.toString(stacks)));
        return preview ? List.of(new Line("Stacks", "5")) : List.of();
    }
}
