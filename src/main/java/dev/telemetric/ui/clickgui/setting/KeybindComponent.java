package dev.telemetric.ui.clickgui.setting;

import dev.telemetric.input.InputUtil;
import dev.telemetric.setting.impl.KeybindSetting;
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
 * A key binder. Left-click starts listening (and takes keyboard focus), and the next key press or mouse button
 * (middle, side buttons, …) binds it. Left-clicking the chip again or pressing Esc cancels, Backspace/Delete unbinds,
 * and right-click unbinds directly.
 */
public class KeybindComponent extends SettingComponent<KeybindSetting> {

    private final Animation listen = new Animation(Durations.HOVER, Easing.OUT_CUBIC);
    private final Animation chipWidth;

    public KeybindComponent(GuiContext ctx, KeybindSetting setting) {
        super(ctx, setting);
        this.chipWidth = new Animation(Durations.MICRO, Easing.OUT_CUBIC, 24);
    }

    private boolean listening() {
        return ctx.isFocused(this);
    }

    @Override
    protected int contentHeight() {
        return Layout.SETTING_HEIGHT;
    }

    @Override
    protected void renderContent(Render2D r, int mouseX, int mouseY, float delta, Theme p) {
        boolean listening = listening();
        listen.animateTo(listening ? 1 : 0);

        String label = listening ? "..." : setting.keyName();
        chipWidth.animateTo(r.textWidth(label) + 8);
        int cw = (int) Math.round(chipWidth.get());
        int cx = right() - cw, cy = y + 2, ch = Layout.SETTING_HEIGHT - 4;

        r.text(r.trim(setting.getLabel(), width - cw - Layout.PAD * 3), left(), labelY(), textColor(p));

        double pulse = listening ? 0.55 + 0.45 * Math.sin(System.currentTimeMillis() / 160.0) : 0;
        int bg = ColorUtil.lerp(p.control(), ColorUtil.shade(p.accent(), -0.55), listen.get());
        r.roundedRect(cx, cy, cw, ch, 4, bg);
        if (listen.get() > 0.01) r.outline(cx, cy, cw, ch, ColorUtil.fade(p.accent(), listen.get() * (0.5 + pulse * 0.5)));
        int textColor = ColorUtil.lerp(setting.isBound() ? p.text() : p.textMuted(), p.accent(), listen.get());
        r.textCentered(label, cx + cw / 2, labelY(), textColor);
    }

    /** While listening, any mouse button except left and right binds, wherever the mouse is. */
    @Override
    public boolean focusedMouseButton(int button) {
        if (!listening() || button == MouseButton.LEFT || button == MouseButton.RIGHT) return false;
        setting.set(InputUtil.mouseCode(button));
        ctx.clearFocus();
        return true;
    }

    @Override
    protected boolean onClick(double mouseX, double mouseY, int button) {
        if (button == MouseButton.LEFT) {
            if (listening()) ctx.clearFocus();
            else ctx.focus(this);
            return true;
        }
        if (button == MouseButton.RIGHT) {
            setting.unbind();
            ctx.releaseFocus(this);
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        if (!listening()) return false;
        if (key == InputUtil.KEY_BACKSPACE || key == InputUtil.KEY_DELETE) setting.unbind();
        else if (key != InputUtil.KEY_ESCAPE) setting.set(key);
        ctx.clearFocus();
        return true;
    }
}
