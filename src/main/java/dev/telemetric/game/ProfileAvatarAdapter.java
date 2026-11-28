package dev.telemetric.game;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.player.PlayerSkin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The 3D player model of the profile screen: your live player for your own profile, otherwise a stand-in
 * {@link RemotePlayer} whose skin comes from the Mojang profile (resolved off-thread by UUID), so it works for players
 * who are not on your server. Drawn like the inventory screen's player, following the mouse.
 */
public final class ProfileAvatarAdapter {

    /** Every model part (hat, jacket, sleeves, pants, cape): a fresh stand-in has none switched on. */
    private static final byte ALL_PARTS = 0x7F;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private static String key;
    private static AbstractClientPlayer avatar;
    private static volatile GameProfile resolved;
    private static volatile String resolvedKey;
    private static String resolvingKey;

    private ProfileAvatarAdapter() {}

    private static final class Avatar extends RemotePlayer {
        private final Supplier<PlayerSkin> skin;

        Avatar(ClientLevel level, GameProfile profile) {
            super(level, profile);
            this.skin = Minecraft.getInstance().getSkinManager().createLookup(profile, true);
            getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, ALL_PARTS);
        }

        @Override
        public PlayerSkin getSkin() {
            return skin.get();
        }
    }

    /**
     * Draws the player into the box. Returns false while there is nothing to draw yet (no world, or the skin profile
     * is still being looked up); call every frame.
     */
    public static boolean draw(GuiGraphicsExtractor g, String uuid, String name, int x0, int y0, int x1, int y1,
                               int size, int mouseX, int mouseY) {
        AbstractClientPlayer p = avatarFor(uuid, name);
        if (p == null) return false;
        InventoryScreen.extractEntityInInventoryFollowsMouse(g, x0, y0, x1, y1, size, 0.0625f, mouseX, mouseY, p);
        return true;
    }

    /** Drops the stand-in (call when the screen closes), so it never outlives its level. */
    public static void clear() {
        key = null;
        avatar = null;
        resolved = null;
        resolvedKey = null;
        resolvingKey = null;
    }

    private static AbstractClientPlayer avatarFor(String uuid, String name) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || name == null || name.isEmpty()) return null;
        LocalPlayer self = mc.player;
        if (self != null && self.getGameProfile().name().equalsIgnoreCase(name)) return self;

        String k = uuid + "|" + name.toLowerCase(Locale.ROOT);
        if (k.equals(key) && avatar != null && avatar.level() == level) return avatar;
        if (k.equals(resolvedKey) && resolved != null) {
            avatar = new Avatar(level, resolved);
            key = k;
            return avatar;
        }
        if (!k.equals(resolvingKey)) {
            resolvingKey = k;
            UUID id = parse(uuid);
            UUID lookup = id != null ? id : UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            CompletableFuture.runAsync(() -> {
                GameProfile gp;
                try {
                    gp = mc.services().profileResolver().fetchById(lookup).orElse(null);
                } catch (RuntimeException e) {
                    gp = null;
                }
                resolved = gp != null ? gp : new GameProfile(lookup, name);
                resolvedKey = k;
            });
        }
        return null;
    }

    /** Names in your tab list (real player names only), for {@code /profile} suggestions and the online dot. */
    public static List<String> onlineNames() {
        Minecraft mc = Minecraft.getInstance();
        List<String> out = new ArrayList<>();
        if (mc.getConnection() == null) return out;
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            String n = info.getProfile().name();
            if (n != null && NAME.matcher(n).matches()) out.add(n);
        }
        return out;
    }

    private static UUID parse(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            String t = s.contains("-") ? s : s.replaceFirst("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)", "$1-$2-$3-$4-$5");
            return UUID.fromString(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
