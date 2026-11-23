package dev.telemetric.ui.clickgui.window;

import dev.telemetric.Telemetric;
import dev.telemetric.game.NametagAdapter;
import dev.telemetric.game.NametagAdapter.LineKind;
import dev.telemetric.game.NametagAdapter.PreviewLine;
import dev.telemetric.game.NametagAdapter.TitlePosition;
import dev.telemetric.module.impl.visual.PlayerCurrencyModule;
import dev.telemetric.module.impl.visual.StatusTitleModule;
import dev.telemetric.ui.framework.GuiContext;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.List;

/**
 * Your Telos name tag above the preview player, with the Status Title and Player Currency lines put in like in game
 * ({@link NametagAdapter#previewLines}). The focused module's line shows even while that module is off, and can be
 * dragged up or down to set its position: it goes to whichever slot (Top / Middle / Bottom) puts it nearest the mouse,
 * so it works for any shape of the server's tag. The other module's line shows only while it's on.
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

    private final LineKind focus;
    private List<PreviewLine> lines = List.of();
    private NametagAdapter.TitleLine title;
    private NametagAdapter.CurrencyLine cur;
    private double top, k; // last frame: GUI y of the tag's top and GUI px per font px
    private double left, right;
    private boolean held;
    private double heldY;

    public NametagLayer(LineKind focus) {
        this.focus = focus;
    }

    private static StatusTitleModule titles() {
        return Telemetric.get().modules().get(StatusTitleModule.class);
    }

    private static PlayerCurrencyModule currency() {
        return Telemetric.get().modules().get(PlayerCurrencyModule.class);
    }

    @Override
    public double topRoom() {
        return HEAD_GAP + Math.max(2, lines.size()) * LINE_H * BLOCKS_PER_PX + 0.05;
    }

    @Override
    public String hint() {
        return focus == LineKind.TITLE ? "Drag the title up / down" : "Drag the line up / down";
    }

    @Override
    public void render(Render2D r, GuiContext ctx, PlayerPreview.View v, int mouseX, int mouseY) {
        Theme p = ctx.palette();
        StatusTitleModule st = titles();
        PlayerCurrencyModule pc = currency();
        title = focus == LineKind.TITLE || st.isEnabled() ? st.previewTitle() : null;
        cur = focus == LineKind.CURRENCY || pc.isEnabled() ? pc.previewCurrency() : null;
        lines = linesWith(position());

        k = v.scale() * BLOCKS_PER_PX;
        double[] anchor = v.project(0, v.playerHeight() + HEAD_GAP, 0);
        int maxW = 0;
        for (PreviewLine l : lines) maxW = Math.max(maxW, r.nametagLineWidth(l));
        int boxW = maxW, boxH = lines.size() * LINE_H;
        left = anchor[0] + (SHIFT_X / BLOCKS_PER_PX + 1 - maxW / 2.0) * k;
        right = left + boxW * k;
        top = anchor[1] - boxH * k;

        boolean hidden = st.hidesOwnTag();
        int hot = held ? -2 : lineAt(mouseX, mouseY);
        r.pushAlpha(hidden ? 0.35 : 1);
        r.pushTransform((float) left, (float) top, (float) k);
        try {
            for (int i = 0; i < lines.size(); i++) {
                PreviewLine l = lines.get(i);
                int lw = r.nametagLineWidth(l);
                boolean mine = l.kind() == focus;
                if (mine && (held || hot == i)) {
                    r.rect(-1, i * LINE_H - 1, boxW + 2, LINE_H, ColorUtil.fade(p.accent(), held ? 0.3 : 0.16));
                }
                r.nametagLine(l, (maxW - lw) / 2, i * LINE_H);
            }
        } finally {
            r.popTransform();
            r.popAlpha();
        }
        if (hidden) r.textCentered("Hidden (Hide Own)", (int) Math.round(anchor[0]), (int) Math.round(top) - 10, p.textDisabled());
        if (!held && hot >= 0 && lines.get(hot).kind() == focus) {
            ctx.requestTooltip(this, "Drag up or down: Top, Middle (above the HP line) or Bottom");
        }
    }

    private TitlePosition position() {
        return focus == LineKind.TITLE ? titles().titlePosition() : currency().currencyPosition();
    }

    /** The tag's lines with the focused line at {@code at}. */
    private List<PreviewLine> linesWith(TitlePosition at) {
        boolean t = focus == LineKind.TITLE;
        return NametagAdapter.previewLines(title, t ? at : titles().titlePosition(), cur,
                t ? currency().currencyPosition() : at, hidesHp(t));
    }

    /** Either module's Hide HP: the one whose window this is always counts, the other only while enabled. */
    private static boolean hidesHp(boolean titleWindow) {
        StatusTitleModule st = titles();
        PlayerCurrencyModule pc = currency();
        return (titleWindow || st.isEnabled()) && st.hidesHp() || (!titleWindow || pc.isEnabled()) && pc.hidesHp();
    }

    private int indexOfFocus(List<PreviewLine> in) {
        for (int i = 0; i < in.size(); i++) if (in.get(i).kind() == focus) return i;
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
        if (i < 0 || lines.get(i).kind() != focus) return false;
        held = true;
        heldY = mouseY;
        return true;
    }

    @Override
    public void drag(PlayerPreview.View view, double dx, double dy) {
        heldY += dy;
        if (k <= 0) return;
        // every slot gives the same number of lines, so the tag's top stays put: take the slot whose line is nearest
        TitlePosition now = position(), to = now;
        double best = Double.MAX_VALUE;
        for (TitlePosition at : TitlePosition.values()) {
            int i = indexOfFocus(linesWith(at));
            if (i < 0) continue;
            double d = Math.abs(top + (i + 0.5) * LINE_H * k - heldY);
            if (d < best - 0.5 || (Math.abs(d - best) <= 0.5 && at == now)) {
                best = d;
                to = at;
            }
        }
        if (to == now) return;
        if (focus == LineKind.TITLE) {
            if (titles().titlePosition() != to) titles().setTitlePosition(to);
        } else if (currency().currencyPosition() != to) {
            currency().setCurrencyPosition(to);
        }
    }

    @Override
    public void release(PlayerPreview.View view) {
        held = false;
    }
}
