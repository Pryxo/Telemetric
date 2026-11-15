package dev.telemetric.ui.clickgui.setting;

import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.clickgui.Layout;
import dev.telemetric.ui.framework.GuiContext;
import dev.telemetric.ui.framework.MouseButton;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

/**
 * Slider. Left-click/drag sets the value, right-click resets to default.
 * The fill eases towards the real value (micro-interaction timing) so jumps never look abrupt.
 */
public class NumberComponent extends SettingComponent<NumberSetting> {

    private static final int HEIGHT = 22, TRACK_H = 3;

    private final Animation fill;
    private final Animation knob = new Animation(Durations.MICRO, Easing.OUT_CUBIC);
    private boolean dragging;

    public NumberComponent(GuiContext ctx, NumberSetting setting) {
        super(ctx, setting);
        this.fill = new Animation(Durations.MICRO, Easing.OUT_CUBIC, setting.getNormalized());
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
        int trackX = left(), trackW = right() - left();
        if (dragging) setting.setNormalized((mouseX - trackX) / (double) Math.max(1, trackW));
        fill.animateTo(setting.getNormalized());

        String value = setting.format();
        r.text(r.trim(setting.getLabel(), trackW - r.textWidth(value) - 4), left(), y + 3, textColor(p));
        r.textRight(value, right(), y + 3, dragging ? p.accent() : p.textMuted());

        int trackY = y + HEIGHT - 6;
        r.pill(trackX, trackY, trackW, TRACK_H, p.control());
        int filled = (int) Math.round(trackW * fill.get());
        if (filled > 0) r.roundedRect(trackX, trackY, filled, TRACK_H, 1, p.accent());

        boolean hot = dragging || (isActive() && isHovered(mouseX, mouseY));
        knob.animateTo(hot ? 1 : 0);
        double radius = 2.5 + knob.get() * 1.0;
        r.circle(trackX + trackW * fill.get(), trackY + TRACK_H / 2.0, radius,
                ColorUtil.lerp(p.text(), 0xFFFFFFFF, knob.get()));
    }

    @Override
    protected boolean onClick(double mouseX, double mouseY, int button) {
        if (button == MouseButton.LEFT) {
            dragging = true;
            ctx.capture(this);
            return true;
        }
        if (button == MouseButton.RIGHT) {
            setting.reset();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging && button == MouseButton.LEFT) {
            dragging = false;
            ctx.releaseCapture(this);
            return true;
        }
        return false;
    }

    @Override
    public void onClose() {
        dragging = false;
        ctx.releaseCapture(this);
    }
}
