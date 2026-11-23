package dev.telemetric.module.impl.hud;

import dev.telemetric.Telemetric;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.ServerInfoAdapter;
import dev.telemetric.game.ServerInfoAdapter.TabStat;
import dev.telemetric.game.TelosCharacter;
import dev.telemetric.hud.HudIcon;
import dev.telemetric.hud.LinesHudModule;
import dev.telemetric.hud.TextRun;
import dev.telemetric.module.Category;
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
 * A group of Tab-list stats (one section of the Telos Tab list) with a toggle for each stat.
 * Stats the server isn't publishing right now are skipped in-game and shown as "—" in the editor.
 */
public class TabStatsHud extends LinesHudModule {

    /** Not a Tab-list stat: the location from Telos's bottom-right HUD box (with the biome name as a fallback). */
    static final String LOCATION = "Location";

    private final String title;
    private final BooleanSetting showTitle;
    private final Map<String, BooleanSetting> stats = new LinkedHashMap<>();
    /** Per-stat "show real value" toggles (only for stats with a known formula). */
    private final Map<String, BooleanSetting> real = new LinkedHashMap<>();
    /**
     * For Stats Info: the stat colour lives on the label, so that's kept. The server's plain value colour is dropped
     * so the values follow Theme → HUD Value.
     */
    private final boolean themeValues;

    /** @param serverColorToggle add "Use Server Color" (not for Stats Info, where every stat has its own colour) */
    public TabStatsHud(String name, String description, String title, List<String> labels,
                       double defaultX, double defaultY, boolean serverColorToggle) {
        this(name, description, Category.STATS, title, labels, defaultX, defaultY, serverColorToggle);
    }

    public TabStatsHud(String name, String description, Category category, String title, List<String> labels,
                       double defaultX, double defaultY, boolean serverColorToggle) {
        super(name, description, category, defaultX, defaultY, false);
        this.title = title;
        this.showTitle = add(new BooleanSetting("Show Title", "Show the \"" + title + "\" heading", true));
        if (serverColorToggle) addUseServerColor();
        this.themeValues = !serverColorToggle;
        for (String label : labels) {
            String desc = "Show " + label + " from the Tab list";
            if (StatFormulas.supported(label)) {
                // a stat with a formula gets a dropdown toggle holding its "Show Real %" (the key stays "<label> Real %")
                GroupToggleSetting shown = add(new GroupToggleSetting(label, desc, true).movable());
                stats.put(label, shown);
                real.put(label, add(shown.child(new BooleanSetting(label + " Real %",
                        "Show what " + label + " actually does (wiki formula) after the value", true)
                        .withLabel("Show Real %").attachedTo(shown))));
            } else {
                stats.put(label, add(new BooleanSetting(label, desc, true).movable()));
            }
        }
        realLeft = real.isEmpty() ? null : add(new BooleanSetting("Real % Left",
                "Put the real % in front of the value (\"(17.3%) 50\") instead of after it", false));
    }

    /** Real % in front of the value instead of after it; null when no stat here has a formula. */
    private final BooleanSetting realLeft;

