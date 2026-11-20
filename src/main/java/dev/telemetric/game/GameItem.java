package dev.telemetric.game;

import net.minecraft.world.item.ItemStack;

/**
 * MC ADAPTER: an opaque handle to an item stack, so UI and module code can pass items to {@code Render2D} without
 * importing Minecraft. Only adapters call {@link #stack()}.
 */
public final class GameItem {

    private final ItemStack stack;

    GameItem(ItemStack stack) {
        this.stack = stack;
    }

    public ItemStack stack() { return stack; }
}
