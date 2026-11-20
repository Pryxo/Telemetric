package dev.telemetric.ui.hud;

import dev.telemetric.Telemetric;
import dev.telemetric.hud.HudModule;
import dev.telemetric.input.InputUtil;
import dev.telemetric.module.impl.misc.HudSettingsModule;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.clickgui.ClickGuiScreen;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MC ADAPTER: the HUD editor. It shows every <b>enabled</b> HUD element (with placeholder data where the real data is
 * missing) and lets you:
 * <ul>
 *   <li>left-drag an element to move it (it snaps to screen edges, the centre and other elements; Shift for free),</li>
 *   <li>drag the corner handle, or scroll over an element, to resize it,</li>
 *   <li>right-click an element to reset its size, and use the arrow keys to nudge the selection (Shift for 10px),</li>
 *   <li>press Delete to switch the selected element off, and Esc to finish (back to the ClickGUI if it was opened from there).</li>
 * </ul>
 * Positions are stored in the elements' own settings, so they get saved with the active profile.
 */
public class HudEditorScreen extends Screen {

    private static final int SNAP = 4, HANDLE = 5;
    private static final double SCROLL_STEP = 0.05;

    private final boolean returnToGui;
    private final Render2D r = new Render2D();
    private final Animation open = new Animation(Durations.SCREEN, Easing.OUT_CUBIC, 0);

    private List<HudModule> elements = new ArrayList<>();
    private HudModule selected;
    private HudModule dragging;
    private boolean resizing;
    private double grabDx, grabDy;
    private int resizeLeft, resizeTop;
    private final List<int[]> guides = new ArrayList<>(); // {vertical?1:0, position}
    private boolean saved;

    public HudEditorScreen(boolean returnToGui) {
        super(Component.literal("HUD Editor"));
        this.returnToGui = returnToGui;
        open.animateTo(1);
    }

