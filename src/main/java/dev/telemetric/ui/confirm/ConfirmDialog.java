package dev.telemetric.ui.confirm;

import dev.telemetric.input.InputUtil;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.framework.MouseButton;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Icons;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.render.WarningColors;

import java.util.ArrayList;
import java.util.List;

/**
 * A modal warning box (see {@link Confirm}). It ignores the theme palette on purpose so a warning looks the same in
 * every theme: dark amber box, yellow border, warning triangle top-left. Esc or a click outside cancels. Buttons only
 * react once the box has faded in, so a double click on whatever opened it can't confirm by accident.
 */
public final class ConfirmDialog {

    // fixed on purpose, not from ThemeManager
    private static final int BACKDROP = 0xA0000000;
    private static final int BOX = WarningColors.BOX;
    private static final int YELLOW = WarningColors.YELLOW;
    private static final int TITLE = WarningColors.TITLE;
    private static final int TEXT = WarningColors.TEXT;
    private static final int DIVIDER = WarningColors.DIVIDER;
    private static final int CANCEL_BG = 0xFF3A3218;
    private static final int CANCEL_HOVER = 0xFF51461F;
    private static final int CONFIRM_HOVER = 0xFFFFDC7A;
    private static final int SHADOW = 0x90000000;

    private static final int WIDTH = 270, PAD = 10, ICON_W = 16, ICON_H = 14, LINE = 11;
    private static final int BTN_H = 18, BTN_PAD = 12, BTN_GAP = 6, SLIDE = 8;

    private final ConfirmRequest request;
    private final Animation open = new Animation(Durations.MODAL, Easing.OUT_CUBIC, 0);
    private final Animation hoverConfirm = new Animation(Durations.HOVER, Easing.OUT_CUBIC, 0);
    private final Animation hoverCancel = new Animation(Durations.HOVER, Easing.OUT_CUBIC, 0);
    private boolean closing;

    // last frame's layout (input uses it)
    private int bx, by, bh;
    private int[] confirmBtn = new int[4], cancelBtn = new int[4];

    ConfirmDialog(ConfirmRequest request) {
        this.request = request;
        open.animateTo(1);
    }

    public boolean isClosing() { return closing; }

    public boolean isFinished() { return closing && (!open.isRunning() || open.get() <= 0.001); }

    /** Draws the dim + box centred in a {@code sw × sh} area (call after {@code r.nextLayer()}). */
    public void render(Render2D r, int mx, int my, int sw, int sh) {
        double a = open.get();
        if (a <= 0.001) return;
        r.pushAlpha(a);
        r.rect(0, 0, sw, sh, BACKDROP);

        int textW = WIDTH - PAD * 2;
        List<String> lines = wrap(r, request.message(), textW);
        int headerH = Math.max(ICON_H, r.fontHeight());
        bh = PAD + headerH + 8 + lines.size() * LINE + 10 + BTN_H + PAD;
        bx = (sw - WIDTH) / 2;
        by = (sh - bh) / 2 + (int) Math.round((1 - a) * SLIDE);

        r.shadow(bx, by, WIDTH, bh, 5, SHADOW, 10);
        r.roundedRect(bx - 1, by - 1, WIDTH + 2, bh + 2, 5, YELLOW);
        r.roundedRect(bx, by, WIDTH, bh, 4, BOX);

        int hy = by + PAD;
        Icons.warning(r, bx + PAD, hy + (headerH - ICON_H) / 2, ICON_W, ICON_H, YELLOW);
        r.text(r.trim(request.title(), WIDTH - PAD * 3 - ICON_W), bx + PAD + ICON_W + 6,
                hy + (headerH - r.fontHeight()) / 2 + 1, TITLE);
        int dy = hy + headerH + 4;
        r.rect(bx + PAD, dy, textW, 1, DIVIDER);

        int ty = dy + 5;
        for (String line : lines) {
            r.text(line, bx + PAD, ty, TEXT);
            ty += LINE;
        }

        // buttons, right-aligned: [Cancel] [Confirm]
        int confirmW = r.textWidth(request.confirmLabel()) + BTN_PAD * 2;
        int cancelW = r.textWidth(request.cancelLabel()) + BTN_PAD * 2;
        int btnY = by + bh - PAD - BTN_H;
        int cx = bx + WIDTH - PAD - confirmW;
        int kx = cx - BTN_GAP - cancelW;
        confirmBtn = new int[]{cx, btnY, confirmW, BTN_H};
        cancelBtn = new int[]{kx, btnY, cancelW, BTN_H};

        boolean live = interactive();
        hoverConfirm.animateTo(live && inside(confirmBtn, mx, my) ? 1 : 0);
        hoverCancel.animateTo(live && inside(cancelBtn, mx, my) ? 1 : 0);

        r.roundedRect(kx, btnY, cancelW, BTN_H, 3, ColorUtil.lerp(CANCEL_BG, CANCEL_HOVER, hoverCancel.get()));
        r.outline(kx, btnY, cancelW, BTN_H, DIVIDER);
        r.textCentered(request.cancelLabel(), kx + cancelW / 2, btnY + (BTN_H - r.fontHeight()) / 2 + 1, TEXT);

        r.roundedRect(cx, btnY, confirmW, BTN_H, 3, ColorUtil.lerp(YELLOW, CONFIRM_HOVER, hoverConfirm.get()));
        r.textCentered(request.confirmLabel(), cx + confirmW / 2, btnY + (BTN_H - r.fontHeight()) / 2 + 1, 0xFFFFFFFF);

        r.popAlpha();
    }

    private static List<String> wrap(Render2D r, String text, int maxW) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n", -1)) {
            if (para.isBlank()) {
                out.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                String next = line.isEmpty() ? word : line + " " + word;
                if (r.textWidth(next) > maxW && !line.isEmpty()) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(next);
                }
            }
            out.add(line.toString());
        }
        return out;
    }

    private boolean interactive() {
        return !closing && open.get() > 0.9;
    }

    private static boolean inside(int[] b, double mx, double my) {
        return mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (!interactive() || button != MouseButton.LEFT) return true;
        if (inside(confirmBtn, mx, my)) confirm();
        else if (inside(cancelBtn, mx, my)) cancel();
        else if (mx < bx || mx >= bx + WIDTH || my < by || my >= by + bh) cancel();
        return true;
    }

    public boolean keyPressed(int key) {
        if (key == InputUtil.KEY_ESCAPE && !closing) cancel();
        return true;
    }

    public void confirm() {
        if (closing) return;
        close();
        if (request.onConfirm() != null) request.onConfirm().run();
    }

    public void cancel() {
        if (closing) return;
        close();
        if (request.onCancel() != null) request.onCancel().run();
    }

    private void close() {
        closing = true;
        open.animateTo(0);
    }
}
