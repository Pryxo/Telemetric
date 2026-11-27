package dev.telemetric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.telemetric.game.CursorAdapter;
import net.minecraft.client.MouseHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keep Cursor: vanilla moves the cursor to the window centre whenever the mouse is grabbed (screen closed) or released
 * (screen opened). With Keep Cursor on, neither happens: the cursor is hidden and shown again in place, so going from
 * one container to the next (server close + reopen) leaves it where it was. Camera turning is unaffected — vanilla
 * sets {@code ignoreFirstMove} on grab. See {@link CursorAdapter}.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Shadow private double xpos;
    @Shadow private double ypos;

    @Unique private double telemetric$x, telemetric$y;
    @Unique private boolean telemetric$keep;

    @Inject(method = {"grabMouse", "releaseMouse"}, at = @At("HEAD"))
    private void telemetric$remember(CallbackInfo ci) {
        telemetric$keep = CursorAdapter.isEnabled();
        telemetric$x = xpos;
        telemetric$y = ypos;
    }

    /** Hide / show the cursor without moving it. Mode first, position after: GLFW on Windows otherwise jumps back. */
    @WrapOperation(method = {"grabMouse", "releaseMouse"}, at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/InputConstants;grabOrReleaseMouse(Lcom/mojang/blaze3d/platform/Window;IDD)V"))
    private void telemetric$noCenter(Window window, int mode, double x, double y, Operation<Void> original) {
        if (!telemetric$keep) {
            original.call(window, mode, x, y);
            return;
        }
        xpos = telemetric$x;
        ypos = telemetric$y;
        GLFW.glfwSetInputMode(window.handle(), GLFW.GLFW_CURSOR, mode);
        if (mode == InputConstants.CURSOR_NORMAL) GLFW.glfwSetCursorPos(window.handle(), xpos, ypos);
    }
}
