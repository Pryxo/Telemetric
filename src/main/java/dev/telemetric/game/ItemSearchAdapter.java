package dev.telemetric.game;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MC ADAPTER: reads the facts the stash search can match on for an item. That's the plain name, tooltip text, Telos
 * rarity (last segment of {@code minecraft:tooltip_style}, e.g. {@code telos:untiered/bloodshot}), Telos item type
 * ({@code custom_data.PublicBukkitValues."realm:item_type"}, e.g. {@code telos:skin/cherub_staff}), the vanilla item
 * id and tags, and the "N Tier Points" lore number (tiered items, style {@code telos:tiered/8}). Results are cached per
 * stack (ItemStack uses identity equality).
 */
public final class ItemSearchAdapter {

    /** The searchable facts of one item. Every string is lower case with the Telos font glyphs cleaned out. */
    public record Info(String name, String tooltip, String rarity, String typePath, List<String> tags, int tierPoints) {}

    private static final Map<ItemStack, Info> CACHE = new WeakHashMap<>();
    /** The lore line "12 Tier Points" (on tiered items, Tier N gives N points). */
    private static final Pattern TIER_POINTS = Pattern.compile("(\\d+) tier points?");

    private ItemSearchAdapter() {}

    public static Info info(GameItem item) {
        if (item == null) return null;
        return CACHE.computeIfAbsent(item.stack(), ItemSearchAdapter::read);
    }

    private static Info read(ItemStack stack) {
        String name = clean(stack.getHoverName().getString()).replace("[", "").replace("]", "").trim();

        StringBuilder tooltip = new StringBuilder(name);
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) tooltip.append('\n').append(clean(line.getString()));
        }

        String rarity = "";
        Identifier style = stack.get(DataComponents.TOOLTIP_STYLE);
        if (style != null && !style.getPath().equals("background")) rarity = style.getPath();

        String typePath = "";
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            CompoundTag bukkit = data.copyTag().getCompoundOrEmpty("PublicBukkitValues");
            typePath = bukkit.getStringOr("realm:item_type", "").toLowerCase(Locale.ROOT);
            int colon = typePath.indexOf(':');
            if (colon >= 0) typePath = typePath.substring(colon + 1);
        }

        List<String> tags = new ArrayList<>();
        tags.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        stack.typeHolder().tags().forEach(t -> tags.add(t.location().toString()));
        Identifier model = stack.get(DataComponents.ITEM_MODEL);
        if (model != null) tags.add(model.toString());

        Matcher tier = TIER_POINTS.matcher(tooltip);
        int tierPoints = tier.find() ? Integer.parseInt(tier.group(1)) : -1;

        return new Info(name, tooltip.toString(), rarity.toLowerCase(Locale.ROOT), typePath, List.copyOf(tags), tierPoints);
    }

    /** Lower case, with Telos spacing and icon glyphs (private-use and supplementary code points) removed and spaces collapsed. */
    static String clean(String s) {
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (cp >= 0x10000 || (cp >= 0xE000 && cp <= 0xF8FF)) return;
            b.appendCodePoint(Character.toLowerCase(cp));
        });
        return b.toString().replaceAll("\\s+", " ");
    }
}
