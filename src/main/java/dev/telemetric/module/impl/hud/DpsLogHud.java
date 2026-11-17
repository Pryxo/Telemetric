package dev.telemetric.module.impl.hud;

import dev.telemetric.Telemetric;
import dev.telemetric.game.DamageNumberAdapter.DamageNumber;
import dev.telemetric.hud.HudModule;
import dev.telemetric.hud.TextRun;
import dev.telemetric.module.impl.misc.HudSettingsModule;
import dev.telemetric.setting.impl.ActionSetting;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ModeSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * DPS Log: the last damage numbers as a list, newest at the top or bottom. Every line slides / fades in and the
 * others glide aside; bigger hits get louder (see {@link DamageTiers}): a colour per tier, a pop from 200, a flash
 * from 500, a shake from 700 and a shiny, pulsing line at 1k+. Lines leave (fade + collapse) after
 * {@code Line Lifetime} or when pushed past {@code Max Lines}. All motion is time-based ({@link Durations}).
 */
public final class DpsLogHud extends HudModule {

    private static final String TOP = "Top", BOTTOM = "Bottom";
    private static final int PAD_X = 4, PAD_Y = 3, LINE_GAP = 2, STRIPE = 2, STRIPE_GAP = 3, AGE_GAP = 6;
    /** Sideways distance a new line slides in from (GUI units). */
    private static final float SLIDE = 12f;

    private final ModeSetting newHits = add(new ModeSetting("New Hits",
            "Where a new hit appears: as the top line (older ones move down) or the bottom line (older ones move up)",
            TOP, TOP, BOTTOM));
    private final NumberSetting maxLines = add(new NumberSetting("Max Lines", "How many hits are listed at once",
            8, 3, 20, 1));
    private final NumberSetting lifetime = add(new NumberSetting("Line Lifetime",
            "Seconds a hit stays in the log before it fades out", 10, 1, 60, 1).withSuffix("s"));
    private final BooleanSetting tierColors = add(new BooleanSetting("Damage Colors",
            "Colour hits by size: 50, 100, 150, 200, 300, 400, 500, 700, shiny at 1k", true));
    private final BooleanSetting hitAnimations = add(new BooleanSetting("Hit Animations",
            "Big hits pop (200+), flash (500+), shake (700+) and pulse (1k+)", true));
    private final BooleanSetting showStripe = add(new BooleanSetting("Color Stripe",
            "Small bar in the hit's colour in front of each line", true));
    private final BooleanSetting showAge = add(new BooleanSetting("Show Age", "Seconds since the hit, next to it", false));
    private final BooleanSetting compact = add(new BooleanSetting("Compact Numbers", "12.3k instead of 12,345", true));
    private final ActionSetting clearAction = add(new ActionSetting("Clear", "Empty the log", this::clear));

    /** One logged hit. {@code leftAt} = -1 while it is listed, else when it started to leave. */
    private static final class Entry {
        final double amount;
        final int tier;
        final long born;
        long leftAt = -1;

        Entry(double amount, long born) {
            this.amount = amount;
            this.tier = DamageTiers.tier(amount);
            this.born = born;
        }

        boolean leaving() {
            return leftAt >= 0;
        }
    }

    /** Oldest first. */
    private final List<Entry> entries = new ArrayList<>();
    private final List<Entry> sample = new ArrayList<>();

