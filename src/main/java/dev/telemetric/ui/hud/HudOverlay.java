package dev.telemetric.ui.hud;

import dev.telemetric.Telemetric;
import dev.telemetric.game.HudHideAdapter;
import dev.telemetric.game.ServerGate;
import dev.telemetric.hud.HudModule;
import dev.telemetric.module.impl.hud.WatermarkHud;
import dev.telemetric.ui.clickgui.ClickGuiScreen;
import dev.telemetric.ui.clickgui.ToastRenderer;
import dev.telemetric.ui.render.Render2D;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Draws the enabled HUD elements in-game through Fabric's {@link HudElementRegistry}, on a layer just below
 * the chat so the chat stays readable on top.
 * <p>
 * It's skipped off Telos Realms ({@link ServerGate}), while F1 hides the GUI, during the black fade when Hide HUD
 * asks for it ({@link HudHideAdapter}), and while the HUD editor is open (the
 * editor draws its own copies). While the ClickGUI is open, the ClickGUI draws the watermark on top of its backdrop
 * instead.
 * <p>
 * Toast notifications ({@code Toasts}) are drawn here too whenever the ClickGUI is closed (the ClickGUI draws its
 * own), so messages from in-game features show up without opening it. Only F1 hides them.
 */
public final class HudOverlay {

    private static final Render2D R = new Render2D();
    private static final java.util.Set<String> FAILED = new java.util.HashSet<>();
    private static final ToastRenderer TOASTS = new ToastRenderer(() -> Telemetric.get().theme().palette());

    /** Free-form drawing in the HUD layer (e.g. a bar projected onto the player), done after the HUD elements. */
    @FunctionalInterface
    public interface ScreenDrawer {
        void draw(Render2D r, int screenWidth, int screenHeight);
    }

    private static final List<ScreenDrawer> DRAWERS = new CopyOnWriteArrayList<>();

    private HudOverlay() {}

    /** Uses the same gates as HUD elements: Telos only, not with F1 or in the HUD editor. */
    public static void addScreenDrawer(ScreenDrawer drawer) {
        DRAWERS.add(drawer);
    }

    public static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(Telemetric.MOD_ID, "hud"), HudOverlay::render);
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || HudHideAdapter.hidden()) return;
        Screen screen = mc.screen;
        boolean clickGui = screen instanceof ClickGuiScreen;

        Telemetric.get().theme().update();
        int sw = graphics.guiWidth(), sh = graphics.guiHeight();
        if (ServerGate.onTelos() && !(screen instanceof HudEditorScreen)) renderElements(graphics, sw, sh, clickGui);
        if (!clickGui) renderToasts(graphics, sw, sh); // on top of the HUD elements
    }

    private static void renderElements(GuiGraphicsExtractor graphics, int sw, int sh, boolean clickGui) {
        R.begin(graphics, Telemetric.get().theme().textShadow());
        try {
            for (HudModule element : HudRenderer.activeElements()) {
                if (clickGui && element instanceof WatermarkHud) continue;
                try {
                    if (!element.hasData()) continue;
                    HudRenderer.render(R, element, sw, sh, false);
                } catch (RuntimeException e) {
                    if (FAILED.add(element.getName())) { // log once per element, not every frame
                        Telemetric.LOGGER.error("HUD element {} failed to render", element.getName(), e);
                    }
                }
            }
            for (ScreenDrawer drawer : DRAWERS) {
                try {
                    drawer.draw(R, sw, sh);
                } catch (RuntimeException e) {
                    if (FAILED.add("drawer:" + drawer.getClass().getName())) Telemetric.LOGGER.error("HUD drawer failed", e);
                }
            }
        } finally {
            R.end();
        }
    }

    private static void renderToasts(GuiGraphicsExtractor graphics, int sw, int sh) {
        R.begin(graphics, Telemetric.get().theme().textShadow());
        try {
            TOASTS.render(R, sw, sh);
        } catch (RuntimeException e) {
            if (FAILED.add("toasts")) Telemetric.LOGGER.error("Toasts failed to render", e);
        } finally {
            R.end();
        }
    }
}
