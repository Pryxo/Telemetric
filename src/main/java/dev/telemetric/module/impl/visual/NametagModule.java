package dev.telemetric.module.impl.visual;

import dev.telemetric.api.HeadStatusApi;
import dev.telemetric.api.HeadStatusCache;
import dev.telemetric.data.TelosData;
import dev.telemetric.game.AnimatedTextAdapter;
import dev.telemetric.game.NametagAdapter.CurrencyColors;
import dev.telemetric.game.NametagAdapter.CurrencyLine;
import dev.telemetric.game.NametagAdapter.FragmentCount;
import dev.telemetric.game.NametagAdapter.PlayerNametag;
import dev.telemetric.game.NametagAdapter.TitleLine;
import dev.telemetric.game.NametagAdapter.TitlePosition;
import dev.telemetric.game.NametagAdapter;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.ServerGate;
import dev.telemetric.hud.StatusRankColors;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.GroupSetting;
import dev.telemetric.setting.impl.GroupToggleSetting;
import dev.telemetric.setting.impl.ModeSetting;
import dev.telemetric.tracking.BalanceLookup;
import dev.telemetric.ui.clickgui.window.NametagLayer;
import dev.telemetric.ui.clickgui.window.PlayerPreview;
import dev.telemetric.ui.clickgui.window.SettingsPreview;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Player → <b>Nametag</b>: every edit of the Telos player name tags (the "[Rank] Name / HP: …" text displays above
 * players) in one module, with one 3D preview where every extra line can be dragged.
 * <ul>
 *   <li><b>General</b>: Hide Own (your tag in third person) and Hide HP (the "HP:" line of every tag).</li>
 *   <li><b>Status Title</b> (group toggle): each player's HeadStatus status ({@link HeadStatusCache}) as an extra line
 *   ("#1 <icon> Miraj - 1,234 kills"), coloured and animated by leaderboard position.</li>
 *   <li><b>Player Currency</b> (group toggle): fame and fragments from {@code /balance <user>}, sent in the background
 *   by {@link BalanceLookup} (rate-limited, cached, replies kept out of the chat),
 *   {@code "❖1,632,163.3 | 0 <icon> 93 <icon> 39 <icon> 69 <icon>"}. Fame Auto follows each player's mode glyph
 *   ({@code TelosCharacter.modeFromGlyph}): normal → normal, seasonal → seasonal, ironman / group / unknown → none.</li>
 * </ul>
 * Other modules plug in rules through {@link #addHideRule} (e.g. Hide Players); those apply whenever their own module
 * wants them to, even with this module off. Game access goes through {@link NametagAdapter}.
 */
public final class NametagModule extends Module {

    /** Fragment names in /balance reply order. */
    private static final String[] FRAGMENTS = {"Irradiated", "Gilded", "Royal", "Bloodshot"};

    // ------------------------------------------------------------------ General

    private final BooleanSetting hideOwn = add(new BooleanSetting("Hide Own",
            "Hide your own name tag in third person", false));
    private final BooleanSetting hideOwnName = add(new BooleanSetting("Hide Own Name",
            "Remove the server's name/rank line from your own name tag only (HP and the extra lines stay)", false));
    private final BooleanSetting hideHp = add(new BooleanSetting("Hide HP",
            "Remove the HP line from every Telos name tag (the extra lines keep their positions)", false));

    // ------------------------------------------------------------------ Status Title

    private final GroupToggleSetting status = add(new GroupToggleSetting("Status Title",
            "Show each player's head status (e.g. Luna Kills) as an extra line in their name tag", true));
    private final ModeSetting statusPosition = add(status.child(new ModeSetting("Status Position",
            "Where the status line sits: Top (above the name), Middle (between name and HP) or Bottom (under everything)",
            "Top", "Top", "Middle", "Bottom")));
    private final ModeSetting statusTheme = add(status.child(new ModeSetting("Status Theme",
            "Colour set of the status titles by leaderboard position", StatusRankColors.Theme.defaultName(),
            StatusRankColors.Theme::names)));
    private final BooleanSetting topWave = add(status.child(new BooleanSetting("Top 1 Wave",
            "Let the #1 status title bob up and down in a wave", true)));
    private final BooleanSetting hideOwnStatus = add(status.child(new BooleanSetting("Hide Own Status",
            "Don't show the status title line (rank, name, count) in your own name tag", false)));
    private final BooleanSetting showColors = add(status.child(new BooleanSetting("Show Status Colors",
            "Show a HUD element with every colour of each status position (#1 … #21-#25) in the current Status Theme",
            false)));
    private final StatusColorsHud colorsHud = new StatusColorsHud(showColors);

    // ------------------------------------------------------------------ Player Currency

    private final GroupToggleSetting currency = add(new GroupToggleSetting("Player Currency",
            "Show each player's fame and fragments (via /balance) as an extra line in their name tag", false));
    private final ModeSetting currencyPosition = add(currency.child(new ModeSetting("Currency Position",
            "Where the line sits: Top (above the name), Middle (between name and HP) or Bottom (under everything)",
            "Middle", "Top", "Middle", "Bottom")));
    private final ModeSetting fame = add(currency.child(new ModeSetting("Fame",
            "Which fame balance to show. Auto: each player's own mode (from the mode icon in their name tag): Normal "
                    + "fame for normal, Seasonal for seasonal, none for ironman", "Auto",
            "Auto", "Normal", "Seasonal", "Nothing")));
    private final BooleanSetting hideOwnCurrency = add(currency.child(new BooleanSetting("Hide Own Currency",
            "Don't show the currency line in your own name tag", false)));
    private final GroupSetting fragmentsGroup = add(currency.child(new GroupSetting("Fragments",
            "Which fragment counts to show")));
    private final BooleanSetting[] fragments = new BooleanSetting[FRAGMENTS.length];

    {
        for (int i = 0; i < FRAGMENTS.length; i++) {
            fragments[i] = add(fragmentsGroup.child(new BooleanSetting(FRAGMENTS[i],
                    "Show " + FRAGMENTS[i] + " Fragments", true)));
        }
    }

    private final GroupSetting colorsGroup = add(currency.child(new GroupSetting("Colors",
            "Colours of the currency line")));
    private final ColorSetting normalIcon = add(colorsGroup.child(new ColorSetting("Normal Fame Icon",
            "❖ in front of Normal fame", 0xFFFFFFFF, false)));
    private final ColorSetting seasonalIcon = add(colorsGroup.child(new ColorSetting("Seasonal Fame Icon",
            "❖ in front of Seasonal fame (yellow like in /balance)", 0xFFFFFF55, false)));
    private final ColorSetting fameColor = add(colorsGroup.child(new ColorSetting("Fame Count",
            "The fame number", 0xFF55FFFF, false)));
    private final ColorSetting separatorColor = add(colorsGroup.child(new ColorSetting("Separator",
            "The \" | \" between fame and fragments", 0xFF555555, false)));
    private final ColorSetting fragmentColor = add(colorsGroup.child(new ColorSetting("Fragment Count",
            "The fragment numbers", 0xFF55FFFF, false)));
    private final ColorSetting fragmentIconColor = add(colorsGroup.child(new ColorSetting("Fragment Icons",
            "Tint of the fragment icons (white = original)", 0xFFFFFFFF, false)));

    /** Rules from other modules. Each one checks its own module's state. */
    private final List<Predicate<PlayerNametag>> hideRules = new CopyOnWriteArrayList<>();

    public NametagModule() {
        super("Nametag", "Everything in Telos name tags: Status Title, Player Currency, Hide Own / Hide HP",
                Category.PLAYER);
        syncOn(this::sync, hideOwn, hideOwnName, hideHp, status, topWave, statusTheme, statusPosition, currency, currencyPosition);
        sync();
        BalanceLookup.register();
        NametagAdapter.setTitleSource(this::statusLine);
        NametagAdapter.setCurrencySource(this::currencyFor);
    }

    /** The Show Status Colors HUD element. Register it after this module. */
    public StatusColorsHud colorsHud() {
        return colorsHud;
    }

    // ------------------------------------------------------------------ sync / tick

    private void sync() {
        AnimatedTextAdapter.setWave(topWave.get());
        StatusRankColors.setTheme(statusTheme.get());
        NametagAdapter.setTitlePosition(titlePosition());
        boolean noHp = isEnabled() && hideHp.get();
        NametagAdapter.setTitleHideHp(noHp);
        NametagAdapter.setHideOwnName(isEnabled() && hideOwnName.get());
        NametagAdapter.setCurrencyLayout(currencyPosition(), noHp);
        boolean own = isEnabled() && hideOwn.get();
        NametagAdapter.setHideRule(!own && hideRules.isEmpty() ? null : tag -> hides(tag, own));
        if (!currencyOn()) BalanceLookup.cancel();
    }

    @Override
    public void onTick() {
        if (!currencyOn() || !ServerGate.onTelos() || !PlayerAdapter.isInWorld()) {
            BalanceLookup.cancel();
            return;
        }
        BalanceLookup.tick(PlayerAdapter.loadedPlayerNames());
    }

    private boolean currencyOn() {
        return isEnabled() && currency.get();
    }

    // ------------------------------------------------------------------ hide rules

    /** Whether your own name tag is hidden in game (Hide Own). */
    public boolean hidesOwnTag() {
        return isEnabled() && hideOwn.get();
    }

    /** Whether Hide HP is on (the setting alone; the Settings preview shows it even with the module off). */
    public boolean hidesOwnName() {
        return hideOwnName.get();
    }

    public boolean hidesHp() {
        return hideHp.get();
    }

    /** Adds a rule that hides the name tags it matches (called from other modules' {@code onPostInit}). */
    public void addHideRule(Predicate<PlayerNametag> rule) {
        hideRules.add(rule);
        sync();
    }

    private boolean hides(PlayerNametag tag, boolean own) {
        if (own && tag.self()) return true;
        for (Predicate<PlayerNametag> rule : hideRules) if (rule.test(tag)) return true;
        return false;
    }

    // ------------------------------------------------------------------ Status Title

    /**
     * Test hook: (owner UUID dashed, is it you) gives a status to show instead of the real one, or null for the real
     * one. It shows even with this module or Status Title off. A null hook means none.
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
        if (self && hideOwnStatus.get()) return null;
        StatusOverride override = statusOverride;
        HeadStatusApi.HeadStatus forced = override == null ? null : override.statusFor(ownerId, self);
        if (forced != null) return statusLine(forced);
        if (!isEnabled() || !status.get()) return null;
        return HeadStatusCache.get(ownerId).map(NametagModule::statusLine).orElse(null);
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

    private static final Map<HeadStatusApi.HeadStatus, TitleLine> LINE_CACHE = new ConcurrentHashMap<>();
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

    // ------------------------------------------------------------------ Player Currency

    private CurrencyLine currencyFor(String ownerName, boolean self, String mode) {
        if (!currencyOn() || (self && hideOwnCurrency.get())) return null;
        return line(ownerName, mode);
    }

    private CurrencyLine line(String ownerName, String mode) {
        BalanceLookup.Balance b = BalanceLookup.get(ownerName);
        if (b == null) return null;
        String fameMode = switch (fame.get()) {
            case "Auto" -> "normal".equals(mode) || "seasonal".equals(mode) ? mode : null; // ironman: none
            case "Normal" -> "normal";
            case "Seasonal" -> "seasonal";
            default -> null;
        };
        String fameValue = fameMode == null ? null : b.fame().get(fameMode);
        List<FragmentCount> shown = new ArrayList<>();
        for (int i = 0; i < b.fragments().size(); i++) {
            if (i < fragments.length && !fragments[i].get()) continue;
            BalanceLookup.Fragment f = b.fragments().get(i);
            shown.add(new FragmentCount(f.have(), f.glyph()));
        }
        if (fameValue == null && shown.isEmpty()) return null;
        return new CurrencyLine(fameValue, List.copyOf(shown), colors("seasonal".equals(fameMode)));
    }

    private CurrencyColors colors(boolean seasonal) {
        return new CurrencyColors(seasonal ? seasonalIcon.get() : normalIcon.get(), fameColor.get(),
                separatorColor.get(), fragmentColor.get(), fragmentIconColor.get());
    }

    // ------------------------------------------------------------------ Settings window preview

    /** One preview for the whole tag: the status and currency lines can both be dragged up / down. */
    @Override
    public SettingsPreview createPreview() {
        return new PlayerPreview(new NametagLayer());
    }

    /**
     * Your title for the name tag preview, shown even with the module off: your real status if there is one, else a
     * sample. Null with Status Title off.
     */
    public TitleLine previewTitle() {
        if (!status.get() || hideOwnStatus.get()) return null;
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

    /**
     * Your currency line for the name tag preview, shown even with the module off: your real balance once it was
     * looked up, else a sample. Null with Player Currency or Hide Own Currency (or nothing to show).
     */
    public CurrencyLine previewCurrency() {
        if (!currency.get() || hideOwnCurrency.get()) return null;
        String name = PlayerAdapter.name();
        CurrencyLine real = name == null ? null : line(name, "normal");
        if (real != null) return real;
        if (fame.is("Nothing")) return null;
        return new CurrencyLine("12,345", List.of(), colors(fame.is("Seasonal")));
    }

    public TitlePosition titlePosition() {
        return toPosition(statusPosition.get(), TitlePosition.TOP);
    }

    public void setTitlePosition(TitlePosition position) {
        statusPosition.set(fromPosition(position));
    }

    public TitlePosition currencyPosition() {
        return toPosition(currencyPosition.get(), TitlePosition.MIDDLE);
    }

    public void setCurrencyPosition(TitlePosition position) {
        currencyPosition.set(fromPosition(position));
    }

    private static TitlePosition toPosition(String mode, TitlePosition fallback) {
        return switch (mode) {
            case "Top" -> TitlePosition.TOP;
            case "Middle" -> TitlePosition.MIDDLE;
            case "Bottom" -> TitlePosition.BOTTOM;
            default -> fallback;
        };
    }

    private static String fromPosition(TitlePosition position) {
        return switch (position) {
            case TOP -> "Top";
            case MIDDLE -> "Middle";
            case BOTTOM -> "Bottom";
        };
    }
}
