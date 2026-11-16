package dev.telemetric.game;

import dev.telemetric.Telemetric;
import dev.telemetric.data.TelosData;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * MC ADAPTER — Item Rarity: a soft rarity-coloured shape behind items in inventory slots and the hotbar. The rarity is
 * the last part of the item's tooltip style ({@code telos:untiered/bloodshot}), its colour comes from
 * {@code data/Extractor/rarities.json}. Called by {@code ContainerSlotMixin} / {@code HotbarSlotMixin} before the item.
 */
public final class ItemRarityAdapter {

    /** Background shape → texture in {@code assets/telemetric/textures/gui/rarity/} (white, tinted per rarity). */
    public enum Shape {
        CIRCLE("circle"), SQUARE("square"), FRAME("frame");

        final Identifier texture;

        Shape(String file) {
            this.texture = Identifier.fromNamespaceAndPath(Telemetric.MOD_ID, "textures/gui/rarity/" + file + ".png");
        }
    }

    /**
     * @param alpha  0..255
     * @param hidden keys ({@code rarities.json}) of rarities not to mark
     */
    public record Style(Shape shape, int alpha, Set<String> hidden, boolean hotbar) {}

    private static final int TEX = 64;
    private static volatile Style style;
    /** Tooltip style → rarity (null = none), rebuilt when the rarity table changes. */
    private static final Map<Identifier, TelosData.Rarity> RARITY = new HashMap<>();
    private static TelosData.Rarities rarityFor;

    private ItemRarityAdapter() {}

    /** Null = off. */
    public static void setStyle(Style s) {
        style = s;
    }

    public static void drawSlot(GuiGraphicsExtractor g, ItemStack stack, int x, int y, boolean hotbar) {
        Style s = style;
        if (s == null || stack == null || stack.isEmpty() || (hotbar && !s.hotbar())) return;
        Identifier tooltipStyle = stack.get(DataComponents.TOOLTIP_STYLE);
        if (tooltipStyle == null || !ServerGate.onTelos()) return;
        int rgb = colorOf(tooltipStyle, s.hidden());
        if (rgb < 0) return;
        g.blit(RenderPipelines.GUI_TEXTURED, s.shape().texture, x, y, 0f, 0f, 16, 16, TEX, TEX, TEX, TEX,
                (s.alpha() << 24) | rgb);
    }

    /** Rarity colour (0xRRGGBB) of an item with this tooltip style, or -1 for none / a hidden rarity. */
    private static int colorOf(Identifier tooltipStyle, Set<String> hidden) {
        TelosData.Rarities table = TelosData.RARITIES.get();
        if (table != rarityFor) {
            RARITY.clear();
            rarityFor = table;
        }
        TelosData.Rarity r;
        if (RARITY.containsKey(tooltipStyle)) r = RARITY.get(tooltipStyle);
        else RARITY.put(tooltipStyle, r = table.forStyle(tooltipStyle.getPath()));
        return r == null || hidden.contains(r.key()) ? -1 : r.rgb();
    }
}
