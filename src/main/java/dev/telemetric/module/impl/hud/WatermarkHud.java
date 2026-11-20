package dev.telemetric.module.impl.hud;

import dev.telemetric.Telemetric;
import dev.telemetric.hud.HudColor;
import dev.telemetric.hud.HudModule;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

/** "Telemetric vX" with the accent underline. It's the ClickGUI watermark, kept on screen in-game. */
public final class WatermarkHud extends HudModule {

    private static final int PAD = 2, UNDERLINE_GAP = 2;

    private final BooleanSetting showVersion = add(new BooleanSetting(
            "Show Version", "Append the mod version", true));

    private final HudColor nameColor, versionColor, underlineColor, underlineFade;

    public WatermarkHud() {
        super("Watermark", "Shows the Telemetric name on screen, also outside the ClickGUI", 1.0, 0.0, true);
        addFrameSettings(false);
        nameColor = paletteColor("Name", "\"Telemetric\"", Theme::accent);
        versionColor = paletteColor("Version", "The version after the name", Theme::textMuted);
        underlineColor = paletteColor("Underline", "Start of the underline", Theme::accent);
        underlineFade = paletteColor("Underline Fade", "End of the underline (fades out)", Theme::accentAlt);
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
        String name = Telemetric.NAME;
        String version = version();
        int total = r.textWidth(name) + r.textWidth(version);
        r.text(name, PAD, PAD, nameColor.get());
        r.text(version, PAD + r.textWidth(name), PAD, versionColor.get());
        r.gradientH(PAD, PAD + r.fontHeight() + UNDERLINE_GAP, total, 1, underlineColor.get(),
                ColorUtil.withAlpha(underlineFade.get(), 0));
    }
}
