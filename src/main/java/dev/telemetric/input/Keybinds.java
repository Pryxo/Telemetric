package dev.telemetric.input;

import com.mojang.blaze3d.platform.InputConstants;
import dev.telemetric.Telemetric;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/** MC ADAPTER: vanilla key mappings (they show up in Options → Controls → "Telemetric"). */
public final class Keybinds {

    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Telemetric.MOD_ID, "main"));

    private static KeyMapping openGui;
    private static KeyMapping worldMap;

    private Keybinds() {}

    public static void register() {
        openGui = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.telemetric.open_gui", InputConstants.Type.KEYSYM, InputConstants.KEY_RSHIFT, CATEGORY));
        worldMap = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.telemetric.world_map", InputConstants.Type.KEYSYM, InputConstants.KEY_M, CATEGORY));
    }

    /** Consumes the queued presses and returns true if the world map key was pressed since the last call. */
    public static boolean consumeWorldMap() {
        boolean pressed = false;
        while (worldMap != null && worldMap.consumeClick()) pressed = true;
        return pressed;
    }

    /** True if {@code key} is the world map key (to close the map with it). */
    public static boolean isWorldMapKey(net.minecraft.client.input.KeyEvent key) {
        return worldMap != null && worldMap.matches(key);
    }

    /** Consumes the queued presses and returns true if the GUI key was pressed since the last call. */
    public static boolean consumeOpenGui() {
        boolean pressed = false;
        while (openGui != null && openGui.consumeClick()) pressed = true;
        return pressed;
    }
}
