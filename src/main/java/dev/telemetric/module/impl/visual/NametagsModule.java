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
import java.util.Map;
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

    /** The Show Status Colors HUD element; register it after this module. */
    public StatusColorsHud colorsHud() {
        return colorsHud;
    }

    /** Rules from other modules; each checks its own module's state. */
    private final List<Predicate<PlayerNametag>> hideRules = new CopyOnWriteArrayList<>();

    public NametagsModule() {
        super("Nametags", "Edits Telos player name tags (other modules like Hide Players use it too)", Category.VISUAL);
        syncOn(this::sync, hideOwn, topWave, statusTheme, statusPosition);
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
     * {@code statusIcons.json}, its name (the table's {@code "Name"} when set; else a "… Kills" board drops "Kills" and puts "kills" after the count) and the
     * count; coloured by position ({@link StatusRankColors}). Single place that decides the status text.
     */
    static TitleLine statusLine(HeadStatusApi.HeadStatus status) {
        // the API may send the text already formatted ("#25 Miraj - 172 kills"): drop its own rank / count
        String name = HeadStatusApi.statusName(status.text()).replaceFirst("^#\\d+\\s*", "");
        int dash = name.indexOf(" - ");
        String unit = "";
        if (dash > 0) {
            String rest = name.substring(dash + 3).replaceFirst("^[\\d,.]+", "").trim(); // "172 kills" → "kills"
            if (!rest.isEmpty()) unit = " " + rest;
            name = name.substring(0, dash).trim();
        }
        String id = boardId(status.text(), name);
        TelosData.StatusIcons table = TelosData.STATUS_ICONS.get();
        TelosData.StatusCategory cat = table.category(id);
        if (cat != null) {
            // "Miraj Kills" → "Miraj", unit from the category ("1 kill" / "2 kills")
            String suffix = " " + cat.name();
            if (name.regionMatches(true, name.length() - suffix.length(), suffix, 0, suffix.length()))
                name = name.substring(0, name.length() - suffix.length());
            if (status.count() > 0) unit = " " + cat.unit(status.count());
        } else if (name.endsWith(" Kills") && status.count() > 0) {
            name = name.substring(0, name.length() - " Kills".length());
            unit = status.count() == 1 ? " Kill" : " Kills"; // same words as the "Kills" category
        }
        String custom = table.name(id);
        if (custom != null) name = custom; // hand-written "Name" beats the auto name
        String text = status.count() > 0
                ? name + " - " + String.format(Locale.ROOT, "%,d", status.count()) + unit
                : name;
        String rank = status.position() > 0 ? "#" + status.position() : "";
        return new TitleLine(rank, table.of(id), text, StatusRankColors.forPosition(status.position()));
    }

    /**
     * Leaderboard ID of a status for the icon table: the text itself when it is an ID, else the ID whose display name
     * is {@code name} or "{@code name} Kills" (the API sends formatted texts like "#25 Miraj - 172 kills").
     */
    private static String boardId(String text, String name) {
        Map<String, String> all = HeadStatusApi.statuses();
        if (all.containsKey(text)) return text;
        for (Map.Entry<String, String> e : all.entrySet()) {
            String n = e.getValue();
            if (n.equalsIgnoreCase(name) || n.equalsIgnoreCase(name + " Kills")) return e.getKey();
        }
        return "STATUS-" + name.trim().toUpperCase(Locale.ROOT).replace(' ', '-');
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
