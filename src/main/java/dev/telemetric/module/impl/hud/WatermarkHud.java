package dev.telemetric.module.impl.hud;

import dev.telemetric.Telemetric;
import dev.telemetric.hud.HudModule;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

/** "Telemetric vX" with the accent underline — the ClickGUI watermark, kept on screen in-game. */
public final class WatermarkHud extends HudModule {

    private static final int PAD = 2, UNDERLINE_GAP = 2;

    private final BooleanSetting showVersion = add(new BooleanSetting(
            "Show Version", "Append the mod version", true));

    public WatermarkHud() {
        super("Watermark", "Shows the Telemetric name on screen, also outside the ClickGUI", 1.0, 0.0, true);
    }

    private String version() {
        return showVersion.get() ? " v" + Telemetric.version() : "";
    }

    @Override
    public int contentWidth(Render2D r, boolean preview) {
        return r.textWidth(Telemetric.NAME) + r.textWidth(version()) + PAD * 2;
    }

    @Override
    public int contentHeight(Render2D r, boolean preview) {
        return PAD + r.fontHeight() + UNDERLINE_GAP + 1 + PAD;
    }

    @Override
    public void draw(Render2D r, boolean preview, boolean alignRight) {
        Theme p = Telemetric.get().theme().palette();
        String name = Telemetric.NAME;
        String version = version();
        int total = r.textWidth(name) + r.textWidth(version);
        r.text(name, PAD, PAD, p.accent());
        r.text(version, PAD + r.textWidth(name), PAD, p.textMuted());
        r.gradientH(PAD, PAD + r.fontHeight() + UNDERLINE_GAP, total, 1, p.accent(),
                ColorUtil.withAlpha(p.accentAlt(), 0));
    }
}
