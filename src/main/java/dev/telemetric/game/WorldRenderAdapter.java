package dev.telemetric.game;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.telemetric.Telemetric;
import dev.telemetric.setting.impl.ColorRamp;
import dev.telemetric.dungeon.Point;
import dev.telemetric.mixin.RenderPipelinesAccessor;
import dev.telemetric.mixin.RenderTypeInvoker;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The only place that draws shapes in the 3D world. Features register a {@link Drawer} and emit plain world-space shapes
 * through a {@link LineSink}: line segments, filled quads, and helpers built on those (box outline and fill,
 * camera-facing rectangles). Once per frame everything is batched into four render types: lines and quads, each either
 * depth-tested or see-through. Drawers added with {@link #addUnderDrawer} (e.g. Hitboxes) form a bottom layer that is
 * drawn first and never writes depth, so everything else (health bars, ranges) stays on top. {@link WorldLabelAdapter}
 * labels are drawn right after.
 * <p>
 * Everything is drawn at {@code END_MAIN}, after translucent terrain (water, stained glass, ice) and translucent
 * particles, so those never cover the overlays. The depth test uses a copy of the depth buffer taken just before
 * translucent terrain ({@link #snapshotDepth}), so only opaque blocks hide the shapes.
 */
public final class WorldRenderAdapter {

    /** Receives shapes in world coordinates. Line width is in screen pixels. */
    public interface LineSink {
        void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb, float width,
                  boolean throughWalls);

        /** A filled quad (corners in order). Translucent colours blend, and both sides are drawn. */
        void quad(double x1, double y1, double z1, double x2, double y2, double z2,
                  double x3, double y3, double z3, double x4, double y4, double z4, int argb, boolean throughWalls);

        /**
         * A rectangle that faces the camera (like a nameplate), anchored at a world point. {@code left/right} run
         * along the screen's right axis and {@code bottom/top} along its up axis, in blocks.
         */
        void billboard(double x, double y, double z, double left, double bottom, double right, double top, int argb,
                       boolean throughWalls);

        /**
         * A {@link #billboard} coloured left to right by {@code ramp} (a gradient colour setting); a uniform ramp is
         * one plain billboard.
         */
        default void billboard(double x, double y, double z, double left, double bottom, double right, double top,
                               ColorRamp ramp, boolean throughWalls) {
            billboard(x, y, z, left, bottom, right, top, ramp.at(0), throughWalls);
        }

        /** The 12 edges of an axis-aligned box. */
        /** A line whose colour blends from {@code argbStart} (point 1) to {@code argbEnd} (point 2). */
        default void line(double x1, double y1, double z1, double x2, double y2, double z2, int argbStart, int argbEnd,
                          float width, boolean throughWalls) {
            line(x1, y1, z1, x2, y2, z2, argbStart, width, throughWalls);
        }

        /** {@link #boxOutline} with a gradient colour running bottom ({@code ramp.at(0)}) to top. */
        default void boxOutline(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                ColorRamp ramp, float width, boolean throughWalls) {
            if (ramp.isUniform()) {
                boxOutline(minX, minY, minZ, maxX, maxY, maxZ, ramp.at(0), width, throughWalls);
                return;
            }
            int lo = ramp.at(0), hi = ramp.at(1);
            line(minX, minY, minZ, maxX, minY, minZ, lo, width, throughWalls);
            line(minX, minY, maxZ, maxX, minY, maxZ, lo, width, throughWalls);
            line(minX, minY, minZ, minX, minY, maxZ, lo, width, throughWalls);
            line(maxX, minY, minZ, maxX, minY, maxZ, lo, width, throughWalls);
            line(minX, maxY, minZ, maxX, maxY, minZ, hi, width, throughWalls);
            line(minX, maxY, maxZ, maxX, maxY, maxZ, hi, width, throughWalls);
            line(minX, maxY, minZ, minX, maxY, maxZ, hi, width, throughWalls);
            line(maxX, maxY, minZ, maxX, maxY, maxZ, hi, width, throughWalls);
            // the vertical edges carry the whole gradient (split so multi-stop gradients show every stop)
            int n = 8;
            double[][] posts = {{minX, minZ}, {maxX, minZ}, {minX, maxZ}, {maxX, maxZ}};
            for (double[] c : posts) {
                for (int i = 0; i < n; i++) {
                    double ya = minY + (maxY - minY) * i / n, yb = minY + (maxY - minY) * (i + 1) / n;
                    line(c[0], ya, c[1], c[0], yb, c[1], ramp.at(i / (float) n), ramp.at((i + 1) / (float) n), width, throughWalls);
                }
            }
        }

        default void boxOutline(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                int argb, float width, boolean throughWalls) {
            line(minX, minY, minZ, maxX, minY, minZ, argb, width, throughWalls);
            line(minX, minY, maxZ, maxX, minY, maxZ, argb, width, throughWalls);
            line(minX, maxY, minZ, maxX, maxY, minZ, argb, width, throughWalls);
            line(minX, maxY, maxZ, maxX, maxY, maxZ, argb, width, throughWalls);
            line(minX, minY, minZ, minX, minY, maxZ, argb, width, throughWalls);
            line(maxX, minY, minZ, maxX, minY, maxZ, argb, width, throughWalls);
            line(minX, maxY, minZ, minX, maxY, maxZ, argb, width, throughWalls);
            line(maxX, maxY, minZ, maxX, maxY, maxZ, argb, width, throughWalls);
            line(minX, minY, minZ, minX, maxY, minZ, argb, width, throughWalls);
            line(maxX, minY, minZ, maxX, maxY, minZ, argb, width, throughWalls);
            line(minX, minY, maxZ, minX, maxY, maxZ, argb, width, throughWalls);
            line(maxX, minY, maxZ, maxX, maxY, maxZ, argb, width, throughWalls);
        }

        /** The 6 faces of an axis-aligned box. */
        default void boxFill(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                             int argb, boolean throughWalls) {
            quad(minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ, argb, throughWalls);
            quad(minX, maxY, minZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, argb, throughWalls);
            quad(minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, maxX, minY, minZ, argb, throughWalls);
            quad(minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, argb, throughWalls);
            quad(minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, argb, throughWalls);
            quad(maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, maxX, minY, maxZ, argb, throughWalls);
        }
    }

    @FunctionalInterface
    public interface Drawer {
        void draw(LineSink sink);
    }

    /** A line from point 1 (colour {@code argb}) to point 2 (colour {@code argb2}); the colour blends along it. */
    private record Segment(float x1, float y1, float z1, float x2, float y2, float z2, int argb, int argb2, float width) {}

    /** Four corners and one ARGB per corner (colours blend across the quad). */
    private record Quad(float[] xyz, int[] argb) {}

    /** One layer's shapes for this frame. */
    static final class Batch {
        final List<Segment> depth = new ArrayList<>(), seeThrough = new ArrayList<>();
        final List<Quad> quadsDepth = new ArrayList<>(), quadsSeeThrough = new ArrayList<>();

        void clear() {
            depth.clear();
            seeThrough.clear();
            quadsDepth.clear();
            quadsSeeThrough.clear();
        }

        boolean isEmpty() {
            return depth.isEmpty() && seeThrough.isEmpty() && quadsDepth.isEmpty() && quadsSeeThrough.isEmpty();
        }
    }

    private static final List<Drawer> DRAWERS = new CopyOnWriteArrayList<>(), UNDER_DRAWERS = new CopyOnWriteArrayList<>();
    private static final Batch MAIN = new Batch(), UNDER = new Batch();
    private static RenderType seeThroughLines, underLines, quadsDepth, quadsSeeThrough;
    private static boolean seeThroughFailed, underFailed, quadsFailed;
    private static boolean registered;

    private WorldRenderAdapter() {}

    public static void register() {
        if (registered) return;
        registered = true;
        LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(ctx -> snapshotDepth());
        LevelRenderEvents.END_MAIN.register(WorldRenderAdapter::render);
    }

    public static void addDrawer(Drawer drawer) {
        DRAWERS.add(drawer);
    }

    /** A drawer for the bottom layer. It's drawn before all the others and doesn't write depth, so it never covers them. */
    public static void addUnderDrawer(Drawer drawer) {
        UNDER_DRAWERS.add(drawer);
    }

    private static void render(LevelRenderContext ctx) {
        captureView(ctx.levelState().cameraRenderState);
        GpuTextureView depth = depthReady ? opaqueDepthView : null;
        depthReady = false;
        GpuTextureView prevDepth = RenderSystem.outputDepthTextureOverride;
        if (depth != null) RenderSystem.outputDepthTextureOverride = depth;
        try {
            renderShapes(ctx);
            WorldLabelAdapter.render(ctx);
        } finally {
            if (depth != null) RenderSystem.outputDepthTextureOverride = prevDepth;
        }
    }

    private static GpuTexture opaqueDepth;
    private static GpuTextureView opaqueDepthView;
    private static boolean depthReady, depthFailed;

    /**
     * Copies the main depth buffer right before translucent terrain, which gives the depth of opaque blocks and
     * entities only. The overlays drawn at {@code END_MAIN} test against this copy (via
     * {@link RenderSystem#outputDepthTextureOverride}, the same mechanism vanilla's late debug pass uses), so water,
     * glass, ice and translucent particles neither hide nor tint them. If the copy fails, they test against the live
     * depth buffer instead.
     */
    private static void snapshotDepth() {
        depthReady = false;
        if (depthFailed) return;
        try {
            RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
            GpuTexture src = main.getDepthTexture();
            if (src == null) return;
            int w = src.getWidth(0), h = src.getHeight(0);
            if (opaqueDepth == null || opaqueDepth.isClosed() || opaqueDepth.getWidth(0) != w
                    || opaqueDepth.getHeight(0) != h || opaqueDepth.getFormat() != src.getFormat()) {
                if (opaqueDepthView != null) opaqueDepthView.close();
                if (opaqueDepth != null) opaqueDepth.close();
                var device = RenderSystem.getDevice();
                opaqueDepth = device.createTexture(Telemetric.MOD_ID + " opaque depth",
                        GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                        src.getFormat(), w, h, 1, 1);
                opaqueDepthView = device.createTextureView(opaqueDepth);
            }
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(src, opaqueDepth, 0, 0, 0, 0, 0, w, h);
            depthReady = true;
        } catch (RuntimeException | LinkageError e) {
            depthFailed = true;
            Telemetric.LOGGER.error("Opaque depth copy unavailable, world overlays use the live depth buffer", e);
        }
    }

    private static final Matrix4f VIEW_PROJ = new Matrix4f();
    private static Vec3 viewCam;
    private static float viewYaw, viewPitch;
    private static long viewAt;

    /** Remembers this frame's camera so HUD code can project world points ({@link #toScreen}). */
    private static void captureView(CameraRenderState cam) {
        if (cam == null || cam.pos == null || cam.projectionMatrix == null || cam.viewRotationMatrix == null) return;
        VIEW_PROJ.set(cam.projectionMatrix).mul(cam.viewRotationMatrix);
        viewCam = cam.pos;
        viewYaw = cam.yRot;
        viewPitch = cam.xRot;
        viewAt = System.nanoTime();
    }

    /** Camera yaw of the last world frame (degrees, vanilla convention, 0 = south). Set before the drawers run. */
    public static float cameraYaw() {
        return viewYaw;
    }

    /** Camera pitch of the last world frame (degrees, −90 = up, 90 = down). Set before the drawers run. */
    public static float cameraPitch() {
        return viewPitch;
    }

    /** Camera position of the last world frame (set before drawers and labels run), or null before the first frame. */
    public static Point cameraPos() {
        Vec3 cam = viewCam;
        return cam == null ? null : new Point(cam.x, cam.y, cam.z);
    }

    /**
     * Where a world point shows up on screen this frame, in GUI units {x, y}. Null if it's behind the camera or no world
     * frame was drawn recently. Call it from HUD rendering (after the world of the same frame).
     */
    public static double[] toScreen(double x, double y, double z) {
        Vec3 cam = viewCam;
        if (cam == null || System.nanoTime() - viewAt > 250_000_000L) return null;
        Vector4f v = VIEW_PROJ.transform(new Vector4f((float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z), 1f));
        if (v.w <= 0.05f) return null;
        Window window = Minecraft.getInstance().getWindow();
        return new double[]{(v.x / v.w + 1) * 0.5 * window.getGuiScaledWidth(),
                (1 - v.y / v.w) * 0.5 * window.getGuiScaledHeight()};
    }

    private static void renderShapes(LevelRenderContext ctx) {
        if (DRAWERS.isEmpty() && UNDER_DRAWERS.isEmpty()) return;
        CameraRenderState camState = ctx.levelState().cameraRenderState;
        Vec3 cam = camState.pos;
        if (cam == null) return;
        MAIN.clear();
        UNDER.clear();
        Sink sink = new Sink(cam, camState.yRot, camState.xRot);
        collect(sink, UNDER_DRAWERS, UNDER);
        collect(sink, DRAWERS, MAIN);
        if (MAIN.isEmpty() && UNDER.isEmpty()) return;

        MultiBufferSource.BufferSource buffers = ctx.bufferSource();
        PoseStack.Pose pose = ctx.poseStack().last();
        if (!UNDER.isEmpty()) {
            RenderType lines = underLines();
            emitBatch(buffers, pose, UNDER, lines != null ? lines : RenderTypes.linesTranslucent());
        }
        if (!MAIN.isEmpty()) emitBatch(buffers, pose, MAIN, RenderTypes.linesTranslucent());
    }

    private static void collect(Sink sink, List<Drawer> drawers, Batch batch) {
        sink.batch = batch;
        for (Drawer d : drawers) {
            try {
                d.draw(sink);
            } catch (RuntimeException e) {
                // logged once per drawer: a broken drawer would otherwise print a stack trace every frame
                if (FAILED_DRAWERS.add(d.getClass())) Telemetric.LOGGER.error("World drawer failed (further failures not logged)", e);
            }
        }
    }

    /** Drawer classes whose failure was already logged (render thread only). */
    private static final java.util.Set<Class<?>> FAILED_DRAWERS = new java.util.HashSet<>();

    /** One layer. Fills go first so outlines drawn on the same box stay on top. */
    private static void emitBatch(MultiBufferSource.BufferSource buffers, PoseStack.Pose pose, Batch b,
                                  RenderType depthLines) {
        if (!b.quadsDepth.isEmpty() || !b.quadsSeeThrough.isEmpty()) buildQuadTypes();
        if (!b.quadsDepth.isEmpty() && quadsDepth != null) emitQuads(buffers, quadsDepth, pose, b.quadsDepth);
        if (!b.quadsSeeThrough.isEmpty()) {
            RenderType type = quadsSeeThrough != null ? quadsSeeThrough : quadsDepth;
            if (type != null) emitQuads(buffers, type, pose, b.quadsSeeThrough);
        }
        if (!b.depth.isEmpty()) emit(buffers, depthLines, pose, b.depth);
        if (!b.seeThrough.isEmpty()) {
            RenderType type = seeThroughLines();
            emit(buffers, type != null ? type : RenderTypes.linesTranslucent(), pose, b.seeThrough);
        }
    }

    /**
     * For the Settings window's 3D preview ({@code ScenePreviewAdapter}): the shapes {@code drawer} makes (world
     * coordinates), stored relative to {@code origin}. Billboards face a camera at {@code yRot / xRot} (degrees, like
     * the game camera); line widths are multiplied by {@code widthScale}.
     */
    static Batch collectFor(Drawer drawer, Vec3 origin, float yRot, float xRot, float widthScale) {
        Batch batch = new Batch();
        Sink sink = new Sink(origin, yRot, xRot);
        sink.batch = batch;
        sink.widthScale = widthScale;
        try {
            drawer.draw(sink);
        } catch (RuntimeException e) {
            if (FAILED_DRAWERS.add(drawer.getClass())) Telemetric.LOGGER.error("Preview drawer failed (further failures not logged)", e);
        }
        return batch;
    }

    /** Draws a {@link #collectFor} batch like the world's bottom layer: depth-tested, writes no depth. */
    static void emitFor(MultiBufferSource.BufferSource buffers, PoseStack.Pose pose, Batch batch) {
        if (batch == null || batch.isEmpty()) return;
        RenderType lines = underLines();
        emitBatch(buffers, pose, batch, lines != null ? lines : RenderTypes.linesTranslucent());
    }

    /** Collects shapes relative to the camera for this frame. */
    private static final class Sink implements LineSink {
        private final Vec3 cam;
        private final Vector3f right, up;
        Batch batch = MAIN;
        float widthScale = 1;

        Sink(Vec3 cam, float yRot, float xRot) {
            this.cam = cam;
            Quaternionf facing = new Quaternionf().rotationYXZ((float) Math.toRadians(180f - yRot),
                    (float) Math.toRadians(-xRot), 0f);
            this.right = facing.transform(new Vector3f(1, 0, 0));
            this.up = facing.transform(new Vector3f(0, 1, 0));
        }

        @Override
        public void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb, float width,
                         boolean throughWalls) {
            line(x1, y1, z1, x2, y2, z2, argb, argb, width, throughWalls);
        }

        @Override
        public void line(double x1, double y1, double z1, double x2, double y2, double z2, int argbStart, int argbEnd,
                         float width, boolean throughWalls) {
            (throughWalls ? batch.seeThrough : batch.depth).add(new Segment((float) (x1 - cam.x), (float) (y1 - cam.y),
                    (float) (z1 - cam.z), (float) (x2 - cam.x), (float) (y2 - cam.y), (float) (z2 - cam.z), argbStart,
                    argbEnd, width * widthScale));
        }

        @Override
        public void quad(double x1, double y1, double z1, double x2, double y2, double z2,
                         double x3, double y3, double z3, double x4, double y4, double z4, int argb,
                         boolean throughWalls) {
            if ((argb >>> 24) == 0) return;
            float[] p = {
                    (float) (x1 - cam.x), (float) (y1 - cam.y), (float) (z1 - cam.z),
                    (float) (x2 - cam.x), (float) (y2 - cam.y), (float) (z2 - cam.z),
                    (float) (x3 - cam.x), (float) (y3 - cam.y), (float) (z3 - cam.z),
                    (float) (x4 - cam.x), (float) (y4 - cam.y), (float) (z4 - cam.z)};
            (throughWalls ? batch.quadsSeeThrough : batch.quadsDepth).add(new Quad(p, new int[] {argb, argb, argb, argb}));
        }

        @Override
        public void billboard(double x, double y, double z, double left, double bottom, double right, double top,
                              ColorRamp ramp, boolean throughWalls) {
            if (ramp.isUniform()) {
                billboard(x, y, z, left, bottom, right, top, ramp.at(0), throughWalls);
                return;
            }
            Vector3f r = this.right, u = this.up;
            int n = 24;
            for (int i = 0; i < n; i++) {
                double a = left + (right - left) * i / n, b = left + (right - left) * (i + 1) / n;
                int ca = ramp.at(i / (float) n), cb = ramp.at((i + 1) / (float) n);
                if ((ca >>> 24) == 0 && (cb >>> 24) == 0) continue;
                float[] p = {
                        (float) (x + r.x * a + u.x * bottom - cam.x), (float) (y + r.y * a + u.y * bottom - cam.y), (float) (z + r.z * a + u.z * bottom - cam.z),
                        (float) (x + r.x * b + u.x * bottom - cam.x), (float) (y + r.y * b + u.y * bottom - cam.y), (float) (z + r.z * b + u.z * bottom - cam.z),
                        (float) (x + r.x * b + u.x * top - cam.x), (float) (y + r.y * b + u.y * top - cam.y), (float) (z + r.z * b + u.z * top - cam.z),
                        (float) (x + r.x * a + u.x * top - cam.x), (float) (y + r.y * a + u.y * top - cam.y), (float) (z + r.z * a + u.z * top - cam.z)};
                (throughWalls ? batch.quadsSeeThrough : batch.quadsDepth).add(new Quad(p, new int[] {ca, cb, cb, ca}));
            }
        }

        @Override
        public void billboard(double x, double y, double z, double left, double bottom, double right, double top,
                              int argb, boolean throughWalls) {
            Vector3f r = this.right, u = this.up;
            quad(x + r.x * left + u.x * bottom, y + r.y * left + u.y * bottom, z + r.z * left + u.z * bottom,
                    x + r.x * right + u.x * bottom, y + r.y * right + u.y * bottom, z + r.z * right + u.z * bottom,
                    x + r.x * right + u.x * top, y + r.y * right + u.y * top, z + r.z * right + u.z * top,
                    x + r.x * left + u.x * top, y + r.y * left + u.y * top, z + r.z * left + u.z * top,
                    argb, throughWalls);
        }
    }

    private static void emit(MultiBufferSource.BufferSource buffers, RenderType type, PoseStack.Pose pose, List<Segment> segments) {
        VertexConsumer vc = buffers.getBuffer(type);
        for (Segment s : segments) {
            float dx = s.x2 - s.x1, dy = s.y2 - s.y1, dz = s.z2 - s.z1;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-5f) continue;
            dx /= len;
            dy /= len;
            dz /= len;
            vc.addVertex(pose, s.x1, s.y1, s.z1).setColor(s.argb).setNormal(pose, dx, dy, dz).setLineWidth(s.width);
            vc.addVertex(pose, s.x2, s.y2, s.z2).setColor(s.argb2).setNormal(pose, dx, dy, dz).setLineWidth(s.width);
        }
        buffers.endBatch(type);
    }

    private static void emitQuads(MultiBufferSource.BufferSource buffers, RenderType type, PoseStack.Pose pose,
                                  List<Quad> quads) {
        VertexConsumer vc = buffers.getBuffer(type);
        for (Quad q : quads) {
            float[] p = q.xyz;
            for (int i = 0; i < 12; i += 3) vc.addVertex(pose, p[i], p[i + 1], p[i + 2]).setColor(q.argb[i / 3]);
        }
        buffers.endBatch(type);
    }

    /** Vanilla's line pipeline with the depth test off, built once. Null if that fails (then we fall back to depth-tested). */
    private static RenderType seeThroughLines() {
        if (seeThroughLines != null || seeThroughFailed) return seeThroughLines;
        try {
            RenderPipeline pipeline = RenderPipeline.builder(RenderPipelinesAccessor.telemetric$linesSnippet())
                    .withLocation(Identifier.fromNamespaceAndPath(Telemetric.MOD_ID, "pipeline/lines_see_through"))
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                    .build();
            seeThroughLines = renderType("lines_see_through", pipeline);
        } catch (RuntimeException | LinkageError e) {
            seeThroughFailed = true;
            Telemetric.LOGGER.error("See-through line render type unavailable, using depth-tested lines", e);
        }
        return seeThroughLines;
    }

    /** Vanilla's line pipeline, depth-tested but without writing depth (bottom layer), built once. Null if that fails. */
    private static RenderType underLines() {
        if (underLines != null || underFailed) return underLines;
        try {
            RenderPipeline pipeline = RenderPipeline.builder(RenderPipelinesAccessor.telemetric$linesSnippet())
                    .withLocation(Identifier.fromNamespaceAndPath(Telemetric.MOD_ID, "pipeline/lines_under"))
                    .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                    .build();
            underLines = renderType("lines_under", pipeline);
        } catch (RuntimeException | LinkageError e) {
            underFailed = true;
            Telemetric.LOGGER.error("Bottom-layer line render type unavailable, using depth-tested lines", e);
        }
        return underLines;
    }

    /** Translucent position-colour quads (vanilla's debug-fill shaders), both depth-tested and see-through, built once. */
    private static void buildQuadTypes() {
        if (quadsDepth != null || quadsFailed) return;
        try {
            quadsDepth = renderType("quads", quadPipeline("quads",
                    new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false)));
            quadsSeeThrough = renderType("quads_see_through", quadPipeline("quads_see_through",
                    new DepthStencilState(CompareOp.ALWAYS_PASS, false)));
        } catch (RuntimeException | LinkageError e) {
            quadsFailed = true;
            quadsDepth = quadsSeeThrough = null;
            Telemetric.LOGGER.error("Filled world shapes unavailable", e);
        }
    }

    private static RenderPipeline quadPipeline(String name, DepthStencilState depth) {
        return RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath(Telemetric.MOD_ID, "pipeline/" + name))
                .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
                .withCull(false)
                .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT),
                        ColorTargetState.WRITE_ALL))
                .withDepthStencilState(depth)
                .build();
    }

    private static RenderType renderType(String name, RenderPipeline pipeline) {
        RenderSetup setup = RenderSetup.builder(pipeline)
                .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                .setOutputTarget(OutputTarget.MAIN_TARGET)
                .createRenderSetup();
        return RenderTypeInvoker.telemetric$create(Telemetric.MOD_ID + "_" + name, setup);
    }
}
