package dev.telemetric.game;

import dev.telemetric.stash.StashSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * MC ADAPTER: a placeholder screen shown while Telos switches the stash between normal and seasonal pages (it closes
 * the stash, shows a pop-up, then opens it again). It keeps drawing the stash overlay with "Switching…", so the player
 * never sees the pop-up or a frame of the bare world in between. When Telos opens the stash it replaces this screen,
 * and it closes itself if the switch times out. Esc closes it.
 */
public final class StashHoldScreen extends Screen {

    StashHoldScreen() {
        super(Component.literal("Stash"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        if (!StashSession.get().isSwitching()) Minecraft.getInstance().setScreen(null);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // the overlay paints its own backdrop
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        StashAdapter.drawOverlay(this, graphics, mouseX, mouseY);
    }

    @Override
    public void onClose() {
        StashSession.get().cancelSwitch();
        super.onClose();
    }
}
