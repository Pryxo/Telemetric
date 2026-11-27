package dev.telemetric.game;

import dev.telemetric.data.TraitTable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.equipment.Equippable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Telos combat data: the held and worn items (classified once per stack and cached), the server's item-cooldown
 * packets ({@code ClientPacketListenerMixin}) and the particles combat features read.
 * <p>
 * Telos puts weapons, abilities and armor abilities on the vanilla item cooldown, so a cooldown packet
 * ({@code cooldownGroup}, {@code duration} in ticks, 0 means over) gives the exact start and end of every cooldown.
 */
public final class CombatAdapter {

    /** What a Telos item is, read from its item model id (or its Bukkit custom data). */
    public enum Kind { WEAPON, ABILITY, ARMOR, OTHER }

    /** Armor slots in HUD order. */
    public enum ArmorSlot {
        HEAD("Helmet", EquipmentSlot.HEAD), CHEST("Chestplate", EquipmentSlot.CHEST),
        LEGS("Leggings", EquipmentSlot.LEGS), FEET("Boots", EquipmentSlot.FEET);

        private final String label;
        private final EquipmentSlot vanilla;

        ArmorSlot(String label, EquipmentSlot vanilla) {
            this.label = label;
            this.vanilla = vanilla;
        }

        public String label() { return label; }
    }

    /**
     * @param item  drawable handle (a private copy, safe to keep after the slot changes)
     * @param name  plain display name (kerning and bracket glyphs removed)
     * @param group vanilla cooldown group id, e.g. {@code "minecraft:carrot_on_a_stick"} or {@code "telos:..."}
     * @param range    effective range from the lore, e.g. {@code "Range » 8 + 2"} gives 10, or -1 if none
     * @param cooldown ability cooldown in seconds from the lore, e.g. {@code "Cooldown » 30s"}, or -1 if none
     * @param slot     armor slot for {@link Kind#ARMOR}, otherwise null
     * @param traits   the lore lines that start with a tier glyph ({@code <glyph> Primed}), in lore order
     */
    public record ItemInfo(GameItem item, Kind kind, String name, String group, float range, float cooldown,
                           ArmorSlot slot, List<ItemTrait> traits) {
        /** Lower-case name for table lookups. */
        public String nameKey() { return name.toLowerCase(Locale.ROOT); }

        /** True if it has an ability you can activate (a cooldown line in its lore). */
        public boolean hasAbility() { return cooldown > 0; }

        /** The trait with this name (case-insensitive), or null. */
        public ItemTrait trait(String traitName) {
            for (ItemTrait t : traits) if (t.name().equalsIgnoreCase(traitName)) return t;
            return null;
        }
    }

    /**
     * A tiered lore line of an item.
     *
     * @param name the text after the tier glyph, e.g. {@code "Primed"} (stat traits give e.g. {@code "+4.5%"})
     * @param tier tier index, 0 is the lowest ({@link TraitTable#tierOfGlyph})
     */
    public record ItemTrait(String name, int tier) {}

    /** A cooldown packet. {@code ticks} is the full duration, and 0 means the cooldown was cleared. */
    public record CooldownEvent(String group, int ticks) {}

    public enum ParticleKind { ELECTRIC_SPARK, DUST }

    /** Receives combat particles (client thread). */
    @FunctionalInterface
    public interface ParticleListener {
        void particle(ParticleKind kind, double x, double y, double z);
    }

    /** "Range » 8", "Range: 6.5 + 2", "Range » 8 - 1". Group 1 is the base, groups 2 and 3 the trait modifier. */
    private static final Pattern RANGE = Pattern.compile(
            "Range\\s*[»:]\\s*(-?\\d+(?:\\.\\d+)?)(?:\\s*([+-])\\s*(\\d+(?:\\.\\d+)?))?");
    /** "Cooldown » 30s", "Cooldown: 2.5s". */
    private static final Pattern COOLDOWN = Pattern.compile("Cooldown\\s*[»:]\\s*(\\d+(?:\\.\\d+)?)");
    private static final int MAX_QUEUED = 256;

    /** Classification per stack instance ({@link ItemStack} uses identity equality). Entries die with the stack. */
    private static final Map<ItemStack, ItemInfo> CACHE = new WeakHashMap<>();
    private static final ArrayDeque<CooldownEvent> COOLDOWNS = new ArrayDeque<>();
    private static final List<ParticleListener> PARTICLE_LISTENERS = new CopyOnWriteArrayList<>();

    private CombatAdapter() {}

    private static LocalPlayer player() {
        return Minecraft.getInstance().player;
    }

    /** Main hand item, or null if it's empty or we're not in a world. */
    public static ItemInfo mainHand() {
        LocalPlayer p = player();
        return p == null ? null : info(p, p.getMainHandItem());
    }

