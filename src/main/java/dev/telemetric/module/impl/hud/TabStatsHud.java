package dev.telemetric.module.impl.hud;

import dev.telemetric.Telemetric;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.ServerInfoAdapter;
import dev.telemetric.game.ServerInfoAdapter.TabStat;
import dev.telemetric.hud.HudIcon;
import dev.telemetric.hud.LinesHudModule;
import dev.telemetric.hud.TextRun;
import dev.telemetric.setting.Setting;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.GroupToggleSetting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A group of Tab-list stats (one section of the Telos Tab list) with a toggle per stat.
 * Stats the server doesn't currently publish are skipped in-game and shown as "—" in the editor.
 */
public class TabStatsHud extends LinesHudModule {

    /** Not a Tab-list stat: the location from Telos's bottom-right HUD box (biome name as fallback). */
    static final String LOCATION = "Location";

    private final String title;
    private final BooleanSetting showTitle;
    private final Map<String, BooleanSetting> stats = new LinkedHashMap<>();
    /** Per-stat "show real value" toggles (only stats with a known formula). */
    private final Map<String, BooleanSetting> real = new LinkedHashMap<>();

    /** @param serverColorToggle add "Use Server Color" (not for Stats Info: every stat has its own colour) */
    public TabStatsHud(String name, String description, String title, List<String> labels,
                       double defaultX, double defaultY, boolean serverColorToggle) {
        super(name, description, defaultX, defaultY, false);
        this.title = title;
        this.showTitle = add(new BooleanSetting("Show Title", "Show the \"" + title + "\" heading", true));
        if (serverColorToggle) addUseServerColor();
        for (String label : labels) {
            String desc = "Show " + label + " from the Tab list";
            if (StatFormulas.supported(label)) {
                // stat with a formula: dropdown toggle holding its "Show Real %" (key stays "<label> Real %")
                GroupToggleSetting shown = add(new GroupToggleSetting(label, desc, true).movable());
                stats.put(label, shown);
                real.put(label, add(shown.child(new BooleanSetting(label + " Real %",
                        "Show what " + label + " actually does (wiki formula) after the value", true)
                        .withLabel("Show Real %").attachedTo(shown))));
            } else {
                stats.put(label, add(new BooleanSetting(label, desc, true).movable()));
            }
        }
    }

    @Override
    protected String title() {
        return showTitle.get() ? title : null;
    }

    @Override
    protected List<Line> lines(boolean preview) {
        List<Line> out = new ArrayList<>();
        // rows follow the user's drag order in the ClickGUI
        for (Setting<?> s : getSettingOrder()) {
            BooleanSetting toggle = stats.get(s.getName());
            if (toggle == null || !toggle.get()) continue;
            String label = s.getName();
            Optional<TabStat> stat = ServerInfoAdapter.tabStat(label);
            if (label.equals(LOCATION)) {
                String biome = PlayerAdapter.locationName();
                if (biome == null && preview) biome = "Plains";
                if (biome != null) {
                    // not a Tab stat: value in the HUD box's own server colour; else borrow a sibling stat's
                    int[] c = siblingColors();
                    int boxColor = dev.telemetric.game.BossBarAdapter.hudLocationColor();
                    int vc = numberColor();
                    if (vc == 0) vc = boxColor != 0 && !isGrey(boxColor) ? boxColor : c[1];
                    out.add(new Line(label, biome, c[0], vc, List.of(), TelosIcons.resolve(label, "", null), TelosIcons.tinted(label)));
                }
                continue;
            }
            if (stat.isPresent()) {
                out.add(line(label, stat.get()));
            } else if (preview) {
                String shown = isFragment(label) ? "" : label;
                out.add(new Line(shown, "—", 0, 0, List.of(), TelosIcons.resolve(label, "", null), TelosIcons.tinted(label)));
            }
        }
        if (out.isEmpty() && preview) out.add(new Line(title, "no stats selected"));
        return out;
    }

    /**
     * {labelColor, valueColor} of the first Tab stat of this element that has server colours
     * (Server first, as it sits next to Location), or {0, 0} = theme default.
     */
    private int[] siblingColors() {
        for (Setting<?> setting : getSettingOrder()) {
            String label = setting.getName();
            if (!stats.containsKey(label) || label.equals(LOCATION)) continue;
            Optional<TabStat> s = ServerInfoAdapter.tabStat(label);
            if (s.isEmpty()) continue;
            int lc = s.get().labelColor();
            int vc = s.get().valueColor();
            // prefer the first coloured run (Server "[test, realm-1]" → the pink of "test", not the grey bracket)
            for (TextRun r : s.get().valueRuns()) if (r.color() != 0 && !isGrey(r.color())) { vc = r.color(); break; }
            if (vc == 0) {
                for (TextRun r : s.get().valueRuns()) if (r.color() != 0) { vc = r.color(); break; }
            }
            if (lc != 0 || vc != 0) return new int[] {lc, vc};
        }
        return new int[] {0, 0};
    }

