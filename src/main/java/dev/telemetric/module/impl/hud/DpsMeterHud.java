package dev.telemetric.module.impl.hud;

import dev.telemetric.Telemetric;
import dev.telemetric.game.BossBarAdapter;
import dev.telemetric.module.impl.visual.BossStageTrackerModule;
import dev.telemetric.game.DamageNumberAdapter;
import dev.telemetric.game.DamageNumberAdapter.DamageNumber;
import dev.telemetric.hud.LinesHudModule;
import dev.telemetric.hud.TextRun;
import dev.telemetric.setting.impl.ActionSetting;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ModeSetting;
import dev.telemetric.setting.impl.NumberSetting;

import java.util.ArrayList;
import java.util.List;

/**
 * DPS meter: reads every floating damage number the server shows (see {@link DamageNumberAdapter}, shared through
 * {@link DamageFeed}). <i>Visibility</i> can be always, only while a known boss bar is up (one fight per boss), or from
 * the first hit until {@code Hide After} seconds without one. Max and Last Hit use the {@link DamageTiers} colours.
 */
public final class DpsMeterHud extends LinesHudModule {

    static final String NEVER_HIDE = "Never Hide", ON_BOSSES = "Only on Bosses", HIDE_AFTER = "Hide After Xs";

    /** The pause that ends a fight when the meter never hides. There's no slider for it since the meter stays up anyway. */
    private static final long FIGHT_GAP_MS = 5000;

    private final ModeSetting visibility = add(new ModeSetting("Visibility",
            "Never Hide: always shown (last fight stays). Only on Bosses: while a boss bar is up, one fight per boss. "
                    + "Hide After Xs: from the first hit until X seconds without one",
            HIDE_AFTER, NEVER_HIDE, ON_BOSSES, HIDE_AFTER));
    private final NumberSetting hideAfter = add(new NumberSetting("Hide After",
            "Seconds without a damage number until the fight ends and the meter hides", 5, 1, 60, 1).withSuffix("s")
            .visibleWhen(() -> visibility.is(HIDE_AFTER)));

    private final BooleanSetting showDps = add(new BooleanSetting("Show DPS", "Damage per second of the current fight", true));
    private final NumberSetting dpsWindow = add(new NumberSetting("DPS Window",
            "DPS = damage of the last X seconds ÷ X. 0 = whole fight (total ÷ fight length)", 0, 0, 60, 1)
            .withSuffix("s").visibleWhen(showDps::get));
    private final BooleanSetting showMax = add(new BooleanSetting("Show Max Hit", "Highest single hit of the fight", true));
    private final BooleanSetting resetMax = add(new BooleanSetting("Reset Max Hit",
            "Forget the max hit after some seconds without a bigger one", false).visibleWhen(showMax::get));
    private final NumberSetting resetMaxAfter = add(new NumberSetting("Reset Max After",
            "Seconds until the max hit resets", 10, 1, 30, 1).withSuffix("s")
            .visibleWhen(() -> showMax.get() && resetMax.get()));
    private final BooleanSetting showLast = add(new BooleanSetting("Show Last Hit", "Most recent hit", true));
    private final BooleanSetting showTotal = add(new BooleanSetting("Show Total", "Total damage of the fight", true));
    private final BooleanSetting showHits = add(new BooleanSetting("Show Hits", "Number of hits in the fight", false));
    private final BooleanSetting compact = add(new BooleanSetting("Compact Numbers", "12.3k instead of 12,345", true));
    private final BooleanSetting tierColors = add(new BooleanSetting("Damage Colors",
            "Colour Max / Last Hit by size (50, 100, 150, 200, 300, 400, 500, 700, shiny at 1k) like the DPS Log", true));
    private final BooleanSetting hideNumbers = add(new BooleanSetting("Hide Damage Numbers",
            "Remove the floating damage numbers from the world once they're counted (only on your screen)", false));
    private final BooleanSetting useServerColor = addUseServerColor();
    private final ActionSetting resetAction = add(new ActionSetting("Reset", "End the current fight", this::resetStats));

    private final DpsTracker tracker = new DpsTracker();
    private boolean bossUp;

