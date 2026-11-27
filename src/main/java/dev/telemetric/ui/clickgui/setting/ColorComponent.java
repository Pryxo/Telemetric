package dev.telemetric.ui.clickgui.setting;

import com.google.gson.JsonElement;
import dev.telemetric.input.InputUtil;
import dev.telemetric.setting.impl.ColorRamp;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.Gradient;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.clickgui.Layout;
import dev.telemetric.ui.framework.GuiContext;
import dev.telemetric.ui.framework.MouseButton;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.List;

/**
 * A colour swatch that expands (accordion) into a picker. Right-clicking the header resets it to the default.
 * <ul>
 *   <li><b>Solid | Gradient</b> switch at the top (hidden for {@link ColorSetting#solidOnly()} colours). Both values
 *   are kept, so switching back and forth loses nothing.</li>
 *   <li>Gradient mode: a gradient bar with stop knobs under it. Click a knob to select it, drag it to move it, click
 *   the bar to add a stop there, right-click a knob or drag it down off the bar to remove it (2–8 stops). Preset chips,
 *   <b>Flow</b> (Off / Slow / Mid / Fast, right-click goes back) and <b>Reverse</b> sit under the picker.</li>
 *   <li>HSV square, hue bar and optional alpha bar edit the solid colour, or the selected stop. HSV is kept locally
 *   so the hue isn't lost when saturation or value hit 0.</li>
 *   <li>Hex field for the edited colour: click to type, Ctrl+C / X / V, Ctrl+A, Enter or click elsewhere closes,
 *   Esc reverts. Pasting a gradient code (two or more colours) into it applies the whole gradient.</li>
 *   <li><b>Copy | Paste</b> for the whole value: a solid colour copies as {@code #RRGGBB}, a gradient as
 *   {@code #FF5F6D, #FFC371} (CSS-like, see {@link Gradient}). Paste takes either, also CSS {@code linear-gradient(…)}.</li>
 * </ul>
 */
public class ColorComponent extends SettingComponent<ColorSetting> {

    private static final int SV_H = 46, BAR_H = 6, GAP = 4, SWATCH_W = 14, SWATCH_H = 8, HEX_ROW = 12;
    private static final int ROW_H = 11, GRAD_H = 8, KNOB_W = 5, KNOB_H = 6, KNOB_RISE = 2, PRESET_H = 6, CELL_GAP = 2;
    /** How far below the knobs a dragged stop has to be pulled to remove it. */
    private static final int REMOVE_PULL = 10;
    private static final int CTRL_MASK = 0x0002;

    private enum Drag { NONE, SV, HUE, ALPHA, STOP }

    private static final int H_SOLID = 0, H_GRADIENT = 1, H_FLOW = 2, H_REVERSE = 3, H_COPY = 4, H_PASTE = 5;
    private static final String[] FLOW_LABELS = {"Off", "Slow", "Mid", "Fast"};

    private final Animation open = new Animation(Durations.ACCORDION, Easing.IN_OUT_CUBIC);
    private final Animation gradOpen;
    private final Animation modeKnob;
    private final Animation[] hov = new Animation[6];
    private boolean expanded;
    private Drag drag = Drag.NONE;

    /** Selected gradient stop (the one the HSV picker and hex field edit in Gradient mode). */
    private int sel;
    /** A stop dragged down off the bar: removed on release. */
    private boolean removing;

    private float hue, sat, val;
    private int alpha;
    private int lastSynced;
    private int lastSel = -1;
    private boolean lastMode;

    private final Animation focus = new Animation(Durations.HOVER, Easing.OUT_CUBIC);
    private String hexBuffer = "";
    private boolean selectAll;
    private JsonElement beforeEdit;
    private long focusTime;
    private int hexBoxW = 50;

    /** A short note ("Copied", "No color") shown on the Copy or Paste button. */
    private String note;
    private int noteSlot;
    private long noteTime;

    public ColorComponent(GuiContext ctx, ColorSetting setting) {
        super(ctx, setting);
        double g = setting.isGradient() ? 1 : 0;
        gradOpen = new Animation(Durations.ACCORDION, Easing.IN_OUT_CUBIC, g);
        modeKnob = new Animation(Durations.STANDARD, Easing.OUT_CUBIC, g);
        for (int i = 0; i < hov.length; i++) hov[i] = new Animation(Durations.HOVER, Easing.OUT_CUBIC);
        syncFromSetting();
    }