    /** Value colour (the green) of Soul Points, Glory or Mastery from Tab, first one found; 0 = none. */
    private static int numberColor() {
        for (String label : List.of("Soul Points", "Glory", "Mastery")) {
            Optional<TabStat> s = ServerInfoAdapter.tabStat(label);
            if (s.isEmpty()) continue;
            int vc = s.get().valueColor();
            for (TextRun r : s.get().valueRuns()) if (r.color() != 0 && !isGrey(r.color())) { vc = r.color(); break; }
            if (vc != 0 && !isGrey(vc)) return vc;
        }
        return 0;
    }

    /** Grey / white / black (all channels within 24 of each other). */
    private static boolean isGrey(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 24;
    }

    private static boolean isFragment(String label) {
        return label.toLowerCase(Locale.ROOT).endsWith("fragments");
    }

    private Line line(String label, TabStat s) {
        String key = label.toLowerCase(Locale.ROOT);
        String value = s.value();
        int valueColor = s.valueColor();
        List<TextRun> runs = s.valueRuns();
        HudIcon.Glyph tabIcon = s.icon();

        if (key.equals("mastery") || key.equals("class")) {
            // the server puts the mastery / ruleset (mode) symbol inside the value: draw exactly that glyph.
            // Class never uses the label-side glyph (none, or a class portrait) — no value glyph = mode guessed
            // from the text in TelosIcons.
            HudIcon.Glyph inValue = firstGlyph(runs);
            if (inValue != null || key.equals("class")) tabIcon = inValue;
            runs = withoutGlyphs(runs);
        }
        if (key.equals("class")) {
            // keep only the class name (drop "Lvl 70" etc.)
            String lower = value.toLowerCase(Locale.ROOT);
            for (String c : TelosIcons.CLASSES) {
                if (!lower.contains(c)) continue;
                for (TextRun r : runs) {
                    if (r.text().toLowerCase(Locale.ROOT).contains(c) && r.color() != 0) { valueColor = r.color(); break; }
                }
                value = Character.toUpperCase(c.charAt(0)) + c.substring(1);
                runs = List.of();
                break;
            }
        }
        BooleanSetting realToggle = real.get(label);
        String extra = realToggle != null && realToggle.get() ? StatFormulas.real(label, s.value()) : null;
        if (extra != null) {
            List<TextRun> withExtra = new ArrayList<>(runs.isEmpty() ? List.of(new TextRun(value, valueColor, null)) : runs);
            withExtra.add(new TextRun(" (" + extra + ")", Telemetric.get().theme().palette().textMuted(), null));
            runs = withExtra;
            value = value + " (" + extra + ")";
        }
        String shown = isFragment(label) ? "" : label;
        return new Line(shown, value, s.labelColor(), valueColor, runs,
                TelosIcons.resolve(label, s.value(), tabIcon), TelosIcons.tinted(label));
    }

    /**
     * First pack-font glyph in the value, as the Tab list draws it. Any code point is accepted: Telos changes
     * them (e.g. Mastery 6 = U+152CB, seasonal mode = U+18057 in 2026-11), so no whitelist.
     */
    private static HudIcon.Glyph firstGlyph(List<TextRun> runs) {
        for (TextRun r : runs) {
            if (r.font() == null) continue;
            for (int cp : r.text().codePoints().toArray()) {
                if (Character.isWhitespace(cp)) continue;
                return new HudIcon.Glyph(Character.toString(cp), r.font());
            }
        }
        return null;
    }

    /** Runs with every pack-font glyph removed (leading whitespace trimmed). */
    private static List<TextRun> withoutGlyphs(List<TextRun> runs) {
        List<TextRun> out = new ArrayList<>();
        for (TextRun r : runs) {
            if (r.font() != null) continue;
            String t = out.isEmpty() ? r.text().stripLeading() : r.text();
            if (!t.isEmpty()) out.add(new TextRun(t, r.color(), r.font()));
        }
        return out;
    }

    // ------------------------------------------------------------------ Telos Realms Tab-list sections
    // (anonymous subclasses: ModuleManager keys modules by class)

    public static TabStatsHud generalInfo() {
        return new TabStatsHud("General Info", "Server, Location,Soul Points, Glory, Mastery and Guild",
                "General Info", List.of("Server", LOCATION, "Soul Points", "Glory", "Mastery", "Guild"), 0.0, 0.40, true) {};
    }

    public static TabStatsHud characterInfo() {
        return new TabStatsHud("Character Info", "Class, Fame, Tier Points and fragments from the Tab list",
                "Character Info", List.of("Class", "Fame", "Tier Points", "Irradiated Fragments",
                        "Gilded Fragments", "Royal Fragments", "Bloodshot Fragments"), 1.0, 0.40, true) {};
    }

    public static TabStatsHud statsInfo() {
        return new TabStatsHud("Stats Info", "Combat stats (Attack, Speed, Defense ...) from the Tab list",
                "Stats Info", List.of("Attack", "Speed", "Defense", "Health", "Vitality", "Evasion",
                        "Critical Chance", "Critical Damage", "Loot Boost", "Fame Boost"), 0.0, 0.75, false) {
            // the tooltip stat symbols are 13 px wide with built-in padding: a tighter gap looks right
            @Override
            protected int iconGap() {
                return 1;
            }
        };
    }
}
