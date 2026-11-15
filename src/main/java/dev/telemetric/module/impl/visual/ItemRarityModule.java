package dev.telemetric.module.impl.visual;

import dev.telemetric.data.TelosData;
import dev.telemetric.game.ItemRarityAdapter;
import dev.telemetric.game.ItemRarityAdapter.Shape;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.EnumSetting;
import dev.telemetric.setting.impl.MultiChoiceSetting;
import dev.telemetric.setting.impl.NumberSetting;

import java.util.HashSet;
import java.util.Set;

/**
 * Marks items by rarity with a soft coloured shape behind them in inventories and the hotbar. Rarities and their
 * colours come from {@code data/telos/rarities.json}, so a new rarity only needs a line there.
 */
public final class ItemRarityModule extends Module {

    private final EnumSetting<ShapeChoice> shape = add(new EnumSetting<>("Shape", "Shape behind the item",
            ShapeChoice.CIRCLE));
    private final NumberSetting opacity = add(new NumberSetting("Opacity", "How strong the colour is",
            35, 5, 100, 5).withSuffix("%"));
    private final BooleanSetting hotbar = add(new BooleanSetting("Hotbar", "Also mark items in the hotbar", true));
    private final MultiChoiceSetting hidden = add(new MultiChoiceSetting("Hidden Rarities",
            "Rarities that get no mark", () -> TelosData.RARITIES.get().labels()));

    public enum ShapeChoice implements EnumSetting.Nameable {
        CIRCLE("Circle", Shape.CIRCLE), SQUARE("Square", Shape.SQUARE), FRAME("Frame", Shape.FRAME);

        private final String label;
        private final Shape shape;

        ShapeChoice(String label, Shape shape) {
            this.label = label;
            this.shape = shape;
        }

        @Override
        public String displayName() {
            return label;
        }
    }

    public ItemRarityModule() {
        super("Item Rarity", "Coloured rarity mark behind items in inventories and the hotbar", Category.VISUAL);
        syncOn(this::sync, shape, opacity, hotbar, hidden);
        TelosData.RARITIES.onChange(t -> sync()); // hidden labels → keys of the current table
    }

    private void sync() {
        if (!isEnabled()) {
            ItemRarityAdapter.setStyle(null);
            return;
        }
        Set<String> keys = new HashSet<>();
        for (TelosData.Rarity r : TelosData.RARITIES.get().byKey().values()) {
            if (hidden.contains(r.label())) keys.add(r.key());
        }
        ItemRarityAdapter.setStyle(new ItemRarityAdapter.Style(shape.get().shape,
                (int) Math.round(opacity.get() * 2.55), Set.copyOf(keys), hotbar.get()));
    }
}
