package dev.telemetric.module.impl.combat;

import dev.telemetric.Telemetric;
import dev.telemetric.hud.HudModule;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.Setting;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.render.Render2D;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * One movable place for every "… Ready!" popup (Weapon / Ability / Armor Cooldowns post here through
 * {@link ReadyCue}). Several alerts at once stack up instead of replacing each other, and each one fades out at the
 * end of its time. On by default, and hidden while there's nothing to show.
 */
public final class ReadyAlertsHud extends HudModule {

    private static final int MAX_ALERTS = 3, LINE_GAP = 2;
    private static final String PREVIEW = "Ability Ready!";

    private record Alert(String text, int color, long startMs, long endMs) {}

    private final ArrayDeque<Alert> alerts = new ArrayDeque<>();

    public ReadyAlertsHud() {
        super("Ready Alerts", "Where \"Weapon / Ability / Armor Ready!\" popups appear (move it in the HUD editor)",
                Category.COMBAT, 0.5, 0.32, true, 2.0);
        addFrameSettings(false);
    }

    static ReadyAlertsHud get() {
        return Telemetric.get().modules().get(ReadyAlertsHud.class);
    }

    /** Shows {@code text} for {@code durationMs}. Posting the same text again restarts it instead of stacking. */
    void post(String text, int color, long durationMs) {
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
        if (alerts.isEmpty() && preview) return List.of(new Alert(PREVIEW, ReadyCue.DEFAULT_COLOR, now, Long.MAX_VALUE));
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
                r.text(a.text(), alignRight ? w - tw : (w - tw) / 2, y, a.color());
            } finally {
                r.popAlpha();
            }
            y += r.fontHeight() + LINE_GAP;
        }
    }

    /** The cue colours (Alert Color of every cooldown HUD), so a gradient alert colour draws as a gradient. */
    @Override
    protected List<ColorSetting> extraColorSources() {
        List<ColorSetting> out = new ArrayList<>();
        for (Module m : Telemetric.get().modules().getModules()) {
            for (Setting<?> s : m.getSettings()) {
                if (s instanceof ColorSetting cs && cs.isGradient() && "Alert Color".equals(cs.getName())) out.add(cs);
            }
        }
        return out;
    }
}
