package dev.telemetric.game;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;

/**
 * How the player sees the world: brightness (Fullbright, read by {@code LightmapMixin}) and the camera perspective
 * (Camera). Modules push their settings through the setters. Every effect only applies on Telos.
 */
public final class ViewAdapter {

    private ViewAdapter() {}

    private static volatile float fullbright;

    /** 0 is off, 1 is full ambient light. */
    public static void setFullbright(float strength) {
        fullbright = Math.clamp(strength, 0f, 1f);
    }

    /** {@code LightmapMixin} reads this on each lightmap update. */
    public static float fullbright() {
        return fullbright > 0 && ServerGate.onTelos() ? fullbright : 0f;
    }

    /**
     * @param noFront        the perspective key only switches between first and third person (no front view)
     * @param autoPerspective third person while looking down further than {@code pitch}; the key inverts it
     * @param pitch          degrees below the horizon
     */
    public record CameraRules(boolean noFront, boolean autoPerspective, float pitch) {}

    private static volatile CameraRules camera;
    private static boolean inverted, registered;

    /** Null means the vanilla camera. */
    public static void setCameraRules(CameraRules rules) {
        camera = rules;
        inverted = false;
        if (rules != null && !registered) {
            registered = true;
            // at the start of the tick, before vanilla reads the perspective key in handleKeybinds
            ClientTickEvents.START_CLIENT_TICK.register(ViewAdapter::cameraTick);
        }
    }

    private static void cameraTick(Minecraft mc) {
        CameraRules rules = camera;
        LocalPlayer player = mc.player;
        if (rules == null || player == null || !ServerGate.onTelos()) return;
        Options o = mc.options;
        if (rules.autoPerspective() || rules.noFront()) {
            while (o.keyTogglePerspective.consumeClick()) {
                if (rules.autoPerspective()) inverted = !inverted;
                else o.setCameraType(o.getCameraType() == CameraType.FIRST_PERSON
                        ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON);
            }
        }
        if (rules.autoPerspective()) {
            boolean third = inverted != (player.getXRot() > rules.pitch());
            CameraType target = third ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON;
            if (o.getCameraType() != target) o.setCameraType(target);
        } else if (rules.noFront() && o.getCameraType() == CameraType.THIRD_PERSON_FRONT) {
            o.setCameraType(CameraType.FIRST_PERSON);
        }
    }

    /** True while the camera shows the player from outside (third person). */
    public static boolean isThirdPerson() {
        return !Minecraft.getInstance().options.getCameraType().isFirstPerson();
    }

    /** True while the HUD editor is open (elements must stay visible there to be moved). */
    public static boolean isHudEditorOpen() {
        return Minecraft.getInstance().screen instanceof dev.telemetric.ui.hud.HudEditorScreen;
    }
}
