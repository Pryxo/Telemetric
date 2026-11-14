package dev.telemetric.module.impl.hud;

import dev.telemetric.game.DamageNumberAdapter;
import dev.telemetric.game.DamageNumberAdapter.DamageNumber;
import dev.telemetric.hud.LinesHudModule;
import dev.telemetric.setting.impl.ActionSetting;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.NumberSetting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * DPS meter: reads the server's floating damage numbers (see {@link DamageNumberAdapter}).
 * Appears with the first damage number of a fight and hides after {@code Hide After} seconds without one.
 */
public final class DpsMeterHud extends LinesHudModule {

    private final NumberSetting hideAfter = add(new NumberSetting("Hide After",
            "Seconds without a damage number until the fight ends and the meter hides", 5, 1, 60, 1).withSuffix("s"));
    private final NumberSetting range = add(new NumberSetting("Range",
            "Only count damage numbers within this distance of you", 24, 4, 64, 1).withSuffix(" blocks"));
    private final BooleanSetting ignoreSelf = add(new BooleanSetting("Ignore Damage Taken",
            "Skip numbers that pop up on yourself", true));
    private final BooleanSetting plainNumbers = add(new BooleanSetting("Count Plain Numbers",
            "Also count numbers in normal text (off on Telos: those are countdowns, damage uses pack glyphs)", false));

    private final BooleanSetting showDps = add(new BooleanSetting("Show DPS", "Damage per second of the current fight", true));
    private final BooleanSetting showMax = add(new BooleanSetting("Show Max Hit", "Highest single hit of the fight", true));
    private final BooleanSetting showLast = add(new BooleanSetting("Show Last Hit", "Most recent hit", true));
    private final BooleanSetting showTotal = add(new BooleanSetting("Show Total", "Total damage of the fight", true));
    private final BooleanSetting showHits = add(new BooleanSetting("Show Hits", "Number of hits in the fight", false));
    private final BooleanSetting showTime = add(new BooleanSetting("Show Fight Time", "Length of the fight", false));
    private final BooleanSetting compact = add(new BooleanSetting("Compact Numbers", "12.3k instead of 12,345", true));
    private final BooleanSetting useServerColor = addUseServerColor();
    private final BooleanSetting debugLog = add(new BooleanSetting("Debug Log",
            "Write every new floating-text entity to latest.log (to identify damage numbers)", false));
    private final ActionSetting resetAction = add(new ActionSetting("Reset", "End the current fight", this::resetStats));

    private final DpsTracker tracker = new DpsTracker();

    public DpsMeterHud() {
        super("DPS Meter", "Damage per second from the server's floating damage numbers", 0.0, 0.62, false);
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    private long timeoutMs() {
        return Math.round(hideAfter.get() * 1000);
    }

    private void resetStats() {
        tracker.reset();
    }

    @Override
    protected void onEnable() {
        DamageNumberAdapter.reset();
        tracker.reset();
    }

    @Override
    public void onTick() {
        long now = now();
        for (DamageNumber n : DamageNumberAdapter.poll(range.get(), ignoreSelf.get(), plainNumbers.get(), debugLog.get())) {
            tracker.add(now, n.amount(), timeoutMs());
        }
    }

    @Override
    protected String title() {
        return "DPS Meter";
    }

    @Override
    protected List<Line> lines(boolean preview) {
        long now = now();
        if (!tracker.active(now, timeoutMs())) return preview ? placeholder() : List.of();

        List<Line> rows = new ArrayList<>();
        if (showDps.get()) rows.add(new Line("DPS", fmt(tracker.dps(now))));
        if (showMax.get()) rows.add(new Line("Max Hit", fmt(tracker.max())));
        if (showLast.get()) rows.add(new Line("Last Hit", fmt(tracker.last())));
        if (showTotal.get()) rows.add(new Line("Total", fmt(tracker.total())));
        if (showHits.get()) rows.add(new Line("Hits", Integer.toString(tracker.hits())));
        if (showTime.get()) rows.add(new Line("Time", time(tracker.duration(now))));
        if (rows.isEmpty()) rows.add(new Line("DPS", fmt(tracker.dps(now))));
        return rows;
    }

    private List<Line> placeholder() {
        List<Line> rows = new ArrayList<>();
        if (showDps.get()) rows.add(new Line("DPS", "33.1"));
        if (showMax.get()) rows.add(new Line("Max Hit", "15.9"));
        if (showLast.get()) rows.add(new Line("Last Hit", "9.9"));
        if (showTotal.get()) rows.add(new Line("Total", "51.8"));
        if (showHits.get()) rows.add(new Line("Hits", "4"));
        if (showTime.get()) rows.add(new Line("Time", "0:02"));
        if (rows.isEmpty()) rows.add(new Line("DPS", "33.1"));
        return rows;
    }

    private String fmt(double v) {
        if (compact.get()) {
            if (v >= 1e9) return String.format(Locale.ROOT, "%.2fB", v / 1e9);
            if (v >= 1e6) return String.format(Locale.ROOT, "%.2fM", v / 1e6);
            if (v >= 1e4) return String.format(Locale.ROOT, "%.1fk", v / 1e3);
        }
        if (v >= 1000 || v == Math.rint(v)) return String.format(Locale.ROOT, "%,.0f", v);
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String time(long ms) {
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