    public static ItemInfo offHand() {
        LocalPlayer p = player();
        return p == null ? null : info(p, p.getOffhandItem());
    }

    /** The held item of {@code kind} (main hand first, then off hand), or null. */
    public static ItemInfo held(Kind kind) {
        ItemInfo main = mainHand();
        if (main != null && main.kind() == kind) return main;
        ItemInfo off = offHand();
        return off != null && off.kind() == kind ? off : null;
    }

    public static ItemInfo worn(ArmorSlot slot) {
        LocalPlayer p = player();
        return p == null ? null : info(p, p.getItemBySlot(slot.vanilla));
    }

    /** Armor pieces with an ability in the inventory (hotbar + main, not worn), in inventory order. */
    public static List<ItemInfo> inventoryArmor() {
        LocalPlayer p = player();
        if (p == null) return List.of();
        List<ItemInfo> out = new ArrayList<>();
        for (ItemStack stack : p.getInventory().getNonEquipmentItems()) {
            ItemInfo info = info(p, stack);
            if (info != null && info.kind() == Kind.ARMOR && info.slot() != null && info.hasAbility()) out.add(info);
        }
        return out;
    }

    /**
     * Every item on the player that has a cooldown (weapons, ability items and anything with a {@code Cooldown »}
     * lore line): hands, worn armor, then the inventory, the first of each name only.
     */
    public static List<ItemInfo> cooldownItems() {
        LocalPlayer p = player();
        if (p == null) return List.of();
        List<ItemStack> stacks = new ArrayList<>();
        stacks.add(p.getMainHandItem());
        stacks.add(p.getOffhandItem());
        for (ArmorSlot slot : ArmorSlot.values()) stacks.add(p.getItemBySlot(slot.vanilla));
        stacks.addAll(p.getInventory().getNonEquipmentItems());
        List<ItemInfo> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ItemStack stack : stacks) {
            ItemInfo info = info(p, stack);
            if (info == null || info.name().isBlank()) continue;
            boolean cools = info.hasAbility() || info.kind() == Kind.WEAPON || info.kind() == Kind.ABILITY;
            if (cools && seen.add(info.nameKey())) out.add(info);
        }
        return out;
    }

    /** The first item on the player (hands, armor, inventory) that uses cooldown {@code group}, or null. */
    public static ItemInfo findByGroup(String group) {
        LocalPlayer p = player();
        if (p == null || group == null) return null;
        ItemInfo hit = matchGroup(p, p.getMainHandItem(), group);
        if (hit == null) hit = matchGroup(p, p.getOffhandItem(), group);
        for (ArmorSlot slot : ArmorSlot.values()) {
            if (hit == null) hit = matchGroup(p, p.getItemBySlot(slot.vanilla), group);
        }
        if (hit != null) return hit;
        for (ItemStack stack : p.getInventory().getNonEquipmentItems()) {
            hit = matchGroup(p, stack, group);
            if (hit != null) return hit;
        }
        return null;
    }

    /** True while the main hand item is on the vanilla cooldown (live, client-side). */
    public static boolean mainHandOnCooldown() {
        LocalPlayer p = player();
        return p != null && p.getCooldowns().isOnCooldown(p.getMainHandItem());
    }

    private static ItemInfo matchGroup(LocalPlayer p, ItemStack stack, String group) {
        ItemInfo info = info(p, stack);
        return info != null && info.group().equals(group) ? info : null;
    }

    private static ItemInfo info(LocalPlayer p, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        ItemInfo cached = CACHE.get(stack);
        if (cached == null) {
            cached = classify(p, stack);
            CACHE.put(stack, cached);
        }
        return cached;
    }

    private static ItemInfo classify(LocalPlayer p, ItemStack stack) {
        Kind kind = kindOf(stack);
        ArmorSlot slot = null;
        if (kind == Kind.OTHER) {
            slot = armorSlot(stack);
            if (slot != null) kind = Kind.ARMOR;
        }
        String group = p.getCooldowns().getCooldownGroup(stack).toString();
        float range = -1f, cooldown = -1f;
        List<ItemTrait> traits = new ArrayList<>(3);
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) {
                String text = line.getString();
                if (range < 0) range = parseRange(text);
                if (cooldown < 0) cooldown = parseFirstNumber(COOLDOWN, text);
                String stripped = text.strip();
                int tier = TraitTable.tierOfGlyph(stripped);
                if (tier >= 0) {
                    String traitName = stripped.substring(Character.charCount(stripped.codePointAt(0))).strip();
                    if (!traitName.isEmpty()) traits.add(new ItemTrait(traitName, tier));
                }
            }
        }
        return new ItemInfo(new GameItem(stack.copy()), kind, plainName(stack.getHoverName()), group,
                range, cooldown, slot, List.copyOf(traits));
    }

    private static Kind kindOf(ItemStack stack) {
        Identifier model = stack.get(DataComponents.ITEM_MODEL);
        Kind kind = model == null ? Kind.OTHER : kindIn(model.toString());
        if (kind != Kind.OTHER) return kind;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) return Kind.OTHER;
        Tag bukkit = data.copyTag().get("PublicBukkitValues"); // done once per stack (cached), never per tick
        return bukkit == null ? Kind.OTHER : kindIn(bukkit.toString());
    }

    private static Kind kindIn(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("weapon")) return Kind.WEAPON;
        if (lower.contains("ability")) return Kind.ABILITY;
        return Kind.OTHER;
    }

    private static ArmorSlot armorSlot(ItemStack stack) {
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        if (equippable == null) return null;
        for (ArmorSlot slot : ArmorSlot.values()) if (slot.vanilla == equippable.slot()) return slot;
        return null;
    }

    /** The effective range in one lore line (base ± trait modifier), or -1 if the line has none. */
    private static float parseRange(String line) {
        Matcher m = RANGE.matcher(line);
        if (!m.find()) return -1f;
        try {
            float base = Math.abs(Float.parseFloat(m.group(1)));
            if (m.group(2) == null) return base;
            float mod = Float.parseFloat(m.group(3));
            return m.group(2).equals("+") ? base + mod : Math.max(0f, base - mod);
        } catch (NumberFormatException e) {
            return -1f;
        }
    }

    private static float parseFirstNumber(Pattern pattern, String line) {
        Matcher m = pattern.matcher(line);
        if (!m.find()) return -1f;
        try {
            return Float.parseFloat(m.group(1));
        } catch (NumberFormatException e) {
            return -1f;
        }
    }

    private static final Map<Kind, GameItem> PREVIEW = new java.util.EnumMap<>(Kind.class);

    /** HUD editor preview icon per kind. A vanilla item, never Telos data. */
    public static GameItem previewItem(Kind kind) {
        return PREVIEW.computeIfAbsent(kind, k -> new GameItem(new ItemStack(switch (k) {
            case WEAPON -> Items.IRON_SWORD;
            case ABILITY -> Items.BLAZE_POWDER;
            case ARMOR -> Items.IRON_CHESTPLATE;
            case OTHER -> Items.BARRIER;
        })));
    }

    private static final Map<ArmorSlot, GameItem> PREVIEW_ARMOR = new java.util.EnumMap<>(ArmorSlot.class);

    public static GameItem previewArmor(ArmorSlot slot) {
        return PREVIEW_ARMOR.computeIfAbsent(slot, s -> new GameItem(new ItemStack(switch (s) {
            case HEAD -> Items.IRON_HELMET;
            case CHEST -> Items.IRON_CHESTPLATE;
            case LEGS -> Items.IRON_LEGGINGS;
            case FEET -> Items.IRON_BOOTS;
        })));
    }

    /** Display name without the resource pack's kerning glyph (U+F801), private-use glyphs and [brackets]. */
    private static String plainName(Component name) {
        String raw = name.getString();
        StringBuilder sb = new StringBuilder(raw.length());
        raw.codePoints().forEach(cp -> {
            boolean privateUse = (cp >= 0xE000 && cp <= 0xF8FF) || cp >= 0xF0000;
            if (!privateUse && cp != '[' && cp != ']') sb.appendCodePoint(cp);
        });
        return sb.toString().trim();
    }

    /** Called from {@code ClientPacketListenerMixin} (client thread) when the server started or cleared a cooldown. */
    public static void onCooldownPacket(Identifier group, int ticks) {
        if (group == null || !ServerGate.onTelos()) return;
        if (COOLDOWNS.size() >= MAX_QUEUED) COOLDOWNS.pollFirst();
        COOLDOWNS.addLast(new CooldownEvent(group.toString(), Math.max(0, ticks)));
    }

    /** Cooldown packets since the last call, oldest first. */
    public static List<CooldownEvent> drainCooldowns() {
        if (COOLDOWNS.isEmpty()) return List.of();
        List<CooldownEvent> out = new ArrayList<>(COOLDOWNS);
        COOLDOWNS.clear();
        return out;
    }

    public static void addParticleListener(ParticleListener listener) {
        PARTICLE_LISTENERS.add(listener);
    }

    /** Called from {@code ClientPacketListenerMixin} (client thread) for every server particle packet. */
    public static void onParticles(ParticleOptions options, double x, double y, double z) {
        if (PARTICLE_LISTENERS.isEmpty()) return;
        ParticleType<?> type = options.getType();
        ParticleKind kind = type == ParticleTypes.ELECTRIC_SPARK ? ParticleKind.ELECTRIC_SPARK
                : type == ParticleTypes.DUST ? ParticleKind.DUST : null;
        if (kind == null) return;
        for (ParticleListener l : PARTICLE_LISTENERS) l.particle(kind, x, y, z);
    }
}
