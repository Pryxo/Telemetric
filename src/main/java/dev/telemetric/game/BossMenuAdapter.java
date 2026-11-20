package dev.telemetric.game;

import dev.telemetric.Telemetric;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * MC ADAPTER: reads server menus as plain text for features (the Boss Tracker's {@code /bosses} list).
 * <ul>
 *   <li>Every container menu the player closes on Telos goes to the {@link #onMenuClosed} listeners (item name and
 *       lore of the menu's own slots, without the player inventory).</li>
 *   <li>{@link #runCommandAndRead} runs a command that opens a menu, reads it once its items have arrived and closes
 *       it again, but only if the caller recognises it ({@code isOurs}). Any other menu stays open.</li>
 * </ul>
 */
public final class BossMenuAdapter {

    /** One menu slot: item name and lore lines joined with spaces (plain text). */
    public record MenuItem(int slot, String name, String lore) {}

    private enum Step { IDLE, SEND, WAIT_MENU, READ }

    private static final long SEND_DELAY_MS = 300, MENU_TIMEOUT_MS = 10_000, MIN_OPEN_MS = 50, MAX_OPEN_MS = 1_500;
    private static final int PLAYER_SLOTS = 36;

    private static final List<Consumer<List<MenuItem>>> CLOSED = new CopyOnWriteArrayList<>();
    private static boolean registered;

    private static Step step = Step.IDLE;
    private static String command;
    private static Predicate<List<MenuItem>> isOurs;
    private static Consumer<List<MenuItem>> result;
    private static long stepAt, openedAt;
    private static Screen ownScreen;

    private BossMenuAdapter() {}

    public static void register() {
        if (registered) return;
        registered = true;
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> menu)) return;
            ScreenEvents.remove(screen).register(s -> {
                if (s == ownScreen || !ServerGate.onTelos() || CLOSED.isEmpty()) return;
                List<MenuItem> items = read(menu);
                for (Consumer<List<MenuItem>> l : CLOSED) l.accept(items);
            });
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    /** Every closed container menu (its own slots), delivered on the client thread. */
    public static void onMenuClosed(Consumer<List<MenuItem>> listener) {
        CLOSED.add(listener);
    }

    /**
     * Runs {@code cmd} (no slash) once no screen is open, waits for the menu it opens, reads it and closes it.
     * {@code isOurs} looks at the items and decides whether it's the expected menu. If it isn't, or nothing opens
     * within 10 s, nothing gets closed and {@code done} isn't called. A new request replaces a pending one.
     */
    public static void runCommandAndRead(String cmd, Predicate<List<MenuItem>> ours, Consumer<List<MenuItem>> done) {
        command = cmd;
        isOurs = ours;
        result = done;
        step = Step.SEND;
        stepAt = System.currentTimeMillis() + SEND_DELAY_MS;
    }

    public static boolean isBusy() {
        return step != Step.IDLE;
    }

    private static void tick() {
        if (step == Step.IDLE) return;
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        if (mc.player == null || !ServerGate.onTelos()) {
            step = Step.IDLE;
            return;
        }
        switch (step) {
            case SEND -> {
                if (now < stepAt) return;
                if (mc.screen != null) { // the player is in a menu, so try again a bit later
                    stepAt = now + 500;
                    return;
                }
                PlayerAdapter.sendCommand(command);
                step = Step.WAIT_MENU;
                stepAt = now + MENU_TIMEOUT_MS;
            }
            case WAIT_MENU -> {
                if (mc.screen instanceof AbstractContainerScreen<?>) {
                    ownScreen = mc.screen;
                    openedAt = now;
                    step = Step.READ;
                } else if (mc.screen != null || now > stepAt) {
                    step = Step.IDLE; // something else opened, or the server sent no menu
                }
            }
            case READ -> {
                if (mc.screen != ownScreen || !(ownScreen instanceof AbstractContainerScreen<?> menu)) {
                    finish(); // closed by the player or the server
                    return;
                }
                List<MenuItem> items = read(menu);
                long open = now - openedAt;
                if (items.isEmpty() && open < MAX_OPEN_MS || open < MIN_OPEN_MS) return; // the items aren't here yet
                if (!isOurs.test(items)) {
                    if (open >= MAX_OPEN_MS) finish(); // not our menu, so leave it open
                    return;
                }
                mc.player.closeContainer();
                if (mc.screen == ownScreen) mc.setScreen(null);
                Consumer<List<MenuItem>> done = result;
                finish();
                try {
                    done.accept(items);
                } catch (RuntimeException e) {
                    Telemetric.LOGGER.error("Menu read for '{}' failed", command, e);
                }
            }
            default -> {}
        }
    }

    private static void finish() {
        step = Step.IDLE;
        ownScreen = null;
        result = null;
        isOurs = null;
    }

    private static List<MenuItem> read(AbstractContainerScreen<?> screen) {
        List<MenuItem> out = new ArrayList<>();
        try {
            AbstractContainerMenu menu = screen.getMenu();
            int top = menu.slots.size() - PLAYER_SLOTS;
            for (int i = 0; i < top; i++) {
                ItemStack stack = menu.getSlot(i).getItem();
                if (stack.isEmpty()) continue;
                StringBuilder lore = new StringBuilder();
                ItemLore l = stack.get(DataComponents.LORE);
                if (l != null) {
                    for (Component line : l.lines()) {
                        if (!lore.isEmpty()) lore.append(' ');
                        lore.append(line.getString());
                    }
                }
                out.add(new MenuItem(i, stack.getHoverName().getString(), lore.toString()));
            }
        } catch (RuntimeException e) {
            // the menu changed while we read it (screen switch), so skip this time
        }
        return out;
    }
}
