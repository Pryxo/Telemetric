package dev.telemetric.ui.clickgui;

import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.framework.GuiContext;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * Description tooltip: appears after a dwell of {@link Durations#TOOLTIP_DELAY} on the same element,
 * fades/slides in over {@link Durations#TOOLTIP}, and fades out when the pointer leaves.
 */
public final class TooltipRenderer {

    private static final int MAX_WIDTH = 150, PAD = 4;

    private final GuiContext ctx;
    private final Animation fade = new Animation(Durations.TOOLTIP, Easing.OUT_CUBIC);
    private Object owner;
    private String text;
    private long hoverStart;

    public TooltipRenderer(GuiContext ctx) {
        this.ctx = ctx;
    }

    public void render(Render2D r, int mouseX, int mouseY, int screenW, int screenH) {
        Object requested = ctx.tooltipOwner();
        boolean enabled = ctx.ui().tooltips.get() && !ctx.hasCapture();
        if (!enabled || requested == null) {
            fade.animateTo(0);
            owner = null;
        } else if (requested != owner) {
            owner = requested;
            text = ctx.tooltipText();
            hoverStart = System.currentTimeMillis();
            fade.snapTo(0);
        } else if (System.currentTimeMillis() - hoverStart >= Durations.TOOLTIP_DELAY) {
            fade.animateTo(1);
        }

        double a = fade.get();
        if (a <= 0.01 || text == null) return;

        List<String> lines = wrap(r, text, MAX_WIDTH);
        int w = 0;
        for (String line : lines) w = Math.max(w, r.textWidth(line));
        w += PAD * 2;
        int lineH = r.fontHeight() + 1;
        int h = lines.size() * lineH + PAD * 2 - 1;

        int tx = mouseX + 10, ty = mouseY + 4 + (int) Math.round((1 - a) * 4);
        if (tx + w > screenW - 2) tx = mouseX - w - 6;
        if (ty + h > screenH - 2) ty = screenH - h - 2;

        Theme p = ctx.palette();
        r.pushAlpha(a);
        r.shadow(tx, ty, w, h, 3, p.shadow(), 4);
        r.roundedRect(tx, ty, w, h, 3, ColorUtil.withAlpha(p.panel(), 245));
        r.rect(tx, ty + 2, 1, h - 4, p.accent());
        for (int i = 0; i < lines.size(); i++) r.text(lines.get(i), tx + PAD, ty + PAD + i * lineH, p.text());
        r.popAlpha();
    }

    private static List<String> wrap(Render2D r, String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (r.textWidth(candidate) > maxWidth && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }
}