    public DpsLogHud() {
        super("DPS Log", "The last hits the DPS Meter registered, coloured and animated by size", 0.0, 0.35, false);
        for (double a : new double[]{18, 64, 120, 230, 410, 512, 720, 1240}) sample.add(new Entry(a, Long.MIN_VALUE / 4));
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    private void clear() {
        entries.clear();
    }

    @Override
    protected void onEnable() {
        entries.clear();
    }

    @Override
    public void onTick() {
        long now = now();
        for (DamageNumber n : DamageFeed.thisTick()) {
            entries.add(new Entry(n.amount(), now));
        }
        update(now);
    }

    /** Starts the leave animation of expired / overflowing lines and drops lines that finished leaving. */
    private void update(long now) {
        long life = Math.round(lifetime.get() * 1000);
        int listed = 0;
        for (int i = entries.size() - 1; i >= 0; i--) { // newest first
            Entry e = entries.get(i);
            if (e.leaving()) continue;
            if (now - e.born > life || ++listed > maxLines.getInt()) e.leftAt = now;
        }
        entries.removeIf(e -> e.leaving() && progress(now, e.leftAt, Durations.STANDARD) >= 1);
    }

    // ------------------------------------------------------------------ animation helpers

    /** 0..1 progress of a {@code baseMs} transition that started at {@code start} (1 with animations off). */
    private static double progress(long now, long start, int baseMs) {
        int d = Durations.scaled(baseMs);
        if (d <= 0) return 1;
        return Math.clamp((now - start) / (double) d, 0, 1);
    }

    /** How much of a row this line takes up right now (grows in, collapses when leaving). */
    private static double presence(Entry e, long now) {
        double in = Easing.OUT_CUBIC.apply(progress(now, e.born, Durations.STANDARD));
        double out = e.leaving() ? Easing.IN_OUT_CUBIC.apply(progress(now, e.leftAt, Durations.STANDARD)) : 0;
        return in * (1 - out);
    }

    private List<Entry> shown(boolean preview) {
        if (!entries.isEmpty() || !preview) return entries;
        return sample.subList(Math.max(0, sample.size() - maxLines.getInt()), sample.size());
    }

    private String text(Entry e) {
        return DamageTiers.format(e.amount, compact.get());
    }

    private static String age(Entry e, long now) {
        return e.born < 0 ? "3s" : Math.max(0, (now - e.born) / 1000) + "s";
    }

    private static HudSettingsModule style() {
        return Telemetric.get().modules().get(HudSettingsModule.class);
    }

    // ------------------------------------------------------------------ HudModule

    @Override
    public boolean hasData() {
        return !entries.isEmpty();
    }

    private int rowHeight(Render2D r) {
        return r.fontHeight() + LINE_GAP;
    }

    private int lead() {
        return showStripe.get() ? STRIPE + STRIPE_GAP : 0;
    }

    private int ageWidth(Render2D r) {
        return showAge.get() ? r.textWidth("00s") + AGE_GAP : 0;
    }

    @Override
    public int contentWidth(Render2D r, boolean preview) {
        int w = r.textWidth("DPS Log");
        for (Entry e : shown(preview)) w = Math.max(w, lead() + r.textWidth(text(e)) + ageWidth(r));
        return w + PAD_X * 2;
    }

    @Override
    public int contentHeight(Render2D r, boolean preview) {
        long now = now();
        double rows = 0;
        for (Entry e : shown(preview)) rows += presence(e, now);
        return (int) Math.ceil(rowHeight(r) * (1 + rows)) - LINE_GAP + PAD_Y * 2;
    }

    @Override
    public void draw(Render2D r, boolean preview, boolean alignRight) {
        long now = now();
        if (!preview || !entries.isEmpty()) update(now);
        Theme p = Telemetric.get().theme().palette();
        HudSettingsModule style = style();
        List<Entry> list = shown(preview);
        int w = contentWidth(r, preview), h = contentHeight(r, preview);
        boolean centered = !alignRight && isCentered();
        int rowH = rowHeight(r), fh = r.fontHeight();

        if (style.background.get()) {
            r.roundedRect(0, 0, w, h, 3, ColorUtil.fade(p.panel(), style.backgroundOpacity.get() / 100.0));
            if (style.accentBar.get()) r.rect(alignRight ? w - 1 : 0, 2, 1, h - 4, p.accent());
        }
        String title = "DPS Log";
        int tw = r.textWidth(title);
        r.text(title, alignRight ? w - PAD_X - tw : centered ? (w - tw) / 2 : PAD_X, PAD_Y, p.accent());

        r.pushClip(0, 0, w, h);
        boolean top = newHits.is(TOP);
        double offset = 0; // rows above the current one, in row units
        for (int k = 0; k < list.size(); k++) {
            Entry e = list.get(top ? list.size() - 1 - k : k); // drawn top → bottom
            double pres = presence(e, now);
            float y = PAD_Y + rowH + (float) (offset * rowH);
            offset += pres;
            if (pres <= 0.001) continue;
            drawRow(r, p, e, now, y, w, rowH, fh, pres, alignRight, centered, preview);
        }
        r.popClip();
    }

    private void drawRow(Render2D r, Theme p, Entry e, long now, float y, int w, int rowH, int fh, double pres,
                         boolean alignRight, boolean centered, boolean preview) {
        boolean anim = hitAnimations.get();
        int tier = tierColors.get() ? e.tier : 0;
        int tierColor = DamageTiers.color(tier);
        int solid = tierColor == 0 ? p.accent() : tierColor;
        String value = text(e);
        int vw = r.textWidth(value), aw = ageWidth(r);

        double inP = Easing.OUT_CUBIC.apply(progress(now, e.born, Durations.STANDARD));
        float slide = (float) ((1 - inP) * SLIDE) * (alignRight ? 1 : -1);

        r.pushAlpha(pres);

        // flash (500+) and steady pulse (1k+) behind the row
        if (anim && e.tier >= DamageTiers.FLASH) {
            double flash = 1 - Easing.OUT_QUART.apply(progress(now, e.born, Durations.MAX));
            double glow = 0;
            if (e.tier >= DamageTiers.TOP) {
                int loop = Durations.loop(Durations.AMBIENT_LOOP);
                double phase = loop <= 0 ? 0.5 : (now % loop) / (double) loop;
                glow = 0.10 + 0.10 * (0.5 + 0.5 * Math.sin(phase * Math.PI * 2));
            }
            double a = Math.max(glow, 0.55 * flash);
            if (a > 0.01) {
                int c = ColorUtil.withAlpha(solid, (int) Math.round(255 * a));
                r.roundedRect(2, Math.round(y) - 1, w - 4, rowH, 2, c);
            }
        }

        // colour stripe
        int lead = lead();
        int rowW = lead + vw + aw;
        float x = alignRight ? w - PAD_X - rowW : centered ? (w - rowW) / 2f : PAD_X;
        x += slide;
        if (anim && e.tier >= DamageTiers.SHAKE) {
            double t = progress(now, e.born, Durations.COMPLEX);
            int period = Math.max(1, Durations.scaled(Durations.INSTANT));
            x += (float) ((1 - t) * 2.0 * Math.sin((now - e.born) * Math.PI * 2 / period));
        }
        if (lead > 0) {
            int stripeX = Math.round(alignRight ? x + rowW - STRIPE : x);
            r.rect(stripeX, Math.round(y), STRIPE, fh - 1, solid);
        }

        // value: tier colour / shiny, popping in for big hits
        float vx = alignRight ? x + aw : x + lead;
        float scale = 1f;
        if (anim && e.tier >= DamageTiers.POP) {
            double pop = 1 - Easing.OUT_CUBIC.apply(progress(now, e.born, Durations.COMPLEX));
            scale += (float) (pop * (e.tier >= DamageTiers.TOP ? 0.6 : e.tier >= DamageTiers.FLASH ? 0.45 : 0.3));
        }
        List<TextRun> runs = DamageTiers.runs(value, tier, p.text());
        r.pushTransform(vx + vw / 2f, y + fh / 2f, scale);
        r.runs(runs, -vw / 2, -fh / 2, p.text(), true);
        r.popTransform();

        if (aw > 0) {
            String age = age(e, now);
            int ax = Math.round(alignRight ? x + aw - AGE_GAP - r.textWidth(age) : x + lead + vw + AGE_GAP);
            r.text(age, ax, Math.round(y), p.textMuted());
        }
        r.popAlpha();
    }
}
