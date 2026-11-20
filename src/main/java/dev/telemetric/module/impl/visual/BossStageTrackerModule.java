package dev.telemetric.module.impl.visual;

import dev.telemetric.game.BossStageAdapter;
import dev.telemetric.game.BossStageAdapter.Kind;
import dev.telemetric.game.BossStageAdapter.Stage;
import dev.telemetric.game.WorldLabelAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.ModeSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.render.BadgeText;

import java.util.ArrayList;
import java.util.List;

/**
 * A floating badge on the ground under each boss that says whether it can be damaged right now (Immune / Shielded /
 * Vulnerable). The stage is read from the boss's status text display ({@link BossStageAdapter}), and the badge is
 * drawn in the world with a badge font ({@link WorldLabelAdapter}).
 */
public final class BossStageTrackerModule extends Module {

    private final ModeSetting style = add(new ModeSetting("Label Style",
            "Pill / Ribbon = badge font, Text = plain Minecraft text", "Pill", "Pill", "Ribbon", "Text"));
    private final BooleanSetting compact = add(new BooleanSetting("Compact",
            "Badge styles: a small blank badge in the stage colour instead of the word", false));
    private final BooleanSetting bold = add(new BooleanSetting("Bold", "Text style: bold label", false));
    private final BooleanSetting underline = add(new BooleanSetting("Underline", "Text style: underlined label", false));
    private final BooleanSetting showDamageable = add(new BooleanSetting("Show Vulnerable",
            "Also show a badge while the boss can be damaged", true));
    private final ColorSetting immuneColor = add(new ColorSetting("Immune Color", "Badge colour while immune",
            0xFF1757A7, false));
    private final ColorSetting shieldedColor = add(new ColorSetting("Shielded Color", "Badge colour while shielded",
            0xFFA167E2, false));
    private final ColorSetting damageableColor = add(new ColorSetting("Vulnerable Color",
            "Badge colour while the boss can be damaged", 0xFF5AD123, false));
    private final NumberSetting size = add(new NumberSetting("Label Size", "Badge height in blocks",
            0.2, 0.08, 0.6, 0.02));
    private final NumberSetting offset = add(new NumberSetting("Height Offset",
            "Blocks above the ground under the boss (negative = into the floor)", 0.05, -1.0, 3.0, 0.05));
    private final BooleanSetting throughWalls = add(new BooleanSetting("Through Walls",
            "Badge stays visible behind blocks", true));
    private final NumberSetting range = add(new NumberSetting("Range", "Track bosses up to this far away",
            48, 8, 128, 4).withSuffix(" blocks"));

    private volatile List<Stage> stages = List.of();

    public BossStageTrackerModule() {
        super("Boss Stage Tracker", "Shows under each boss whether it is immune, shielded or vulnerable",
                Category.VISUAL);
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

    private List<WorldLabelAdapter.Label> labels(float partialTick) {
        List<Stage> current = stages;
        if (!isEnabled() || current.isEmpty()) return List.of();
        BadgeText.Style font = switch (style.get()) {
            case "Ribbon" -> BadgeText.Style.RIBBON;
            case "Text" -> BadgeText.Style.PLAIN;
            default -> BadgeText.Style.PILL;
        };
        boolean blank = compact.get() && font != BadgeText.Style.PLAIN;
        List<WorldLabelAdapter.Label> out = new ArrayList<>(current.size());
        for (Stage s : current) {
            if (s.kind() == Kind.DAMAGEABLE && !showDamageable.get()) continue;
            double[] xz = BossStageAdapter.position(s.entityId(), partialTick);
            if (xz == null) continue;
            String text = blank ? "" : name(s.kind());
            out.add(new WorldLabelAdapter.Label(xz[0], s.groundY() + offset.get(), xz[1], text, font, size.get(),
                    color(s.kind()), throughWalls.get(), true, bold.get(), underline.get()));
        }
        return out;
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
