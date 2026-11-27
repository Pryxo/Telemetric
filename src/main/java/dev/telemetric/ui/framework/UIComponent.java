package dev.telemetric.ui.framework;

import dev.telemetric.ui.render.Render2D;

/**
 * Base of every ClickGUI element. It's framework-level and doesn't know about MC: input arrives as primitives,
 * already converted to ClickGUI units by {@code ClickGuiScreen}.
 * <p>
 * Layout contract (immediate mode): the parent calls {@link #setBounds} every frame right before
 * {@link #render}. {@link #getHeight()} may be animated, and parents stack their children using it. Input handlers use
 * the bounds from the last rendered frame.
 * <p>
 * Event methods return {@code true} when they consumed the event, which stops propagation.
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

    /** The height this component wants to take up right now (it may be mid-animation). */
    public int getHeight() {
        return height;
    }

    public int getX() { return x; }
    public int getY() { return y; }
    public int getWidth() { return width; }

    public boolean isHovered(double mouseX, double mouseY) {
        return Hit.inside(mouseX, mouseY, x, y, width, height);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) { return false; }

    public boolean mouseReleased(double mouseX, double mouseY, int button) { return false; }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) { return false; }

    public boolean keyPressed(int key, int scancode, int modifiers) { return false; }

    /** A mouse press sent to the focused component before normal click routing (anywhere on screen). True means consumed. */
    public boolean focusedMouseButton(int button) { return false; }

    public boolean charTyped(int codepoint, int modifiers) { return false; }

    /** Called when this component loses keyboard focus. */
    public void onFocusLost() {}

    /** Called when the GUI closes. */
    public void onClose() {}
}
