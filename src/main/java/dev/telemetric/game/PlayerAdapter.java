package dev.telemetric.game;

import dev.telemetric.dungeon.Point;
import dev.telemetric.mixin.LivingEntityAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/**
 * MC ADAPTER — the only place gameplay modules read or change the local player.
 * Every method is null-safe: outside a world they simply report "nothing to do".
 */
public final class PlayerAdapter {

    private PlayerAdapter() {}

    private static LocalPlayer player() {
        return Minecraft.getInstance().player;
    }

    /** True while a world is loaded and the local player exists. */
    public static boolean isInWorld() {
        return player() != null;
    }

    /** True while the vanilla jump key (Options → Controls) is held and no screen is open. */
    public static boolean isJumpKeyHeld() {
        Minecraft mc = Minecraft.getInstance();
        return mc.screen == null && mc.options.keyJump.isDown();
    }

    public static boolean isOnGround() {
        LocalPlayer p = player();
        return p != null && p.onGround();
    }

    /** Remaining ticks of the vanilla jump cooldown (0 = can jump). */
    public static int jumpCooldown() {
        LocalPlayer p = player();
        return p == null ? 0 : ((LivingEntityAccessor) p).telemetric$getNoJumpDelay();
    }

    /** Registered id of the biome the player stands in ("minecraft:warped_forest"), or null outside a world. */
    public static String biomeId() {
        LocalPlayer p = player();
        return p == null ? null : p.level().getBiome(p.blockPosition()).getRegisteredName();
    }

    /**
     * Where the player is: the Telos location name ({@link TelosLocations#resolve} — HUD box, made part-specific
     * by the biome), else the biome name. Null outside a world.
     */
    public static String locationName() {
        String location = TelosLocations.resolve(BossBarAdapter.hudLocation(), biomeId());
        return location != null ? location : biomeName();
    }

    /**
     * Readable name of the biome the player stands in ("Dark Forest", not "minecraft:dark_forest"):
     * the biome's language entry when the game/resource pack has one, otherwise the id path title-cased.
     * Null outside a world.
     */
    public static String biomeName() {
        LocalPlayer p = player();
        if (p == null) return null;
        String id = p.level().getBiome(p.blockPosition()).getRegisteredName(); // "ns:path"
        int colon = id.indexOf(':');
        String ns = colon < 0 ? "minecraft" : id.substring(0, colon);
        String path = colon < 0 ? id : id.substring(colon + 1);
        String key = "biome." + ns + "." + path.replace('/', '.');
        String translated = net.minecraft.locale.Language.getInstance().getOrDefault(key, null);
        if (translated != null && !translated.isBlank()) return translated;
        String last = path.substring(path.lastIndexOf('/') + 1);
        StringBuilder sb = new StringBuilder();
        for (String w : last.split("[_\\-.]+")) {
            if (w.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        return sb.isEmpty() ? id : sb.toString();
    }

    /**
     * Clears the vanilla jump cooldown. Vanilla only jumps from the ground when it is 0, so with it
     * cleared, a held jump key fires on the very next tick after landing.
     */
    public static void clearJumpCooldown() {
        LocalPlayer p = player();
        if (p != null) ((LivingEntityAccessor) p).telemetric$setNoJumpDelay(0);
    }

    // ------------------------------------------------------------------ combat

    /** True while the vanilla attack key (left click by default) is held and no screen is open. */
    public static boolean isAttackKeyHeld() {
        Minecraft mc = Minecraft.getInstance();
        return mc.screen == null && mc.options.keyAttack.isDown();
    }

    /** Swings the main hand (sends the swing packet — Telos fires weapons on it). */
    public static void swingMainHand() {
        LocalPlayer p = player();
        if (p != null) p.swing(InteractionHand.MAIN_HAND);
    }

    /** True while riding something (mount, boat). */
    public static boolean isRiding() {
        LocalPlayer p = player();
        return p != null && p.isPassenger();
    }

    /** Feet position interpolated for the current frame plus the body yaw (degrees, 0 = south / +Z). */
    public record Pose(double x, double y, double z, float yaw) {}

    /** Smooth per-frame pose for world drawing, or null outside a world. */
    public static Pose renderPose() {
        LocalPlayer p = player();
        if (p == null) return null;
        float pt = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        return new Pose(Mth.lerp(pt, p.xo, p.getX()), Mth.lerp(pt, p.yo, p.getY()), Mth.lerp(pt, p.zo, p.getZ()),
                p.getViewYRot(pt));
    }

    /** Position and velocity (blocks per tick) this tick. */
    public record Motion(double x, double y, double z, double vx, double vy, double vz) {}

    public static Motion motion() {
        LocalPlayer p = player();
        if (p == null) return null;
        Vec3 v = p.getDeltaMovement();
        return new Motion(p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z);
    }

    private static volatile boolean autoSprint;

    /** Auto Sprint: treat the sprint key as held (vanilla still decides whether sprinting is possible). */
    public static void setAutoSprint(boolean on) {
        autoSprint = on;
    }

    /** Read by {@code LocalPlayerMixin} every tick; only on Telos. */
    public static boolean autoSprint() {
        return autoSprint && ServerGate.onTelos();
    }

    // ------------------------------------------------------------------ health

    /** Current and max health (hearts × 2), or null outside a world. */
    public record Health(float current, float max) {
        /** 0..1 (0 when max is unknown). */
        public float fraction() {
            return max > 0 && Float.isFinite(current) ? Math.clamp(current / max, 0f, 1f) : 0f;
        }
    }

    public static Health health() {
        LocalPlayer p = player();
        return p == null ? null : new Health(p.getHealth(), p.getMaxHealth());
    }

    /**
     * Changes (identity) whenever the player is put into another world — realm, hub, dungeon. Compare it between
     * ticks to notice a world change; null outside a world.
     */
    public static Object worldToken() {
        return Minecraft.getInstance().level;
    }

    /** Interpolated position of the top of the player's head this frame, or null. */
    public static Point headTop(float partialTick) {
        LocalPlayer p = player();
        if (p == null) return null;
        return new Point(Mth.lerp(partialTick, p.xo, p.getX()), Mth.lerp(partialTick, p.yo, p.getY()) + p.getBbHeight(),
                Mth.lerp(partialTick, p.zo, p.getZ()));
    }

    /** Partial tick of the frame being drawn. */
    public static float partialTick() {
        return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }
}
