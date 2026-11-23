package dev.telemetric.module.impl.visual;

import dev.telemetric.Telemetric;
import dev.telemetric.game.BossStageAdapter;
import dev.telemetric.game.BossStageAdapter.Kind;
import dev.telemetric.game.BossStageAdapter.Stage;
import dev.telemetric.game.WorldLabelAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.module.impl.hud.DpsMeterHud;
import dev.telemetric.setting.Setting;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.GroupSetting;
import dev.telemetric.setting.impl.GroupToggleSetting;
import dev.telemetric.setting.impl.ModeSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.render.BadgeText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A floating badge on the ground under each boss that says whether it can be damaged right now (Immune / Shielded /
 * Vulnerable). The stage is read from the boss's status text display ({@link BossStageAdapter}), and the badge is
 * drawn in the world with a badge font ({@link WorldLabelAdapter}).
 * <p>
 * The label is a stack of lines — Stage Label, Health and the DPS Meter's toggled stats — whose order the player
 * drags in the ClickGUI (movable settings, like Stats Info). DPS only shows on the nearest boss.
 */
public final class BossStageTrackerModule extends Module {

    static final String REST_HP = "Rest HP", PERCENT_HP = "% HP", CURRENT_MAX = "Current / Max";

    private final GroupSetting styleGroup = add(new GroupSetting("Style", "Look of the label"));
    private final ModeSetting style = add(styleGroup.child(new ModeSetting("Label Style",
            "Pill / Ribbon = badge font, Text = plain Minecraft text", "Pill", "Pill", "Ribbon", "Text")));
    private final BooleanSetting compact = add(styleGroup.child(new BooleanSetting("Compact",
            "Badge styles: a small blank badge in the stage colour instead of the word", false)
            .visibleWhen(() -> !style.is("Text"))));
    private final BooleanSetting bold = add(styleGroup.child(new BooleanSetting("Bold", "Text style: bold label", false)
            .visibleWhen(() -> style.is("Text"))));
    private final BooleanSetting underline = add(styleGroup.child(new BooleanSetting("Underline",
            "Text style: underlined label", false).visibleWhen(() -> style.is("Text"))));

    private final GroupSetting colors = add(new GroupSetting("Colors", "Colours of the label lines"));
    private final ColorSetting immuneColor = add(colors.child(new ColorSetting("Immune Color", "Badge colour while immune",
            0xFF1757A7, false)));
    private final ColorSetting shieldedColor = add(colors.child(new ColorSetting("Shielded Color",
            "Badge colour while shielded", 0xFFA167E2, false)));
    private final ColorSetting damageableColor = add(colors.child(new ColorSetting("Vulnerable Color",
            "Badge colour while the boss can be damaged", 0xFF5AD123, false)));
    private final ColorSetting healthColor = add(colors.child(new ColorSetting("Health Color", "Colour of the Health line",
            0xFFE5484D, false)));
    private final ColorSetting dpsColor = add(colors.child(new ColorSetting("DPS Color",
            "Colour of the DPS lines (Max / Last Hit use their damage colour when the DPS Meter's Damage Colors is on)",
            0xFFF5A524, false)));

    private final GroupSetting sizeGroup = add(new GroupSetting("Size & Position", "Size and height of the label"));
    private final NumberSetting size = add(sizeGroup.child(new NumberSetting("Label Size", "Badge height in blocks",
            0.2, 0.08, 0.6, 0.02)));
    private final NumberSetting infoSize = add(sizeGroup.child(new NumberSetting("Info Size",
            "Size of the Health and DPS lines next to the Label Size", 0.8, 0.4, 1.5, 0.05).withSuffix("x")));
    private final NumberSetting spacing = add(sizeGroup.child(new NumberSetting("Line Spacing",
            "Gap between the lines, in line heights", 0.35, 0, 2, 0.05)));
    private final NumberSetting offset = add(sizeGroup.child(new NumberSetting("Height Offset",
            "Blocks above the ground under the boss (negative = into the floor)", 0.05, -1.0, 3.0, 0.05)));

    private final BooleanSetting showDamageable = add(new BooleanSetting("Show Vulnerable",
            "Also show the stage badge while the boss can be damaged", true));
    private final BooleanSetting throughWalls = add(new BooleanSetting("Through Walls",
            "Label stays visible behind blocks", true));
    private final NumberSetting range = add(new NumberSetting("Range", "Track bosses up to this far away",
            48, 8, 128, 4).withSuffix(" blocks"));

    // Label lines, top to bottom in the user's drag order
    private final BooleanSetting stageLine = add(new BooleanSetting("Stage Label",
            "The Immune / Shielded / Vulnerable badge (drag to reorder the label lines)", true).movable());
    private final GroupToggleSetting healthLine = add(new GroupToggleSetting("Health",
            "Boss health on the label (drag to reorder; right-click for the format)", false).movable());
    private final ModeSetting healthFormat = add(healthLine.child(new ModeSetting("Health Format",
            "Rest HP: health left. % HP: health in percent. Current / Max: both numbers",
            REST_HP, REST_HP, PERCENT_HP, CURRENT_MAX).withLabel("Format").attachedTo(healthLine)));
    private final GroupToggleSetting dpsLine = add(new GroupToggleSetting("DPS",
            "The DPS Meter's toggled stats on the nearest boss's label (needs the DPS Meter on; drag to reorder)", false)
            .movable());
    private final BooleanSetting hideDpsHud = add(dpsLine.child(new BooleanSetting("Hide DPS HUD",
            "Hide the DPS Meter HUD element while a boss is tracked here (the stats are on the label instead)", true)
            .attachedTo(dpsLine)));