    // ---- the edited colour (solid colour, or the selected stop) ---------------------------------------------------

    private boolean gradientMode() {
        return setting.isGradient();
    }

    private int editColor() {
        if (!gradientMode()) return setting.solid();
        List<Gradient.Stop> stops = setting.gradient().stops();
        sel = Math.clamp(sel, 0, stops.size() - 1);
        return stops.get(sel).argb();
    }

    private void setEditColor(int argb) {
        if (gradientMode()) setting.setGradient(setting.gradient().withColor(sel, argb));
        else setting.setSolid(argb);
    }

    private void syncFromSetting() {
        int c = editColor();
        float[] hsv = ColorUtil.toHsv(c);
        if (hsv[1] > 0.001f && hsv[2] > 0.001f) hue = hsv[0];
        sat = hsv[1];
        val = hsv[2];
        alpha = ColorUtil.alpha(c);
        lastSynced = c;
        lastSel = sel;
        lastMode = gradientMode();
    }

    private void pushToSetting() {
        setEditColor(ColorUtil.hsv(hue, sat, val, setting.allowsAlpha() ? alpha : 255));
        lastSynced = editColor();
    }

    // ---- layout (shared by render and input) ----------------------------------------------------------------------

    /** Row tops of the open picker; gradient-only rows are scaled by the Gradient switch's animation. */
    private final class Rows {
        int mode = -1, grad, hex, sv, hue, alpha = -1, presets, tools, actions, end;
        int gradH, extraH;

        Rows(int top) {
            double g = gradOpen.get();
            int yy = top;
            if (setting.allowsGradient()) {
                mode = yy;
                yy += ROW_H + GAP;
            }
            grad = yy;
            gradH = (int) Math.round((GRAD_H + KNOB_H - KNOB_RISE + GAP) * g);
            yy += gradH;
            hex = yy;
            yy += HEX_ROW + GAP;
            sv = yy;
            yy += SV_H + GAP;
            hue = yy;
            yy += BAR_H + GAP;
            if (setting.allowsAlpha()) {
                alpha = yy;
                yy += BAR_H + GAP;
            }
            presets = yy;
            tools = presets + PRESET_H + GAP;
            extraH = (int) Math.round((PRESET_H + GAP + ROW_H + GAP) * g);
            yy += extraH;
            actions = yy;
            end = yy + ROW_H + GAP;
        }

        int knobY() {
            return grad + GRAD_H - KNOB_RISE;
        }
    }

    private int pickerHeight() {
        return new Rows(0).end;
    }

    @Override
    protected int contentHeight() {
        return Layout.SETTING_HEIGHT + (int) Math.round(pickerHeight() * open.get());
    }

    // ---- render ---------------------------------------------------------------------------------------------------

