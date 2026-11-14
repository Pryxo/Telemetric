package dev.telemetric.ui.clickgui.setting;

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
 * Colour swatch that expands (accordion) into an HSV picker:
 * saturation/value square, hue bar and optional alpha bar. Right-click header resets to default.
 * HSV is kept locally so hue isn't lost when saturation/value hit 0.
 */
public class ColorComponent extends SettingComponent<ColorSetting> {

    private static final int SV_H = 46, BAR_H = 6, GAP = 4, SWATCH_W = 14, SWATCH_H = 8;

    private enum Drag { NONE, SV, HUE, ALPHA }

    private final Animation open = new Animation(Durations.ACCORDION, Easing.IN_OUT_CUBIC);
    private boolean expanded;
    private Drag drag = Drag.NONE;

    private float hue, sat, val;
    private int alpha;
    private int lastSynced;

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
        return GAP + SV_H + GAP + BAR_H + (setting.allowsAlpha() ? GAP + BAR_H : 0) + GAP;
    }

    @Override
    protected int contentHeight() {
        return Layout.SETTING_HEIGHT + (int) Math.round(pickerHeight() * open.get());
    }

    @Override
    protected void renderContent(Render2D r, int mouseX, int mouseY, float delta, Theme p) {
        if (setting.get() != lastSynced && drag == Drag.NONE) syncFromSetting();
        open.animateTo(expanded ? 1 : 0);

        // header: name, hex (when open), swatch
        int sx = right() - SWATCH_W, sy = y + (Layout.SETTING_HEIGHT - SWATCH_H) / 2;
        r.text(r.trim(setting.getName(), width - SWATCH_W - Layout.PAD * 3), left(), labelY(), textColor(p));
        if (open.get() > 0.01) {
            r.pushAlpha(open.get());
            String hex = setting.toHex().substring(setting.allowsAlpha() ? 1 : 3);
            r.textRight(hex, sx - 4, labelY(), p.textMuted());
            r.popAlpha();
        }
        drawChecker(r, sx, sy, SWATCH_W, SWATCH_H);
        r.roundedRect(sx, sy, SWATCH_W, SWATCH_H, 2, setting.get());
        r.outline(sx, sy, SWATCH_W, SWATCH_H, ColorUtil.fade(p.border(), 0.8));

        double o = open.get();
        if (o <= 0.001) return;

        int px = left(), pw = right() - left();
        int top = y + Layout.SETTING_HEIGHT;
        r.pushClip(x, top, width, getHeight() - Layout.SETTING_HEIGHT);
        r.pushAlpha(o);

        // drag handling (immediate mode)
        int svY = top + GAP;
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

        // saturation / value square: each column is a vertical gradient from (s,v=1) to black
        for (int i = 0; i < pw; i++) {
            float s = i / (float) Math.max(1, pw - 1);
            r.gradientV(px + i, svY, 1, SV_H, ColorUtil.hsv(hue, s, 1f, 255), 0xFF000000);
        }
        r.outline(px, svY, pw, SV_H, ColorUtil.fade(p.border(), 0.6));
        int cx = px + Math.round(sat * (pw - 1)), cy = svY + Math.round((1 - val) * (SV_H - 1));
        r.circle(cx + 0.5, cy + 0.5, 3, 0xFFFFFFFF);
        r.circle(cx + 0.5, cy + 0.5, 2, ColorUtil.hsv(hue, sat, val, 255));

        // hue bar
        for (int i = 0; i < pw; i++) r.rect(px + i, hueY, 1, BAR_H, ColorUtil.hsv(i / (float) pw, 1f, 1f, 255));
        drawHandle(r, px + Math.round(hue * (pw - 1)), hueY, p);

        // alpha bar
        if (setting.allowsAlpha()) {
            drawChecker(r, px, alphaY, pw, BAR_H);
            int opaque = ColorUtil.hsv(hue, sat, val, 255);
            r.gradientH(px, alphaY, pw, BAR_H, ColorUtil.withAlpha(opaque, 0), opaque);
            drawHandle(r, px + Math.round(alpha / 255f * (pw - 1)), alphaY, p);
        }

        r.popAlpha();
        r.popClip();
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
        int svY = top + GAP, hueY = svY + SV_H + GAP, alphaY = hueY + BAR_H + GAP;
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
        expanded = false;
    }
}
