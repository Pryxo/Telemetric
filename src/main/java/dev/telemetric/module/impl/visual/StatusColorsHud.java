package dev.telemetric.module.impl.visual;

import dev.telemetric.Telemetric;
import dev.telemetric.api.HeadStatusApi;
import dev.telemetric.api.HeadStatusCache;
import dev.telemetric.game.MojangSessionAdapter;
import dev.telemetric.game.NametagAdapter.TitleLine;
import dev.telemetric.hud.HudModule;
import dev.telemetric.hud.StatusRankColors.Tier;
import dev.telemetric.module.Category;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * Status Title → <i>Show Status Colors</i>: a HUD preview of the current Status Theme. It shows one example status title per
 * leaderboard slot ({@code "#1"} … {@code "#21-#25"}), drawn exactly like on a name tag (same text, animated gradient,
 * #1 wave, icon, text-display background). It uses your own status (board and count) if you have one, otherwise an
 * example. Not listed in the ClickGUI; shown while the Status Title toggle is on, and movable in the HUD editor.
 */
public final class StatusColorsHud extends HudModule {

    private static final int PAD = 2, GAP = 4, ROW_GAP = 2;
    /** The vanilla text display background (for name tags without their own colour). */
    private static final int TAG_BG = 0x40000000;
    /** An example position for each slot: the slot's first position. */
    private static final int[] POSITIONS = {1, 2, 3, 4, 11, 21};
    private static final HeadStatusApi.HeadStatus EXAMPLE = new HeadStatusApi.HeadStatus("STATUS-LUNA", 0, 1234);

    private final BooleanSetting toggle;

    StatusColorsHud(BooleanSetting toggle) {
        super("Status Colors", "Status Title: example status title of each position", Category.PLAYER, 0.0, 0.5, true);
        this.toggle = toggle;
    }

    @Override
    public boolean isVisibleInGui() {
        return false;
    }

    @Override
    public boolean isShown() {
        return toggle.get();
    }

    /** HUD editor "Del" turns off the Status Title toggle. */
    @Override
    public void hide() {
        toggle.set(false);
    }

    /** One title per tier, from your own status if it's known. */
    private static List<TitleLine> lines() {
        HeadStatusApi.HeadStatus own = HeadStatusCache.get(MojangSessionAdapter.playerUuid()).orElse(EXAMPLE);
        List<TitleLine> out = new ArrayList<>(POSITIONS.length);
        for (int pos : POSITIONS) {
            out.add(StatusTitleModule.statusLine(new HeadStatusApi.HeadStatus(own.id(), pos, own.count())));
        }
        return out;
    }

    private static int labelWidth(Render2D r) {
        int w = 0;
        for (Tier t : Tier.values()) w = Math.max(w, r.textWidth(t.label()));
        return w;
    }

    private static int rowHeight(Render2D r) {
        return r.fontHeight() + 1 + ROW_GAP;
    }

    @Override
    public int contentWidth(Render2D r, boolean preview) {
        int most = 0;
        for (TitleLine l : lines()) most = Math.max(most, r.statusTitleWidth(l));
        return PAD + labelWidth(r) + GAP + 1 + most + 1 + PAD;
    }

    @Override
    public int contentHeight(Render2D r, boolean preview) {
        return PAD * 2 + Tier.values().length * rowHeight(r) - ROW_GAP;
    }

    @Override
    public void draw(Render2D r, boolean preview, boolean alignRight) {
        Theme p = Telemetric.get().theme().palette();
        int lw = labelWidth(r), x = PAD + lw + GAP, y = PAD + 1;
        for (TitleLine line : lines()) {
            Tier tier = line.tier();
            if (tier != null) r.text(tier.label(), PAD, y, p.textMuted());
            int w = r.statusTitleWidth(line);
            r.rect(x, y - 1, w + 2, r.fontHeight() + 1, TAG_BG);
            r.statusTitle(line, x + 1, y);
            y += rowHeight(r);
        }
    }
}
