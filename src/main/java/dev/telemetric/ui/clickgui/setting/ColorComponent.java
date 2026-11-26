package dev.telemetric.ui.clickgui.setting;

import dev.telemetric.input.InputUtil;
import dev.telemetric.setting.impl.ColorSetting;
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
 * A colour swatch that expands (accordion) into an HSV picker with a saturation/value square, a hue bar and an
 * optional alpha bar. Right-clicking the header resets it to the default. HSV is kept locally so the hue isn't lost
 * when saturation or value hit 0.
 * Under the name there's an editable hex field: click to type, Ctrl+C / Ctrl+X / Ctrl+V copy, cut and paste, Ctrl+A
 * selects all, Enter or clicking elsewhere closes it, and Esc reverts. A complete value (6 digits, or 8 with alpha)
 * applies live.
 */
public class ColorComponent extends SettingComponent<ColorSetting> {

    private static final int SV_H = 46, BAR_H = 6, GAP = 4, SWATCH_W = 14, SWATCH_H = 8, HEX_ROW = 12;
    private static final int CTRL_MASK = 0x0002;

    private enum Drag { NONE, SV, HUE, ALPHA }

    private final Animation open = new Animation(Durations.ACCORDION, Easing.IN_OUT_CUBIC);
    private boolean expanded;
    private Drag drag = Drag.NONE;

    private float hue, sat, val;
    private int alpha;
    private int lastSynced;

    private final Animation focus = new Animation(Durations.HOVER, Easing.OUT_CUBIC);
    private String hexBuffer = "";
    private boolean selectAll;
    private int beforeEdit;
    private long focusTime;
    private int hexBoxW = 50;

    public ColorComponent(GuiContext ctx, ColorSetting setting) {
        super(ctx, setting);
        syncFromSetting();
    }

    private void syncFromSetting() {
        int c = setting.get();
        float[] hsv = ColorUtil.toHsv(c);
        if (hsv[1] > 0.001f && hsv[2] > 0.001f) hue = hsv[0];
        sat = hsv[1];
        val = hsv[2];
        alpha = ColorUtil.alpha(c);
        lastSynced = c;
    }

    private void pushToSetting() {
        int c = ColorUtil.hsv(hue, sat, val, setting.allowsAlpha() ? alpha : 255);
        setting.set(c);
        lastSynced = setting.get();
    }

    private int pickerHeight() {
        return HEX_ROW + GAP + SV_H + GAP + BAR_H + (setting.allowsAlpha() ? GAP + BAR_H : 0) + GAP;
    }

    @Override
    protected int contentHeight() {
        return Layout.SETTING_HEIGHT + (int) Math.round(pickerHeight() * open.get());
    }

    @Override
    protected void renderContent(Render2D r, int mouseX, int mouseY, float delta, Theme p) {
        if (setting.get() != lastSynced && drag == Drag.NONE) syncFromSetting();
        open.animateTo(expanded ? 1 : 0);

        // header: name and swatch (the hex field sits under the name while it's open)
        int sx = right() - SWATCH_W, sy = y + (Layout.SETTING_HEIGHT - SWATCH_H) / 2;
        r.text(r.trim(setting.getLabel(), sx - 4 - left()), left(), labelY(), textColor(p));
        drawChecker(r, sx, sy, SWATCH_W, SWATCH_H);
        r.roundedRect(sx, sy, SWATCH_W, SWATCH_H, 3, setting.get());
        r.outline(sx, sy, SWATCH_W, SWATCH_H, ColorUtil.fade(p.border(), 0.8));

        double o = open.get();
        if (o <= 0.001) return;

        int px = left(), pw = right() - left();
        int top = y + Layout.SETTING_HEIGHT;
        r.pushClip(x, top, width, getHeight() - Layout.SETTING_HEIGHT);
        r.pushAlpha(o);

        renderHexField(r, top, p);

        // drag handling (immediate mode)
        int svY = top + HEX_ROW + GAP;
        int hueY = svY + SV_H + GAP;
        int alphaY = hueY + BAR_H + GAP;
        if (drag != Drag.NONE) {
            float fx = (float) Math.clamp((mouseX - px) / (double) pw, 0, 1);
            switch (drag) {
                case SV -> {
                    sat = fx;
                    val = 1f - (float) Math.clamp((mouseY - svY) / (double) SV_H, 0, 1);
                }
                case HUE -> hue = Math.min(fx, 0.999f);
                case ALPHA -> alpha = Math.round(fx * 255);
                default -> { }
            }
            pushToSetting();
        }

        // saturation / value square: each column is a vertical gradient from (s, v=1) down to black
        for (int i = 0; i < pw; i++) {
            float s = i / (float) Math.max(1, pw - 1);
            r.gradientV(px + i, svY, 1, SV_H, ColorUtil.hsv(hue, s, 1f, 255), 0xFF000000);
        }
        r.outline(px, svY, pw, SV_H, ColorUtil.fade(p.border(), 0.6));
        int cx = px + Math.round(sat * (pw - 1)), cy = svY + Math.round((1 - val) * (SV_H - 1));
        r.circle(cx + 0.5, cy + 0.5, 3, 0xFFFFFFFF);
        r.circle(cx + 0.5, cy + 0.5, 2, ColorUtil.hsv(hue, sat, val, 255));

        for (int i = 0; i < pw; i++) r.rect(px + i, hueY, 1, BAR_H, ColorUtil.hsv(i / (float) pw, 1f, 1f, 255));
        drawHandle(r, px + Math.round(hue * (pw - 1)), hueY, p);

        if (setting.allowsAlpha()) {
            drawChecker(r, px, alphaY, pw, BAR_H);
            int opaque = ColorUtil.hsv(hue, sat, val, 255);
            r.gradientH(px, alphaY, pw, BAR_H, ColorUtil.withAlpha(opaque, 0), opaque);
            drawHandle(r, px + Math.round(alpha / 255f * (pw - 1)), alphaY, p);
        }

        r.popAlpha();
        r.popClip();
    }

