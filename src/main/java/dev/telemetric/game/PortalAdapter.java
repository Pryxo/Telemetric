package dev.telemetric.game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.illager.Evoker;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MC ADAPTER — dungeon portals near you. A Telos portal is an Evoker wearing the portal model on its head
 * ({@code telos:…/<portal_name>}), with a timer name tag {@code -=[42]=-} above it (seconds the portal stays open).
 * Read once per Callouts key press (one entity query in a small box).
 */
public final class PortalAdapter {

    private static final double RANGE = 10;
    private static final Pattern TIMER = Pattern.compile("-=\\[(\\d+)]=-");

    /** @param model last part of the head model id, upper case ({@code TOMB_OF_SHADOWS}); "" for a portal without one */
    public record Portal(String model, int secondsLeft) {}

    private PortalAdapter() {}

    /** The closest open portal within 10 blocks that shows a timer, or null. */
    public static Portal nearest() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) return null;
        List<Evoker> evokers = new ArrayList<>(level.getEntitiesOfClass(Evoker.class, player.getBoundingBox().inflate(RANGE),
                e -> e.distanceToSqr(player) <= RANGE * RANGE));
        evokers.sort(Comparator.comparingDouble(e -> e.distanceToSqr(player)));
        for (Evoker evoker : evokers) {
            int seconds = timerAbove(level, evoker);
            if (seconds < 0) continue;
            Identifier model = evoker.getItemBySlot(EquipmentSlot.HEAD).get(DataComponents.ITEM_MODEL);
            String path = model == null ? "" : model.getPath();
            return new Portal(path.substring(path.lastIndexOf('/') + 1).toUpperCase(java.util.Locale.ROOT), seconds);
        }
        return null;
    }

    /** Seconds on the closest timer tag at or above the portal, or -1. */
    private static int timerAbove(ClientLevel level, Evoker evoker) {
        AABB box = evoker.getBoundingBox().inflate(4, 10, 4);
        List<ArmorStand> stands = new ArrayList<>(level.getEntitiesOfClass(ArmorStand.class, box,
                s -> s.getY() >= evoker.getY() && s.hasCustomName()));
        stands.sort(Comparator.comparingDouble(s -> s.distanceToSqr(evoker)));
        for (ArmorStand s : stands) {
            Matcher m = TIMER.matcher(s.getCustomName().getString());
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return -1;
    }
}
