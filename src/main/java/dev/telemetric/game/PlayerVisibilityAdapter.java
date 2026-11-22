package dev.telemetric.game;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.function.Predicate;

/**
 * MC ADAPTER: which entities aren't drawn at all. Hide Players skips other players' models and everything riding
 * them, while Telos name tags are handled by {@link NametagAdapter} (the Status Title module). The hook is
 * {@link #skipRender}, called from {@code EntityRenderDispatcherMixin} ({@code shouldRender}). Only real accounts
 * (UUID v4) count as players, so NPC player entities stay visible. All of it is off away from Telos.
 */
public final class PlayerVisibilityAdapter {

    private PlayerVisibilityAdapter() {}

    /** Takes a username and returns true to hide that player. Null means hide nobody. */
    private static volatile Predicate<String> hiddenPlayers;

    public static void setHiddenPlayers(Predicate<String> rule) {
        hiddenPlayers = rule;
    }

    /** True if {@code player} is another real player that the Hide Players rule hides. */
    public static boolean isHidden(Player player) {
        Predicate<String> rule = hiddenPlayers;
        return rule != null && ServerGate.onTelos() && player != Minecraft.getInstance().player
                && player.getUUID().version() == 4 && rule.test(player.getScoreboardName());
    }

    /** Render hook: true means don't draw this entity this frame. */
    public static boolean skipRender(Entity entity) {
        if (!ServerGate.onTelos()) return false;
        if (entity instanceof Player player) return isHidden(player);
        if (entity instanceof Display.TextDisplay text && NametagAdapter.isNametag(text)) {
            return NametagAdapter.hidden(text);
        }
        return entity.getRootVehicle() instanceof Player rider && isHidden(rider);
    }
}
