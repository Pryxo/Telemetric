package dev.telemetric.game;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.telemetric.Telemetric;
import dev.telemetric.mixin.GuiGraphicsExtractorAccessor;
import dev.telemetric.mixin.RenderPipelinesAccessor;
import dev.telemetric.mixin.RenderTypeInvoker;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Settings window's 3D preview as one real 3D scene: the blocks around you, your player, nearby entities and the
 * preview's shapes (e.g. hitbox lines), all drawn into one picture-in-picture texture with a shared depth buffer. So
 * walls hide models and lines behind them, and models hide each other. Same mechanism vanilla uses for the inventory
 * player ({@code GuiEntityRenderer}): a {@link PictureInPictureRenderState} collected during GUI extraction, and a
 * {@link PictureInPictureRenderer} (registered through {@link PictureInPictureRendererRegistry}) that renders it into its
 * own texture before the GUI is drawn.
 * <p>
 * Coordinates are blocks relative to your feet, world axes, under {@code R = Rz(π)·Rx(pitch)·Ry(yaw)} like
 * {@link EntityPreviewAdapter}, so 2D overlays placed with {@code PlayerPreview.View#project} line up. Blocks above your
 * feet are drawn last, translucent and without depth, so they never hide you. The orthographic PiP projection only spans
 * ±1000 depth units, so the scene's depth is squashed to fit (this changes the depth range, not the picture).
 */
public final class ScenePreviewAdapter {

    private static final int GHOST_ALPHA = 102; // 40 %

    private static boolean registered, available, typesFailed;
    private static RenderType faces, ghostFaces;

    private ScenePreviewAdapter() {}

    /** Registers the scene renderer (client init, before the GUI renderer is built). */
    public static void register() {
        if (registered) return;
        registered = true;
        try {
            PictureInPictureRendererRegistry.register(ctx -> new SceneRenderer(ctx.bufferSource()));
            available = true;
        } catch (RuntimeException | LinkageError e) {
            Telemetric.LOGGER.error("3D preview scene renderer unavailable, using the flat preview", e);
        }
    }

    /** False if the renderer couldn't be registered or its render types failed: the preview falls back to 2D. */
    public static boolean available() {
        return available && !typesFailed;
    }

    /**
     * What to put in the scene.
     *
     * @param player      draw your own player model
     * @param worldRadius blocks around you ({@link WorldPreviewAdapter}), 0 = none
     * @param entityRadius how far around you other entities are taken
     * @param maxEntities the most other entities (nearest first), 0 = none
     * @param shapes      extra lines / quads in world coordinates (null = none), drawn depth-tested
     */
    public record Request(boolean player, int worldRadius, double entityRadius, int maxEntities,
                          WorldRenderAdapter.Drawer shapes) {}

    /**
     * Adds the scene for the box (GUI coordinates under the current pose).
     *
     * @param pxPerBlock GUI pixels per block
     * @param shiftX     blocks the feet sit right of the box centre
     * @param lift       blocks the feet sit below the box centre
     * @return false outside a world (nothing drawn)
     */
    public static boolean draw(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, float pxPerBlock,
                               float shiftX, float lift, float yaw, float pitch, Request req) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return false;

        Matrix3x2f pose = g.pose();
        Vector2f a = pose.transformPosition(x0, y0, new Vector2f());
        Vector2f b = pose.transformPosition(x1, y1, new Vector2f());
        float poseScale = (float) Math.sqrt(Math.abs(pose.determinant()));
        int ax0 = Math.round(Math.min(a.x, b.x)), ay0 = Math.round(Math.min(a.y, b.y));
        int ax1 = Math.round(Math.max(a.x, b.x)), ay1 = Math.round(Math.max(a.y, b.y));
        if (ax1 - ax0 < 2 || ay1 - ay0 < 2) return true;

        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 feet = player.getPosition(pt);
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI).rotateX(pitch).rotateY(yaw);
        Quaternionf camera = new Quaternionf().rotateX(pitch).rotateY(yaw);
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();

        List<Model> models = new ArrayList<>();
        if (req.player()) models.add(new Model(EntityPreviewAdapter.ownState(dispatcher, player), 0, 0, 0));
        if (req.maxEntities() > 0 && req.entityRadius() > 0 && mc.level != null) {
            double r = req.entityRadius();
            List<Entity> near = new ArrayList<>();
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e == player || e.isRemoved() || !shown(e)) continue;
                double dx = e.getX() - feet.x, dy = e.getY() - feet.y, dz = e.getZ() - feet.z;
                if (dx * dx + dz * dz > r * r || Math.abs(dy) > r) continue;
                near.add(e);
            }
            near.sort(Comparator.comparingDouble(e -> e.distanceToSqr(feet)));
            for (int i = 0; i < near.size() && i < req.maxEntities(); i++) {
                Entity e = near.get(i);
                EntityRenderState state;
                try {
                    state = dispatcher.getRenderer(e).createRenderState(e, pt);
                } catch (RuntimeException ex) {
                    continue; // a renderer that can't build its state outside the world pass: skip that entity
                }
                state.shadowPieces.clear();
                state.outlineColor = 0;
                state.nameTag = null;
                Vec3 at = e.getPosition(pt);
                models.add(new Model(state, at.x - feet.x, at.y - feet.y, at.z - feet.z));
            }
        }

        WorldPreviewAdapter.Snapshot world = req.worldRadius() > 0 ? WorldPreviewAdapter.around(req.worldRadius()) : null;
        Window window = mc.getWindow();
        int guiScale = Math.max(1, window.getGuiScale());
        int texW = (ax1 - ax0) * guiScale, texH = (ay1 - ay0) * guiScale;
        // vanilla's line shader sizes lines against the window, not the PiP texture: undo that
        float widthScale = (float) Math.sqrt((double) window.getWidth() / texW * window.getHeight() / texH);
        WorldRenderAdapter.Batch shapes = req.shapes() == null ? null
                : WorldRenderAdapter.collectFor(req.shapes(), feet, (float) Math.toDegrees(yaw),
                (float) -Math.toDegrees(pitch), widthScale);

        float scale = pxPerBlock * poseScale;
        double reach = (Math.max(req.worldRadius(), req.entityRadius()) + 4) * 1.75;
        float zScale = (float) Math.min(1, 900 / Math.max(1, reach * scale * guiScale));
        float[] forward = {(float) (-Math.sin(yaw) * Math.cos(pitch)), (float) Math.sin(pitch),
                (float) (Math.cos(yaw) * Math.cos(pitch))};

        SceneState state = new SceneState(world, feet, models, shapes, new Vector3f(shiftX, lift, 0), rotation,
                camera, forward, zScale, ax0, ay0, ax1, ay1, scale, null,
                PictureInPictureRenderState.getBounds(ax0, ay0, ax1, ay1, null));
        ((GuiGraphicsExtractorAccessor) g).telemetric$guiRenderState().addPicturesInPictureState(state);
        return true;
    }

    private record Model(EntityRenderState state, double dx, double dy, double dz) {}

    /** Everything the renderer needs, collected on the extract pass. */
    private record SceneState(WorldPreviewAdapter.Snapshot world, Vec3 feet, List<Model> models,
                              WorldRenderAdapter.Batch shapes, Vector3f translation, Quaternionf rotation,
                              Quaternionf camera, float[] forward, float zScale,
                              int x0, int y0, int x1, int y1, float scale, ScreenRectangle scissorArea,
                              ScreenRectangle bounds) implements PictureInPictureRenderState {}

    private static boolean shown(Entity e) {
        if (e instanceof Display.TextDisplay) return false;
        if (e instanceof Display) return true; // item / block displays (Telos mob models)
        return e instanceof LivingEntity && !e.isInvisible();
    }

    private static final class SceneRenderer extends PictureInPictureRenderer<SceneState> {

        SceneRenderer(MultiBufferSource.BufferSource buffers) {
            super(buffers);
        }

        @Override
        public Class<SceneState> getRenderStateClass() {
            return SceneState.class;
        }

        @Override
        protected float getTranslateY(int height, int guiScale) {
            return height / 2f;
        }

        @Override
        protected String getTextureLabel() {
            return "telemetric_preview_scene";
        }

        @Override
        protected void renderToTexture(SceneState s, PoseStack pose) {
            Minecraft mc = Minecraft.getInstance();
            mc.gameRenderer.getLighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
            pose.pushPose();
            pose.scale(1, 1, s.zScale());
            pose.translate(s.translation().x, s.translation().y, s.translation().z);
            pose.mulPose(s.rotation());
            buildTypes();

            // 1. solid blocks (write depth)
            if (faces != null && s.world() != null) {
                emitFaces(bufferSource.getBuffer(faces), pose.last(), s, false);
                bufferSource.endBatch();
            }
            // 2. models, depth-tested against the blocks and each other
            if (!s.models().isEmpty()) {
                CameraRenderState cam = new CameraRenderState();
                cam.orientation = new Quaternionf(s.camera()).conjugate().rotateY((float) Math.PI);
                FeatureRenderDispatcher features = mc.gameRenderer.getFeatureRenderDispatcher();
                EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
                for (Model m : s.models()) {
                    try {
                        dispatcher.submit(m.state(), cam, m.dx(), m.dy(), m.dz(), pose, features.getSubmitNodeStorage());
                    } catch (RuntimeException e) {
                        // one entity that can't draw here shouldn't take the whole preview down
                    }
                }
                features.renderAllFeatures();
            }
            // 3. shapes (hitboxes …), depth-tested, no depth write
            WorldRenderAdapter.emitFor(bufferSource, pose.last(), s.shapes());
            // 4. blocks above your feet, see-through
            if (ghostFaces != null && s.world() != null) {
                emitFaces(bufferSource.getBuffer(ghostFaces), pose.last(), s, true);
                bufferSource.endBatch();
            }
            pose.popPose();
        }

        private static void emitFaces(VertexConsumer vc, PoseStack.Pose p, SceneState s, boolean ghost) {
            WorldPreviewAdapter.Snapshot snap = s.world();
            float ox = (float) (snap.ox() - s.feet().x), oy = (float) (snap.oy() - s.feet().y),
                    oz = (float) (snap.oz() - s.feet().z);
            float[] f = s.forward();
            for (WorldPreviewAdapter.Face face : snap.faces()) {
                if (face.ghost() != ghost) continue;
                // see-through faces: front faces only, so a block doesn't blend twice
                if (ghost && face.nx() * f[0] + face.ny() * f[1] + face.nz() * f[2] > -1e-3) continue;
                int argb = ghost ? (face.argb() & 0xFFFFFF) | (GHOST_ALPHA << 24) : face.argb();
                float[] q = face.pos(), uv = face.uv();
                for (int k = 0; k < 4; k++) {
                    vc.addVertex(p, ox + q[k * 3], oy + q[k * 3 + 1], oz + q[k * 3 + 2])
                            .setUv(uv[k * 2], uv[k * 2 + 1]).setColor(argb);
                }
            }
        }
    }

    /** Block-atlas quads without lighting (the face colours already carry the world's shading), built once. */
    private static void buildTypes() {
        if (faces != null || typesFailed) return;
        try {
            faces = facesType("preview_faces", new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true));
            ghostFaces = facesType("preview_faces_ghost", new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false));
        } catch (RuntimeException | LinkageError e) {
            typesFailed = true;
            faces = ghostFaces = null;
            Telemetric.LOGGER.error("3D preview block render type unavailable", e);
        }
    }

    private static RenderType facesType(String name, DepthStencilState depth) {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelinesAccessor.telemetric$guiTexturedSnippet())
                .withLocation(Identifier.fromNamespaceAndPath(Telemetric.MOD_ID, "pipeline/" + name))
                .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
                .withCull(false)
                .withDepthStencilState(depth)
                .build();
        RenderSetup setup = RenderSetup.builder(pipeline)
                .withTexture("Sampler0", TextureAtlas.LOCATION_BLOCKS)
                .createRenderSetup();
        return RenderTypeInvoker.telemetric$create(Telemetric.MOD_ID + "_" + name, setup);
    }
}
