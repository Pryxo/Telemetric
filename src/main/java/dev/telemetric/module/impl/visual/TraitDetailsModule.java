package dev.telemetric.module.impl.visual;

import dev.telemetric.data.TelosData;
import dev.telemetric.data.TraitTable;
import dev.telemetric.game.TooltipAdapter;
import dev.telemetric.game.TooltipAdapter.Insert;
import dev.telemetric.input.InputUtil;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;

import java.util.ArrayList;
import java.util.List;

/**
 * Visual → Trait Details: under each unique trait in an item's lore ({@code <tier glyph> <name>}) it adds the trait's
 * description with the numbers for that tier, from the wiki's trait table ({@code tables/traits.json}). It has no data
 * of its own, so new traits show up when the table updates.
 */
public class TraitDetailsModule extends Module {

    private final BooleanSetting requireShift = add(new BooleanSetting("Hold Shift",
            "Only show the descriptions while Shift is held", true));

    public TraitDetailsModule() {
        super("Trait Details", "Trait descriptions with their tier's numbers in item tooltips", Category.PLAYER);
        TooltipAdapter.addEditor(this::describe);
    }

    private List<Insert> describe(List<String> lines) {
        if (!isEnabled() || (requireShift.get() && !InputUtil.isShiftDown())) return List.of();
        TraitTable table = TelosData.TRAITS.get();
        List<Insert> out = new ArrayList<>(3);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            int tier = TraitTable.tierOfGlyph(line);
            if (tier < 0) continue;
            String name = line.substring(Character.charCount(line.codePointAt(0))).strip();
            TraitTable.Trait trait = table.get(name);
            String letter = table.tier(tier);
            if (trait != null && letter != null) out.add(new Insert(i, trait.describe(letter), name));
        }
        return out;
    }
}
