package dev.telemetric.game;

import dev.telemetric.Telemetric;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Reads server menus as plain text, for the Boss Tracker's {@code /bosses} list. Every container menu the player closes
 * on Telos goes to the {@link #onMenuClosed} listeners. {@link #runCommandAndRead} runs a command that opens a menu,
 * reads it and closes it again, but only if {@code isOurs} recognises it. Other menus stay open.
 * <p>
 * <b>Background read</b>: the open-screen packet that answers our command is caught in {@code ClientPacketListenerMixin}
 * ({@link #onOpenScreen}); the menu is created without a screen ({@code player.containerMenu}), filled by the normal
 * content packets, read and closed with a close packet, so nothing shows. A menu that turns out not to be ours gets
 * its real screen after all, with the items it already received.
 */
public final class BossMenuAdapter {

    /** One menu slot: item name and lore lines joined with spaces (plain text). */
    public record MenuItem(int slot, String name, String lore) {}

    private enum Step { IDLE, SEND, WAIT_MENU, READ }

    /**
     * SEND_DELAY: after the request (the server drops commands sent the same moment a world loads). RESEND: no menu
     * came, so send once more (the first command was lost in the server switch). NOT_OURS: a hidden menu with items that
     * isn't ours is shown after this.
     */
    private static final long SEND_DELAY_MS = 150, RESEND_MS = 2_500, MENU_TIMEOUT_MS = 10_000,
            MIN_OPEN_MS = 50, MAX_OPEN_MS = 1_500, NOT_OURS_MS = 250;
    private static final int PLAYER_SLOTS = 36, MAX_SENDS = 2;

    private static final List<Consumer<List<MenuItem>>> CLOSED = new CopyOnWriteArrayList<>();
    private static boolean registered;

    private static Step step = Step.IDLE;
    private static String command;
    private static Predicate<List<MenuItem>> isOurs;
    private static Consumer<List<MenuItem>> result;
    private static boolean background;
    private static long stepAt, openedAt, sentAt;
    private static int sends;
    /** Answers to a resent command still to come (until {@link #lateUntil}): swallowed so they don't pop up. */
    private static int lateMenus;
    private static long lateUntil;
    private static Screen ownScreen;
    /** Background read: the menu we created without a screen, and its open packet (to show it if it isn't ours). */
    private static AbstractContainerMenu hidden;
    private static MenuType<?> hiddenType;
    private static Component hiddenTitle;

    private BossMenuAdapter() {}

    public static void register() {
        if (registered) return;
        registered = true;
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> menu)) return;
            ScreenEvents.remove(screen).register(s -> {
                if (s == ownScreen || !ServerGate.onTelos() || CLOSED.isEmpty()) return;
                List<MenuItem> items = read(menu.getMenu());
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
     * Runs {@code cmd} (no slash) once no container screen is open, reads the menu it opens and closes it. With
     * {@code inBackground} the menu never shows (see class doc). If {@code isOurs} rejects the items, or nothing opens
     * within 10 s, the menu is left alone and {@code done} isn't called. A new request replaces a pending one.
     */
    public static void runCommandAndRead(String cmd, boolean inBackground, Predicate<List<MenuItem>> ours,
                                         Consumer<List<MenuItem>> done) {
        if (step == Step.READ) closeHidden(); // a newer request wins
        finish();
        command = cmd;
        isOurs = ours;
        result = done;
        background = inBackground;
        sends = 0;
        step = Step.SEND;
        stepAt = System.currentTimeMillis() + SEND_DELAY_MS;
    }

    public static boolean isBusy() {
        return step != Step.IDLE;
    }

    /**
     * {@code ClientPacketListenerMixin}, client thread: the server opens a menu. True = we took it as a background
     * read (the vanilla screen isn't created).
     */
    public static boolean onOpenScreen(int containerId, MenuType<?> type, Component title) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        long now = System.currentTimeMillis();
        if (step != Step.WAIT_MENU || !background) {
            if (lateMenus <= 0 || now > lateUntil || p == null || p.connection == null
                    || mc.screen instanceof AbstractContainerScreen<?>) return false;
            lateMenus--; // the second answer to a resent command: close it unseen
            p.connection.send(new ServerboundContainerClosePacket(containerId));
            return true;
        }
        if (p == null || mc.screen instanceof AbstractContainerScreen<?>) {
            finish(); // the player opened a menu of their own meanwhile, so don't touch this one
            return false;
        }
        AbstractContainerMenu menu;
        try {
            menu = type.create(containerId, p.getInventory());
        } catch (RuntimeException e) {
            finish();
            return false;
        }
        p.containerMenu = menu; // content packets for this id now fill it
        hidden = menu;
        hiddenType = type;
        hiddenTitle = title;
        openedAt = now;
        lateMenus = sends - 1;
        lateUntil = now + MENU_TIMEOUT_MS / 2;
        step = Step.READ;
        return true;
    }

    /**
     * {@code ClientPacketListenerMixin}: the server closes the open menu. True = it was our background menu, handled
     * here (vanilla would also close any screen the player has open, like chat).
     */
    public static boolean onServerClose() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (hidden == null || p == null || p.containerMenu != hidden) return false;
        List<MenuItem> items = read(hidden);
        p.containerMenu = p.inventoryMenu;
        deliverIfOurs(items);
        return true;
    }

    private static void tick() {
        if (step == Step.IDLE) return;
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        if (mc.player == null || !ServerGate.onTelos()) {
            finish();
            return;
        }
        switch (step) {
            case SEND -> {
                if (now < stepAt) return;
                if (!canSend(mc.screen)) return; // a menu or the loading screen is open, try again next tick
                PlayerAdapter.sendCommand(command);
                sends++;
                sentAt = now;
                step = Step.WAIT_MENU;
            }
            case WAIT_MENU -> {
                if (!background && mc.screen instanceof AbstractContainerScreen<?>) {
                    ownScreen = mc.screen;
                    openedAt = now;
                    step = Step.READ;
                } else if (!background && mc.screen != null) {
                    finish(); // something else opened
                } else if (now - sentAt > MENU_TIMEOUT_MS) {
                    finish(); // the server sent no menu
                } else if (now - sentAt > RESEND_MS && sends < MAX_SENDS) {
                    step = Step.SEND; // the command got lost (sent while the server switch was finishing)
                    stepAt = now;
                }
            }
            case READ -> {
                if (hidden != null) readHidden(mc.player, now);
                else readScreen(mc, now);
            }
            default -> {}
        }
    }

    /**
     * Background: chat, the pause menu or nothing open, not a container screen (the player's) or the world loading
     * screen. Otherwise only with nothing open.
     */
    private static boolean canSend(Screen s) {
        if (!background) return s == null; // the menu would replace whatever is open
        return s == null || s instanceof ChatScreen || s instanceof PauseScreen;
    }

    private static void readHidden(LocalPlayer p, long now) {
        if (p.containerMenu != hidden) { // another menu replaced it (the server, or the player opened one)
            List<MenuItem> items = read(hidden);
            deliverIfOurs(items);
            return;
        }
        List<MenuItem> items = read(hidden);
        long open = now - openedAt;
        if (items.isEmpty() && open < MAX_OPEN_MS || open < MIN_OPEN_MS) return; // the items aren't here yet
        if (isOurs.test(items)) {
            closeHidden();
            deliver(items);
        } else if (open >= NOT_OURS_MS || items.isEmpty()) {
            showHidden(p); // not ours after all: open it like vanilla would have
            finish();
        }
    }

    private static void readScreen(Minecraft mc, long now) {
        if (mc.screen != ownScreen || !(ownScreen instanceof AbstractContainerScreen<?> menu)) {
            finish(); // closed by the player or the server
            return;
        }
        List<MenuItem> items = read(menu.getMenu());
        long open = now - openedAt;
        if (items.isEmpty() && open < MAX_OPEN_MS || open < MIN_OPEN_MS) return; // the items aren't here yet
        if (!isOurs.test(items)) {
            if (open >= MAX_OPEN_MS) finish(); // not our menu, so leave it open
            return;
        }
        mc.player.closeContainer();
        if (mc.screen == ownScreen) mc.setScreen(null);
        deliver(items);
    }

    /** Closes the background menu on the server and locally, without touching the player's screen. */
    private static void closeHidden() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (hidden == null || p == null || p.containerMenu != hidden) return;
        if (p.connection != null) p.connection.send(new ServerboundContainerClosePacket(hidden.containerId));
        p.containerMenu = p.inventoryMenu;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void showHidden(LocalPlayer p) {
        AbstractContainerMenu old = hidden;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AbstractContainerScreen<?>) return; // the player opened one of their own
        try {
            MenuScreens.create((MenuType) hiddenType, mc, old.containerId, hiddenTitle);
            if (p.containerMenu != old && p.containerMenu.containerId == old.containerId) {
                p.containerMenu.initializeContents(old.getStateId(), old.getItems(), old.getCarried());
            }
        } catch (RuntimeException e) {
            Telemetric.LOGGER.warn("Could not show menu for '{}'", command, e);
            closeHidden();
        }
    }

    private static void deliverIfOurs(List<MenuItem> items) {
        if (!items.isEmpty() && isOurs != null && isOurs.test(items)) deliver(items);
        else finish();
    }

    private static void deliver(List<MenuItem> items) {
        Consumer<List<MenuItem>> done = result;
        String cmd = command;
        finish();
        if (done == null) return;
        try {
            done.accept(items);
        } catch (RuntimeException e) {
            Telemetric.LOGGER.error("Menu read for '{}' failed", cmd, e);
        }
    }

    private static void finish() {
        step = Step.IDLE;
        ownScreen = null;
        result = null;
        isOurs = null;
        hidden = null;
        hiddenType = null;
        hiddenTitle = null;
    }

    private static List<MenuItem> read(AbstractContainerMenu menu) {
        List<MenuItem> out = new ArrayList<>();
        try {
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
