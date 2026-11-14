package dev.telemetric.ui.framework;

import dev.telemetric.ui.render.Render2D;

/**
 * Base of every ClickGUI element. Framework-level and MC-agnostic: input arrives as primitives
 * (already converted to ClickGUI units by {@code ClickGuiScreen}).
 * <p>
 * <b>Layout contract (immediate mode):</b> the parent calls {@link #setBounds} every frame right before
 * {@link #render}. {@link #getHeight()} may be animated; parents stack children using it.
 * Input handlers use the bounds from the last rendered frame.
 * <p>
 * Event methods return {@code true} when consumed (stops propagation).
 */
public abstract class UIComponent {

    protected final GuiContext ctx;
    protected int x, y, width, height;

    protected UIComponent(GuiContext ctx) {
        this.ctx = ctx;
    }

    public abstract void render(Render2D r, int mouseX, int mouseY, float delta);

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /** Height this component wants to occupy right now (may be mid-animation). */
    public int getHeight() {
        return height;
    }

    public int getX() { return x; }
    public int getY() { return y; }
    public int getWidth() { return width; }

    public boolean isHovered(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    // ------------------------------------------------------------------ input (default: ignore)

    public boolean mouseClicked(double mouseX, double mouseY, int button) { return false; }

    public boolean mouseReleased(double mouseX, double mouseY, int button) { return false; }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) { return false; }

    public boolean keyPressed(int key, int scancode, int modifiers) { return false; }

    public boolean charTyped(int codepoint, int modifiers) { return false; }

    /** Called when this component loses keyboard focus. */
    public void onFocusLost() {}

    /** Called when the GUI closes. */
    public void onClose() {}
}