    @Override
    protected void renderContent(Render2D r, int mouseX, int mouseY, float delta, Theme p) {
        boolean colorDrag = drag == Drag.SV || drag == Drag.HUE || drag == Drag.ALPHA;
        if (!colorDrag && (editColor() != lastSynced || sel != lastSel || gradientMode() != lastMode)) syncFromSetting();
        open.animateTo(expanded ? 1 : 0);
        gradOpen.animateTo(gradientMode() ? 1 : 0);
        modeKnob.animateTo(gradientMode() ? 1 : 0);

        // header: name and swatch (a gradient swatch shows the gradient)
        int sx = right() - SWATCH_W, sy = y + (Layout.SETTING_HEIGHT - SWATCH_H) / 2;
        r.text(r.trim(setting.getLabel(), sx - 4 - left()), left(), labelY(), textColor(p));
        drawChecker(r, sx, sy, SWATCH_W, SWATCH_H);
        r.fillRamp(sx, sy, SWATCH_W, SWATCH_H, 3, Render2D.CORNERS_ALL, setting, 1);
        r.outline(sx, sy, SWATCH_W, SWATCH_H, ColorUtil.fade(p.border(), 0.8));

        double o = open.get();
        if (o <= 0.001) return;

        int px = left(), pw = right() - left();
        int top = y + Layout.SETTING_HEIGHT;
        Rows rows = new Rows(top);
        r.pushClip(x, top, width, getHeight() - Layout.SETTING_HEIGHT);
        r.pushAlpha(o);

        if (rows.mode >= 0) renderModeSwitch(r, rows.mode, px, pw, mouseX, mouseY, p);

        // drag handling (immediate mode)
        if (drag != Drag.NONE) {
            float fx = (float) Math.clamp((mouseX - px) / (double) pw, 0, 1);
            switch (drag) {
                case SV -> {
                    sat = fx;
                    val = 1f - (float) Math.clamp((mouseY - rows.sv) / (double) SV_H, 0, 1);
                    pushToSetting();
                }
                case HUE -> {
                    hue = Math.min(fx, 0.999f);
                    pushToSetting();
                }
                case ALPHA -> {
                    alpha = Math.round(fx * 255);
                    pushToSetting();
                }
                case STOP -> {
                    Gradient g = setting.gradient();
                    removing = g.size() > Gradient.MIN_STOPS && mouseY > rows.knobY() + KNOB_H + REMOVE_PULL;
                    if (!removing) setting.setGradient(g.withPos(sel, fx));
                }
                default -> { }
            }
        }

        if (rows.gradH > 0) {
            r.pushClip(x, rows.grad, width, rows.gradH);
            r.pushAlpha(gradOpen.get());
            renderGradientBar(r, rows, px, pw, p);
            r.popAlpha();
            r.popClip();
        }

        renderHexField(r, rows.hex, p);

        // saturation / value square: each column is a vertical gradient from (s, v=1) down to black
        for (int i = 0; i < pw; i++) {
            float s = i / (float) Math.max(1, pw - 1);
            r.gradientV(px + i, rows.sv, 1, SV_H, ColorUtil.hsv(hue, s, 1f, 255), 0xFF000000);
        }
        r.outline(px, rows.sv, pw, SV_H, ColorUtil.fade(p.border(), 0.6));
        int cx = px + Math.round(sat * (pw - 1)), cy = rows.sv + Math.round((1 - val) * (SV_H - 1));
        r.circle(cx + 0.5, cy + 0.5, 3, p.text());
        r.circle(cx + 0.5, cy + 0.5, 2, ColorUtil.hsv(hue, sat, val, 255));

        for (int i = 0; i < pw; i++) r.rect(px + i, rows.hue, 1, BAR_H, ColorUtil.hsv(i / (float) pw, 1f, 1f, 255));
        drawHandle(r, px + Math.round(hue * (pw - 1)), rows.hue, p);

        if (rows.alpha >= 0) {
            drawChecker(r, px, rows.alpha, pw, BAR_H);
            int opaque = ColorUtil.hsv(hue, sat, val, 255);
            r.gradientH(px, rows.alpha, pw, BAR_H, ColorUtil.withAlpha(opaque, 0), opaque);
            drawHandle(r, px + Math.round(alpha / 255f * (pw - 1)), rows.alpha, p);
        }

        if (rows.extraH > 0) {
            r.pushClip(x, rows.presets, width, rows.extraH);
            r.pushAlpha(gradOpen.get());
            renderPresets(r, rows.presets, px, pw, mouseX, mouseY, p);
            Gradient g = setting.gradient();
            int half = (pw - CELL_GAP) / 2;
            renderButton(r, H_FLOW, px, rows.tools, half, "Flow " + FLOW_LABELS[g.flow()], g.flow() > 0, mouseX, mouseY, p);
            renderButton(r, H_REVERSE, px + half + CELL_GAP, rows.tools, pw - half - CELL_GAP, "Reverse", false, mouseX, mouseY, p);
            r.popAlpha();
            r.popClip();
        }

        int half = (pw - CELL_GAP) / 2;
        renderButton(r, H_COPY, px, rows.actions, half, noteFor(H_COPY, "Copy"), false, mouseX, mouseY, p);
        renderButton(r, H_PASTE, px + half + CELL_GAP, rows.actions, pw - half - CELL_GAP, noteFor(H_PASTE, "Paste"), false, mouseX, mouseY, p);

        r.popAlpha();
        r.popClip();
    }

