package dev.telemetric.module.impl.hud;

import dev.telemetric.Telemetric;
import dev.telemetric.api.TelemetricServer;
import dev.telemetric.hud.HudColor;
import dev.telemetric.hud.HudModule;
import dev.telemetric.module.Category;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

/** "Telemetric vX" with the accent underline. It's the ClickGUI watermark, kept on screen in-game. */
public final class WatermarkHud extends HudModule {

    private static final int PAD = 2, UNDERLINE_GAP = 2;

    private final BooleanSetting showVersion = add(new BooleanSetting(
            "Show Version", "Append the mod version", true));
    private final BooleanSetting showOnline = add(new BooleanSetting(
            "Show Online", "Append how many players are online with Telemetric right now", true));
    private final BooleanSetting showConnection = add(new BooleanSetting(
            "Show Connection", "Show \"Connected\" / \"Disconnected\" (Telemetric server) in the bottom-right", true));
    private final ConnectionHud connectionHud = new ConnectionHud(this, showConnection);

    private final HudColor nameColor, versionColor, onlineColor, underlineColor, underlineFade;

    public WatermarkHud() {
        super("Watermark", "Shows the Telemetric name on screen, also outside the ClickGUI", Category.MISC, 1.0, 0.0, true);
        addFrameSettings(false);
        nameColor = paletteColor("Name", "\"Telemetric\"", Theme::accent);
        versionColor = paletteColor("Version", "The version after the name", Theme::textMuted);
        onlineColor = paletteColor("Online", "The online-user count", Theme::textMuted);
        underlineColor = paletteColor("Underline", "Start of the underline", Theme::accent);
        underlineFade = paletteColor("Underline Fade", "End of the underline (fades out)", Theme::accentAlt);
    }

    /** The Show Connection HUD element. Register it after this module. */
    public ConnectionHud connectionHud() {
        return connectionHud;
    }

    private String version() {
        return showVersion.get() ? " v" + Telemetric.version() : "";
    }

    /** " · 12 online", or empty while off / not connected (the preview shows a sample). */
    private String online(boolean preview) {
        if (!showOnline.get()) return "";
        int n = TelemetricServer.online();
        if (n < 0 && preview) n = 12;
        return n < 0 ? "" : " · " + n + " online";
    }

    @Override
    public int contentWidth(Render2D r, boolean preview) {
        return r.textWidth(Telemetric.NAME) + r.textWidth(version()) + r.textWidth(online(preview)) + PAD * 2;
    }

    @Override
    public int contentHeight(Render2D r, boolean preview) {
        return PAD + r.fontHeight() + UNDERLINE_GAP + 1 + PAD;
    }

    @Override
    public void draw(Render2D r, boolean preview, boolean alignRight) {
        String name = Telemetric.NAME;
        String version = version();
        String online = online(preview);
        int total = r.textWidth(name) + r.textWidth(version) + r.textWidth(online);
        r.text(name, PAD, PAD, nameColor.get());
        r.text(version, PAD + r.textWidth(name), PAD, versionColor.get());
        r.text(online, PAD + r.textWidth(name) + r.textWidth(version), PAD, onlineColor.get());
        r.gradientH(PAD, PAD + r.fontHeight() + UNDERLINE_GAP, total, 1, underlineColor.get(),
                ColorUtil.withAlpha(underlineFade.get(), 0));
    }
}