    private boolean realOnLeft() {
        return realLeft != null && realLeft.get();
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
                    // not a Tab stat, so the value gets the HUD box's own server colour; otherwise borrow a sibling stat's colours
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
     * {labelColor, valueColor} of the first Tab stat in this element that has server colours (Server first, since it
     * sits next to Location), or {0, 0} for the theme default.
     */
    private int[] siblingColors() {
        for (Setting<?> setting : getSettingOrder()) {
            String label = setting.getName();
            if (!stats.containsKey(label) || label.equals(LOCATION)) continue;
            Optional<TabStat> s = ServerInfoAdapter.tabStat(label);
            if (s.isEmpty()) continue;
            int lc = s.get().labelColor();
            int vc = s.get().valueColor();
            // prefer the first coloured run (for Server "[test, realm-1]" that's the pink of "test", not the grey bracket)
            for (TextRun r : s.get().valueRuns()) if (r.color() != 0 && !isGrey(r.color())) { vc = r.color(); break; }
            if (vc == 0) {
                for (TextRun r : s.get().valueRuns()) if (r.color() != 0) { vc = r.color(); break; }
            }
            if (lc != 0 || vc != 0) return new int[] {lc, vc};
        }
        return new int[] {0, 0};
    }

    /** The value colour (the green) of Soul Points, Glory or Mastery from Tab, whichever is found first. 0 if none. */
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

    /** True for grey, white or black (all channels within 24 of each other). */
    private static boolean isGrey(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 24;
    }

    private static boolean isFragment(String label) {
        return label.toLowerCase(Locale.ROOT).endsWith("fragments");
    }

    /** Mirrored rows read right to left, so the " (real %)" extra goes in front of the value: "(12%) 5 :Attack". */
    @Override
    protected List<TextRun> mirroredValueRuns(Line line) {
        List<TextRun> runs = line.valueRuns();
        if (real.isEmpty() || runs == null || runs.size() < 2) return runs;
        if (realOnLeft()) {
            // "(12%) 5" mirrored keeps the extra on the outside: "5 (12%) :Attack"
            TextRun first = runs.getFirst();
            if (!first.text().startsWith("(") || !first.text().endsWith(") ")) return runs;
            List<TextRun> out = new ArrayList<>(runs.subList(1, runs.size()));
            out.add(new TextRun(" " + first.text().substring(0, first.text().length() - 1), first.color(), first.font()));
            return out;
        }
        TextRun last = runs.getLast();
        if (!last.text().startsWith(" (") || !last.text().endsWith(")")) return runs;
        List<TextRun> out = new ArrayList<>(runs.size());
        out.add(new TextRun(last.text().substring(1) + " ", last.color(), last.font()));
        out.addAll(runs.subList(0, runs.size() - 1));
        return out;
    }

    private Line line(String label, TabStat s) {
        String key = label.toLowerCase(Locale.ROOT);
        String value = s.value();
        int valueColor = s.valueColor();
        List<TextRun> runs = s.valueRuns();
        HudIcon.Glyph tabIcon = s.icon();

        if (key.equals("mastery") || key.equals("class")) {
            // The server puts the mastery or ruleset (mode) symbol inside the value, so draw exactly that glyph.
            // Class never uses the label-side glyph (it has none, or a class portrait). Without a value glyph,
            // TelosIcons guesses the mode from the text.
            HudIcon.Glyph inValue = firstGlyph(runs);
            if (inValue != null || key.equals("class")) tabIcon = inValue;
            runs = withoutGlyphs(runs);
        }
        if (key.equals("class")) {
            // keep only the class name (drop "Lvl 70" etc.)
            String lower = value.toLowerCase(Locale.ROOT);
            for (String c : TelosCharacter.CLASSES) {
                if (!lower.contains(c)) continue;
                for (TextRun r : runs) {
                    if (r.text().toLowerCase(Locale.ROOT).contains(c) && r.color() != 0) { valueColor = r.color(); break; }
                }
                value = Character.toUpperCase(c.charAt(0)) + c.substring(1);
                runs = List.of();
                break;
            }
        }
        if (themeValues) {
            valueColor = 0;
            List<TextRun> plain = new ArrayList<>(runs.size());
            for (TextRun r : runs) plain.add(new TextRun(r.text(), 0, r.font()));
            runs = plain;
        }
        BooleanSetting realToggle = real.get(label);
        String extra = realToggle != null && realToggle.get() ? StatFormulas.real(label, s.value()) : null;
        if (extra != null) {
            List<TextRun> withExtra = new ArrayList<>(runs.isEmpty() ? List.of(new TextRun(value, valueColor, null)) : runs);
            int muted = Telemetric.get().theme().palette().textMuted();
            if (realOnLeft()) {
                withExtra.addFirst(new TextRun("(" + extra + ") ", muted, null));
                value = "(" + extra + ") " + value;
            } else {
                withExtra.add(new TextRun(" (" + extra + ")", muted, null));
                value = value + " (" + extra + ")";
            }
            runs = withExtra;
        }
        HudIcon icon = TelosIcons.resolve(label, s.value(), tabIcon);
        // icon-only mode drops the name, but only if there's an icon to stand in for it
        boolean hideName = isFragment(label) || (iconOnly != null && iconOnly.get() && icon != null);
        String shown = hideName ? "" : label;
        return new Line(shown, value, s.labelColor(), valueColor, runs, icon, TelosIcons.tinted(label));
    }

    /** The optional "Icon Only" toggle: hides stat names and shows just the icon and the number. */
    private BooleanSetting iconOnly;

    protected final void addIconOnly() {
        iconOnly = add(new BooleanSetting("Icon Only", "Hide the stat names: show only the icon and the number", false));
    }

    /**
     * The first pack-font glyph in the value, as the Tab list draws it. Any code point is accepted, because Telos
     * changes them (e.g. Mastery 6 = U+152CB, seasonal mode = U+18057 in 2026-11), so there's no whitelist.
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

    /** The runs with every pack-font glyph removed (and leading whitespace trimmed). */
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
    // (anonymous subclasses, because ModuleManager keys modules by class)

    public static TabStatsHud generalInfo() {
        return new TabStatsHud("General Info", "Server, Location,Soul Points, Glory, Mastery and Guild",
                "General Info", List.of("Server", LOCATION, "Soul Points", "Glory", "Mastery", "Guild"), 0.0, 0.40, true) {};
    }

    public static TabStatsHud characterInfo() {
        return new TabStatsHud("Character Info", "Class, Fame, Tier Points and fragments from the Tab list", Category.PLAYER,
                "Character Info", List.of("Class", "Fame", "Tier Points", "Irradiated Fragments",
                        "Gilded Fragments", "Royal Fragments", "Bloodshot Fragments"), 1.0, 0.40, true) {};
    }

    public static TabStatsHud statsInfo() {
        return new TabStatsHud("Stats Info", "Combat stats (Attack, Speed, Defense ...) from the Tab list",
                "Stats Info", List.of("Attack", "Speed", "Defense", "Health", "Vitality", "Evasion",
                        "Critical Chance", "Critical Damage", "Loot Boost", "Fame Boost"), 0.0, 0.75, false) {
            { addIconOnly(); }

            // the tooltip stat symbols are 13 px wide with built-in padding, so a tighter gap looks right
            @Override
            protected int iconGap() {
                return 1;
            }
        };
    }
}
