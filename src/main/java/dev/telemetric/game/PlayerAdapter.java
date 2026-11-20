package dev.telemetric.game;

import dev.telemetric.dungeon.Point;
import dev.telemetric.mixin.LivingEntityAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/**
 * MC ADAPTER: the only place gameplay modules read or change the local player.
 * Every method is null-safe, so outside a world they just report "nothing to do".
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

    /** Remaining ticks of the vanilla jump cooldown (0 means you can jump). */
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
     * Where the player is: the Telos location name ({@link TelosLocations#resolve}, the HUD box made part-specific by
     * the biome), or else the biome name. Null outside a world.
     */
    public static String locationName() {
        String location = TelosLocations.resolve(BossBarAdapter.hudLocation(), biomeId());
        return location != null ? location : biomeName();
    }

    /**
     * A readable name for the biome the player stands in ("Dark Forest", not "minecraft:dark_forest"). Uses the biome's
     * language entry if the game or resource pack has one, otherwise the id path in title case.
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
     * Clears the vanilla jump cooldown. Vanilla only jumps from the ground when it's 0, so with it cleared, a held jump
     * key fires on the very next tick after landing.
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

    /** Swings the main hand. This sends the swing packet, which Telos fires weapons on. */
    public static void swingMainHand() {
        LocalPlayer p = player();
        if (p != null) p.swing(InteractionHand.MAIN_HAND);
    }

    /** True while riding something (mount, boat). */
    public static boolean isRiding() {
        LocalPlayer p = player();
        return p != null && p.isPassenger();
    }

    /** Feet position interpolated for the current frame, plus the body yaw (degrees, 0 is south / +Z). */
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

    /** Auto Sprint: treat the sprint key as held (vanilla still decides whether you can sprint). */
    public static void setAutoSprint(boolean on) {
        autoSprint = on;
    }

    /** {@code LocalPlayerMixin} reads this every tick. Only on Telos. */
    public static boolean autoSprint() {
        return autoSprint && ServerGate.onTelos();
    }

    // ------------------------------------------------------------------ health

    /** Current and max health (hearts × 2), or null outside a world. */
    public record Health(float current, float max) {
        /** 0..1 (0 if max is unknown). */
        public float fraction() {
            return max > 0 && Float.isFinite(current) ? Math.clamp(current / max, 0f, 1f) : 0f;
        }
    }

    public static Health health() {
        LocalPlayer p = player();
        return p == null ? null : new Health(p.getHealth(), p.getMaxHealth());
    }

    /**
     * Changes (by identity) whenever the player is put into another world: realm, hub, dungeon. Compare it between
     * ticks to notice a world change. Null outside a world.
     */
    public static Object worldToken() {
        return Minecraft.getInstance().level;
    }

    /** Interpolated position of the player's feet this frame, or null. */
    public static Point feet(float partialTick) {
        LocalPlayer p = player();
        if (p == null) return null;
        return new Point(Mth.lerp(partialTick, p.xo, p.getX()), Mth.lerp(partialTick, p.yo, p.getY()),
                Mth.lerp(partialTick, p.zo, p.getZ()));
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

    /** The player's name, or null outside a world. */
    public static String name() {
        LocalPlayer p = player();
        return p == null ? null : p.getGameProfile().name();
    }

    /**
     * Stable id of the logged-in account (UUID string), for per-account local files. Null outside a world.
     * It's never sent anywhere.
     */
    public static String accountId() {
        LocalPlayer p = player();
        return p == null ? null : p.getUUID().toString();
    }

    /**
     * UUIDs (dashed) of every real player the client has loaded around you, yourself included. Empty outside a world.
     * Only version-4 (Mojang account) UUIDs count; server NPCs shown as players use other versions and are left out.
     */
    public static java.util.List<String> loadedPlayerIds() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return java.util.List.of();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.player.Player pl : mc.level.players()) {
            java.util.UUID id = pl.getUUID();
            if (id.version() == 4) out.add(id.toString());
        }
        return out;
    }

    /** Eye position, or null. */
    public static Point eyes() {
        LocalPlayer p = player();
        return p == null ? null : new Point(p.getX(), p.getEyeY(), p.getZ());
    }

    /** Unit look direction {x, y, z}, or null. */
    public static double[] look() {
        LocalPlayer p = player();
        if (p == null) return null;
        Vec3 v = p.getLookAngle();
        return new double[]{v.x, v.y, v.z};
    }

    /** Sends a chat message as if typed (in the current server chat mode). Does nothing outside a world. */
    public static void sendChat(String message) {
        LocalPlayer p = player();
        if (p != null && p.connection != null) p.connection.sendChat(message);
    }

    /** Runs a server command as if typed ({@code "bosses"}, no leading slash). Does nothing outside a world. */
    public static void sendCommand(String command) {
        LocalPlayer p = player();
        if (p != null && p.connection != null) p.connection.sendCommand(command);
    }
}
