package dev.telemetric.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * MC ADAPTER — the only place that queries raw key state / key names.
 * Key codes are {@link InputConstants} key codes (never hard-code backend numbers elsewhere).
 */
public final class InputUtil {

    public static final int KEY_ESCAPE = InputConstants.KEY_ESCAPE;
    public static final int KEY_BACKSPACE = InputConstants.KEY_BACKSPACE;
    public static final int KEY_DELETE = InputConstants.KEY_DELETE;
    public static final int KEY_ENTER = InputConstants.KEY_RETURN;
    public static final int KEY_LEFT = InputConstants.KEY_LEFT;
    public static final int KEY_RIGHT = InputConstants.KEY_RIGHT;
    public static final int KEY_HOME = InputConstants.KEY_HOME;
    public static final int KEY_END = InputConstants.KEY_END;
    public static final int KEY_UP = InputConstants.KEY_UP;
    public static final int KEY_M = InputConstants.KEY_M;
    public static final int KEY_N = InputConstants.KEY_N;
    public static final int KEY_DOWN = InputConstants.KEY_DOWN;
    public static final int KEY_LSHIFT = InputConstants.KEY_LSHIFT;
    public static final int KEY_RSHIFT = InputConstants.KEY_RSHIFT;
    public static final int KEY_A = InputConstants.KEY_A;
    public static final int KEY_C = InputConstants.KEY_C;
    public static final int KEY_F = InputConstants.KEY_F;
    public static final int KEY_V = InputConstants.KEY_V;
    public static final int KEY_X = InputConstants.KEY_X;
    public static final int KEY_F6 = InputConstants.KEY_F6;

    /** System clipboard text ("" when empty / unavailable). */
    public static String getClipboard() {
        String s = Minecraft.getInstance().keyboardHandler.getClipboard();
        return s == null ? "" : s;
    }

    public static void setClipboard(String text) {
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
    }

    public static boolean isShiftDown() {
        return isKeyDown(KEY_LSHIFT) || isKeyDown(KEY_RSHIFT);
    }

    private InputUtil() {}

    /**
     * Bind codes at or above this are mouse buttons ({@code MOUSE_OFFSET + button}); below it are keyboard key codes.
     * Keyboard codes never reach it (highest GLFW key is 348).
     */
    public static final int MOUSE_OFFSET = 1000;

    /** Bind code for a mouse button (0 = left, 1 = right, 2 = middle, 3/4 = side buttons, …). */
    public static int mouseCode(int button) {
        return MOUSE_OFFSET + button;
    }

    public static boolean isMouseCode(int key) {
        return key >= MOUSE_OFFSET;
    }

    /** True while the key or mouse button behind a bind code is held. */
    public static boolean isKeyDown(int key) {
        if (key < 0) return false;
        Minecraft mc = Minecraft.getInstance();
        if (isMouseCode(key)) {
            return GLFW.glfwGetMouseButton(mc.getWindow().handle(), key - MOUSE_OFFSET) == GLFW.GLFW_PRESS;
        }
        return InputConstants.isKeyDown(mc.getWindow(), key);
    }

    public static String keyName(int key) {
        if (key < 0) return "None";
        try {
            if (isMouseCode(key)) return InputConstants.Type.MOUSE.getOrCreate(key - MOUSE_OFFSET).getDisplayName().getString();
            return InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
        } catch (RuntimeException e) {
            return "Key " + key;
        }
    }

    /** True if Minecraft is currently showing any screen (chat, inventory, our GUI, …). */
    public static boolean isScreenOpen() {
        return Minecraft.getInstance().screen != null;
    }
}