    public DpsMeterHud() {
        super("DPS Meter", "Damage per second from the server's floating damage numbers", 0.0, 0.62, false);
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    private long timeoutMs() {
        if (visibility.is(ON_BOSSES)) return bossUp ? Long.MAX_VALUE / 4 : 0;
        if (visibility.is(NEVER_HIDE)) return FIGHT_GAP_MS;
        return Math.round(hideAfter.get() * 1000);
    }

    private void resetStats() {
        tracker.reset();
    }

    @Override
    protected void onEnable() {
        tracker.reset();
        bossUp = false;
    }

    @Override
    public void onTick() {
        boolean boss = !BossBarAdapter.knownBosses().isEmpty();
        if (boss && !bossUp && visibility.is(ON_BOSSES)) tracker.reset(); // a new boss means a new fight
        bossUp = boss;

        long now = now();
        for (DamageNumber n : DamageFeed.thisTick()) {
            tracker.add(now, n.amount(), timeoutMs());
            if (hideNumbers.get()) DamageNumberAdapter.hide(n.entityId());
        }
        if (showMax.get() && resetMax.get()) tracker.expireMax(now, Math.round(resetMaxAfter.get() * 1000));
    }

    @Override
    protected String title() {
        return "DPS Meter";
    }

    private boolean visible(long now) {
        if (visibility.is(NEVER_HIDE)) return true;
        if (visibility.is(ON_BOSSES)) return bossUp;
        return tracker.active(now, timeoutMs());
    }

    /** One DPS stat for the Boss Stage Tracker label: name, value and its damage-tier colour (ARGB, 0 = none). */
    public record Row(String label, String value, int argb) {}

    /**
     * The stats this meter has toggled on (same order as the HUD), for the current fight, for the Boss Stage Tracker's
     * world label. Empty while the meter is off or there's no fight yet.
     */
    public List<Row> statRows() {
        if (!isEnabled() || !tracker.hasFight()) return List.of();
        long now = now();
        boolean running = tracker.active(now, timeoutMs());
        String dps = fmt(running ? tracker.dps(now, Math.round(dpsWindow.get() * 1000)) : 0);
        List<Row> rows = new ArrayList<>();
        if (showDps.get()) rows.add(new Row("DPS", dps, 0));
        if (showMax.get()) rows.add(hitRow("Max Hit", tracker.max()));
        if (showLast.get()) rows.add(hitRow("Last Hit", tracker.last()));
        if (showTotal.get()) rows.add(new Row("Total", fmt(tracker.total()), 0));
        if (showHits.get()) rows.add(new Row("Hits", Integer.toString(tracker.hits()), 0));
        if (rows.isEmpty()) rows.add(new Row("DPS", dps, 0));
        return rows;
    }

    private Row hitRow(String label, double amount) {
        return new Row(label, fmt(amount), tierColors.get() ? DamageTiers.color(DamageTiers.tier(amount)) : 0);
    }

    @Override
    protected List<Line> lines(boolean preview) {
        long now = now();
        // Boss Stage Tracker → DPS → Hide DPS HUD: the stats sit on the boss label instead
        if (!preview && Telemetric.get().modules().get(BossStageTrackerModule.class).hidesDpsHud()) return List.of();
        if (!visible(now) || (preview && !tracker.hasFight())) return preview ? placeholder() : List.of();

        // DPS drops to 0 once the fight ended (a frozen clock kept the last value forever); Max/Total stay.
        boolean running = tracker.active(now, timeoutMs());
        String dps = fmt(running ? tracker.dps(now, Math.round(dpsWindow.get() * 1000)) : 0);
        List<Line> rows = new ArrayList<>();
        if (showDps.get()) rows.add(new Line("DPS", dps));
        if (showMax.get()) rows.add(hit("Max Hit", tracker.max()));
        if (showLast.get()) rows.add(hit("Last Hit", tracker.last()));
        if (showTotal.get()) rows.add(new Line("Total", fmt(tracker.total())));
        if (showHits.get()) rows.add(new Line("Hits", Integer.toString(tracker.hits())));
        if (rows.isEmpty()) rows.add(new Line("DPS", dps));
        return rows;
    }

    /** A hit row, with the value in its damage-tier colour (shiny at 1k+) when Damage Colors is on. */
    private Line hit(String label, double amount) {
        Line plain = new Line(label, fmt(amount));
        int tier = DamageTiers.tier(amount);
        if (!tierColors.get() || tier == 0) return plain;
        return plain.styledRuns(List.of(new TextRun(label, 0, null)), DamageTiers.runs(plain.value(), tier, 0));
    }

    private List<Line> placeholder() {
        List<Line> rows = new ArrayList<>();
        if (showDps.get()) rows.add(new Line("DPS", "331"));
        if (showMax.get()) rows.add(hit("Max Hit", 1240));
        if (showLast.get()) rows.add(hit("Last Hit", 215));
        if (showTotal.get()) rows.add(new Line("Total", "5,180"));
        if (showHits.get()) rows.add(new Line("Hits", "14"));
        if (rows.isEmpty()) rows.add(new Line("DPS", "331"));
        return rows;
    }

    private String fmt(double v) {
        return DamageTiers.format(v, compact.get());
    }
}
