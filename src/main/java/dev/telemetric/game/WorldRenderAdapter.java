package dev.telemetric.game;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.telemetric.Telemetric;
import dev.telemetric.mixin.RenderPipelinesAccessor;
import dev.telemetric.mixin.RenderTypeInvoker;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MC ADAPTER — the only place that draws in the 3D world. Features register a {@link Drawer} and emit
 * plain world-space line segments through a {@link LineSink}; this class batches them into vanilla's
 * line render type (depth-tested) or a see-through variant (no depth test) once per frame.
 */
public final class WorldRenderAdapter {

    /** Receives line segments in world coordinates. Width is in screen pixels. */
    public interface LineSink {
        void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb, float width,
                  boolean throughWalls);
    }

    @FunctionalInterface
    public interface Drawer {
        void draw(LineSink sink);
    }

    private record Segment(float x1, float y1, float z1, float x2, float y2, float z2, int argb, float width) {}

    private static final List<Drawer> DRAWERS = new CopyOnWriteArrayList<>();
    private static final List<Segment> DEPTH = new ArrayList<>();
    private static final List<Segment> SEE_THROUGH = new ArrayList<>();
    private static RenderType seeThroughType;
    private static boolean seeThroughFailed;
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
        if (DRAWERS.isEmpty()) return;
        Vec3 cam = ctx.levelState().cameraRenderState.pos;
        if (cam == null) return;
        DEPTH.clear();
        SEE_THROUGH.clear();
        LineSink sink = (x1, y1, z1, x2, y2, z2, argb, width, throughWalls) -> {
            Segment s = new Segment((float) (x1 - cam.x), (float) (y1 - cam.y), (float) (z1 - cam.z),
                    (float) (x2 - cam.x), (float) (y2 - cam.y), (float) (z2 - cam.z), argb, width);
            (throughWalls ? SEE_THROUGH : DEPTH).add(s);
        };
        for (Drawer d : DRAWERS) {
            try {
                d.draw(sink);
            } catch (RuntimeException e) {
                Telemetric.LOGGER.error("World drawer failed", e);
            }
        }
        if (DEPTH.isEmpty() && SEE_THROUGH.isEmpty()) return;

        MultiBufferSource.BufferSource buffers = ctx.bufferSource();
        PoseStack.Pose pose = ctx.poseStack().last();
        if (!DEPTH.isEmpty()) emit(buffers, RenderTypes.linesTranslucent(), pose, DEPTH);
        if (!SEE_THROUGH.isEmpty()) {
            RenderType type = seeThroughType();
            emit(buffers, type != null ? type : RenderTypes.linesTranslucent(), pose, SEE_THROUGH);
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

    /** Vanilla's line pipeline with depth test off, built once. Null if that fails (falls back to depth-tested). */
    private static RenderType seeThroughType() {
        if (seeThroughType != null || seeThroughFailed) return seeThroughType;
        try {
            RenderPipeline pipeline = RenderPipeline.builder(RenderPipelinesAccessor.telemetric$linesSnippet())
                    .withLocation(Identifier.fromNamespaceAndPath(Telemetric.MOD_ID, "pipeline/lines_see_through"))
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                    .build();
            RenderSetup setup = RenderSetup.builder(pipeline)
                    .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                    .setOutputTarget(OutputTarget.MAIN_TARGET)
                    .createRenderSetup();
            seeThroughType = RenderTypeInvoker.telemetric$create(Telemetric.MOD_ID + "_lines_see_through", setup);
        } catch (RuntimeException | LinkageError e) {
            seeThroughFailed = true;
            Telemetric.LOGGER.error("See-through line render type unavailable, using depth-tested lines", e);
        }
        return seeThroughType;
    }
}
