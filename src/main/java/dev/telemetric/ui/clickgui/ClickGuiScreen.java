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
 * MC ADAPTER: the ClickGUI screen. It converts Minecraft's input records into framework primitives (in ClickGUI units,
 * i.e. divided by the UI scale) and drives the open/close sequence:
 * <ul>
 *   <li>on open, the backdrop fades in over {@link Durations#SCREEN} and the panels slide and fade in, staggered;</li>
 *   <li>on close, everything fades out over {@link Durations#MODAL} before the screen is actually removed.</li>
 * </ul>
 * Rendering order: backdrop, then panels (one stratum each, topmost last), then tooltip, toasts and watermark.
 */
public class ClickGuiScreen extends Screen {

    private final GuiContext ctx;
    private final Render2D r = new Render2D();
    /** Stacking order, bottom-most first (the last one is on top and gets input first). */
    private final List<Panel> panels = new ArrayList<>();
    /** The same panels in category order, used for auto-arranging. */
    private final List<Panel> categoryOrder = new ArrayList<>();
    private final TooltipRenderer tooltip;
    private final ToastRenderer toasts;
    private final SearchBar search;

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
        this.search = new SearchBar(ctx, query -> panels.forEach(panel -> panel.setSearch(query)));
        search.playAppear(panels.size() * Durations.STAGGER);
    }

    /** Re-stacks the panels the way they were when the GUI was last closed (panels not in the list stay at the bottom). */
    private void restoreZOrder(List<String> saved) {
        panels.sort(Comparator.comparingInt(p -> saved.indexOf(p.getId())));
    }

    // ================================================================== helpers

    private float appliedScale;
    private int heldButtons; // bitmask of the mouse buttons currently held down inside this screen

    private InterfaceModule ui() {
        return ctx.ui();
    }

    /**
     * The scale that's actually applied to the GUI. It's frozen while a mouse button is held: the UI Scale slider maps
     * the mouse in scaled coordinates, so rescaling live mid-drag would feed back into the slider and make it jump. The
     * new value gets applied on release.
     */
    private float scale() {
        if (heldButtons == 0 || appliedScale <= 0) appliedScale = ui().uiScale.getFloat() * fitFactor();
        return appliedScale;
    }

    /**
     * Reference canvas for "Fit to Screen": 4K at GUI scale 3 (1280×720 GUI pixels). Every other resolution or GUI scale
     * is scaled so the ClickGUI covers the same share of the screen, and saved panel positions (stored in canvas
     * pixels) end up in the same place.
     */
    private static final float REF_WIDTH = 1280f, REF_HEIGHT = 720f;

    private float fitFactor() {
        if (!ui().fitToScreen.get() || this.width <= 0 || this.height <= 0) return 1f;
        return Math.min(this.width / REF_WIDTH, this.height / REF_HEIGHT);
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
     * Places the panels that have never been positioned (first launch, a new category, or "Reset Layout"). Each one goes
     * into the left-most column slot that no placed panel overlaps, so a newly added category never lands on top of
     * panels the user already arranged.
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
        // our own backdrop instead of vanilla's blur: a tinted dim that fades with the open/close animation
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
        search.render(r, mx, my, delta, sw, sh);

        r.nextLayer();
        tooltip.render(r, mx, my, sw, sh);
        toasts.render(r, sw, sh);
        WatermarkHud hudWatermark = Telemetric.get().modules().get(WatermarkHud.class);
        if (!hudWatermark.isEnabled()) drawWatermark(sw);

        r.popAlpha();
        r.popScale();
        // HUD watermark: same spot as in-game, drawn above the backdrop at full opacity (so it doesn't blink on open)
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

    /** Esc or a close from code: play the close animation first. */
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

    /** This also covers the screen being replaced from outside (disconnect, another mod opening a screen…). */
    @Override
    public void removed() {
        persist();
        super.removed();
    }

    private void persist() {
        if (finished) return;
        finished = true;
        search.onClose();
        panels.forEach(UIComponent::onClose); // the panels write their open/scroll state into GuiLayout here
        Telemetric.get().config().getLayout().setZOrder(panels.stream().map(Panel::getId).toList());
        Telemetric.get().config().onGuiClosed();
    }

    // ================================================================== input (MC records to primitives)

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (closing) return true;
        double mx = event.x() / scale(), my = event.y() / scale();
        int button = event.button();
        if (button >= 0 && button < 31) heldButtons |= 1 << button;

        UIComponent focused = ctx.getFocused();
        // a key binder that's listening takes middle and side buttons wherever the mouse is
        if (focused != null && focused.focusedMouseButton(button)) return true;
        if (focused != null && !focused.isHovered(mx, my)) ctx.clearFocus();

        if (search.mouseClicked(mx, my, button)) return true; // drawn above the panels, so it gets input first
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
        int button = event.button();
        if (button >= 0 && button < 31) heldButtons &= ~(1 << button);
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
        return super.keyPressed(event); // Esc goes to onClose()
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        UIComponent focused = ctx.getFocused();
        if (focused != null && focused.charTyped(event.codepoint(), 0)) return true;
        return super.charTyped(event);
    }
}
