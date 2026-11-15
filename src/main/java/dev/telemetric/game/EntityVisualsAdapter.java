package dev.telemetric.game;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.phys.AABB;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * MC ADAPTER — how entities are drawn: Player Size (scale players and their Telos nametags), Hide Armor (skip worn
 * armor, briefly reveal the real item models under skins) and Hitboxes (entity boxes for the world drawer).
 * Modules push settings with the setters; the render mixins ({@code AvatarRendererMixin}, {@code DisplayRendererMixin},
 * {@code ArmorLayerMixin}, {@code LivingEntityRendererMixin}, {@code ItemModelResolverMixin},
 * {@code HumanoidMobRendererMixin}) call the hooks. Everything is off away from Telos.
 */
public final class EntityVisualsAdapter {

    private EntityVisualsAdapter() {}

    /** Which players a per-player effect applies to. */
    public enum Target { SELF, OTHERS, EVERYONE;

        boolean matches(boolean self) {
            return this == EVERYONE || (this == SELF) == self;
        }
    }

    // ================================================================== player size

    /** @param x,y,z scale per axis (negative y = upside down) */
    public record PlayerSize(Target target, float x, float y, float z) {}

    /** Set on each player's / nametag's render state: true = draw it scaled this frame. */
    public static final RenderStateDataKey<Boolean> SCALED = RenderStateDataKey.create(() -> "telemetric:scaled");
    /** Set on a Telos nametag's render state when its player is scaled. */
    public static final RenderStateDataKey<Boolean> SCALED_TAG = RenderStateDataKey.create(() -> "telemetric:scaled_tag");

    private static volatile PlayerSize playerSize;

    /** Null = vanilla size. */
    public static void setPlayerSize(PlayerSize size) {
        playerSize = size;
    }

    /** Extract hook for a player: should it be drawn scaled? */
    public static boolean shouldScalePlayer(Entity player) {
        PlayerSize size = playerSize;
        return size != null && ServerGate.onTelos() && size.target().matches(isSelf(player));
    }

    /** Extract hook for a text display: is it the Telos nametag ("… HP: …") of a player that is scaled? */
    public static boolean shouldScaleNametag(Display.TextDisplay display) {
        PlayerSize size = playerSize;
        if (size == null || !ServerGate.onTelos() || !isNametag(display)) return false;
        Player owner = nametagOwner(display);
        if (owner == null) return false;
        if (isSelf(owner) && !ViewAdapter.isThirdPerson()) return false;
        return size.target().matches(isSelf(owner));
    }

    /** Scales a player's model (call where vanilla applies the model scale). */
    public static void scalePlayer(PoseStack pose) {
        PlayerSize size = playerSize;
        if (size == null) return;
        if (size.y() < 0) pose.translate(0, size.y() * 2, 0); // flipped: keep the feet on the ground
        pose.scale(size.x(), size.y(), size.z());
    }

    /** Moves a nametag up/down with the scaled player's head and scales it the same way. */
    public static void scaleNametag(PoseStack pose) {
        PlayerSize size = playerSize;
        if (size == null) return;
        pose.translate(0, 1.8 * (size.y() - 1), 0);
        scalePlayer(pose);
    }

    private static final Map<Display.TextDisplay, Object[]> TAG_CACHE = new WeakHashMap<>();

    /** Telos draws player names as text displays with the health in them; checked once per text change. */
    private static boolean isNametag(Display.TextDisplay display) {
        Component text = display.getText();
        if (text == null) return false;
        Object[] cached = TAG_CACHE.get(display);
        if (cached != null && cached[0] == text) return (Boolean) cached[1];
        boolean tag = text.getString().contains("HP:");
        TAG_CACHE.put(display, new Object[]{text, tag});
        return tag;
    }

