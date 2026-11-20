package dev.telemetric.ui.clickgui;

import dev.telemetric.ui.framework.GuiContext;
import dev.telemetric.ui.notification.Toasts;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.List;
import java.util.function.Supplier;

/**
 * Draws the {@link Toasts} in the bottom right. They slide and fade in (drawer timing), reflow smoothly, and fade out.
 * The ClickGUI uses it, and so does the in-game HUD layer ({@code HudOverlay}) while the ClickGUI is closed.
 */
public final class ToastRenderer {

    private static final int HEIGHT = 18, GAP = 4, MARGIN = 8;
    private static final int SUCCESS = 0xFF4ADE80, ERROR = 0xFFF87171;

    private final Supplier<Theme> palette;

    public ToastRenderer(GuiContext ctx) {
        this(ctx::palette);
    }

    public ToastRenderer(Supplier<Theme> palette) {
        this.palette = palette;
    }

    public void render(Render2D r, int screenW, int screenH) {
        List<Toasts.Toast> toasts = Toasts.tick();
        Theme p = palette.get();
        int count = toasts.size();
        for (int i = 0; i < count; i++) {
            Toasts.Toast toast = toasts.get(i);
            int fromBottom = count - 1 - i; // newest at the bottom
            toast.slot.animateTo(fromBottom);
            double a = toast.presence.get();
            if (a <= 0.01) continue;

            int w = r.textWidth(toast.message) + 18;
            int tx = screenW - MARGIN - w + (int) Math.round((1 - a) * 24);
            int ty = screenH - MARGIN - HEIGHT - (int) Math.round(toast.slot.get() * (HEIGHT + GAP));
            int stripe = switch (toast.kind) {
                case SUCCESS -> SUCCESS;
                case ERROR -> ERROR;
                case INFO -> p.accent();
            };

            r.pushAlpha(a);
            r.shadow(tx, ty, w, HEIGHT, 3, p.shadow(), 4);
            r.roundedRect(tx, ty, w, HEIGHT, 3, ColorUtil.withAlpha(p.panel(), 245));
            r.roundedRect(tx + 4, ty + 5, 3, HEIGHT - 10, 1, stripe);
            r.text(toast.message, tx + 12, ty + 5, p.text());
            // the remaining-lifetime bar
            double life = 1 - Math.min(1, (System.currentTimeMillis() - toast.createdAt) / (double) Toasts.LIFETIME_MS);
            r.rect(tx + 3, ty + HEIGHT - 2, (int) Math.round((w - 6) * life), 1, ColorUtil.fade(stripe, 0.6));
            r.popAlpha();
        }
    }
}
