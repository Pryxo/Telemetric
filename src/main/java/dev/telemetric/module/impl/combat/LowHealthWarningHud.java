package dev.telemetric.module.impl.combat;

import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.hud.HudModule;
import dev.telemetric.module.Category;
import dev.telemetric.module.common.SoundCue;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ColorRamp;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.GroupToggleSetting;
import dev.telemetric.setting.impl.ModeSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.setting.impl.TextSetting;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;

/**
 * Big warning text (and a sound) while your health is below a threshold. There's a "Low" and a "Mid" level, and the
 * lower one wins. In Continuous mode it shows (and the sound repeats) for as long as you stay low; in Once mode it shows
 * for a moment each time you drop below. It stays quiet for a moment after every world change, because health can
 * jump around while the world loads.
 */
public final class LowHealthWarningHud extends HudModule {

    private static final long GRACE_MS = 2000;

    /** One warning level: toggle and threshold, text, colour and sound. */
    private final class Level {
        final GroupToggleSetting on;
        final NumberSetting threshold;
        final TextSetting text;
        final ColorSetting color;
        final SoundCue sound;
        long since = -1, lastSound;

        Level(String name, double defaultPct, String defaultText, int defaultColor, String defaultSound) {
            on = add(new GroupToggleSetting(name, "Warn below the " + name.toLowerCase() + " threshold", true));
            threshold = add(on.child(new NumberSetting(name + " Threshold", "Warn at or below this much health",
                    defaultPct, 1, 100, 1).withSuffix("%")));
            text = add(on.child(new TextSetting(name + " Text", "Warning text", defaultText, 32)));
            color = add(on.child(new ColorSetting(name + " Color", "Warning colour", defaultColor)));
            sound = addBundle(new SoundCue(name + " Sound", "while the warning shows", defaultSound));
        }

        boolean active(double pct) {
            return on.get() && pct <= threshold.get();
        }
    }

    private final ModeSetting mode = add(new ModeSetting("Mode",
            "Continuous: shown while low (sound repeats). Once: shown for a moment each time you drop below",
            "Continuous", "Continuous", "Once"));
    private final NumberSetting onceTime = add(new NumberSetting("Show Time", "How long Once mode shows the warning",
            1.5, 0.5, 5, 0.5).withSuffix("s").visibleWhen(() -> mode.get().equals("Once")));
    private final NumberSetting repeat = add(new NumberSetting("Sound Repeat", "Seconds between sounds in Continuous mode",
            1, 0.25, 5, 0.25).withSuffix("s").visibleWhen(() -> mode.get().equals("Continuous")));
    private final BooleanSetting flashing = add(new BooleanSetting("Flashing", "Flash the warning text", true));
    private final Level lowLevel = new Level("Low", 35, "LOW HEALTH!", 0xFFFF3B3B, "block.note_block.bass");
    private final Level midLevel = new Level("Mid", 50, "Medium Health", 0xFFFFAA00, "block.note_block.pling");

    private Object world;
    private long graceUntil;
    private Level current;

    public LowHealthWarningHud() {
        super("Low Health Warning", "Warning text and sound while your health is low", Category.COMBAT,
                0.5, 0.40, false, 2.0);
        addFrameSettings(false);
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        Object w = PlayerAdapter.worldToken();
        if (w != world) {
            world = w;
            graceUntil = now + GRACE_MS;
            lowLevel.since = midLevel.since = -1;
        }
        PlayerAdapter.Health h = PlayerAdapter.health();
        Level level = null;
        if (h != null && h.max() > 0 && now >= graceUntil) {
            double pct = h.fraction() * 100;
            level = lowLevel.active(pct) ? lowLevel : midLevel.active(pct) ? midLevel : null;
        }
        for (Level l : new Level[]{lowLevel, midLevel}) {
            if (l != level) l.since = -1;
            else if (l.since < 0) {
                l.since = now;
                l.lastSound = now;
                l.sound.play();
            } else if (mode.get().equals("Continuous") && now - l.lastSound >= repeat.get() * 1000) {
                l.lastSound = now;
                l.sound.play();
            }
        }
        current = level;
    }

    @Override
    protected void onDisable() {
        current = null;
        lowLevel.since = midLevel.since = -1;
    }

    /** The level whose text is showing now (in Once mode only for its show time), or null. */
    private Level showing(boolean preview) {
        Level l = current;
        if (l != null && mode.get().equals("Once") && System.currentTimeMillis() - l.since > onceTime.get() * 1000) l = null;
        return l == null && preview ? lowLevel : l;
    }

    @Override
    public boolean hasData() {
        return showing(false) != null;
    }

    @Override
    public int contentWidth(Render2D r, boolean preview) {
        Level l = showing(preview);
        return l == null ? 1 : Math.max(1, r.textWidth(l.text.get()));
    }

    @Override
    public int contentHeight(Render2D r, boolean preview) {
        return r.fontHeight();
    }

    @Override
    public void draw(Render2D r, boolean preview, boolean alignRight) {
        Level l = showing(preview);
        if (l == null) return;
        ColorRamp c = l.color;
        int half = Durations.loop(Durations.ALERT_FLASH);
        if (flashing.get() && half > 0 && (System.currentTimeMillis() / half) % 2 == 0) c = c.map(v -> ColorUtil.shade(v, 0.35));
        String t = l.text.get();
        r.text(t, 0, 0, c, null, 0, r.textWidth(t));
    }
}