    private volatile List<Stage> stages = List.of();

    public BossStageTrackerModule() {
        super("Boss Stage Tracker", "Shows under each boss whether it is immune, shielded or vulnerable",
                Category.COMBAT);
        WorldLabelAdapter.addSource(this::labels);
    }

    @Override
    public void onTick() {
        stages = BossStageAdapter.scan(range.get());
    }

    @Override
    protected void onDisable() {
        stages = List.of();
    }

    /** True while the DPS Meter HUD element should hide: a boss is tracked and its label carries the DPS stats. */
    public boolean hidesDpsHud() {
        return isEnabled() && dpsLine.get() && hideDpsHud.get() && !stages.isEmpty();
    }

    /** One label line before it's stacked: text, font, height (blocks) and colour. */
    private record Row(String text, BadgeText.Style font, double height, int argb, boolean stage) {}

    private List<WorldLabelAdapter.Label> labels(float partialTick) {
        List<Stage> current = stages;
        if (!isEnabled() || current.isEmpty()) return List.of();
        BadgeText.Style font = switch (style.get()) {
            case "Ribbon" -> BadgeText.Style.RIBBON;
            case "Text" -> BadgeText.Style.PLAIN;
            default -> BadgeText.Style.PILL;
        };
        boolean blank = compact.get() && font != BadgeText.Style.PLAIN;
        double infoHeight = size.get() * infoSize.get();
        List<DpsMeterHud.Row> dps = dpsLine.get()
                ? Telemetric.get().modules().get(DpsMeterHud.class).statRows() : List.of();

        List<WorldLabelAdapter.Label> out = new ArrayList<>();
        for (int i = 0; i < current.size(); i++) {
            Stage s = current.get(i);
            double[] xz = BossStageAdapter.position(s.entityId(), partialTick);
            if (xz == null) continue;
            List<Row> rows = new ArrayList<>();
            for (Setting<?> line : getSettingOrder()) {
                if (line == stageLine && stageLine.get()) {
                    if (s.kind() == Kind.DAMAGEABLE && !showDamageable.get()) continue;
                    rows.add(new Row(blank ? "" : name(s.kind()), font, size.get(), color(s.kind()), true));
                } else if (line == healthLine && healthLine.get()) {
                    String hp = healthText(s);
                    if (hp != null) rows.add(new Row(hp, font, infoHeight, healthColor.get(), false));
                } else if (line == dpsLine && i == 0) { // DPS is global, so it only goes on the nearest boss
                    for (DpsMeterHud.Row r : dps) {
                        rows.add(new Row(r.label() + " " + r.value(), font, infoHeight,
                                r.argb() != 0 ? r.argb() : dpsColor.get(), false));
                    }
                }
            }
            // stack bottom-up: the last row stands on the ground, each row above it one line (+ spacing) higher
            double y = s.groundY() + offset.get();
            for (int r = rows.size() - 1; r >= 0; r--) {
                Row row = rows.get(r);
                out.add(new WorldLabelAdapter.Label(xz[0], y, xz[1], row.text(), row.font(), row.height(), row.argb(),
                        throughWalls.get(), true, bold.get(), underline.get()));
                y += row.height() * (1 + spacing.get());
            }
        }
        return out;
    }

    /** The Health line in the chosen format, or null when the boss's health isn't known. */
    private String healthText(Stage s) {
        boolean numbers = s.hasHp();
        if (healthFormat.is(PERCENT_HP) || !numbers) {
            return s.percent() < 0 ? null : String.format(Locale.ROOT, "%.0f%%", s.percent());
        }
        if (healthFormat.is(CURRENT_MAX)) return compact(s.hp()) + "/" + compact(s.maxHp());
        return compact(s.hp()) + " HP";
    }

    /** "950", "12.3k", "1.25M" (no thousands commas: the badge fonts don't have them). */
    static String compact(double v) {
        if (v >= 1e9) return trim(String.format(Locale.ROOT, "%.2f", v / 1e9)) + "B";
        if (v >= 1e6) return trim(String.format(Locale.ROOT, "%.2f", v / 1e6)) + "M";
        if (v >= 1e4) return trim(String.format(Locale.ROOT, "%.1f", v / 1e3)) + "k";
        return String.format(Locale.ROOT, "%.0f", v);
    }

    private static String trim(String s) {
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    private static String name(Kind kind) {
        return switch (kind) {
            case IMMUNE -> "Immune";
            case SHIELDED -> "Shielded";
            case DAMAGEABLE -> "Vulnerable";
        };
    }

    private int color(Kind kind) {
        return switch (kind) {
            case IMMUNE -> immuneColor.get();
            case SHIELDED -> shieldedColor.get();
            case DAMAGEABLE -> damageableColor.get();
        };
    }
}
