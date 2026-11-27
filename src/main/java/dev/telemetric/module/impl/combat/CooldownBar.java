package dev.telemetric.module.impl.combat;

import dev.telemetric.Telemetric;
import dev.telemetric.setting.Setting;
import dev.telemetric.setting.SettingBundle;
import dev.telemetric.setting.impl.ColorRamp;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.GroupSetting;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.List;

/**
 * The cooldown bar every cooldown HUD draws, plus its "Bar Colors" dropdown. The outline and track come from the
 * theme. The fill shifts from <i>Cooldown Color</i> (just started) to <i>Almost Ready Color</i>, and turns into a
 * full <i>Ready Color</i> bar once the item can be used. A short ring flashes when a cooldown ends ({@link #drawFlash}).
 */
final class CooldownBar implements SettingBundle {

    private final GroupSetting colors = new GroupSetting("Bar Colors", "Colours of the cooldown bar");
    private final ColorSetting ready = colors.child(new ColorSetting("Ready Color", "Bar when the item can be used",
            0xFF02963D, false));
    private final ColorSetting cooling = colors.child(new ColorSetting("Cooldown Color",
            "Fill right after the cooldown starts", 0xFF8B0000, false));
    private final ColorSetting almost = colors.child(new ColorSetting("Almost Ready Color",
            "Fill just before the cooldown ends", 0xFFFA812A, false));

    @Override
    public List<Setting<?>> settings() {
        return List.of(colors, ready, cooling, almost);
    }

    /**
     * @param remaining 1 means the cooldown just started, 0 means ready (full bar in the ready colour)
     * @param vertical  fills from bottom to top; otherwise from left to right
     */
    void draw(Render2D r, int x, int y, int w, int h, float remaining, boolean vertical) {
        Theme p = Telemetric.get().theme().palette();
        r.rect(x, y, w, h, p.border());
        int ix = x + 1, iy = y + 1, iw = w - 2, ih = h - 2;
        if (iw <= 0 || ih <= 0) return;
        r.rect(ix, iy, iw, ih, ColorUtil.fade(p.control(), 0.85));
        if (remaining <= 0f) {
            if (vertical) r.fillRampV(ix, iy, iw, ih, ready);
            else r.fillRamp(ix, iy, iw, ih, 0, 0, ready, 1);
            return;
        }
        // gradients run along the whole bar (left to right / bottom to top); the fill reveals its part of it
        ColorRamp fill = ColorRamp.lerp(almost, cooling, remaining);
        float done = 1f - remaining;
        if (vertical) {
            int fh = Math.round(ih * done);
            // fillRampV runs top to bottom: the top of the fill is at 'done' along the bar, the bottom at 0
            if (fh > 0) r.fillRampV(ix, iy + ih - fh, iw, fh, fill.part(fh / (float) ih, 0f));
        } else {
            int fw = Math.round(iw * done);
            if (fw > 0) r.fillRamp(ix, iy, fw, ih, 0, 0, fill.part(0f, fw / (float) iw), 1);
        }
    }

    /** A ring with a "!" centred on (cx, cy) for a moment after a cooldown ends. Does nothing once it has faded. */
    void drawFlash(Render2D r, int cx, int cy, long readyAtMs) {
        if (readyAtMs <= 0) return;
        int length = Durations.scaled(Durations.STANDARD);
        long age = System.currentTimeMillis() - readyAtMs;
        if (length <= 0 || age >= length) return;
        r.pushAlpha(1.0 - age / (double) length);
        try {
            r.circle(cx, cy, 5, ready.get());
            int mark = Telemetric.get().theme().palette().panel();
            r.rect(cx - 1, cy - 3, 2, 4, mark);
            r.rect(cx - 1, cy + 2, 2, 1, mark);
        } finally {
            r.popAlpha();
        }
    }
}
