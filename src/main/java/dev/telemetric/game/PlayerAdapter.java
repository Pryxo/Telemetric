package dev.telemetric.game;

import dev.telemetric.mixin.LivingEntityAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

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
}