    private int hexDigits() {
        return setting.allowsAlpha() ? 8 : 6;
    }

    private String currentHex() {
        return setting.toHex().substring(setting.allowsAlpha() ? 1 : 3);
    }

    private void renderHexField(Render2D r, int top, Theme p) {
        boolean focused = ctx.isFocused(this);
        focus.animateTo(focused ? 1 : 0);
        int bx = left();
        hexBoxW = r.textWidth("#" + "D".repeat(hexDigits())) + 8;
        int bw = hexBoxW;
        r.roundedRect(bx, top, bw, HEX_ROW, 4, p.control());
        int lineW = (int) Math.round(bw * focus.get());
        if (lineW > 0) r.rect(bx + (bw - lineW) / 2, top + HEX_ROW - 1, lineW, 1, p.accent());
        String shown = "#" + (focused ? hexBuffer : currentHex());
        int tx = bx + 4, ty = top + 2;
        if (focused && selectAll && !hexBuffer.isEmpty())
            r.rect(tx + r.textWidth("#") - 1, ty - 1, r.textWidth(hexBuffer) + 2, 10, ColorUtil.fade(p.accent(), 0.45));
        r.text(shown, tx, ty, focused ? p.text() : p.textMuted());
        boolean caretOn = focused && !selectAll && ((System.currentTimeMillis() - focusTime) / 530) % 2 == 0;
        if (caretOn) r.rect(tx + r.textWidth(shown), ty, 1, 8, p.accent());
    }

    /** Applies the buffer if it's a complete colour (6 digits, or 8 with alpha). */
    private boolean applyBuffer() {
        int len = hexBuffer.length();
        if (len != 6 && !(len == 8 && setting.allowsAlpha())) return false;
        long v = Long.parseLong(hexBuffer, 16);
        if (len == 6) v |= ((long) (setting.allowsAlpha() ? alpha : 255)) << 24;
        setting.set((int) v);
        syncFromSetting();
        return true;
    }

    /** Only the hex digits ('#', "0x" and anything else dropped), at most as many as the field holds. */
    private String cleanHex(String raw) {
        String t = raw.trim();
        if (t.startsWith("0x") || t.startsWith("0X")) t = t.substring(2);
        StringBuilder b = new StringBuilder();
        for (char ch : t.toCharArray()) if (Character.digit(ch, 16) >= 0) b.append(Character.toUpperCase(ch));
        String hex = b.toString();
        // "#AARRGGBB" pasted into a colour without alpha keeps RRGGBB
        if (!setting.allowsAlpha() && hex.length() == 8) hex = hex.substring(2);
        return hex.length() > hexDigits() ? hex.substring(0, hexDigits()) : hex;
    }

