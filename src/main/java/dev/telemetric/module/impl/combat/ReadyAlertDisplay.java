package dev.telemetric.module.impl.combat;

import dev.telemetric.hud.HudModule;
import dev.telemetric.module.Category;
import dev.telemetric.setting.impl.ColorRamp;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.render.Render2D;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A movable stack of "… Ready!" popups: {@link ReadyAlertsHud} (the main one) and the separate
 * {@link ReadyAlertGroupHud} elements a Ready Alert tab can be moved to (tab → Position).
 * <p>
 * Several alerts at once stack up instead of replacing each other, and each one fades out at the end of its time.
 * Hidden while there's nothing to show.
 */
abstract class ReadyAlertDisplay extends HudModule {

    private static final int MAX_ALERTS = 3, LINE_GAP = 2;
    private static final String PREVIEW = "Ability Ready!";

    private record Alert(String text, ColorRamp color, long startMs, long endMs) {}

    private final ArrayDeque<Alert> alerts = new ArrayDeque<>();

    ReadyAlertDisplay(String name, String description, double defaultX, double defaultY) {
        super(name, description, Category.COMBAT, defaultX, defaultY, true, 2.0);
        addFrameSettings(false);
    }

    /** Sample text in the HUD editor. */
    protected String previewText() {
        return PREVIEW;
    }

    /** Shows {@code text} for {@code durationMs}. Posting the same text again restarts it instead of stacking. */
    void post(String text, ColorRamp color, long durationMs) {
        if (!isEnabled() || text == null || text.isBlank()) return;
        long now = System.currentTimeMillis();
        alerts.removeIf(a -> a.text().equals(text));
        alerts.addFirst(new Alert(text, color, now, now + Math.max(1, durationMs)));
        while (alerts.size() > MAX_ALERTS) alerts.removeLast();
    }

    @Override
    protected void onDisable() {
        alerts.clear();
    }

    @Override
    public boolean hasData() {
        return !visible(false).isEmpty();
    }

    /** The alerts still showing (expired ones are dropped). In preview, a sample when none is showing. */
    private List<Alert> visible(boolean preview) {
        long now = System.currentTimeMillis();
        alerts.removeIf(a -> now >= a.endMs());
        if (alerts.isEmpty() && preview)
            return List.of(new Alert(previewText(), ColorRamp.solid(ReadyAlertTab.DEFAULT_COLOR), now, Long.MAX_VALUE));
        return new ArrayList<>(alerts);
    }

    @Override
    public int contentWidth(Render2D r, boolean preview) {
        int w = 1;
        for (Alert a : visible(preview)) w = Math.max(w, r.textWidth(a.text()));
        return w;
    }

    @Override
    public int contentHeight(Render2D r, boolean preview) {
        int n = Math.max(1, visible(preview).size());
        return n * (r.fontHeight() + LINE_GAP) - LINE_GAP;
    }

    @Override
    public void draw(Render2D r, boolean preview, boolean alignRight) {
        List<Alert> list = visible(preview);
        int w = contentWidth(r, preview);
        long now = System.currentTimeMillis();
        int fadeIn = Durations.scaled(Durations.MICRO), fadeOut = Durations.scaled(Durations.STANDARD);
        int y = 0;
        for (Alert a : list) {
            double alpha = 1;
            if (fadeIn > 0) alpha = Math.min(alpha, (now - a.startMs()) / (double) fadeIn);
            if (fadeOut > 0 && a.endMs() != Long.MAX_VALUE) alpha = Math.min(alpha, (a.endMs() - now) / (double) fadeOut);
            r.pushAlpha(Math.clamp(alpha, 0, 1));
            try {
                int tw = r.textWidth(a.text());
                int tx = alignRight ? w - tw : (w - tw) / 2;
                r.text(a.text(), tx, y, a.color(), null, tx, tw); // a gradient runs across the alert's text
            } finally {
                r.popAlpha();
            }
            y += r.fontHeight() + LINE_GAP;
        }
    }
}