    /** Opens the editor. When it's called from the ClickGUI, Esc goes back there. */
    public static void openFromGui() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new HudEditorScreen(mc.screen instanceof ClickGuiScreen));
    }

    private HudSettingsModule style() {
        return Telemetric.get().modules().get(HudSettingsModule.class);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ================================================================== rendering

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        Theme p = Telemetric.get().theme().palette();
        graphics.fill(0, 0, this.width, this.height, ColorUtil.fade(p.backdrop(), 0.25 * open.get()));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        Telemetric mod = Telemetric.get();
        mod.theme().update();
        Theme p = mod.theme().palette();
        elements = HudRenderer.activeElements();
        if (selected != null && !elements.contains(selected)) selected = null;
        int sw = this.width, sh = this.height;

        r.begin(graphics, mod.theme().textShadow());
        try {
            updateDrag(mouseX, mouseY, sw, sh);

            // centre guides
            r.pushAlpha(open.get());
            int faint = ColorUtil.fade(p.border(), 0.5);
            r.rect(sw / 2, 0, 1, sh, faint);
            r.rect(0, sh / 2, sw, 1, faint);
            r.popAlpha();

            HudModule hovered = dragging != null ? dragging : elementAt(mouseX, mouseY);
            for (HudModule element : elements) {
                r.nextLayer();
                drawElement(element, element == hovered, sw, sh, p);
            }

            r.nextLayer();
            for (int[] g : guides) {
                if (g[0] == 1) r.rect(g[1], 0, 1, sh, p.accent());
                else r.rect(0, g[1], sw, 1, p.accent());
            }
            drawHint(sw, sh, p);
        } finally {
            r.end();
        }
    }

    private void drawElement(HudModule element, boolean hovered, int sw, int sh, Theme p) {
        int[] b = HudRenderer.bounds(r, element, sw, sh, true);
        boolean dimmed = !element.hasData();
        r.pushAlpha(dimmed ? 0.55 : 1.0);
        HudRenderer.render(r, element, sw, sh, true);
        r.popAlpha();

        boolean active = hovered || element == selected;
        int outline = active ? p.accent() : ColorUtil.fade(p.border(), 0.9);
        r.rect(b[0], b[1], b[2], b[3], ColorUtil.fade(p.accent(), active ? 0.10 : 0.04));
        r.outline(b[0] - 1, b[1] - 1, b[2] + 2, b[3] + 2, outline);

        // resize handle
        r.rect(b[0] + b[2] - HANDLE + 1, b[1] + b[3] - HANDLE + 1, HANDLE, HANDLE, active ? p.accent() : p.border());

        if (active) {
            String label = element.getName() + "  " + Math.round(element.getScale() * 100) + "%"
                    + (dimmed ? "  (no data here)" : "");
            int tw = r.textWidth(label) + 6;
            int th = r.fontHeight() + 3;
            int lx = Math.clamp(b[0], 0, Math.max(0, sw - tw));
            int ly = b[1] - th - 2 >= 0 ? b[1] - th - 2 : b[1] + b[3] + 2;
            r.nextLayer();
            r.roundedRect(lx, ly, tw, th, 2, p.panel());
            r.text(label, lx + 3, ly + 2, p.text());
        }
    }

    private void drawHint(int sw, int sh, Theme p) {
        r.pushAlpha(open.get());
        String hint = elements.isEmpty()
                ? "No HUD elements enabled — turn some on in the HUD panel of the ClickGUI"
                : "Drag: move · Corner / scroll: resize · Right-click: reset size · Arrows: nudge · M: align (left / mid / right) · N: mirror text · Del: hide · Shift: no snap · Esc: done";
        String text = r.trim(hint, sw - 20);
        int w = r.textWidth(text) + 12, h = r.fontHeight() + 7;
        int x = (sw - w) / 2, y = elements.isEmpty() ? sh / 2 - h / 2 : sh - h - 40;
        r.roundedRect(x, y, w, h, 3, ColorUtil.fade(p.panel(), 0.92));
        r.textCentered(text, sw / 2, y + 4, p.textMuted());
        r.popAlpha();
    }

    // ================================================================== drag / resize

    private void updateDrag(int mx, int my, int sw, int sh) {
        guides.clear();
        if (dragging == null) return;
        if (resizing) {
            int baseW = dragging.frameWidth(r, true), baseH = dragging.frameHeight(r, true);
            double ratio = Math.max((mx - resizeLeft) / (double) Math.max(1, baseW),
                    (my - resizeTop) / (double) Math.max(1, baseH));
            dragging.setScale(ratio);
            int w = dragging.scaledWidth(r, true), h = dragging.scaledHeight(r, true);
            dragging.setTopLeft(resizeLeft, resizeTop, sw, sh, w, h);
            return;
        }
        int w = dragging.scaledWidth(r, true), h = dragging.scaledHeight(r, true);
        double left = mx - grabDx, top = my - grabDy;
        if (style().snapping.get() && !InputUtil.isShiftDown()) {
            left = snap(left, w, sw, true);
            top = snap(top, h, sh, false);
        }
        dragging.setTopLeft(left, top, sw, sh, w, h);
    }

    /** Snaps an element's edge or centre to the screen edges, the screen centre, or other elements' edges and centres. */
    private double snap(double start, int size, int screen, boolean vertical) {
        List<Integer> targets = new ArrayList<>();
        targets.add(HudModule.MARGIN);
        targets.add(screen - HudModule.MARGIN);
        targets.add(screen / 2);
        for (HudModule other : elements) {
            if (other == dragging) continue;
            int[] b = HudRenderer.bounds(r, other, this.width, this.height, true);
            int pos = vertical ? b[0] : b[1], len = vertical ? b[2] : b[3];
            targets.add(pos);
            targets.add(pos + len);
            targets.add(pos + len / 2);
        }
        double[] anchors = {0, size, size / 2.0};
        double bestDelta = SNAP + 1;
        int bestTarget = 0;
        for (double anchor : anchors) {
            for (int target : targets) {
                double delta = target - (start + anchor);
                if (Math.abs(delta) < Math.abs(bestDelta)) {
                    bestDelta = delta;
                    bestTarget = target;
                }
            }
        }
        if (Math.abs(bestDelta) > SNAP) return start;
        guides.add(new int[]{vertical ? 1 : 0, Math.min(bestTarget, screen - 1)});
        return start + bestDelta;
    }

    private HudModule elementAt(double mx, double my) {
        for (int i = elements.size() - 1; i >= 0; i--) {
            HudModule e = elements.get(i);
            int[] b = HudRenderer.bounds(r, e, this.width, this.height, true);
            if (mx >= b[0] - 1 && mx < b[0] + b[2] + 1 && my >= b[1] - 1 && my < b[1] + b[3] + 1) return e;
        }
        return null;
    }

    private boolean onHandle(HudModule e, double mx, double my) {
        int[] b = HudRenderer.bounds(r, e, this.width, this.height, true);
        int hx = b[0] + b[2], hy = b[1] + b[3];
        return mx >= hx - HANDLE - 1 && mx <= hx + 2 && my >= hy - HANDLE - 1 && my <= hy + 2;
    }

    // ================================================================== input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        if (!r.isActive()) r.begin(null, false); // measuring only needs the font
        HudModule hit = elementAt(mx, my);
        selected = hit;
        if (hit == null) return false;
        int[] b = HudRenderer.bounds(r, hit, this.width, this.height, true);
        if (event.button() == 1) {
            hit.setScale(1.0);
            keepCorner(hit, b);
            return true;
        }
        if (event.button() != 0) return false;
        dragging = hit;
        resizing = onHandle(hit, mx, my);
        resizeLeft = b[0];
        resizeTop = b[1];
        grabDx = mx - b[0];
        grabDy = my - b[1];
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging == null) return false;
        dragging = null;
        resizing = false;
        guides.clear();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!r.isActive()) r.begin(null, false);
        HudModule hit = elementAt(mouseX, mouseY);
        if (hit == null || scrollY == 0) return false;
        int[] b = HudRenderer.bounds(r, hit, this.width, this.height, true);
        hit.setScale(hit.getScale() + Math.signum(scrollY) * SCROLL_STEP);
        keepCorner(hit, b);
        selected = hit;
        return true;
    }

    /** After a scale change, keeps the element's outer corner in place (the right edge for elements on the right side). */
    private void keepCorner(HudModule e, int[] oldBounds) {
        int w = e.scaledWidth(r, true), h = e.scaledHeight(r, true);
        double left = e.isRightSide() ? oldBounds[0] + oldBounds[2] - w : oldBounds[0];
        e.setTopLeft(left, oldBounds[1], this.width, this.height, w, h);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (selected != null && dragging == null) {
            int step = InputUtil.isShiftDown() ? 10 : 1;
            int dx = key == InputUtil.KEY_LEFT ? -step : key == InputUtil.KEY_RIGHT ? step : 0;
            int dy = key == InputUtil.KEY_UP ? -step : key == InputUtil.KEY_DOWN ? step : 0;
            if (dx != 0 || dy != 0) {
                if (!r.isActive()) r.begin(null, false);
                int[] b = HudRenderer.bounds(r, selected, this.width, this.height, true);
                selected.setTopLeft(b[0] + dx, b[1] + dy, this.width, this.height, b[2], b[3]);
                return true;
            }
            if (key == InputUtil.KEY_N) {
                selected.toggleMirrorText();
                return true;
            }
            if (key == InputUtil.KEY_M) {
                selected.cycleAlign();
                return true;
            }
            if (key == InputUtil.KEY_DELETE || key == InputUtil.KEY_BACKSPACE) {
                selected.hide();
                selected = null;
                return true;
            }
        }
        return super.keyPressed(event); // Esc goes to onClose()
    }

    // ================================================================== lifecycle

    @Override
    public void onClose() {
        save();
        Minecraft.getInstance().setScreen(returnToGui ? new ClickGuiScreen() : null);
    }

    @Override
    public void removed() {
        save();
        super.removed();
    }

    private void save() {
        if (saved) return;
        saved = true;
        Telemetric.get().config().onGuiClosed();
    }
}
