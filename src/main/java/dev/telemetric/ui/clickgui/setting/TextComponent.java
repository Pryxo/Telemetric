package dev.telemetric.ui.clickgui.setting;

import dev.telemetric.input.InputUtil;
import dev.telemetric.setting.impl.TextSetting;
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
 * Single-line text field (label above, input below). Click to focus; Enter/Esc or clicking elsewhere
 * unfocuses. Backspace deletes, Ctrl+Backspace clears. Caret always at the end (simple & robust).
 */
public class TextComponent extends SettingComponent<TextSetting> {

    private static final int HEIGHT = 27, BOX_H = 12;
    private static final int CTRL_MASK = 0x0002;

    private final Animation focus = new Animation(Durations.HOVER, Easing.OUT_CUBIC);
    private long focusTime;

    public TextComponent(GuiContext ctx, TextSetting setting) {
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
        boolean focused = ctx.isFocused(this);
        focus.animateTo(focused ? 1 : 0);

        r.text(setting.getLabel(), left(), y + 3, textColor(p));

        int bx = left(), by = y + 13, bw = right() - left();
        r.roundedRect(bx, by, bw, BOX_H, 2, p.control());
        int lineW = (int) Math.round(bw * focus.get());
        if (lineW > 0) r.rect(bx + (bw - lineW) / 2, by + BOX_H - 1, lineW, 1, p.accent());

        String value = setting.get();
        int innerW = bw - 6;
        if (value.isEmpty() && !focused) {
            r.text("Click to type", bx + 3, by + 2, p.textDisabled());
        } else {
            // show the tail of the text if it overflows
            String shown = value;
            while (!shown.isEmpty() && r.textWidth(shown) > innerW - 2) shown = shown.substring(1);
            r.text(shown, bx + 3, by + 2, p.text());
            boolean caretOn = focused && ((System.currentTimeMillis() - focusTime) / 530) % 2 == 0;
            if (caretOn) r.rect(bx + 3 + r.textWidth(shown), by + 2, 1, 8, p.accent());
        }
    }

    @Override
    protected boolean onClick(double mouseX, double mouseY, int button) {
        if (button != MouseButton.LEFT) return false;
        ctx.focus(this);
        focusTime = System.currentTimeMillis();
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        if (!ctx.isFocused(this)) return false;
        focusTime = System.currentTimeMillis();
        String value = setting.get();
        if (key == InputUtil.KEY_BACKSPACE) {
            if ((modifiers & CTRL_MASK) != 0) setting.set("");
            else if (!value.isEmpty()) setting.set(value.substring(0, value.offsetByCodePoints(value.length(), -1)));
            return true;
        }
        if (key == InputUtil.KEY_ENTER || key == InputUtil.KEY_ESCAPE) {
            ctx.clearFocus();
            return true;
        }
        // swallow every other key so typing never triggers game / GUI shortcuts
        return true;
    }

    @Override
    public boolean charTyped(int codepoint, int modifiers) {
        if (!ctx.isFocused(this)) return false;
        if (!setting.accepts(codepoint)) return true;
        String value = setting.get();
        if (value.codePointCount(0, value.length()) >= setting.getMaxLength()) return true;
        setting.set(value + new String(Character.toChars(codepoint)));
        focusTime = System.currentTimeMillis();
        return true;
    }

    @Override
    public void onFocusLost() {
        focus.animateTo(0);
    }

    @Override
    public void onClose() {
        ctx.releaseFocus(this);
    }
}
