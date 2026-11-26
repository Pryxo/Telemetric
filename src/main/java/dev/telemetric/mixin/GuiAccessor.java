package dev.telemetric.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the title on screen ({@code Gui#title}) and its remaining ticks ({@code Gui#titleTime},
 * 0 = gone). Only used through {@link dev.telemetric.game.HudHideAdapter}.
 */
@Mixin(Gui.class)
public interface GuiAccessor {

    @Accessor("title")
    Component telemetric$getTitle();

    @Accessor("titleTime")
    int telemetric$getTitleTime();
}