    private void startEdit() {
        if (!ctx.isFocused(this)) {
            beforeEdit = setting.get();
            hexBuffer = currentHex();
            ctx.focus(this);
        }
        selectAll = true;
        focusTime = System.currentTimeMillis();
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        if (!ctx.isFocused(this)) return false;
        focusTime = System.currentTimeMillis();
        boolean ctrl = (modifiers & CTRL_MASK) != 0;
        if (key == InputUtil.KEY_ENTER) {
            ctx.clearFocus();
        } else if (key == InputUtil.KEY_ESCAPE) {
            setting.set(beforeEdit);
            syncFromSetting();
            hexBuffer = currentHex();
            ctx.clearFocus();
        } else if (ctrl && key == InputUtil.KEY_A) {
            selectAll = true;
        } else if (ctrl && (key == InputUtil.KEY_C || key == InputUtil.KEY_X)) {
            InputUtil.setClipboard("#" + (hexBuffer.isEmpty() ? currentHex() : hexBuffer));
            if (key == InputUtil.KEY_X) {
                hexBuffer = "";
                selectAll = false;
            }
        } else if (ctrl && key == InputUtil.KEY_V) {
            String pasted = cleanHex(InputUtil.getClipboard());
            if (!pasted.isEmpty()) {
                hexBuffer = selectAll ? pasted : cleanHex(hexBuffer + pasted);
                selectAll = false;
                applyBuffer();
            }
        } else if (key == InputUtil.KEY_BACKSPACE) {
            if (selectAll || ctrl) hexBuffer = "";
            else if (!hexBuffer.isEmpty()) hexBuffer = hexBuffer.substring(0, hexBuffer.length() - 1);
            selectAll = false;
        }
        // swallow every other key so typing never triggers game or GUI shortcuts
        return true;
    }

    @Override
    public boolean charTyped(int codepoint, int modifiers) {
        if (!ctx.isFocused(this)) return false;
        if (Character.digit(codepoint, 16) < 0) return true;
        if (selectAll) {
            hexBuffer = "";
            selectAll = false;
        }
        if (hexBuffer.length() < hexDigits()) {
            hexBuffer += Character.toUpperCase((char) codepoint);
            applyBuffer();
        }
        focusTime = System.currentTimeMillis();
        return true;
    }

    @Override
    public void onFocusLost() {
        focus.animateTo(0);
        selectAll = false;
        hexBuffer = currentHex();
    }

    private static void drawHandle(Render2D r, int hx, int barY, Theme p) {
        r.rect(hx - 1, barY - 1, 3, BAR_H + 2, 0xFFFFFFFF);
        r.rect(hx, barY, 1, BAR_H, p.panel());
    }

    private static void drawChecker(Render2D r, int x, int y, int w, int h) {
        r.rect(x, y, w, h, 0xFFBDBDBD);
        for (int cx = 0; cx < w; cx += 3) {
            for (int cy = 0; cy < h; cy += 3) {
                if (((cx / 3) + (cy / 3)) % 2 == 0) r.rect(x + cx, y + cy, Math.min(3, w - cx), Math.min(3, h - cy), 0xFF7A7A7A);
            }
        }
    }

    @Override
    protected boolean onClick(double mouseX, double mouseY, int button) {
        int top = y + Layout.SETTING_HEIGHT;
        if (mouseY < top) {
            if (button == MouseButton.LEFT) expanded = !expanded;
            else if (button == MouseButton.RIGHT) {
                setting.reset();
                syncFromSetting();
            } else return false;
            return true;
        }
        if (!expanded || button != MouseButton.LEFT || open.get() < 0.9) return expanded;
        if (mouseY < top + HEX_ROW) {
            if (mouseX >= left() && mouseX < left() + hexBoxW) startEdit();
            return true;
        }
        int svY = top + HEX_ROW + GAP, hueY = svY + SV_H + GAP, alphaY = hueY + BAR_H + GAP;
        if (mouseY >= svY && mouseY < svY + SV_H) drag = Drag.SV;
        else if (mouseY >= hueY - 1 && mouseY < hueY + BAR_H + 1) drag = Drag.HUE;
        else if (setting.allowsAlpha() && mouseY >= alphaY - 1 && mouseY < alphaY + BAR_H + 1) drag = Drag.ALPHA;
        if (drag != Drag.NONE) ctx.capture(this);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (drag != Drag.NONE && button == MouseButton.LEFT) {
            drag = Drag.NONE;
            ctx.releaseCapture(this);
            return true;
        }
        return false;
    }

    @Override
    public boolean isExpandable() {
        return true;
    }

    @Override
    public boolean isExpanded() {
        return expanded;
    }

    @Override
    public void setExpanded(boolean expanded, boolean instant) {
        this.expanded = expanded;
        if (instant) {
            double target = expanded ? 1 : 0;
            open.snapTo(target);
        }
    }

    @Override
    public void onClose() {
        drag = Drag.NONE;
        ctx.releaseCapture(this);
        ctx.releaseFocus(this);
        expanded = false;
    }
}
