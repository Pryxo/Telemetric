package dev.telemetric.game;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.telemetric.Telemetric;
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
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MC ADAPTER — the only place that draws shapes in the 3D world. Features register a {@link Drawer} and emit plain
 * world-space shapes through a {@link LineSink}: line segments, filled quads, and the helpers built on them (box
 * outline / fill, camera-facing rectangles). Everything is batched into four render types once per frame:
 * lines / quads, each depth-tested or see-through (no depth test). {@link WorldLabelAdapter} labels are drawn
 * right after, from the same hook.
 */
public final class WorldRenderAdapter {

    /** Receives shapes in world coordinates. Line width is in screen pixels. */
    public interface LineSink {
        void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb, float width,
                  boolean throughWalls);

        /** Filled quad (corners in order). Translucent colours blend; both sides are drawn. */
        void quad(double x1, double y1, double z1, double x2, double y2, double z2,
                  double x3, double y3, double z3, double x4, double y4, double z4, int argb, boolean throughWalls);

        /**
         * Rectangle that faces the camera (like a nameplate), anchored at a world point. {@code left/right} go
         * along the screen's right axis, {@code bottom/top} along its up axis, in blocks.
         */
        void billboard(double x, double y, double z, double left, double bottom, double right, double top, int argb,
                       boolean throughWalls);

        /** The 12 edges of an axis-aligned box. */
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

    private record Segment(float x1, float y1, float z1, float x2, float y2, float z2, int argb, float width) {}

    private record Quad(float[] xyz, int argb) {}

    private static final List<Drawer> DRAWERS = new CopyOnWriteArrayList<>();
    private static final List<Segment> DEPTH = new ArrayList<>(), SEE_THROUGH = new ArrayList<>();
    private static final List<Quad> QUADS_DEPTH = new ArrayList<>(), QUADS_SEE_THROUGH = new ArrayList<>();
    private static RenderType seeThroughLines, quadsDepth, quadsSeeThrough;
    private static boolean seeThroughFailed, quadsFailed;
    private static boolean registered;

    private WorldRenderAdapter() {}

    public static void register() {
        if (registered) return;
        registered = true;
        LevelRenderEvents.BEFORE_GIZMOS.register(WorldRenderAdapter::render);
    }

    public static void addDrawer(Drawer drawer) {
        DRAWERS.add(drawer);
    }

    private static void render(LevelRenderContext ctx) {
        renderShapes(ctx);
        WorldLabelAdapter.render(ctx);
    }

    private static void renderShapes(LevelRenderContext ctx) {
        if (DRAWERS.isEmpty()) return;
        CameraRenderState camState = ctx.levelState().cameraRenderState;
        Vec3 cam = camState.pos;
        if (cam == null) return;
        DEPTH.clear();
        SEE_THROUGH.clear();
        QUADS_DEPTH.clear();
        QUADS_SEE_THROUGH.clear();
        Sink sink = new Sink(cam, camState.yRot, camState.xRot);
        for (Drawer d : DRAWERS) {
            try {
                d.draw(sink);
            } catch (RuntimeException e) {
                Telemetric.LOGGER.error("World drawer failed", e);
            }
        }
        if (DEPTH.isEmpty() && SEE_THROUGH.isEmpty() && QUADS_DEPTH.isEmpty() && QUADS_SEE_THROUGH.isEmpty()) return;

        MultiBufferSource.BufferSource buffers = ctx.bufferSource();
        PoseStack.Pose pose = ctx.poseStack().last();
        // Fills first so outlines drawn on the same box stay on top.
        if (!QUADS_DEPTH.isEmpty() || !QUADS_SEE_THROUGH.isEmpty()) buildQuadTypes();
        if (!QUADS_DEPTH.isEmpty() && quadsDepth != null) emitQuads(buffers, quadsDepth, pose, QUADS_DEPTH);
        if (!QUADS_SEE_THROUGH.isEmpty()) {
            RenderType type = quadsSeeThrough != null ? quadsSeeThrough : quadsDepth;
            if (type != null) emitQuads(buffers, type, pose, QUADS_SEE_THROUGH);
        }
        if (!DEPTH.isEmpty()) emit(buffers, RenderTypes.linesTranslucent(), pose, DEPTH);
        if (!SEE_THROUGH.isEmpty()) {
            RenderType type = seeThroughLines();
            emit(buffers, type != null ? type : RenderTypes.linesTranslucent(), pose, SEE_THROUGH);
        }
    }

    /** Collects shapes relative to the camera for this frame. */
    private static final class Sink implements LineSink {
        private final Vec3 cam;
        private final Vector3f right, up;

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
            (throughWalls ? SEE_THROUGH : DEPTH).add(new Segment((float) (x1 - cam.x), (float) (y1 - cam.y),
                    (float) (z1 - cam.z), (float) (x2 - cam.x), (float) (y2 - cam.y), (float) (z2 - cam.z), argb, width));
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
            (throughWalls ? QUADS_SEE_THROUGH : QUADS_DEPTH).add(new Quad(p, argb));
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
            vc.addVertex(pose, s.x2, s.y2, s.z2).setColor(s.argb).setNormal(pose, dx, dy, dz).setLineWidth(s.width);
        }
        buffers.endBatch(type);
    }

    private static void emitQuads(MultiBufferSource.BufferSource buffers, RenderType type, PoseStack.Pose pose,
                                  List<Quad> quads) {
        VertexConsumer vc = buffers.getBuffer(type);
        for (Quad q : quads) {
            float[] p = q.xyz;
            for (int i = 0; i < 12; i += 3) vc.addVertex(pose, p[i], p[i + 1], p[i + 2]).setColor(q.argb);
        }
        buffers.endBatch(type);
    }

    /** Vanilla's line pipeline with depth test off, built once. Null if that fails (falls back to depth-tested). */
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

    /** Translucent position-colour quads (vanilla's debug-fill shaders), depth-tested and see-through, built once. */
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
