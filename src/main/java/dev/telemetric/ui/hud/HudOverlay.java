package dev.telemetric.ui.hud;

import dev.telemetric.Telemetric;
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

/**
 * MC ADAPTER — draws enabled HUD elements in-game through Fabric's {@link HudElementRegistry}
 * (layer placed just below chat, so chat stays readable on top).
 * <p>
 * Skipped off Telos Realms ({@link ServerGate}), while F1 hides the GUI and while the HUD editor is open (the editor draws its own copies).
 * While the ClickGUI is open the watermark is drawn by the ClickGUI on top of its backdrop instead.
 * <p>
 * Toast notifications ({@code Toasts}) are drawn here too whenever the ClickGUI is closed (the ClickGUI draws its
 * own), so messages from in-game features show up without opening it. Only F1 hides them.
 */
public final class HudOverlay {

    private static final Render2D R = new Render2D();
    private static final java.util.Set<String> FAILED = new java.util.HashSet<>();
    private static final ToastRenderer TOASTS = new ToastRenderer(() -> Telemetric.get().theme().palette());

    private HudOverlay() {}

    public static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(Telemetric.MOD_ID, "hud"), HudOverlay::render);
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
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
