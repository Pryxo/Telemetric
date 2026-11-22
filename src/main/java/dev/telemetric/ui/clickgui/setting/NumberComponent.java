package dev.telemetric.ui.clickgui.setting;

import dev.telemetric.input.InputUtil;
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
 * A slider. Left-click or drag sets the value, right-click resets it to the default. The fill eases towards the real
 * value (micro-interaction timing) so jumps never look abrupt. Clicking the value number opens a text field where you
 * can type a number. Enter or clicking away applies it (clamped to the range and snapped to the step), and Esc
 * cancels.
 */
public class NumberComponent extends SettingComponent<NumberSetting> {

    private static final int HEIGHT = 24, TRACK_H = 2, CTRL_MASK = 0x0002;

    private final Animation fill;
    private final Animation knob = new Animation(Durations.MICRO, Easing.OUT_CUBIC);
    private boolean dragging;

    // the typed value field
    private String buffer = "";
    private boolean selectAll;
    private long focusTime;
    private int valueW;

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

        boolean editing = ctx.isFocused(this);
        String value = editing ? buffer : setting.format();
        valueW = Math.max(r.textWidth(value), 12);
        // the label line sits on the same baseline as every other setting row
        int ty = y + Layout.textY(Layout.SETTING_HEIGHT);
        r.text(r.trim(setting.getLabel(), trackW - valueW - 6), left(), ty, textColor(p));
        if (editing) {
            int vx = right() - valueW;
            r.rect(vx - 2, ty + 9, valueW + 2, 1, p.accent());
            if (selectAll && !buffer.isEmpty())
                r.rect(right() - r.textWidth(buffer) - 1, ty - 1, r.textWidth(buffer) + 2, 10, ColorUtil.fade(p.accent(), 0.45));
            r.textRight(buffer, right(), ty, p.text());
            boolean caretOn = !selectAll && ((System.currentTimeMillis() - focusTime) / 530) % 2 == 0;
            if (caretOn) r.rect(right() + 1, ty - 1, 1, 9, p.text());
        } else {
            r.textRight(value, right(), ty, dragging ? p.accent() : p.textMuted());
        }

        // 2 px track centred 3 px above the row's bottom, knob centred on whole pixels
        int trackY = y + HEIGHT - 7;
        r.pill(trackX, trackY, trackW, TRACK_H, p.control());
        int filled = (int) Math.round(trackW * fill.get());
        if (filled > 0) r.pill(trackX, trackY, filled, TRACK_H, p.accent());

        boolean hot = dragging || (isActive() && isHovered(mouseX, mouseY));
        knob.animateTo(hot ? 1 : 0);
        double radius = 3 + knob.get();
        r.circle(trackX + filled, trackY + TRACK_H / 2.0, radius, ColorUtil.lerp(p.text(), 0xFFFFFFFF, knob.get() * 0.5));
    }

    @Override
    protected boolean onClick(double mouseX, double mouseY, int button) {
        if (button == MouseButton.LEFT) {
            if (mouseY < y + Layout.SETTING_HEIGHT - 2 && mouseX >= right() - valueW - 4) {
                startEdit();
                return true;
            }
            if (ctx.isFocused(this)) ctx.clearFocus();
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

    // ------------------------------------------------------------------ typed value

    private String plainValue() {
        String f = setting.format();
        int end = f.length();
        while (end > 0 && !Character.isDigit(f.charAt(end - 1))) end--;
        return f.substring(0, end);
    }

    private void startEdit() {
        if (!ctx.isFocused(this)) {
            buffer = plainValue();
            ctx.focus(this);
        }
        selectAll = true;
        focusTime = System.currentTimeMillis();
    }

    private void commit() {
        String t = buffer.trim().replace(',', '.');
        try {
            if (!t.isEmpty() && !t.equals("-") && !t.equals(".")) setting.set(Double.parseDouble(t));
        } catch (NumberFormatException ignored) {
            // invalid text, so keep the old value
        }
    }

    private static String clean(String raw) {
        StringBuilder b = new StringBuilder();
        for (char c : (raw == null ? "" : raw.trim()).toCharArray())
            if (Character.isDigit(c) || c == '.' || c == ',' || (c == '-' && b.isEmpty())) b.append(c);
        return b.toString();
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        if (!ctx.isFocused(this)) return false;
        focusTime = System.currentTimeMillis();
        boolean ctrl = (modifiers & CTRL_MASK) != 0;
        if (key == InputUtil.KEY_ENTER) {
            ctx.clearFocus(); // this commits in onFocusLost
        } else if (key == InputUtil.KEY_ESCAPE) {
            buffer = plainValue();
            ctx.clearFocus();
        } else if (ctrl && key == InputUtil.KEY_A) {
            selectAll = true;
        } else if (ctrl && (key == InputUtil.KEY_C || key == InputUtil.KEY_X)) {
            InputUtil.setClipboard(buffer);
            if (key == InputUtil.KEY_X) {
                buffer = "";
                selectAll = false;
            }
        } else if (ctrl && key == InputUtil.KEY_V) {
            String pasted = clean(InputUtil.getClipboard());
            if (!pasted.isEmpty()) {
                buffer = selectAll ? pasted : clean(buffer + pasted);
                selectAll = false;
            }
        } else if (key == InputUtil.KEY_BACKSPACE) {
            if (selectAll || ctrl) buffer = "";
            else if (!buffer.isEmpty()) buffer = buffer.substring(0, buffer.length() - 1);
            selectAll = false;
        }
        // swallow every other key so typing never triggers game or GUI shortcuts
        return true;
    }

    @Override
    public boolean charTyped(int codepoint, int modifiers) {
        if (!ctx.isFocused(this)) return false;
        char c = (char) codepoint;
        boolean minus = c == '-', dot = c == '.' || c == ',';
        if (!Character.isDigit(c) && !minus && !dot) return true;
        if (selectAll) {
            buffer = "";
            selectAll = false;
        }
        if (minus && !buffer.isEmpty()) return true;
        if (dot && (buffer.contains(".") || buffer.contains(","))) return true;
        if (buffer.length() < 12) buffer += c;
        focusTime = System.currentTimeMillis();
        return true;
    }

    @Override
    public void onFocusLost() {
        commit();
        selectAll = false;
        buffer = plainValue();
    }

    @Override
    public void onClose() {
        dragging = false;
        ctx.releaseCapture(this);
        if (ctx.isFocused(this)) ctx.clearFocus();
    }
}
