package dev.telemetric.game;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MC ADAPTER — an item to draw from just its item model id ({@code telos:material/boss/anubis}), e.g. boss or
 * drop icons from the data tables. Stacks are cached per model; a bad id gives null.
 */
public final class ItemIcons {

    private static final Map<String, GameItem> CACHE = new ConcurrentHashMap<>();
    private static final GameItem NONE = new GameItem(ItemStack.EMPTY);

    private ItemIcons() {}

    /** Drawable item for this model id, or null if the id is empty / invalid. */
    public static GameItem of(String model) {
        if (model == null || model.isEmpty()) return null;
        GameItem item = CACHE.computeIfAbsent(model, m -> {
            Identifier id = Identifier.tryParse(m);
            if (id == null) return NONE;
            ItemStack stack = new ItemStack(Items.PAPER);
            stack.set(DataComponents.ITEM_MODEL, id);
            return new GameItem(stack);
        });
        return item == NONE ? null : item;
    }
}
