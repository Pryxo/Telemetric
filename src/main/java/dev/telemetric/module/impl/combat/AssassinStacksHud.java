package dev.telemetric.module.impl.combat;

import dev.telemetric.game.CombatAdapter;
import dev.telemetric.game.CombatAdapter.ParticleKind;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.ServerInfoAdapter;
import dev.telemetric.hud.LinesHudModule;
import dev.telemetric.module.Category;

import java.util.List;
import java.util.Locale;

/**
 * Assassin Stacks: your current stack count as a HUD row, read from the stack particles around you
 * ({@link AssassinStackCounter}). Only shown while your class (Tab list) is Assassin, and hidden at 0 stacks.
 */
public final class AssassinStacksHud extends LinesHudModule {

    /** Server particles show where the player was 1–2 ticks ago, so we step the player back this many ticks. */
    private static final double LAG_TICKS = 1.5, LAG_TICKS_Y = 0.2;

    private final AssassinStackCounter counter = new AssassinStackCounter();
    private volatile boolean assassin;

    public AssassinStacksHud() {
        super("Assassin Stacks", "Your Assassin stack count (only while playing Assassin)", Category.COMBAT,
                0.0, 0.62, false);
        CombatAdapter.addParticleListener(this::onParticle);
    }

    @Override
    public void onTick() {
        boolean now = ServerInfoAdapter.tabStat("Class")
                .map(s -> s.value().toLowerCase(Locale.ROOT).contains("assassin")).orElse(false);
        if (!now && assassin) counter.reset();
        assassin = now;
    }

    @Override
    protected void onDisable() {
        assassin = false;
        counter.reset();
    }

    private void onParticle(ParticleKind kind, double x, double y, double z) {
        if (!assassin || !isEnabled()) return;
        PlayerAdapter.Motion m = PlayerAdapter.motion();
        if (m == null) return;
        counter.accept(kind == ParticleKind.ELECTRIC_SPARK,
                x - (m.x() - m.vx() * LAG_TICKS), y - (m.y() - m.vy() * LAG_TICKS_Y), z - (m.z() - m.vz() * LAG_TICKS),
                System.currentTimeMillis());
    }

    @Override
    protected List<Line> lines(boolean preview) {
        int stacks = assassin ? counter.stacks(System.currentTimeMillis()) : 0;
        if (stacks > 0) return List.of(new Line("Stacks", Integer.toString(stacks)));
        return preview ? List.of(new Line("Stacks", "5")) : List.of();
    }
}
