package dev.telemetric.game;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.Locale;
import java.util.Map;

/**
 * Player → Keybinds → Realm Selector region switch. Runs {@code /locations} and clicks the region's item in that menu
 * (slots 0-3 = NA, 5-8 = EU, 9-12 = SG). The server does the transfer.
 */
public final class RegionSwitchAdapter {

    private RegionSwitchAdapter() {}

    /** First {@code /locations} slot per API region id. */
    private static final Map<String, Integer> SLOTS = Map.of("ny", 0, "de", 5, "sg", 9);
    /** The {@code /locations} menu has to open within this time. */
    private static final long MENU_MS = 5_000;

    /** Waiting for the {@code /locations} menu: the slot to click, -1 if not waiting. */
    private static int pendingSlot = -1;
    private static String pendingHost;
    private static long menuAsked;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    /** True if {@code regionId} (API id: ny / de / sg) has a {@code /locations} slot. */
    public static boolean canSwitchTo(String regionId) {
        return regionId != null && SLOTS.containsKey(regionId);
    }

    /** Opens {@code /locations} and clicks the region's item once the menu is there. Client thread only. */
    public static void switchTo(String regionId) {
        if (!canSwitchTo(regionId) || !PlayerAdapter.isInWorld()) return;
        pendingSlot = SLOTS.get(regionId);
        pendingHost = regionId + ".telosrealms.com";
        menuAsked = System.currentTimeMillis();
        PlayerAdapter.sendCommand("locations");
    }

    private static void tick() {
        if (pendingSlot < 0) return;
        if (System.currentTimeMillis() - menuAsked > MENU_MS) {
            pendingSlot = -1;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof AbstractContainerScreen<?> acs) || mc.gameMode == null || mc.player == null) return;
        AbstractContainerMenu menu = acs.getMenu();
        if (pendingSlot >= menu.slots.size()) return;
        // only click once the slot holds that region's item (its lore names the host)
        ItemStack stack = menu.getSlot(pendingSlot).getItem();
        if (stack.isEmpty() || !lore(stack).contains(pendingHost)) return;
        mc.gameMode.handleContainerInput(menu.containerId, pendingSlot, 0, ContainerInput.PICKUP, mc.player);
        pendingSlot = -1;
    }

    /** All lore lines in lower case, joined. */
    private static String lore(ItemStack stack) {
        ItemLore l = stack.get(DataComponents.LORE);
        if (l == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Component line : l.lines()) sb.append(line.getString()).append(' ');
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
