package dev.telemetric.game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain-text view of the open server menu, plus clicks, for walking through Telos' {@code /wiki} (used by
 * {@code tracking/WikiStatsScan}). Only the menu's own slots are read; the player inventory is left out.
 */
public final class WikiMenuAdapter {

    /** One menu slot: item name and lore lines (plain text). */
    public record Slot(String name, List<String> lore) {}

    /** The open menu: its container id (the server opens a new one per page) and its non-empty slots. */
    public record Page(int containerId, Map<Integer, Slot> slots) {
        public Slot at(int slot) {
            return slots.get(slot);
        }
    }

    private static final int PLAYER_SLOTS = 36;

    private WikiMenuAdapter() {}

    /** True if any screen is open (ClickGUI, chat, a menu…). */
    public static boolean screenOpen() {
        return Minecraft.getInstance().screen != null;
    }

    /** The open container menu, or null if none is open. */
    public static Page current() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return null;
        Map<Integer, Slot> out = new HashMap<>();
        try {
            AbstractContainerMenu menu = screen.getMenu();
            int top = menu.slots.size() - PLAYER_SLOTS;
            for (int i = 0; i < top; i++) {
                ItemStack stack = menu.getSlot(i).getItem();
                if (stack.isEmpty()) continue;
                List<String> lore = new ArrayList<>();
                ItemLore l = stack.get(DataComponents.LORE);
                if (l != null) for (Component line : l.lines()) lore.add(line.getString());
                out.put(i, new Slot(stack.getHoverName().getString().strip(), List.copyOf(lore)));
            }
            return new Page(menu.containerId, Map.copyOf(out));
        } catch (RuntimeException e) {
            return null; // the menu changed while we read it, so try again next tick
        }
    }

    /** Left-clicks a slot of the open menu. The server answers with the next page. */
    public static void click(int slot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null || mc.player == null) return;
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return;
        mc.gameMode.handleContainerInput(screen.getMenu().containerId, slot, 0, ContainerInput.PICKUP, mc.player);
    }

    /** Closes whatever is open. A server menu is closed properly, so the server knows about it. */
    public static void closeScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.screen instanceof AbstractContainerScreen<?>) mc.player.closeContainer();
        if (mc.screen != null) mc.setScreen(null);
    }
}
