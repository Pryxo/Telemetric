package dev.telemetric.module.impl.visual;

import dev.telemetric.api.HeadStatusApi;
import dev.telemetric.api.HeadStatusCache;
import dev.telemetric.data.TelosData;
import dev.telemetric.game.NametagAdapter;
import dev.telemetric.game.NametagAdapter.PlayerNametag;
import dev.telemetric.game.NametagAdapter.TitleLine;
import dev.telemetric.hud.StatusRankColors;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Telos player name tags (the "[Rank] Name / HP: …" text displays above players). Single place for name tag edits:
 * its own settings act while the module is on, and other modules plug in rules with {@link #addHideRule} (e.g. Hide
 * Players) that apply whenever their module wants them to, even with this module off. Game access:
 * {@link NametagAdapter}. <b>Show Status</b> puts each player's HeadStatus head status ({@link HeadStatusCache}) as a
 * new top line into their Telos name tag ("#1 <icon> Miraj - 1,234 kills"), coloured and animated by leaderboard
 * position.
 */
public final class NametagsModule extends Module {

    private final BooleanSetting hideOwn = add(new BooleanSetting("Hide Own",
            "Hide your own name tag in third person", false));
    private final BooleanSetting showStatus = add(new BooleanSetting("Show Status",
            "Show each player's head status (e.g. Luna Kills) above their name tag", true));

    /** Rules from other modules; each checks its own module's state. */
    private final List<Predicate<PlayerNametag>> hideRules = new CopyOnWriteArrayList<>();

    public NametagsModule() {
        super("Nametags", "Edits Telos player name tags (other modules like Hide Players use it too)", Category.VISUAL);
        syncOn(this::sync, hideOwn);
        sync();
        NametagAdapter.setTitleSource(this::statusLine);
    }

    /**
     * Test hook: (owner UUID dashed, is it you) → status to show instead of the real one, or null for the real one.
     * Shown even with this module or Show Status off. Null hook = none.
     */
    public interface StatusOverride {
        HeadStatusApi.HeadStatus statusFor(String ownerId, boolean self);
    }

    private static volatile StatusOverride statusOverride;

    public static void setStatusOverride(StatusOverride override) {
        statusOverride = override;
    }

    /** Title line for a name tag owner (dashed UUID), or null when off / they have no status. */
    private TitleLine statusLine(String ownerId, boolean self) {
        StatusOverride override = statusOverride;
        HeadStatusApi.HeadStatus forced = override == null ? null : override.statusFor(ownerId, self);
        if (forced != null) return statusLine(forced);
        if (!isEnabled() || !showStatus.get()) return null;
        return HeadStatusCache.get(ownerId).map(NametagsModule::statusLine).orElse(null);
    }

    /**
     * "#1 <icon> Miraj - 1,234 kills" — position (when the API sends one), the leaderboard's icon from
     * {@code statusIcons.json}, its name (a "… Kills" board drops "Kills" and puts "kills" after the count) and the
     * count; coloured by position ({@link StatusRankColors}). Single place that decides the status text.
     */
    static TitleLine statusLine(HeadStatusApi.HeadStatus status) {
        String name = HeadStatusApi.statusName(status.text());
        String unit = "";
        if (name.endsWith(" Kills") && status.count() > 0) {
            name = name.substring(0, name.length() - " Kills".length());
            unit = " kills";
        }
        String text = status.count() > 0
                ? name + " - " + String.format(Locale.ROOT, "%,d", status.count()) + unit
                : name;
        String rank = status.position() > 0 ? "#" + status.position() : "";
        return new TitleLine(rank, TelosData.STATUS_ICONS.get().of(status.text()), text,
                StatusRankColors.forPosition(status.position()));
    }

    /** Adds a rule that hides the name tags it matches (called from other modules' {@code onPostInit}). */
    public void addHideRule(Predicate<PlayerNametag> rule) {
        hideRules.add(rule);
        sync();
    }

    private void sync() {
        boolean own = isEnabled() && hideOwn.get();
        NametagAdapter.setHideRule(!own && hideRules.isEmpty() ? null : tag -> hides(tag, own));
    }

    private boolean hides(PlayerNametag tag, boolean own) {
        if (own && tag.self()) return true;
        for (Predicate<PlayerNametag> rule : hideRules) if (rule.test(tag)) return true;
        return false;
    }
}
