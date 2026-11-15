package dev.telemetric.game;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.telemetric.Telemetric;
import dev.telemetric.ui.render.BadgeText;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
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
                        boolean throughWalls, boolean shadow, boolean bold, boolean underline,
                        double sideOffset, float align, boolean outline) {

        public Label(double x, double y, double z, String text, BadgeText.Style style, double height, int argb,
                     boolean throughWalls, boolean shadow, boolean bold, boolean underline) {
            this(x, y, z, text, style, height, argb, throughWalls, shadow, bold, underline, 0, 0.5f, false);
        }

        public Label(double x, double y, double z, String text, BadgeText.Style style, double height, int argb,
                     boolean throughWalls) {
            this(x, y, z, text, style, height, argb, throughWalls, true, false, false);
        }

        /**
         * Moves the anchor {@code sideOffset} blocks to the camera's right (negative = left) and aligns the text on
         * it: {@code align} 0 = text starts there, 0.5 = centred, 1 = text ends there.
         */
        public Label aligned(double sideOffset, float align) {
            return new Label(x, y, z, text, style, height, argb, throughWalls, shadow, bold, underline, sideOffset,
                    align, outline);
        }

        /** Shadow off / on, or a black outline instead (outline wins). */
        public Label look(boolean shadow, boolean outline) {
            return new Label(x, y, z, text, style, height, argb, throughWalls, shadow && !outline, bold, underline,
                    sideOffset, align, outline);
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

    static {
        for (BadgeText.Style s : BadgeText.Style.values()) {
            FONTS.put(s, s.font == null ? Style.EMPTY : Style.EMPTY.withFont(
                    new FontDescription.Resource(Identifier.fromNamespaceAndPath(Telemetric.MOD_ID, s.font))));
        }
    }

    private WorldLabelAdapter() {}

    public static void addSource(Source source) {
        SOURCES.add(source);
    }

    /** Called by {@link WorldRenderAdapter} right after the world shapes, so labels sit on top of filled shapes. */
    static void render(LevelRenderContext ctx) {
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
                if (l.sideOffset() != 0) pose.translate(l.sideOffset(), 0, 0);
                pose.scale(unit, -unit, unit);
                float tx = -font.width(text) * l.align();
                if (l.outline() && l.throughWalls()) {
                    // drawInBatch8xOutline is always depth-tested, so build the outline from 8 see-through copies.
                    int black = argb & 0xFF000000;
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            if (dx == 0 && dy == 0) continue;
                            font.drawInBatch(text, tx + dx, -GLYPH_PX + dy, black, false, pose.last().pose(),
                                    buffers, Font.DisplayMode.SEE_THROUGH, 0, FULL_BRIGHT);
                        }
                    }
                    font.drawInBatch(text, tx, -GLYPH_PX, argb, false, pose.last().pose(), buffers,
                            Font.DisplayMode.SEE_THROUGH, 0, FULL_BRIGHT);
                } else if (l.outline()) {
                    font.drawInBatch8xOutline(text.getVisualOrderText(), tx, -GLYPH_PX, argb, argb & 0xFF000000,
                            pose.last().pose(), buffers, FULL_BRIGHT);
                } else {
                    font.drawInBatch(text, tx, -GLYPH_PX, argb, l.shadow(), pose.last().pose(),
                            buffers, l.throughWalls() ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL,
                            0, FULL_BRIGHT);
                }
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
