package dev.telemetric.ui.clickgui;

import dev.telemetric.Telemetric;
import dev.telemetric.config.GuiLayout;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.module.impl.misc.ConfigModule;
import dev.telemetric.module.impl.hud.WatermarkHud;
import dev.telemetric.module.impl.misc.InterfaceModule;
import dev.telemetric.ui.hud.HudRenderer;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.framework.GuiContext;
import dev.telemetric.ui.framework.UIComponent;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * MC ADAPTER — the ClickGUI screen. Converts Minecraft's input records into framework primitives
 * (in ClickGUI units, i.e. divided by UI scale) and drives the open/close sequence:
 * <ul>
 *   <li>open: backdrop fades in over {@link Durations#SCREEN}, panels slide/fade in staggered,</li>
 *   <li>close: everything fades out over {@link Durations#MODAL} before the screen is actually removed.</li>
 * </ul>
 * Rendering order: backdrop → panels (one stratum each, topmost last) → tooltip → toasts → watermark.
 */
public class ClickGuiScreen extends Screen {

    private final GuiContext ctx;
    private final Render2D r = new Render2D();
    /** Stacking order, bottom-most first (last = top, gets input first). */
    private final List<Panel> panels = new ArrayList<>();
    /** Same panels in category order — used for auto-arranging. */
    private final List<Panel> categoryOrder = new ArrayList<>();
    private final TooltipRenderer tooltip;
    private final ToastRenderer toasts;

    private final Animation open = new Animation(Durations.SCREEN, Easing.OUT_CUBIC, 0);
    private final Animation close = new Animation(Durations.MODAL, Easing.IN_CUBIC, 1);
    private boolean closing;
    private boolean finished;

    public ClickGuiScreen() {
        super(Component.literal(Telemetric.NAME));
        Telemetric mod = Telemetric.get();
        this.ctx = new GuiContext(mod.theme());
        this.tooltip = new TooltipRenderer(ctx);
        this.toasts = new ToastRenderer(ctx);

        GuiLayout layout = mod.config().getLayout();
        for (Category category : Category.values()) {
            List<Module> modules = mod.modules().getByCategory(category);
            Panel panel = new Panel(ctx, category, modules, layout.get(category.getName()));
            panels.add(panel); // every registered category gets a panel, even before it has modules
        }
        categoryOrder.addAll(panels);
        mod.modules().get(ConfigModule.class).refreshProfiles();

        open.animateTo(1);
        for (int i = 0; i < panels.size(); i++) panels.get(i).playAppear(i * Durations.STAGGER);
        restoreZOrder(layout.getZOrder());
    }

    /** Re-stacks panels as they were when the GUI was last closed (panels not listed stay at the bottom). */
    private void restoreZOrder(List<String> saved) {
        panels.sort(Comparator.comparingInt(p -> saved.indexOf(p.getId())));
    }

    // ================================================================== helpers

    private InterfaceModule ui() {
        return ctx.ui();
    }

    private float scale() {
        return ui().uiScale.getFloat();
    }

    private int scaledWidth() {
        return (int) (this.width / scale());
    }

    private int scaledHeight() {
        return (int) (this.height / scale());
    }

    private double screenAlpha() {
        return open.get() * close.get();
    }

    /**
     * Places panels that have never been positioned (first launch, new category, or "Reset Layout").
     * Each one goes into the left-most column slot that no placed panel overlaps, so a newly added
     * category never lands on top of panels the user already arranged.
     */
    private void arrangeUnplaced() {
        int panelWidth = ui().panelWidth.getInt();
        int step = panelWidth + Layout.PANEL_GAP;
        int start = Layout.PANEL_GAP * 2;
        int screenW = scaledWidth();
        for (Panel panel : categoryOrder) {
            GuiLayout.PanelState state = panel.getState();
            if (state.placed) continue;
            int x = start;
            for (int slot = start; slot + panelWidth <= Math.max(screenW, start + panelWidth); slot += step) {
                if (isColumnFree(slot, panelWidth)) {
                    x = slot;
                    break;
                }
            }
            state.x = x;
            state.y = Layout.PANEL_MARGIN_TOP;
            state.placed = true;
        }
    }

    private boolean isColumnFree(int x, int width) {
        for (Panel other : categoryOrder) {
            GuiLayout.PanelState s = other.getState();
            if (s.placed && x < s.x + width && s.x < x + width) return false;
        }
        return true;
    }

    private void bringToFront(Panel panel) {
        if (panels.remove(panel)) panels.add(panel);
    }

    // ================================================================== rendering

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Own backdrop instead of vanilla blur: tinted dim, fades with the open/close animation.
        Theme p = Telemetric.get().theme().palette();
        double dim = ui().backgroundDim.get() / 100.0;
        int top = ColorUtil.fade(p.backdrop(), dim * screenAlpha());
        int bottom = ColorUtil.fade(ColorUtil.shade(p.backdrop(), -0.4), dim * screenAlpha());
        graphics.fillGradient(0, 0, this.width, this.height, top, bottom);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        Telemetric.get().theme().update();
        arrangeUnplaced();

        float scale = scale();
        int mx = (int) (mouseX / scale), my = (int) (mouseY / scale);
        int sw = scaledWidth(), sh = scaledHeight();
        if (closing) mx = my = Integer.MIN_VALUE / 2; // no hover effects while closing

        r.begin(graphics, Telemetric.get().theme().textShadow());
        r.pushScale(scale);
        r.pushAlpha(screenAlpha());

        ctx.clearTooltipRequest();
        for (Panel panel : panels) {
            r.nextLayer();
            panel.render(r, mx, my, delta, sw, sh);
        }

        r.nextLayer();
        tooltip.render(r, mx, my, sw, sh);
        toasts.render(r, sw, sh);
        WatermarkHud hudWatermark = Telemetric.get().modules().get(WatermarkHud.class);
        if (!hudWatermark.isEnabled()) drawWatermark(sw);

        r.popAlpha();
        r.popScale();
        // HUD watermark: same spot as in-game, drawn above the backdrop at full opacity (no blink on open)
        if (hudWatermark.isEnabled()) HudRenderer.render(r, hudWatermark, this.width, this.height, false);
        r.end();
    }

    private void drawWatermark(int sw) {
        Theme p = ctx.palette();
        String name = Telemetric.NAME;
        String version = " v" + Telemetric.version();
        int total = r.textWidth(name) + r.textWidth(version);
        int x = sw - total - 8;
        r.text(name, x, 7, p.accent());
        r.text(version, x + r.textWidth(name), 7, p.textMuted());
        r.gradientH(x, 18, total, 1, p.accent(), ColorUtil.withAlpha(p.accentAlt(), 0));
    }

    // ================================================================== lifecycle

    @Override
    public void tick() {
        super.tick();
        if (closing && !close.isRunning()) finishClose();
    }

    @Override
    public boolean isPauseScreen() {
        return ui().pauseGame.get();
    }

    /** Esc / programmatic close: play the close animation first. */
    @Override
    public void onClose() {
        if (closing) return;
        closing = true;
        ctx.clearFocus();
        close.animateTo(0);
        if (!Durations.isEnabled()) finishClose();
    }

    private void finishClose() {
        if (finished) return;
        persist();
        Minecraft.getInstance().setScreen(null);
    }

    /** Also covers the screen being replaced externally (disconnect, another mod opening a screen…). */
    @Override
    public void removed() {
        persist();
        super.removed();
    }

    private void persist() {
        if (finished) return;
        finished = true;
        panels.forEach(UIComponent::onClose); // panels write open/scroll state into GuiLayout here
        Telemetric.get().config().getLayout().setZOrder(panels.stream().map(Panel::getId).toList());
        Telemetric.get().config().onGuiClosed();
    }

    // ================================================================== input (MC records → primitives)

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (closing) return true;
        double mx = event.x() / scale(), my = event.y() / scale();
        int button = event.button();

        UIComponent focused = ctx.getFocused();
        if (focused != null && !focused.isHovered(mx, my)) ctx.clearFocus();

        for (int i = panels.size() - 1; i >= 0; i--) {
            Panel panel = panels.get(i);
            if (panel.mouseClicked(mx, my, button)) {
                bringToFront(panel);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        double mx = event.x() / scale(), my = event.y() / scale();
        boolean handled = false;
        for (Panel panel : panels) handled |= panel.mouseReleased(mx, my, event.button());
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (closing) return true;
        double mx = mouseX / scale(), my = mouseY / scale();
        for (int i = panels.size() - 1; i >= 0; i--) {
            Panel panel = panels.get(i);
            if (panel.isHovered(mx, my)) return panel.mouseScrolled(mx, my, scrollY);
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (closing) return true;
        UIComponent focused = ctx.getFocused();
        if (focused != null && focused.keyPressed(event.key(), event.scancode(), event.modifiers())) return true;
        return super.keyPressed(event); // Esc → onClose()
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        UIComponent focused = ctx.getFocused();
        if (focused != null && focused.charTyped(event.codepoint(), 0)) return true;
        return super.charTyped(event);
    }
}
