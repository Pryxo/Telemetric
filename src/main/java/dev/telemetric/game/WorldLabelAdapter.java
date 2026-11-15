package dev.telemetric.game;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.telemetric.Telemetric;
import dev.telemetric.ui.render.BadgeText;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.joml.Quaternionf;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MC ADAPTER — floating text labels in the 3D world (billboards that face the camera, like nameplates).
 * Text is drawn with Minecraft's font renderer in a {@link BadgeText} font, so a badge is just a string of
 * glyphs that tile into one coloured shape. Features register a {@link Source}; it is asked once per frame.
 */
public final class WorldLabelAdapter {

    /**
     * One label. Centred on (x, z); its bottom edge sits at y. {@code height} = glyph height in blocks
     * (badges are 8 px tall, so 1 px = height / 8 blocks). {@code text} is plain text; for badge styles an
     * empty text draws a small blank badge.
     */
    public record Label(double x, double y, double z, String text, BadgeText.Style style, double height, int argb,
                        boolean throughWalls, boolean shadow, boolean bold, boolean underline) {

        public Label(double x, double y, double z, String text, BadgeText.Style style, double height, int argb,
                     boolean throughWalls) {
            this(x, y, z, text, style, height, argb, throughWalls, true, false, false);
        }
    }

    @FunctionalInterface
    public interface Source {
        /** Labels for this frame. {@code partialTick} interpolates positions between ticks. */
        List<Label> labels(float partialTick);
    }

    private static final int FULL_BRIGHT = 0xF000F0;
    private static final float GLYPH_PX = 8f;
    private static final List<Source> SOURCES = new CopyOnWriteArrayList<>();
    private static final Map<BadgeText.Style, Style> FONTS = new EnumMap<>(BadgeText.Style.class);
    private static boolean registered;

    static {
        for (BadgeText.Style s : BadgeText.Style.values()) {
            FONTS.put(s, s.font == null ? Style.EMPTY : Style.EMPTY.withFont(
                    new FontDescription.Resource(Identifier.fromNamespaceAndPath(Telemetric.MOD_ID, s.font))));
        }
    }

    private WorldLabelAdapter() {}

    public static void addSource(Source source) {
        SOURCES.add(source);
        if (registered) return;
        registered = true;
        LevelRenderEvents.BEFORE_GIZMOS.register(WorldLabelAdapter::render);
    }

    private static void render(LevelRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (SOURCES.isEmpty() || mc.level == null || mc.options.hideGui) return;
        CameraRenderState cam = ctx.levelState().cameraRenderState;
        if (cam == null || cam.pos == null) return;
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Quaternionf facing = new Quaternionf().rotationYXZ((float) Math.toRadians(180f - cam.yRot),
                (float) Math.toRadians(-cam.xRot), 0f);
        Font font = mc.font;
        PoseStack pose = ctx.poseStack();
        MultiBufferSource.BufferSource buffers = ctx.bufferSource();
        boolean drew = false;
        for (Source source : SOURCES) {
            List<Label> labels;
            try {
                labels = source.labels(partial);
            } catch (RuntimeException e) {
                Telemetric.LOGGER.error("World label source failed", e);
                continue;
            }
            if (labels == null) continue;
            for (Label l : labels) {
                if (l.height() <= 0) continue;
                Component text = component(l);
                float unit = (float) (l.height() / GLYPH_PX);
                int argb = (l.argb() >>> 24) == 0 ? l.argb() | 0xFF000000 : l.argb();
                pose.pushPose();
                pose.translate(l.x() - cam.pos.x, l.y() - cam.pos.y, l.z() - cam.pos.z);
                pose.mulPose(facing);
                pose.scale(unit, -unit, unit);
                font.drawInBatch(text, -font.width(text) / 2f, -GLYPH_PX, argb, l.shadow(), pose.last().pose(),
                        buffers, l.throughWalls() ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL,
                        0, FULL_BRIGHT);
                pose.popPose();
                drew = true;
            }
        }
        if (drew) buffers.endBatch();
    }

    private static Component component(Label l) {
        if (l.style() == BadgeText.Style.PLAIN) {
            Style style = Style.EMPTY.withBold(l.bold()).withUnderlined(l.underline());
            return Component.literal(l.text() == null ? "" : l.text()).withStyle(style);
        }
        MutableComponent c = Component.literal(BadgeText.encode(l.text()));
        return c.withStyle(FONTS.get(l.style()));
    }
}
