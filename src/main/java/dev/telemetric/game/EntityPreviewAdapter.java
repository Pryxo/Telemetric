package dev.telemetric.game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Pose;
import org.joml.Matrix3x2f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * Draws your own player model into a GUI box (the 3D preview in the Settings window), turned by the preview's yaw and
 * pitch. Same path as the inventory screen's player: a PiP render to a texture via {@link GuiGraphicsExtractor#entity}.
 * This is the flat fallback; normally the preview draws one depth-tested scene ({@link ScenePreviewAdapter}).
 * <p>
 * The PiP state ignores the GUI pose, so the box and scale are mapped through the current pose here. A point {@code p}
 * of the entity (blocks, feet at the origin) lands at {@code centre + scale · ((shiftX, lift) + R·p)} with
 * {@code R = Rz(π)·Rx(pitch)·Ry(yaw)} and GUI y pointing down. The preview uses the same formula for its 2D overlays
 * (see {@code PlayerPreview}).
 * <p>
 * Our render mixins (Player Size, Hide Armor) run on this render state too, so the model shows them live.
 */
public final class EntityPreviewAdapter {

    private EntityPreviewAdapter() {}

    /** Your hitbox height in blocks (1.8 standing, less while sneaking / swimming), or 1.8 without a player. */
    public static double playerHeight() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null ? 1.8 : p.getBbHeight() / Math.max(0.01f, p.getScale());
    }

    /** Your hitbox width in blocks. */
    public static double playerWidth() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null ? 0.6 : p.getBbWidth() / Math.max(0.01f, p.getScale());
    }

    /**
     * Draws your player into the box (GUI coordinates under the current pose).
     *
     * @param pxPerBlock GUI pixels per block
     * @param shiftX     blocks the feet sit right of the box centre
     * @param lift       blocks the feet sit below the box centre
     * @return false if there is no player (e.g. not in a world)
     */
    public static boolean drawLocalPlayer(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, float pxPerBlock,
                                          float shiftX, float lift, float yaw, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return false;
        EntityRenderState state = ownState(mc.getEntityRenderDispatcher(), player);

        Matrix3x2f pose = g.pose();
        Vector2f a = pose.transformPosition(x0, y0, new Vector2f());
        Vector2f b = pose.transformPosition(x1, y1, new Vector2f());
        float poseScale = (float) Math.sqrt(Math.abs(pose.determinant()));
        int ax0 = Math.round(Math.min(a.x, b.x)), ay0 = Math.round(Math.min(a.y, b.y));
        int ax1 = Math.round(Math.max(a.x, b.x)), ay1 = Math.round(Math.max(a.y, b.y));
        if (ax1 - ax0 < 2 || ay1 - ay0 < 2) return true;

        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI).rotateX(pitch).rotateY(yaw);
        Quaternionf camera = new Quaternionf().rotateX(pitch).rotateY(yaw);
        g.entity(state, pxPerBlock * poseScale, new Vector3f(shiftX, lift, 0), rotation, camera, ax0, ay0, ax1, ay1);
        return true;
    }

    /** Your own model: facing the default way, upright, at scale 1 (Player Size still applies through the mixins). */
    static EntityRenderState ownState(EntityRenderDispatcher dispatcher, LocalPlayer player) {
        EntityRenderState state = dispatcher.getRenderer(player).createRenderState(player, 1.0f);
        state.shadowPieces.clear();
        state.outlineColor = 0;
        state.nameTag = null;
        if (state instanceof LivingEntityRenderState living) {
            living.bodyRot = 180f;
            living.yRot = 0f;
            if (living.pose != Pose.FALL_FLYING) living.xRot = 0f;
            living.boundingBoxWidth /= living.scale;
            living.boundingBoxHeight /= living.scale;
            living.scale = 1.0f;
        }
        return state;
    }
}
