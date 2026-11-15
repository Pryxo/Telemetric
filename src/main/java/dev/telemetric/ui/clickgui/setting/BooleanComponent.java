package dev.telemetric.ui.clickgui.setting;

import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.clickgui.Layout;
import dev.telemetric.ui.framework.GuiContext;
import dev.telemetric.ui.framework.MouseButton;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

/** Label + animated switch. Click anywhere on the row toggles. */
public class BooleanComponent extends SettingComponent<BooleanSetting> {

    protected static final int TRACK_W = 16, TRACK_H = 8;

    private final Animation state;

    public BooleanComponent(GuiContext ctx, BooleanSetting setting) {
        super(ctx, setting);
        this.state = new Animation(Durations.MICRO, Easing.OUT_CUBIC, setting.get() ? 1 : 0);
    }

    @Override
    protected int contentHeight() {
        return Layout.SETTING_HEIGHT;
    }

    @Override
    protected void renderContent(Render2D r, int mouseX, int mouseY, float delta, Theme p) {
        state.animateTo(setting.get() ? 1 : 0);
        double t = state.get();

        r.text(r.trim(setting.getLabel(), width - TRACK_W - Layout.PAD * 3 - labelReserve()), left(), labelY(), textColor(p));

        int tx = right() - TRACK_W;
        int ty = y + (Layout.SETTING_HEIGHT - TRACK_H) / 2;
        r.pill(tx, ty, TRACK_W, TRACK_H, ColorUtil.lerp(p.control(), p.accent(), t));
        double knobR = TRACK_H / 2.0 - 1;
        double knobX = tx + 1 + knobR + (TRACK_W - 2 - knobR * 2) * t;
        r.circle(knobX, ty + TRACK_H / 2.0, knobR, ColorUtil.lerp(p.textMuted(), 0xFFFFFFFF, t));
    }

    /** Extra space kept free left of the switch (subclasses draw icons there). */
    protected int labelReserve() {
        return 0;
    }

    @Override
    protected boolean onClick(double mouseX, double mouseY, int button) {
        if (button != MouseButton.LEFT) return false;
        setting.toggle();
        return true;
    }
}
