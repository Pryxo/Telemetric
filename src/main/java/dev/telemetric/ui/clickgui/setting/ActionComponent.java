package dev.telemetric.ui.clickgui.setting;

import dev.telemetric.setting.impl.ActionSetting;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.clickgui.Layout;
import dev.telemetric.ui.framework.GuiContext;
import dev.telemetric.ui.framework.MouseButton;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

/** Button. Hover tint + instant press flash (≈75 ms) for immediate feedback. */
public class ActionComponent extends SettingComponent<ActionSetting> {

    private static final int HEIGHT = 16;

    private final Animation press = new Animation(Durations.STANDARD, Easing.OUT_CUBIC);

    public ActionComponent(GuiContext ctx, ActionSetting setting) {
        super(ctx, setting);
    }

    @Override
    protected int contentHeight() {
        return HEIGHT;
    }

    @Override
    protected int rowHeight() {
        return HEIGHT;
    }

    @Override
    protected void renderContent(Render2D r, int mouseX, int mouseY, float delta, Theme p) {
        int bx = left(), by = y + 2, bw = right() - left(), bh = HEIGHT - 4;
        double h = hover.get();
        double flash = press.get();
        int bg = ColorUtil.lerp(p.control(), ColorUtil.shade(p.accent(), -0.45), h * 0.8);
        bg = ColorUtil.lerp(bg, p.accent(), flash);
        r.roundedRect(bx, by, bw, bh, 2, bg);
        if (h > 0.01) r.outline(bx, by, bw, bh, ColorUtil.fade(p.accent(), h * 0.6));
        int text = ColorUtil.lerp(ColorUtil.lerp(p.textMuted(), p.text(), h), 0xFFFFFFFF, flash);
        r.textCentered(r.trim(setting.getLabel(), bw - 4), bx + bw / 2, by + Layout.textY(bh), text);
    }

    @Override
    protected boolean onClick(double mouseX, double mouseY, int button) {
        if (button != MouseButton.LEFT) return false;
        // instant feedback: snap to full flash, then fade out
        press.snapTo(1).animateTo(0);
        setting.run();
        return true;
    }
}
