package dev.telemetric.ui.clickgui.window;

import dev.telemetric.Telemetric;
import dev.telemetric.game.NametagAdapter;
import dev.telemetric.game.NametagAdapter.LineKind;
import dev.telemetric.game.NametagAdapter.PreviewLine;
import dev.telemetric.game.NametagAdapter.TitlePosition;
import dev.telemetric.module.impl.visual.NametagModule;
import dev.telemetric.ui.framework.GuiContext;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.List;

/**
 * Your Telos name tag above the preview player (Nametag module), with the Status Title and Player Currency lines put in
 * like in game ({@link NametagAdapter#previewLines}). Each extra line shows while its group toggle is on (even with the
 * module off) and can be dragged up or down to set its position: it goes to whichever slot (Top / Middle / Bottom) puts
 * it nearest the mouse, so it works for any shape of the server's tag.
 */
public final class NametagLayer implements PlayerPreview.Layer {

    /** Text displays draw one font pixel as 1/40 block. */
    private static final double BLOCKS_PER_PX = 0.025;
    private static final int LINE_H = 10;
    /**
     * Telos' tag (read from the live text display): it rides on your head (passenger point = hitbox top) with a
     * translation of (-0.04, 0.29, 0) blocks, no background, no shadow, centred lines. The text block's bottom sits
     * on that point and vanilla puts its left edge at {@code 1 - width / 2} font px.
     */
    private static final double HEAD_GAP = 0.29, SHIFT_X = -0.04;

    private List<PreviewLine> lines = List.of();
    private NametagAdapter.TitleLine title;
    private NametagAdapter.CurrencyLine cur;
    private double top, k; // last frame: GUI y of the tag's top and GUI px per font px
    private double left, right;
    /** The line being dragged (TITLE / CURRENCY), or null. */
    private LineKind held;
    private double heldY;

    private static NametagModule nametag() {
        return Telemetric.get().modules().get(NametagModule.class);
    }

    private static boolean draggable(LineKind kind) {
        return kind == LineKind.TITLE || kind == LineKind.CURRENCY;
    }

    @Override
    public double topRoom() {
        return HEAD_GAP + Math.max(2, lines.size()) * LINE_H * BLOCKS_PER_PX + 0.05;
    }

    @Override
    public String hint() {
        return "Drag the status / currency line up / down";
    }

    @Override
    public void render(Render2D r, GuiContext ctx, PlayerPreview.View v, int mouseX, int mouseY) {
        Theme p = ctx.palette();
        NametagModule nt = nametag();
        title = nt.previewTitle();
        cur = nt.previewCurrency();
        lines = linesWith(null, null);

        k = v.scale() * BLOCKS_PER_PX;
        double[] anchor = v.project(0, v.playerHeight() + HEAD_GAP, 0);
        int maxW = 0;
        for (PreviewLine l : lines) maxW = Math.max(maxW, r.nametagLineWidth(l));
        int boxW = maxW, boxH = lines.size() * LINE_H;
        left = anchor[0] + (SHIFT_X / BLOCKS_PER_PX + 1 - maxW / 2.0) * k;
        right = left + boxW * k;
        top = anchor[1] - boxH * k;

        boolean hidden = nt.hidesOwnTag();
        int hot = held != null ? -2 : lineAt(mouseX, mouseY);
        r.pushAlpha(hidden ? 0.35 : 1);
        r.pushTransform((float) left, (float) top, (float) k);
        try {
            for (int i = 0; i < lines.size(); i++) {
                PreviewLine l = lines.get(i);
                int lw = r.nametagLineWidth(l);
                boolean dragged = held != null && l.kind() == held;
                if (dragged || (hot == i && draggable(l.kind()))) {
                    r.rect(-1, i * LINE_H - 1, boxW + 2, LINE_H, ColorUtil.fade(p.accent(), dragged ? 0.3 : 0.16));
                }
                r.nametagLine(l, (maxW - lw) / 2, i * LINE_H);
            }
        } finally {
            r.popTransform();
            r.popAlpha();
        }
        if (hidden) r.textCentered("Hidden (Hide Own)", (int) Math.round(anchor[0]), (int) Math.round(top) - 10, p.textDisabled());
        if (held == null && hot >= 0 && draggable(lines.get(hot).kind())) {
            ctx.requestTooltip(this, "Drag up or down: Top, Middle (above the HP line) or Bottom");
        }
    }

    private static TitlePosition position(LineKind kind) {
        return kind == LineKind.TITLE ? nametag().titlePosition() : nametag().currencyPosition();
    }

    /** The tag's lines, with the {@code kind} line at {@code at} (both null: every line at its saved position). */
    private List<PreviewLine> linesWith(LineKind kind, TitlePosition at) {
        NametagModule nt = nametag();
        return NametagAdapter.previewLines(title, kind == LineKind.TITLE ? at : nt.titlePosition(), cur,
                kind == LineKind.CURRENCY ? at : nt.currencyPosition(), nt.hidesHp(), nt.hidesOwnName());
    }

    private static int indexOf(List<PreviewLine> in, LineKind kind) {
        for (int i = 0; i < in.size(); i++) if (in.get(i).kind() == kind) return i;
        return -1;
    }

    /** The index of the line under the mouse, or -1. */
    private int lineAt(double mx, double my) {
        if (k <= 0 || mx < left - 4 || mx >= right + 4) return -1;
        int i = (int) Math.floor((my - top) / (LINE_H * k));
        return i >= 0 && i < lines.size() ? i : -1;
    }

    @Override
    public boolean grab(PlayerPreview.View view, double mouseX, double mouseY) {
        int i = lineAt(mouseX, mouseY);
        if (i < 0 || !draggable(lines.get(i).kind())) return false;
        held = lines.get(i).kind();
        heldY = mouseY;
        return true;
    }

    @Override
    public void drag(PlayerPreview.View view, double dx, double dy) {
        heldY += dy;
        if (k <= 0 || held == null) return;
        // every slot gives the same number of lines, so the tag's top stays put: take the slot whose line is nearest
        TitlePosition now = position(held), to = now;
        double best = Double.MAX_VALUE;
        for (TitlePosition at : TitlePosition.values()) {
            int i = indexOf(linesWith(held, at), held);
            if (i < 0) continue;
            double d = Math.abs(top + (i + 0.5) * LINE_H * k - heldY);
            if (d < best - 0.5 || (Math.abs(d - best) <= 0.5 && at == now)) {
                best = d;
                to = at;
            }
        }
        if (to == now) return;
        if (held == LineKind.TITLE) nametag().setTitlePosition(to);
        else nametag().setCurrencyPosition(to);
    }

    @Override
    public void release(PlayerPreview.View view) {
        held = null;
    }
}
