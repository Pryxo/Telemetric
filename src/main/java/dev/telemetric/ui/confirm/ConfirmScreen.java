package dev.telemetric.ui.confirm;

import dev.telemetric.Telemetric;
import dev.telemetric.ui.render.Render2D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * MC ADAPTER: hosts {@link Confirm#current()} when no ClickGUI is open (e.g. a module turned on by its keybind).
 * Goes back to the screen that was open before (usually none) once the dialog has closed.
 */
public final class ConfirmScreen extends Screen {

    private final Screen previous;
    private final Render2D r = new Render2D();

    public ConfirmScreen(Screen previous) {
        super(Component.literal("Confirm"));
        this.previous = previous;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // the dialog draws its own dim
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        ConfirmDialog d = Confirm.current();
        if (d == null) return;
        r.begin(graphics, Telemetric.get().theme().textShadow());
        try {
            r.nextLayer();
            d.render(r, mouseX, mouseY, this.width, this.height);
        } finally {
            r.end();
        }
    }

    @Override
    public void tick() {
        if (Confirm.current() == null) Minecraft.getInstance().setScreen(previous);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        ConfirmDialog d = Confirm.current();
        return d == null || d.mouseClicked(event.x(), event.y(), event.button());
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        ConfirmDialog d = Confirm.current();
        return d == null || d.keyPressed(event.key());
    }

    @Override
    public void onClose() {
        ConfirmDialog d = Confirm.current();
        if (d != null) d.cancel(); // Esc reaches here only if keyPressed didn't take it
    }

    @Override
    public void removed() {
        // replaced from outside (e.g. disconnect): don't leave a dialog hanging
        if (Minecraft.getInstance().screen != this) Confirm.dismiss();
    }
}
