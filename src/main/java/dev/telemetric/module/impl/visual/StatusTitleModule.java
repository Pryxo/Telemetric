package dev.telemetric.module.impl.visual;

import dev.telemetric.api.HeadStatusApi;
import dev.telemetric.api.HeadStatusCache;
import dev.telemetric.data.TelosData;
import dev.telemetric.game.AnimatedTextAdapter;
import dev.telemetric.game.NametagAdapter.PlayerNametag;
import dev.telemetric.game.NametagAdapter.TitleLine;
import dev.telemetric.game.NametagAdapter;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.hud.StatusRankColors;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ModeSetting;
import dev.telemetric.ui.clickgui.window.NametagLayer;
import dev.telemetric.ui.clickgui.window.PlayerPreview;
import dev.telemetric.ui.clickgui.window.SettingsPreview;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
    private final BooleanSetting hideHp = add(new BooleanSetting("Hide HP",
            "Remove the HP line from every Telos name tag (Status Position still moves the status line)", false));
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
        syncOn(this::sync, hideOwn, topWave, statusTheme, statusPosition, hideHp);
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
        // asked every frame for every visible tag: cached per status (a record) until the table changes
        TelosData.StatusIcons table = TelosData.STATUS_ICONS.get();
        if (table != lineTable || LINE_CACHE.size() > 512) {
            LINE_CACHE.clear();
            lineTable = table;
        }
        return LINE_CACHE.computeIfAbsent(status, s -> buildStatusLine(s, table));
    }

    private static final java.util.Map<HeadStatusApi.HeadStatus, TitleLine> LINE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile TelosData.StatusIcons lineTable;

    private static TitleLine buildStatusLine(HeadStatusApi.HeadStatus status, TelosData.StatusIcons table) {
        String id = status.id();
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

    // ------------------------------------------------------------------ Settings window preview

    @Override
    public SettingsPreview createPreview() {
        return new PlayerPreview(new NametagLayer(NametagAdapter.LineKind.TITLE));
    }

    /**
     * Your title for the name tag preview, shown even with the module off: your real status if there is one, else a
     * sample. Null with Show Status off.
     */
    public TitleLine previewTitle() {
        if (!showStatus.get()) return null;
        String id = PlayerAdapter.accountId();
        StatusOverride override = statusOverride;
        HeadStatusApi.HeadStatus forced = override == null || id == null ? null : override.statusFor(id, true);
        if (forced != null) return statusLine(forced);
        if (id != null) {
            Optional<HeadStatusApi.HeadStatus> real = HeadStatusCache.get(id);
            if (real.isPresent()) return statusLine(real.get());
        }
        return new TitleLine("#1", null, "Sample Title - 1,234 Kills", StatusRankColors.forPosition(1));
    }

    public NametagAdapter.TitlePosition titlePosition() {
        return switch (statusPosition.get()) {
            case "Middle" -> NametagAdapter.TitlePosition.MIDDLE;
            case "Bottom" -> NametagAdapter.TitlePosition.BOTTOM;
            default -> NametagAdapter.TitlePosition.TOP;
        };
    }

    public void setTitlePosition(NametagAdapter.TitlePosition position) {
        statusPosition.set(switch (position) {
            case MIDDLE -> "Middle";
            case BOTTOM -> "Bottom";
            default -> "Top";
        });
    }

    /** Whether your own name tag is hidden in game (Hide Own). */
    public boolean hidesOwnTag() {
        return isEnabled() && hideOwn.get();
    }

    /** Whether Hide HP is on (the setting alone; the Settings preview checks the enabled state itself). */
    public boolean hidesHp() {
        return hideHp.get();
    }

    /** The Hide HP setting itself; Player Currency keeps its own Hide HP in sync with it. */
    public BooleanSetting hideHpSetting() {
        return hideHp;
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
        NametagAdapter.setTitleHideHp(isEnabled() && hideHp.get());
        boolean own = isEnabled() && hideOwn.get();
        NametagAdapter.setHideRule(!own && hideRules.isEmpty() ? null : tag -> hides(tag, own));
    }

    private boolean hides(PlayerNametag tag, boolean own) {
        if (own && tag.self()) return true;
        for (Predicate<PlayerNametag> rule : hideRules) if (rule.test(tag)) return true;
        return false;
    }
}