    private void renderModeSwitch(Render2D r, int top, int px, int pw, int mouseX, int mouseY, Theme p) {
        r.roundedRect(px, top, pw, ROW_H, 3, p.control());
        int half = pw / 2;
        int kx = px + (int) Math.round((pw - half) * modeKnob.get());
        r.roundedRect(kx, top, half, ROW_H, 3, p.surfaceActive());
        boolean inRow = mouseY >= top && mouseY < top + ROW_H && mouseX >= px && mouseX < px + pw;
        String[] labels = {"Solid", "Gradient"};
        for (int i = 0; i < 2; i++) {
            int cx0 = px + i * half, cw = i == 0 ? half : pw - half;
            boolean active = (i == 1) == gradientMode();
            boolean hovered = inRow && mouseX >= cx0 && mouseX < cx0 + cw;
            Animation h = hov[i == 0 ? H_SOLID : H_GRADIENT].animateTo(hovered ? 1 : 0);
            int col = active ? p.text() : ColorUtil.lerp(p.textMuted(), p.text(), h.get() * 0.6);
            String t = r.trim(labels[i], cw - 4);
            r.text(t, cx0 + (cw - r.textWidth(t)) / 2, top + Layout.textY(ROW_H) + 1, col);
        }
    }

    private void renderGradientBar(Render2D r, Rows rows, int px, int pw, Theme p) {
        Gradient g = setting.gradient();
        drawChecker(r, px, rows.grad, pw, GRAD_H);
        // the editor shows the still gradient so the knobs sit on their colours
        ColorRamp still = g::sample;
        r.fillRamp(px, rows.grad, pw, GRAD_H, 2, Render2D.CORNERS_ALL, still, 1);
        r.outline(px, rows.grad, pw, GRAD_H, ColorUtil.fade(p.border(), 0.6));
        List<Gradient.Stop> stops = g.stops();
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < stops.size(); i++) {
                boolean selected = i == sel;
                if ((pass == 1) != selected) continue; // selected knob on top
                int kx = knobX(stops.get(i).pos(), px, pw);
                double fade = selected && removing ? 0.35 : 1;
                r.roundedRect(kx - KNOB_W / 2, rows.knobY(), KNOB_W, KNOB_H, 2,
                        ColorUtil.fade(selected ? p.accent() : p.text(), fade));
                r.rect(kx - KNOB_W / 2 + 1, rows.knobY() + 1, KNOB_W - 2, KNOB_H - 2,
                        ColorUtil.fade(stops.get(i).argb() | 0xFF000000, fade));
            }
        }
    }

    private void renderPresets(Render2D r, int top, int px, int pw, int mouseX, int mouseY, Theme p) {
        List<Gradient.Preset> presets = Gradient.PRESETS;
        int n = presets.size();
        int cw = (pw - (n - 1) * CELL_GAP) / n;
        for (int i = 0; i < n; i++) {
            int cx0 = px + i * (cw + CELL_GAP);
            Gradient g = presets.get(i).gradient();
            r.fillRamp(cx0, top, cw, PRESET_H, 2, Render2D.CORNERS_ALL, g::sample, 1);
            boolean hovered = mouseX >= cx0 && mouseX < cx0 + cw && mouseY >= top && mouseY < top + PRESET_H;
            r.outline(cx0, top, cw, PRESET_H, hovered ? p.text() : ColorUtil.fade(p.border(), 0.6));
        }
    }

    private void renderButton(Render2D r, int slot, int bx, int by, int bw, String label, boolean on,
                              int mouseX, int mouseY, Theme p) {
        boolean hovered = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + ROW_H;
        Animation h = hov[slot].animateTo(hovered ? 1 : 0);
        r.roundedRect(bx, by, bw, ROW_H, 3, ColorUtil.lerp(p.control(), p.surfaceHover(), h.get()));
        String t = r.trim(label, bw - 4);
        int col = on ? p.accent() : ColorUtil.lerp(p.textMuted(), p.text(), h.get());
        r.text(t, bx + (bw - r.textWidth(t)) / 2, by + Layout.textY(ROW_H) + 1, col);
    }

    private String noteFor(int slot, String label) {
        if (note != null && noteSlot == slot && System.currentTimeMillis() - noteTime < Durations.CLIPBOARD_NOTE) return note;
        return label;
    }

    private void showNote(int slot, String text) {
        note = text;
        noteSlot = slot;
        noteTime = System.currentTimeMillis();
    }

    private static int knobX(float pos, int px, int pw) {
        return px + Math.round(pos * (pw - 1));
    }

    // ---- hex field ------------------------------------------------------------------------------------------------

    private int hexDigits() {
        return setting.allowsAlpha() ? 8 : 6;
    }

    private String currentHex() {
        return String.format(java.util.Locale.ROOT, setting.allowsAlpha() ? "%08X" : "%06X",
                setting.allowsAlpha() ? editColor() : editColor() & 0xFFFFFF);
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
        boolean caretOn = focused && !selectAll && ((System.currentTimeMillis() - focusTime) / Durations.CARET_BLINK) % 2 == 0;
        if (caretOn) r.rect(tx + r.textWidth(shown), ty, 1, 8, p.accent());
    }

    /** Applies the buffer if it's a complete colour (6 digits, or 8 with alpha). */
    private boolean applyBuffer() {
        int len = hexBuffer.length();
        if (len != 6 && !(len == 8 && setting.allowsAlpha())) return false;
        long v = Long.parseLong(hexBuffer, 16);
        if (len == 6) v |= ((long) (setting.allowsAlpha() ? alpha : 255)) << 24;
        setEditColor((int) v);
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
            beforeEdit = setting.toJson();
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
            if (beforeEdit != null) setting.fromJson(beforeEdit);
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
            String clip = InputUtil.getClipboard();
            if (setting.allowsGradient() && Gradient.parse(clip, 0) != null) {
                // a whole gradient pasted into the field: apply it
                setting.applyCode(clip);
                sel = 0;
                syncFromSetting();
                hexBuffer = currentHex();
                selectAll = true;
            } else {
                String pasted = cleanHex(clip);
                if (!pasted.isEmpty()) {
                    hexBuffer = selectAll ? pasted : cleanHex(hexBuffer + pasted);
                    selectAll = false;
                    applyBuffer();
                }
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

    // ---- drawing helpers ------------------------------------------------------------------------------------------

    private static void drawHandle(Render2D r, int hx, int barY, Theme p) {
        r.rect(hx - 1, barY - 1, 3, BAR_H + 2, p.text());
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

    // ---- input ----------------------------------------------------------------------------------------------------

    @Override
    protected boolean onClick(double mouseX, double mouseY, int button) {
        int top = y + Layout.SETTING_HEIGHT;
        if (mouseY < top) {
            if (button == MouseButton.LEFT) expanded = !expanded;
            else if (button == MouseButton.RIGHT) {
                setting.reset();
                sel = 0;
                syncFromSetting();
            } else return false;
            return true;
        }
        if (!expanded || open.get() < 0.9) return expanded;
        int px = left(), pw = right() - left();
        Rows rows = new Rows(top);
        boolean inX = mouseX >= px && mouseX < px + pw;
        boolean gradReady = gradientMode() && gradOpen.get() > 0.9;

        // Solid | Gradient
        if (rows.mode >= 0 && mouseY >= rows.mode && mouseY < rows.mode + ROW_H) {
            if (button == MouseButton.LEFT && inX) {
                boolean wantGradient = mouseX >= px + pw / 2;
                if (wantGradient != gradientMode()) {
                    setting.setGradientMode(wantGradient);
                    sel = 0;
                    syncFromSetting();
                }
            }
            return true;
        }

        // gradient bar and stop knobs
        if (gradReady && mouseY >= rows.grad - 1 && mouseY < rows.knobY() + KNOB_H + 1) {
            Gradient g = setting.gradient();
            int hit = knobAt(g, mouseX, px, pw);
            if (button == MouseButton.RIGHT) {
                if (hit >= 0 && g.size() > Gradient.MIN_STOPS) {
                    setting.setGradient(g.without(hit));
                    if (sel >= hit) sel = Math.max(0, sel - 1);
                    syncFromSetting();
                }
                return true;
            }
            if (button != MouseButton.LEFT || !inX) return true;
            if (hit < 0) {
                if (g.size() >= Gradient.MAX_STOPS) return true;
                float pos = (float) Math.clamp((mouseX - px) / (double) pw, 0, 1);
                setting.setGradient(g.withAdded(pos));
                hit = setting.gradient().size() - 1;
            }
            sel = hit;
            syncFromSetting();
            removing = false;
            drag = Drag.STOP;
            ctx.capture(this);
            return true;
        }

        if (mouseY >= rows.hex && mouseY < rows.hex + HEX_ROW) {
            if (button == MouseButton.LEFT && mouseX >= left() && mouseX < left() + hexBoxW) startEdit();
            return true;
        }
        if (button == MouseButton.RIGHT && gradReady && mouseY >= rows.tools && mouseY < rows.tools + ROW_H) {
            int half = (pw - CELL_GAP) / 2;
            Gradient g = setting.gradient();
            if (mouseX >= px && mouseX < px + half) setting.setGradient(g.withFlow((g.flow() + Gradient.FLOW_NAMES.length - 1) % Gradient.FLOW_NAMES.length));
            return true;
        }
        if (button != MouseButton.LEFT) return true;
        if (mouseY >= rows.sv && mouseY < rows.sv + SV_H) drag = Drag.SV;
        else if (mouseY >= rows.hue - 1 && mouseY < rows.hue + BAR_H + 1) drag = Drag.HUE;
        else if (rows.alpha >= 0 && mouseY >= rows.alpha - 1 && mouseY < rows.alpha + BAR_H + 1) drag = Drag.ALPHA;
        if (drag != Drag.NONE) {
            ctx.capture(this);
            return true;
        }

        int half = (pw - CELL_GAP) / 2;
        boolean leftCell = mouseX >= px && mouseX < px + half, rightCell = mouseX >= px + half + CELL_GAP && mouseX < px + pw;

        if (gradReady && mouseY >= rows.presets && mouseY < rows.presets + PRESET_H) {
            int n = Gradient.PRESETS.size(), cw = (pw - (n - 1) * CELL_GAP) / n;
            int i = (int) ((mouseX - px) / (cw + CELL_GAP));
            if (i >= 0 && i < n && mouseX - px - i * (cw + CELL_GAP) < cw) {
                setting.setGradient(Gradient.PRESETS.get(i).gradient().withFlow(setting.gradient().flow()));
                sel = 0;
                syncFromSetting();
            }
            return true;
        }
        if (gradReady && mouseY >= rows.tools && mouseY < rows.tools + ROW_H) {
            Gradient g = setting.gradient();
            if (leftCell) setting.setGradient(g.withFlow((g.flow() + 1) % Gradient.FLOW_NAMES.length));
            else if (rightCell) {
                setting.setGradient(g.reversed());
                syncFromSetting();
            }
            return true;
        }
        if (mouseY >= rows.actions && mouseY < rows.actions + ROW_H) {
            if (leftCell) {
                InputUtil.setClipboard(setting.toCode());
                showNote(H_COPY, "Copied");
            } else if (rightCell) {
                if (setting.applyCode(InputUtil.getClipboard())) {
                    sel = 0;
                    syncFromSetting();
                    showNote(H_PASTE, "Pasted");
                } else {
                    showNote(H_PASTE, "No color");
                }
            }
            return true;
        }
        return true;
    }

    /** The knob under {@code mouseX} (nearest within its half width + 1), or -1. */
    private int knobAt(Gradient g, double mouseX, int px, int pw) {
        int best = -1;
        double bestD = KNOB_W / 2.0 + 1.5;
        for (int i = 0; i < g.size(); i++) {
            double d = Math.abs(mouseX - (knobX(g.stops().get(i).pos(), px, pw) + 0.5));
            if (d < bestD || (d == bestD && i == sel)) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (drag != Drag.NONE && button == MouseButton.LEFT) {
            if (drag == Drag.STOP && removing) {
                Gradient g = setting.gradient();
                if (g.size() > Gradient.MIN_STOPS) setting.setGradient(g.without(sel));
                sel = Math.max(0, sel - 1);
                syncFromSetting();
            }
            removing = false;
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
            gradOpen.snapTo(gradientMode() ? 1 : 0);
            modeKnob.snapTo(gradientMode() ? 1 : 0);
        }
    }

    @Override
    public void onClose() {
        drag = Drag.NONE;
        removing = false;
        ctx.releaseCapture(this);
        ctx.releaseFocus(this);
        expanded = false;
    }
}