    /** The player a nametag belongs to: the one it rides, else the nearest player under it. */
    private static Player nametagOwner(Display.TextDisplay tag) {
        if (tag.getRootVehicle() instanceof Player p) return p;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return null;
        Player best = null;
        double bestD = 1.5 * 1.5;
        for (Player p : level.players()) {
            double dx = p.getX() - tag.getX(), dz = p.getZ() - tag.getZ();
            double d = dx * dx + dz * dz;
            if (d < bestD && tag.getY() >= p.getY()) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    // ================================================================== hide armor

    /**
     * @param hiddenSlots bit set: 1 = helmet, 2 = chestplate, 4 = leggings, 8 = boots
     * @param target      whose armor is hidden
     */
    public record ArmorHiding(int hiddenSlots, Target target) {}

    private static volatile ArmorHiding armorHiding;
    private static volatile long revealUntil;
    private static EquipmentAssetManager equipmentAssets;

    /** Null = armor drawn as usual. */
    public static void setArmorHiding(ArmorHiding hiding) {
        armorHiding = hiding;
    }

    /** Shows the real (unskinned) item models of everyone's items and armor for {@code millis}. */
    public static void revealTrueItems(long millis) {
        revealUntil = System.currentTimeMillis() + millis;
    }

    public static void stopReveal() {
        revealUntil = 0;
    }

    public static boolean isRevealing() {
        return revealUntil > System.currentTimeMillis() && ServerGate.onTelos();
    }

    /** Captured when the client builds its equipment asset manager ({@code EquipmentAssetManagerMixin}). */
    public static void setEquipmentAssets(EquipmentAssetManager manager) {
        equipmentAssets = manager;
    }

    /** Armor-layer hook: skip this worn piece of the player with this entity id? */
    public static boolean hideArmorPiece(int entityId, EquipmentSlot slot) {
        ArmorHiding h = armorHiding;
        if (h == null || isRevealing() || !ServerGate.onTelos()) return false;
        int bit = switch (slot) {
            case HEAD -> 1;
            case CHEST -> 2;
            case LEGS -> 4;
            case FEET -> 8;
            default -> 0;
        };
        return (h.hiddenSlots() & bit) != 0 && h.target().matches(isSelf(entityId));
    }

    /**
     * Head-item hook: Telos helmets are items drawn on the head (not armor models) — hide them like a helmet, and hide
     * the skin while revealing a helmet that has a real armor model.
     */
    public static boolean hideHeadItem(LivingEntity entity) {
        if (!(entity instanceof Player)) return false;
        ItemStack head = entity.getItemBySlot(EquipmentSlot.HEAD);
        if (head.isEmpty()) return false;
        if (isRevealing()) return trueWornAsset(head) != null;
        Identifier model = head.get(DataComponents.ITEM_MODEL);
        return model != null && model.getPath().startsWith("material/armour/")
                && hideArmorPiece(entity.getId(), EquipmentSlot.HEAD);
    }

    /** Item-model hook: while revealing, a skinned Telos item is drawn with its base item's model. */
    public static ItemStack revealItem(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !isRevealing()) return stack;
        Identifier type = telosItemType(stack);
        if (type == null) return stack;
        Identifier real = Identifier.fromNamespaceAndPath(type.getNamespace(), "material/" + type.getPath());
        if (real.equals(stack.get(DataComponents.ITEM_MODEL))) return stack;
        ItemStack copy = stack.copy();
        copy.set(DataComponents.ITEM_MODEL, real);
        return copy;
    }

    /** Worn-armor hook: while revealing, a skinned armor piece is drawn with its base piece's armor model. */
    public static ItemStack revealWorn(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !isRevealing()) return stack;
        Equippable eq = stack.get(DataComponents.EQUIPPABLE);
        ResourceKey<EquipmentAsset> real = eq == null ? null : trueWornAsset(stack);
        if (real == null || real.equals(eq.assetId().orElse(null))) return stack;
        ItemStack copy = stack.copy();
        copy.set(DataComponents.EQUIPPABLE, new Equippable(eq.slot(), eq.equipSound(), Optional.of(real),
                eq.cameraOverlay(), eq.allowedEntities(), eq.dispensable(), eq.swappable(), eq.damageOnHurt(),
                eq.equipOnInteract(), eq.canBeSheared(), eq.shearingSound()));
        return copy;
    }

    /** {@code telos:armour/<weight>/<slot>/<name>} → equipment asset {@code telos:<weight>/<name>}, if it exists. */
    @SuppressWarnings("unchecked")
    private static ResourceKey<EquipmentAsset> trueWornAsset(ItemStack stack) {
        Identifier type = telosItemType(stack);
        EquipmentAssetManager assets = equipmentAssets;
        if (type == null || assets == null) return null;
        String[] parts = type.getPath().split("/");
        if (parts.length < 4 || !parts[0].equals("armour")) return null;
        ResourceKey<EquipmentAsset> key = ResourceKey.create(
                (ResourceKey<? extends Registry<EquipmentAsset>>) EquipmentAssets.ROOT_ID,
                Identifier.fromNamespaceAndPath(type.getNamespace(), parts[1] + "/" + parts[parts.length - 1]));
        return assets.get(key) == EquipmentAssetManager.MISSING ? null : key;
    }

    /** Telos base item type ({@code custom_data.PublicBukkitValues."realm:item_type"}), or null. */
    private static Identifier telosItemType(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag bukkit = data.copyTag().getCompoundOrEmpty("PublicBukkitValues");
        String type = bukkit.getStringOr("realm:item_type", "");
        return type.isEmpty() ? null : Identifier.tryParse(type);
    }

    // ================================================================== hitboxes

    public enum BoxKind { SELF, PLAYER, MOB, ARMOR_STAND, ITEM }

    @FunctionalInterface
    public interface BoxConsumer {
        void box(BoxKind kind, double minX, double minY, double minZ, double maxX, double maxY, double maxZ);
    }

    /**
     * Interpolated bounding boxes of the entities within {@code range} blocks (your own only in third person).
     * Paper-thin boxes (interaction / display entities) are skipped.
     */
    public static void forEachBox(double range, BoxConsumer out) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer self = mc.player;
        if (level == null || self == null || !ServerGate.onTelos()) return;
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        double r2 = range * range;
        for (Entity e : level.entitiesForRendering()) {
            BoxKind kind;
            if (e == self) {
                if (!ViewAdapter.isThirdPerson()) continue;
                kind = BoxKind.SELF;
            } else if (e instanceof Player) kind = BoxKind.PLAYER;
            else if (e instanceof ArmorStand) kind = BoxKind.ARMOR_STAND;
            else if (e instanceof LivingEntity) kind = BoxKind.MOB;
            else if (e instanceof ItemEntity) kind = BoxKind.ITEM;
            else continue;
            if (e != self && e.distanceToSqr(self) > r2) continue;
            AABB b = e.getBoundingBox();
            if (b.getXsize() < 0.01 || b.getYsize() < 0.01 || b.getZsize() < 0.01) continue;
            double dx = (e.xo - e.getX()) * (1 - pt), dy = (e.yo - e.getY()) * (1 - pt), dz = (e.zo - e.getZ()) * (1 - pt);
            out.box(kind, b.minX + dx, b.minY + dy, b.minZ + dz, b.maxX + dx, b.maxY + dy, b.maxZ + dz);
        }
    }

    // ================================================================== helpers

    private static boolean isSelf(Entity e) {
        return e == Minecraft.getInstance().player;
    }

    private static boolean isSelf(int entityId) {
        LocalPlayer self = Minecraft.getInstance().player;
        return self != null && self.getId() == entityId;
    }
}
