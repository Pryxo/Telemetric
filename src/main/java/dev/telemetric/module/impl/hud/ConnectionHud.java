package dev.telemetric.module.impl.hud;

import dev.telemetric.api.TelemetricServer;
import dev.telemetric.hud.HudColor;
import dev.telemetric.hud.HudModule;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

/** "Connected" (green) / "Disconnected" (grey): state of the link to the Telemetric API server. Bottom-right. */
public final class ConnectionHud extends HudModule {

    private static final int PAD = 2;
    /** Status green: a fixed default (the palette has no success colour), editable in Colors. */
    private static final int GREEN = 0xFF55FF55;

    private final HudColor connectedColor, disconnectedColor;

    public ConnectionHud() {
        super("Connection", "Shows whether the mod is connected to the Telemetric server", 1.0, 1.0, true);
        addFrameSettings(false);
        connectedColor = hudColor("Connected", "Text while connected", () -> GREEN);
        disconnectedColor = paletteColor("Disconnected", "Text while disconnected", Theme::textMuted);
    }

    private static String label() {
        return TelemetricServer.connected() ? "Connected" : "Disconnected";
    }

    @Override
    public int contentWidth(Render2D r, boolean preview) {
        return r.textWidth(label()) + PAD * 2;
    }

    @Override
    public int contentHeight(Render2D r, boolean preview) {
        return r.fontHeight() + PAD * 2;
    }

    @Override
    public void draw(Render2D r, boolean preview, boolean alignRight) {
        boolean on = TelemetricServer.connected();
        r.text(label(), PAD, PAD, (on ? connectedColor : disconnectedColor).get());
    }
}
