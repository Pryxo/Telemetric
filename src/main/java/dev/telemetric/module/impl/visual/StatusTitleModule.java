package dev.telemetric.module.impl.visual;

import dev.telemetric.api.HeadStatusApi;
import dev.telemetric.api.HeadStatusCache;
import dev.telemetric.data.TelosData;
import dev.telemetric.game.AnimatedTextAdapter;
import dev.telemetric.game.NametagAdapter;
import dev.telemetric.game.NametagAdapter.PlayerNametag;
import dev.telemetric.game.NametagAdapter.TitleLine;
import dev.telemetric.hud.StatusRankColors;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ModeSetting;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Telos player name tags (the "[Rank] Name / HP: …" text displays above players). This is the one place for name tag
 * edits. Its own settings apply while the module is on, and other modules plug in rules through {@link #addHideRule}
 * (e.g. Hide Players) that apply whenever their module wants them to, even with this module off. Game access goes
 * through {@link NametagAdapter}. <b>Show Status</b> adds each player's HeadStatus head status ({@link HeadStatusCache})
 * as a new top line in their Telos name tag ("#1 <icon> Miraj - 1,234 kills"), coloured and animated by leaderboard
 * position.
 */
public final class StatusTitleModule extends Module {

    private final BooleanSetting hideOwn = add(new BooleanSetting("Hide Own",
            "Hide your own name tag in third person", false));
    private final BooleanSetting showStatus = add(new BooleanSetting("Show Status",
            "Show each player's head status (e.g. Luna Kills) above their name tag", true));
    private final BooleanSetting topWave = add(new BooleanSetting("Top 1 Wave",
            "Let the #1 status title bob up and down in a wave", true));
    private final ModeSetting statusTheme = add(new ModeSetting("Status Theme",
            "Colour set of the status titles by leaderboard position", StatusRankColors.Theme.defaultName(),
            StatusRankColors.Theme::names));
    private final ModeSetting statusPosition = add(new ModeSetting("Status Position",
            "Where the status line sits: Top (above the name), Middle (between name and HP) or Bottom (under everything)",
            "Top", "Top", "Middle", "Bottom"));
    private final BooleanSetting showColors = add(new BooleanSetting("Show Status Colors",
            "Show a HUD element with every colour of each status position (#1 … #21-#25) in the current Status Theme",
            false));
    private final StatusColorsHud colorsHud = new StatusColorsHud(showColors);

    /** The Show Status Colors HUD element. Register it after this module. */
    public StatusColorsHud colorsHud() {
        return colorsHud;
    }

    /** Rules from other modules. Each one checks its own module's state. */
    private final List<Predicate<PlayerNametag>> hideRules = new CopyOnWriteArrayList<>();

    public StatusTitleModule() {
        super("Status Title", "Shows HeadStatus titles in Telos name tags (Hide Players uses it too)", Category.PLAYER);
        syncOn(this::sync, hideOwn, topWave, statusTheme, statusPosition);
        sync();
        NametagAdapter.setTitleSource(this::statusLine);
    }

    /**
     * Test hook: (owner UUID dashed, is it you) gives a status to show instead of the real one, or null for the real
     * one. It shows even with this module or Show Status off. A null hook means none.
     */
    public interface StatusOverride {
        HeadStatusApi.HeadStatus statusFor(String ownerId, boolean self);
    }

    private static volatile StatusOverride statusOverride;

    public static void setStatusOverride(StatusOverride override) {
        statusOverride = override;
    }

    /** The title line for a name tag owner (dashed UUID), or null if it's off or they have no status. */
    private TitleLine statusLine(String ownerId, boolean self) {
        StatusOverride override = statusOverride;
        HeadStatusApi.HeadStatus forced = override == null ? null : override.statusFor(ownerId, self);
        if (forced != null) return statusLine(forced);
        if (!isEnabled() || !showStatus.get()) return null;
        return HeadStatusCache.get(ownerId).map(StatusTitleModule::statusLine).orElse(null);
    }

    /**
     * Builds "#1 <icon> Miraj - 1,234 Kills": the position (if the API sends one), and the icon, {@code "Name"} and
     * category unit of the API's leaderboard ID in {@code statusIcons.json}, coloured by position
     * ({@link StatusRankColors}). An ID missing from the table is shown as the raw ID. The API's own text is not used.
     * This is the one place that decides the status text.
     */
    static TitleLine statusLine(HeadStatusApi.HeadStatus status) {
        String id = status.id();
        TelosData.StatusIcons table = TelosData.STATUS_ICONS.get();
        String name = table.name(id);
        if (name == null) name = id;
        TelosData.StatusCategory cat = table.category(id);
        String unit = cat != null && status.count() > 0 ? " " + cat.unit(status.count()) : "";
        String text = status.count() > 0
                ? name + " - " + String.format(Locale.ROOT, "%,d", status.count()) + unit
                : name;
        String rank = status.position() > 0 ? "#" + status.position() : "";
        return new TitleLine(rank, table.of(id), text, StatusRankColors.forPosition(status.position()));
    }

    /** Adds a rule that hides the name tags it matches (called from other modules' {@code onPostInit}). */
    public void addHideRule(Predicate<PlayerNametag> rule) {
        hideRules.add(rule);
        sync();
    }

    private void sync() {
        AnimatedTextAdapter.setWave(topWave.get());
        StatusRankColors.setTheme(statusTheme.get());
        NametagAdapter.setTitlePosition(switch (statusPosition.get()) {
            case "Middle" -> NametagAdapter.TitlePosition.MIDDLE;
            case "Bottom" -> NametagAdapter.TitlePosition.BOTTOM;
            default -> NametagAdapter.TitlePosition.TOP;
        });
        boolean own = isEnabled() && hideOwn.get();
        NametagAdapter.setHideRule(!own && hideRules.isEmpty() ? null : tag -> hides(tag, own));
    }

    private boolean hides(PlayerNametag tag, boolean own) {
        if (own && tag.self()) return true;
        for (Predicate<PlayerNametag> rule : hideRules) if (rule.test(tag)) return true;
        return false;
    }
}
