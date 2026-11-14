package dev.telemetric.game;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.telemetric.Telemetric;
import dev.telemetric.input.InputUtil;
import dev.telemetric.module.impl.visual.StashOverlayModule;
import dev.telemetric.stash.StashMode;
import dev.telemetric.stash.StashPage;
import dev.telemetric.stash.StashSession;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.stash.StashOverlay;
import dev.telemetric.stash.SlotAction;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MC ADAPTER — the Telos stash screen.
 * <ul>
 *   <li>Detection: a 6-row chest whose slot 53 has an {@code telos:icon/stash/...} item model (the title is
 *       a single resource-pack glyph and shows no page number).</li>
 *   <li>Mode: slot 53 is {@code view_seasonal} while normal pages are shown (lore "Currently viewing normal
 *       pages"), and the reverse in seasonal mode.</li>
 *   <li>Clicks are sent with {@code MultiPlayerGameMode#handleContainerInput}; the mixin on that method
 *       reports every click (ours and the player's) to {@link StashSession#onSlotClick}.</li>
 *   <li>Items are saved with {@link ItemStack#OPTIONAL_CODEC} through {@link RegistryOps} as JSON.</li>
 *   <li>The overlay replaces the stash: {@code StashScreenMixin}/{@code StashBackgroundMixin} skip the container's own
 *       drawing, the overlay is drawn after the screen ({@code ScreenEvents.afterExtract}) and takes every
 *       click, release, drag, scroll and slot key while it is shown.</li>
 *   <li>Telos asks to confirm a normal/seasonal switch in a pop-up; {@link #confirmPopup} answers it.</li>
 * </ul>
 */
public final class StashAdapter {

    private static final Render2D R = new Render2D();
    private static final Set<String> FAILED = new HashSet<>();

    private StashAdapter() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?>)) {
                // Telos' mode-switch pop-up: press Ok before it is ever drawn (tasks queued here still run
                // before this frame renders), then the hold screen keeps the overlay up
                if (enabled() && !(screen instanceof StashHoldScreen) && StashSession.get().isSwitching()) {
                    client.execute(() -> StashSession.get().answerPopup());
                }
                return;
            }
            ScreenEvents.afterExtract(screen).register(StashAdapter::afterExtract);
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) ->
                    !active(s) || !StashOverlay.mouseClicked(event.x(), event.y(), event.button(), event.hasShiftDown()));
            ScreenEvents.remove(screen).register(s -> {
                StashOverlay.onClose();
                // Telos closes the stash for a mode switch: no frame of the bare world before it reopens
                if (enabled() && StashSession.get().isSwitching()) client.execute(StashAdapter::holdOverlay);
            });
            ScreenMouseEvents.allowMouseScroll(screen).register((s, mx, my, h2, v) ->
                    !active(s) || !StashOverlay.mouseScrolled(mx, my, v));
            // the real slots are invisible but still there: never let vanilla see releases/drags (quick-craft, drop);
            // the overlay gets them instead (shift + hold drag quick-moves)
            ScreenMouseEvents.allowMouseRelease(screen).register((s, event) -> {
                if (!active(s)) return true;
                StashOverlay.mouseReleased(event.button());
                return false;
            });
            ScreenMouseEvents.allowMouseDrag(screen).register((s, event, dx, dy) -> !active(s)
                    || !StashOverlay.mouseDragged(event.x(), event.y(), event.button(), event.hasShiftDown() || InputUtil.isShiftDown()));
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, key) -> !active(s) || !onKey(key));
            ScreenKeyboardEvents.allowCharType(screen).register((s, ch) ->
                    !active(s) || !StashOverlay.charTyped(ch.codepointAsString()));
        });
    }

    /** @return true if the overlay used the key (it is then not passed to the screen). */
    private static boolean onKey(KeyEvent key) {
        int k = key.key();
        if (StashOverlay.isRenaming()) {
            if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) StashOverlay.renameKey(StashOverlay.RenameKey.CONFIRM);
            else if (k == GLFW.GLFW_KEY_ESCAPE) StashOverlay.renameKey(StashOverlay.RenameKey.CANCEL);
            else if (k == GLFW.GLFW_KEY_BACKSPACE) StashOverlay.renameKey(StashOverlay.RenameKey.BACKSPACE);
            return true; // typing must not close the screen (E) or trigger slot keys
        }
        if (StashOverlay.isSearchFocused()) {
            if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) StashOverlay.searchKey(StashOverlay.SearchKey.CONFIRM);
            else if (k == GLFW.GLFW_KEY_ESCAPE) StashOverlay.searchKey(StashOverlay.SearchKey.CANCEL);
            else if (k == GLFW.GLFW_KEY_BACKSPACE) {
                StashOverlay.searchKey(key.hasControlDown() ? StashOverlay.SearchKey.CLEAR : StashOverlay.SearchKey.BACKSPACE);
            }
            return true; // same as rename: typed letters must not close the screen or hit slots
        }
        if (k == GLFW.GLFW_KEY_F && key.hasControlDown()) {
            StashOverlay.focusSearch();
            return true;
        }
        Options o = Minecraft.getInstance().options;
        for (int i = 0; i < o.keyHotbarSlots.length; i++) {
            if (o.keyHotbarSlots[i].matches(key)) return StashOverlay.hoverAction(SlotAction.SWAP, i);
        }
        if (o.keySwapOffhand.matches(key)) return StashOverlay.hoverAction(SlotAction.SWAP, 40);
        if (o.keyDrop.matches(key)) return StashOverlay.hoverAction(SlotAction.THROW, key.hasControlDown() ? 1 : 0);
        if (k == GLFW.GLFW_KEY_LEFT) {
            StashSession.get().step(-1);
            return true;
        }
        if (k == GLFW.GLFW_KEY_RIGHT) {
            StashSession.get().step(1);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ mixin hooks

    public static void onContainerPacket(int containerId) {
        if (enabled()) StashSession.get().onContainerPacket(containerId);
    }

    public static void onSlotClick(int containerId, int slot) {
        if (enabled()) StashSession.get().onSlotClick(containerId, slot);
    }

    // ------------------------------------------------------------------ screen state

    private static boolean enabled() {
        try {
            return ServerGate.onTelos() && Telemetric.get().modules().get(StashOverlayModule.class).isEnabled();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** Mixin hook: true while the overlay replaces this container screen. */
    public static boolean replacesScreen(Object screen) {
        return screen instanceof Screen s && active(s);
    }

    private static boolean active(Screen screen) {
        return enabled() && isStash(screen) && StashSession.get().isOpen();
    }

    public static boolean isStashOpen() {
        return isStash(Minecraft.getInstance().screen);
    }

    private static boolean isStash(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> acs)) return false;
        if (!(acs.getMenu() instanceof ChestMenu menu) || menu.getRowCount() != 6) return false;
        // a click on the mode toggle puts it on the cursor client-side until Telos answers: still the stash
        return isStashIcon(menu.getSlot(StashSession.SLOT_MODE).getItem()) || isStashIcon(menu.getCarried());
    }

    private static boolean isStashIcon(ItemStack stack) {
        Identifier model = stack.get(DataComponents.ITEM_MODEL);
        return model != null && model.getNamespace().equals("telos") && model.getPath().startsWith("icon/stash/");
    }

    public static int containerId() {
        return Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> acs ? acs.getMenu().containerId : -1;
    }

    /** Mode shown right now, or {@code null} if slot 53 can't be read. */
    public static StashMode readMode() {
        if (!(Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> acs)) return null;
        ItemStack toggle = acs.getMenu().getSlot(StashSession.SLOT_MODE).getItem();
        ItemLore lore = toggle.get(DataComponents.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) {
                String text = line.getString().toLowerCase(Locale.ROOT);
                if (text.contains("currently viewing")) {
                    if (text.contains("seasonal")) return StashMode.SEASONAL;
                    if (text.contains("normal")) return StashMode.NORMAL;
                }
            }
        }
        Identifier model = toggle.get(DataComponents.ITEM_MODEL);
        if (model == null) return null;
        if (model.getPath().endsWith("view_seasonal")) return StashMode.NORMAL;  // button offers the other mode
        if (model.getPath().endsWith("view_normal")) return StashMode.SEASONAL;
        return null;
    }

    /** The 45 item slots of the open stash as item JSON ({@code null} = empty). */
    /** True if the shown stash page has no items in slots 0–44 (Telos only converts empty pages). */
    public static boolean isPageEmpty() {
        if (!(Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> acs)) return false;
        for (int i = 0; i < StashPage.SLOTS; i++) if (!acs.getMenu().getSlot(i).getItem().isEmpty()) return false;
        return true;
    }

    public static String[] readPage() {
        String[] out = new String[StashPage.SLOTS];
        if (!(Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> acs)) return out;
        RegistryOps<JsonElement> ops = ops();
        if (ops == null) return out;
        for (int i = 0; i < StashPage.SLOTS; i++) {
            ItemStack stack = acs.getMenu().getSlot(i).getItem();
            if (stack.isEmpty()) continue;
            out[i] = ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).result().map(JsonElement::toString).orElse(null);
        }
        return out;
    }

    /** Clicks a slot of the open stash (left click, like the player would). */
    public static void click(int slot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null || mc.player == null) return;
        if (!(mc.screen instanceof AbstractContainerScreen<?> acs)) return;
        mc.gameMode.handleContainerInput(acs.getMenu().containerId, slot, 0, ContainerInput.PICKUP, mc.player);
    }

    /** Sends a slot click of the open stash menu (stash slots 0-44, inventory 54-80, hotbar 81-89). */
    public static void slotInput(int slot, SlotAction action, int button) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null || mc.player == null) return;
        if (!(mc.screen instanceof AbstractContainerScreen<?> acs)) return;
        AbstractContainerMenu menu = acs.getMenu();
        if (slot != -999 && (slot < 0 || slot >= menu.slots.size())) return; // -999 = outside the window (drop)
        ContainerInput input = switch (action) {
            case PICKUP -> ContainerInput.PICKUP;
            case QUICK_MOVE -> ContainerInput.QUICK_MOVE;
            case SWAP -> ContainerInput.SWAP;
            case THROW -> ContainerInput.THROW;
            case PICKUP_ALL -> ContainerInput.PICKUP_ALL;
        };
        if (action == SlotAction.THROW && !menu.getCarried().isEmpty()) return; // vanilla: no drop while carrying
        mc.gameMode.handleContainerInput(menu.containerId, slot, button, input, mc.player);
    }

    /**
     * Item in a slot of the open stash menu right now, or {@code null} if empty. Without a container screen
     * (mode switch in progress) the inventory slots (54-89) come from the player's inventory.
     */
    public static GameItem liveItem(int slot) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof AbstractContainerScreen<?> acs)) {
            if (mc.player == null || slot < 54 || slot > 89) return null;
            ItemStack stack = mc.player.getInventory().getItem(slot >= 81 ? slot - 81 : slot - 54 + 9);
            return stack.isEmpty() ? null : new GameItem(stack);
        }
        if (slot < 0 || slot >= acs.getMenu().slots.size()) return null;
        ItemStack stack = acs.getMenu().getSlot(slot).getItem();
        return stack.isEmpty() ? null : new GameItem(stack);
    }

    /** Item on the mouse cursor, or {@code null}. */
    public static GameItem carried() {
        if (!(Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> acs)) return null;
        ItemStack stack = acs.getMenu().getCarried();
        return stack.isEmpty() ? null : new GameItem(stack);
    }

    /** True if both items stack together (same item and components). */
    public static boolean sameItem(GameItem a, GameItem b) {
        return a != null && b != null && ItemStack.isSameItemSameComponents(a.stack(), b.stack());
    }

    // ------------------------------------------------------------------ mode-switch confirm pop-up

    private static final String[] YES = {"ok", "okay", "confirm", "yes", "accept", "continue", "proceed", "switch", "view"};
    private static final String[] NO = {"cancel", "deny", "decline", "back", "close", "return", "convert", "learn"};
    private static Screen loggedPopup;

    /**
     * Answers the pop-up Telos shows after the normal/seasonal toggle. Seen live (2026-11-14): a
     * {@code SimpleDialogScreen} titled "Stash" with a seasonal-mode WARNING text and one "Ok" button (plus the
     * vanilla "This is a custom screen. Learn more." info button, which must not be pressed). Presses the
     * matching dialog button. Never clicks into a container screen: until 0.17.6 a container here was taken as a
     * chest-menu pop-up, but it was the stash itself mid-click — that picked up stash items and hid the real pop-up.
     * Only called right after a mode-switch click while the stash is not the screen.
     * Logs what it saw once per pop-up so the rule can be tightened.
     * @return true if something was clicked
     */
    public static boolean confirmPopup() {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        if (screen == null || screen instanceof AbstractContainerScreen<?>) return false;
        boolean log = loggedPopup != screen;
        loggedPopup = screen;
        if (log) Telemetric.LOGGER.info("Stash: mode-switch pop-up {} '{}'", screen.getClass().getName(),
                screen.getTitle().getString());

        List<AbstractButton> buttons = new ArrayList<>();
        collectButtons(screen.children(), buttons);
        AbstractButton best = null;
        int bestScore = 0;
        for (AbstractButton b : buttons) {
            String text = b.getMessage().getString().toLowerCase(Locale.ROOT);
            if (log) Telemetric.LOGGER.info("Stash:   button '{}'", text);
            if (!b.visible || !b.isActive()) continue;
            int score = score(text);
            if (score > bestScore) {
                bestScore = score;
                best = b;
            }
        }
        if (best == null) return false;
        Telemetric.LOGGER.info("Stash: confirming mode switch with button '{}'", best.getMessage().getString());
        best.onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
        return true;
    }

    private static void collectButtons(List<? extends GuiEventListener> children, List<AbstractButton> out) {
        for (GuiEventListener c : children) {
            if (c instanceof AbstractButton b) out.add(b);
            else if (c instanceof ContainerEventHandler h) collectButtons(h.children(), out);
        }
    }

    /** 2 = says yes/confirm and not cancel, 0 = no match. */
    private static int score(String text) {
        for (String n : NO) if (containsWord(text, n)) return 0;
        for (String y : YES) if (containsWord(text, y)) return 2;
        return 0;
    }

    private static boolean containsWord(String text, String word) {
        int i = text.indexOf(word);
        while (i >= 0) {
            boolean start = i == 0 || !Character.isLetter(text.charAt(i - 1));
            int end = i + word.length();
            boolean stop = end >= text.length() || !Character.isLetter(text.charAt(end));
            if (start && stop) return true;
            i = text.indexOf(word, i + 1);
        }
        return false;
    }

    /** Decodes saved item JSON; {@code null} if it can't be read (e.g. no world loaded). */
    public static GameItem decode(String json) {
        RegistryOps<JsonElement> ops = ops();
        if (ops == null || json == null) return null;
        try {
            return ItemStack.OPTIONAL_CODEC.parse(ops, JsonParser.parseString(json)).result()
                    .filter(s -> !s.isEmpty()).map(GameItem::new).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Key that separates saved stashes per account. */
    public static String playerKey() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null ? mc.player.getUUID().toString() : "unknown";
    }

    private static RegistryOps<JsonElement> ops() {
        Minecraft mc = Minecraft.getInstance();
        HolderLookup.Provider registries = mc.level != null ? mc.level.registryAccess() : null;
        return registries == null ? null : RegistryOps.create(JsonOps.INSTANCE, registries);
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Mode switch in progress and no stash on screen (Telos closed it / its pop-up was answered): show the
     * overlay on a {@link StashHoldScreen} until the stash reopens, instead of the world or the pop-up.
     */
    public static void holdOverlay() {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled() || !StashSession.get().isSwitching()) return;
        Screen screen = mc.screen;
        if (screen instanceof StashHoldScreen || isStash(screen)) return;
        if (screen instanceof AbstractContainerScreen<?>) return; // never cover a container
        if (screen != null && !StashSession.get().isPopupAnswered()) return; // pop-up not answered yet
        mc.setScreen(new StashHoldScreen());
    }

    private static void afterExtract(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (!active(screen)) return;
        drawOverlay(screen, graphics, mouseX, mouseY);
    }

    static void drawOverlay(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Telemetric mod = Telemetric.get();
        mod.theme().update();
        R.begin(graphics, mod.theme().textShadow());
        try {
            StashOverlay.render(R, mouseX, mouseY, screen.width, screen.height);
        } catch (RuntimeException e) {
            if (FAILED.add(e.getClass().getName())) Telemetric.LOGGER.error("Stash overlay failed to render", e);
        } finally {
            R.end();
        }
    }
}
