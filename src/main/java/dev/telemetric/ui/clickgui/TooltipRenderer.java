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
    private String title;
    private static final float TITLE_SCALE = 1.15f;
    /** Overall tooltip text size (title + description). */
    private static final float TEXT_SCALE = 0.7f;
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
            title = ctx.tooltipTitle();
            hoverStart = System.currentTimeMillis();
            fade.snapTo(0);
        } else if (System.currentTimeMillis() - hoverStart >= Durations.TOOLTIP_DELAY) {
            fade.animateTo(1);
        }

        double a = fade.get();
        if (a <= 0.01 || text == null) return;

        // Content is laid out in unscaled text units, then drawn at TEXT_SCALE.
        List<String> lines = wrap(r, text, Math.round(MAX_WIDTH / TEXT_SCALE));
        int cw = 0;
        for (String line : lines) cw = Math.max(cw, r.textWidth(line));
        boolean hasTitle = title != null && !title.isBlank();
        int titleH = 0;
        if (hasTitle) {
            cw = Math.max(cw, Math.round(r.textWidth(title) * TITLE_SCALE));
            titleH = Math.round(r.fontHeight() * TITLE_SCALE) + 3;
        }
        int lineH = r.fontHeight() + 1;
        int ch = titleH + lines.size() * lineH - 1;
        int w = Math.round(cw * TEXT_SCALE) + PAD * 2;
        int h = Math.round(ch * TEXT_SCALE) + PAD * 2;

        int tx = mouseX + 10, ty = mouseY + 4 + (int) Math.round((1 - a) * 4);
        if (tx + w > screenW - 2) tx = mouseX - w - 6;
        if (ty + h > screenH - 2) ty = screenH - h - 2;

        Theme p = ctx.palette();
        r.pushAlpha(a);
        r.shadow(tx, ty, w, h, 3, p.shadow(), 4);
        r.roundedRect(tx, ty, w, h, 3, ColorUtil.withAlpha(p.panel(), 245));
        r.rect(tx, ty + 2, 1, h - 4, p.accent());
        r.pushTransform(tx + PAD, ty + PAD, TEXT_SCALE);
        if (hasTitle) {
            r.pushTransform(0, 0, TITLE_SCALE);
            r.text(title, 0, 0, p.accent());
            r.popTransform();
        }
        for (int i = 0; i < lines.size(); i++) r.text(lines.get(i), 0, titleH + i * lineH, p.text());
        r.popTransform();
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
