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
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MC ADAPTER: how entities are drawn. Covers Player Size (scales players and their Telos nametags), Hide Armor (skips
 * worn armor and can briefly reveal the real item models under skins) and Hitboxes (entity boxes for the world
 * drawer). Modules push their settings through the setters, and the render mixins ({@code AvatarRendererMixin},
 * {@code DisplayRendererMixin}, {@code ArmorLayerMixin}, {@code LivingEntityRendererMixin},
 * {@code ItemModelResolverMixin}, {@code HumanoidMobRendererMixin}) call the hooks. All of it is off away from Telos.
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

    /** Scale per axis (a negative y means upside down). */
    public record Scale(float x, float y, float z) {
        /** Render-state marker for "not scaled", because render-state data can't be cleared to null. */
        public static final Scale NONE = new Scale(1, 1, 1);
    }

    /** Separate sizes for you and for other players. A null side keeps its vanilla size. */
    public record PlayerSize(Scale self, Scale others) {
        Scale of(boolean isSelf) {
            return isSelf ? self : others;
        }
    }

    /** Each player's and nametag's scale this frame ({@link Scale#NONE} means vanilla size). */
    public static final RenderStateDataKey<Scale> SCALED = RenderStateDataKey.create(() -> "telemetric:scaled");
    /** Set on a Telos nametag's render state: the scale of the player it belongs to. */
    public static final RenderStateDataKey<Scale> SCALED_TAG = RenderStateDataKey.create(() -> "telemetric:scaled_tag");

    private static volatile PlayerSize playerSize;

    /** Null means vanilla size. */
    public static void setPlayerSize(PlayerSize size) {
        playerSize = size;
    }

    /** Extract hook for a player: its scale, or null for vanilla size. */
    public static Scale playerScale(Entity player) {
        PlayerSize size = playerSize;
        return size != null && ServerGate.onTelos() ? size.of(isSelf(player)) : null;
    }

    /** Extract hook for a text display: the scale of the player whose Telos nametag ("… HP: …") it is, or null. */
    public static Scale nametagScale(Display.TextDisplay display) {
        PlayerSize size = playerSize;
        if (size == null || !ServerGate.onTelos() || !NametagAdapter.isNametag(display)) return null;
        Player owner = NametagAdapter.owner(display);
        if (owner == null) return null;
        if (isSelf(owner) && !ViewAdapter.isThirdPerson()) return null;
        return size.of(isSelf(owner));
    }

    /** Scales a player's model. Call it where vanilla applies the model scale. Null or NONE does nothing. */
    public static void scalePlayer(PoseStack pose, Scale size) {
        if (size == null || size == Scale.NONE) return;
        if (size.y() < 0) pose.translate(0, size.y() * 2, 0); // flipped, so keep the feet on the ground
        pose.scale(size.x(), size.y(), size.z());
    }

    /** Moves a nametag up or down with the scaled player's head and scales it the same way. */
    public static void scaleNametag(PoseStack pose, Scale size) {
        if (size == null || size == Scale.NONE) return;
        pose.translate(0, 1.8 * (size.y() - 1), 0);
        scalePlayer(pose, size);
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

    /** Null means armor is drawn as usual. */
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

    /** Armor-layer hook: should this worn piece of the player with this entity id be skipped? */
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
     * Head-item hook. Telos helmets are items drawn on the head rather than armor models, so we hide them like a
     * helmet, and hide the skin while revealing a helmet that has a real armor model.
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

    /** Maps {@code telos:armour/<weight>/<slot>/<name>} to the equipment asset {@code telos:<weight>/<name>}, if it exists. */
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

    public enum BoxKind { SELF, PLAYER, MOB, ARMOR_STAND, ITEM, OTHER }

    @FunctionalInterface
    public interface BoxConsumer {
        void box(BoxKind kind, double minX, double minY, double minZ, double maxX, double maxY, double maxZ);
    }

    /**
     * Interpolated bounding boxes of the entities within {@code range} blocks (your own only in third person).
     * Telos mobs and dummies (an {@link Interaction} hitbox with a model and an HP stack, see
     * {@link #forEachHittableBox}) count as {@link BoxKind#MOB}, and every other entity is {@link BoxKind#OTHER}.
     * Paper-thin (display) boxes are skipped.
     */
    public static void forEachBox(double range, BoxConsumer out) {
        forEachBox(range, true, out);
    }

    /** {@link #forEachBox(double, BoxConsumer)}; {@code withSelf} false leaves your own box out (the 3D preview draws its own). */
    public static void forEachBox(double range, boolean withSelf, BoxConsumer out) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer self = mc.player;
        if (level == null || self == null || !ServerGate.onTelos()) return;
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        double r2 = range * range;
        List<Display.TextDisplay> tags = hpTags(level, self, range);
        boolean boss = BossBarAdapter.hasBossBar();
        for (Entity e : level.entitiesForRendering()) {
            BoxKind kind;
            if (e == self) {
                if (!withSelf || !ViewAdapter.isThirdPerson()) continue;
                kind = BoxKind.SELF;
            } else if (e instanceof Player) kind = BoxKind.PLAYER;
            else if (e instanceof ArmorStand) kind = BoxKind.ARMOR_STAND;
            else if (e instanceof LivingEntity) kind = BoxKind.MOB;
            else if (e instanceof ItemEntity) kind = BoxKind.ITEM;
            else if (e instanceof Interaction h && e.distanceToSqr(self) <= r2 && isTelosMob(h, tags, boss)) {
                kind = BoxKind.MOB;
            } else kind = BoxKind.OTHER;
            if (e != self && e.distanceToSqr(self) > r2) continue;
            AABB b = e.getBoundingBox();
            // Telos spawns marker armor stands (no hitbox) to carry ability and projectile models, so show a small box for them.
            if (kind == BoxKind.ARMOR_STAND && b.getXsize() < 0.01) b = b.inflate(MARKER_HALF, 0, MARKER_HALF).expandTowards(0, MARKER_HALF * 2, 0);
            if (b.getXsize() < 0.01 || b.getYsize() < 0.01 || b.getZsize() < 0.01) continue;
            double dx = (e.xo - e.getX()) * (1 - pt), dy = (e.yo - e.getY()) * (1 - pt), dz = (e.zo - e.getZ()) * (1 - pt);
            out.box(kind, b.minX + dx, b.minY + dy, b.minZ + dz, b.maxX + dx, b.maxY + dy, b.maxZ + dz);
        }
    }

    /** How far (in blocks) a Telos HP text stack can sit beside or above its mob's hitbox top. */
    private static final double HP_TAG_SIDE = 0.75, HP_TAG_ABOVE = 2.5;

    /**
     * Interpolated boxes of the mobs you can actually hit within {@code range} blocks.
     * <p>
     * Telos mobs (and training dummies) aren't vanilla mobs. They're an {@link Interaction} hitbox riding a carrier
     * entity next to its {@link Display.ItemDisplay} model, plus a stack of text displays (the HP bar) just above.
     * A hitbox like that counts when it has a model (isn't invisible) and an HP stack; static props and NPC hitboxes
     * have neither. Vanilla mobs count when they're alive, visible and AI-driven. Players and armor stands never count.
     */
    public static void forEachHittableBox(double range, BoxConsumer out) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer self = mc.player;
        if (level == null || self == null || !ServerGate.onTelos()) return;
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        double r2 = range * range;
        List<Display.TextDisplay> tags = hpTags(level, self, range);
        boolean boss = BossBarAdapter.hasBossBar();
        for (Entity e : level.entitiesForRendering()) {
            if (e.distanceToSqr(self) > r2) continue;
            if (e instanceof Interaction hitbox) {
                if (!isTelosMob(hitbox, tags, boss)) continue;
            } else if (!(e instanceof Mob mob) || !mob.isAlive() || mob.getHealth() <= 0 || mob.isInvisible()
                    || mob.isNoAi() || !mob.isPickable() || !mob.isAttackable()) {
                continue;
            }
            AABB b = e.getBoundingBox();
            if (b.getXsize() < 0.01 || b.getYsize() < 0.01 || b.getZsize() < 0.01) continue;
            double dx = (e.xo - e.getX()) * (1 - pt), dy = (e.yo - e.getY()) * (1 - pt), dz = (e.zo - e.getZ()) * (1 - pt);
            out.box(BoxKind.MOB, b.minX + dx, b.minY + dy, b.minZ + dz, b.maxX + dx, b.maxY + dy, b.maxZ + dz);
        }
    }

    /** Half size (blocks) of the box drawn for a marker armor stand, which has no hitbox of its own. */
    private static final double MARKER_HALF = 0.25;
    /** A boss hitbox (HP in the boss bar, no HP text stack) is at least this wide... */
    private static final double BOSS_MIN_WIDTH = 2.0;
    /** ...or at least this tall (Sylvaris has a 1.25-wide, roughly 80-tall box; minions are under 2 tall). */
    private static final double BOSS_MIN_HEIGHT = 4.0;

    /**
     * A Telos mob hitbox: it has a model and either an HP text stack or, while a boss bar is showing, a boss-sized box
     * that's wide or tall. Bosses like Sylvaris only carry their HP in the boss bar, so they have no HP stack.
     */
    private static boolean isTelosMob(Interaction hitbox, List<Display.TextDisplay> tags, boolean bossBar) {
        if (!hasModel(hitbox)) return false;
        if (hasHpTag(hitbox, tags)) return true;
        AABB b = hitbox.getBoundingBox();
        if (!bossBar || b.getYsize() < 0.5) return false;
        return Math.min(b.getXsize(), b.getZsize()) >= BOSS_MIN_WIDTH || b.getYsize() >= BOSS_MIN_HEIGHT;
    }

    /** Text displays near enough to be the HP stack of a hitbox within {@code range}. */
    private static List<Display.TextDisplay> hpTags(ClientLevel level, LocalPlayer self, double range) {
        double r = range + HP_TAG_ABOVE + 2, r2 = r * r;
        List<Display.TextDisplay> tags = new ArrayList<>();
        for (Entity e : level.entitiesForRendering()) {
            if (e instanceof Display.TextDisplay t && t.distanceToSqr(self) <= r2) tags.add(t);
        }
        return tags;
    }

    /** True if the hitbox shares its carrier with an item-display model. */
    private static boolean hasModel(Interaction hitbox) {
        Entity carrier = hitbox.getVehicle();
        if (carrier == null) return false;
        for (Entity p : carrier.getPassengers()) {
            if (p instanceof Display.ItemDisplay && !p.isInvisible()) return true;
        }
        return false;
    }

    /** True if a text display (the HP bar) floats right above the hitbox. */
    private static boolean hasHpTag(Interaction hitbox, List<Display.TextDisplay> tags) {
        AABB b = hitbox.getBoundingBox();
        double cx = (b.minX + b.maxX) / 2, cz = (b.minZ + b.maxZ) / 2;
        // Big mobs (bosses like Valus) carry their HP stack further out, so the tolerance scales with the box.
        double side = Math.max(HP_TAG_SIDE, Math.max(b.getXsize(), b.getZsize()) / 2);
        double above = HP_TAG_ABOVE + b.getYsize() / 2;
        for (Display.TextDisplay t : tags) {
            double dx = t.getX() - cx, dz = t.getZ() - cz;
            if (dx * dx + dz * dz > side * side) continue;
            if (t.getY() >= b.minY && t.getY() <= b.maxY + above) return true;
        }
        return false;
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
