package dev.telemetric.game;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Loot bag events for the drop and pity trackers, as plain strings.
 * <p>
 * A dropped bag shows as a totem pop of {@code telos:<totem>}, read in {@code GameRendererMixin} before Custom Bags
 * swaps the model. The bag-open sound ({@code noise:player.bags.open*}) starts a scan over the next {@link #SCAN_TICKS}
 * ticks that reports each glowing dropped item within {@link #SCAN_RADIUS} blocks once (Telos makes your own loot glow).
 */
public final class DropAdapter {

    /** Receives bag events on the client thread. */
    public interface Listener {
        /** A loot bag dropped. {@code totem} is the item model path without the namespace ({@code mob/pouch/royal_totem}). */
        default void bagPopped(String totem) {}

        /** A dropped item is glowing near you after a bag opened. {@code model} is its full item model id. */
        default void itemGlowing(String model) {}
    }

    private static final String OPEN_SOUND = "noise:player.bags.open";
    private static final int SCAN_TICKS = 20;
    private static final double SCAN_RADIUS = 10;

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Set<Integer> SEEN = new HashSet<>();
    private static int scanTicks;
    private static boolean registered;

    private DropAdapter() {}

    public static void register() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> scan());
    }

    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    /** {@code GameRendererMixin}: a totem pop is about to show {@code stack}. */
    public static void onTotemPop(ItemStack stack) {
        if (!ServerGate.onTelos() || CustomBagAdapter.isPreviewing() || LISTENERS.isEmpty()) return;
        Identifier model = stack.get(DataComponents.ITEM_MODEL);
        if (model == null || !"telos".equals(model.getNamespace())) return;
        for (Listener l : LISTENERS) l.bagPopped(model.getPath());
    }

    /** {@code SoundEngineMixin}: a sound starts. */
    public static void onSound(Identifier sound) {
        if (sound == null || !ServerGate.onTelos() || !sound.toString().startsWith(OPEN_SOUND)) return;
        Minecraft.getInstance().execute(() -> {
            scanTicks = SCAN_TICKS;
            SEEN.clear();
        });
    }

    private static void scan() {
        if (scanTicks <= 0) return;
        scanTicks--;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer self = mc.player;
        if (level == null || self == null || LISTENERS.isEmpty()) return;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, self.getBoundingBox().inflate(SCAN_RADIUS),
                it -> it.isAlive() && it.isCurrentlyGlowing())) {
            if (!SEEN.add(e.getId())) continue;
            Identifier model = e.getItem().get(DataComponents.ITEM_MODEL);
            if (model == null) continue;
            String id = model.toString();
            for (Listener l : LISTENERS) l.itemGlowing(id);
        }
    }
}
