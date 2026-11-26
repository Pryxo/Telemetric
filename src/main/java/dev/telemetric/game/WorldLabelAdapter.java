package dev.telemetric.game;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.telemetric.Telemetric;
import dev.telemetric.ui.render.BadgeText;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Floating text labels in the 3D world (billboards that face the camera, like nameplates). The text is drawn with the
 * font renderer in a {@link BadgeText} font, so a badge is a string of glyphs that tiles into one coloured shape.
 * Features register a {@link Source}, which is asked once per frame.
 */
public final class WorldLabelAdapter {

    /**
     * One label, centred on (x, z) with its bottom edge at y. {@code height} is the glyph height in blocks (badges are
     * 8 px tall, so 1 px = height / 8 blocks). {@code text} is plain text. For badge styles, empty text draws a small
     * blank badge. {@code bg} (ARGB, 0 = none) is a rounded panel behind the text, {@code bgPad} label px around it,
     * with an {@code accent} bar (ARGB, 0 = none) on its left edge, as on the HUD elements.
     */
    public record Label(double x, double y, double z, String text, BadgeText.Style style, double height, int argb,
                        boolean throughWalls, boolean shadow, boolean bold, boolean underline,
                        double sideOffset, float align, boolean outline, String icon, int bg, int accent, int bgPad) {

        public Label(double x, double y, double z, String text, BadgeText.Style style, double height, int argb,
                     boolean throughWalls, boolean shadow, boolean bold, boolean underline) {
            this(x, y, z, text, style, height, argb, throughWalls, shadow, bold, underline, 0, 0.5f, false, null, 0, 0, 0);
        }

        public Label(double x, double y, double z, String text, BadgeText.Style style, double height, int argb,
                     boolean throughWalls) {
            this(x, y, z, text, style, height, argb, throughWalls, true, false, false);
        }

        /**
         * Moves the anchor {@code sideOffset} blocks to the camera's right (negative for left) and aligns the text on
         * it: an {@code align} of 0 starts the text there, 0.5 centres it, and 1 ends it there.
         */
        public Label aligned(double sideOffset, float align) {
            return new Label(x, y, z, text, style, height, argb, throughWalls, shadow, bold, underline, sideOffset,
                    align, outline, icon, bg, accent, bgPad);
        }

        /** Shadow off or on, or a black outline instead (the outline wins). */
        public Label look(boolean shadow, boolean outline) {
            return new Label(x, y, z, text, style, height, argb, throughWalls, shadow && !outline, bold, underline,
                    sideOffset, align, outline, icon, bg, accent, bgPad);
        }

        /** A rounded background panel (ARGB {@code bg}, 0 = none), {@code pad} label px around the text, with an accent bar (0 = none). */
        public Label withBackground(int bg, int accent, int pad) {
            return new Label(x, y, z, text, style, height, argb, throughWalls, shadow, bold, underline, sideOffset,
                    align, outline, icon, bg, accent, Math.max(0, pad));
        }

        /** True when a background panel is drawn. */
        public boolean hasBackground() {
            return (bg >>> 24) != 0;
        }

        /**
         * Adds a picture {@link #ICON_PX} px square: the item model's texture ({@code "telos:material/boss/anubis"} →
         * {@code telos:textures/material/boss/anubis.png}; needs the pack that has it). It's drawn above the text, or
         * stands on the anchor when the text is empty. Null or empty means no icon.
         */
        public Label withIcon(String model) {
            return new Label(x, y, z, text, style, height, argb, throughWalls, shadow, bold, underline, sideOffset,
                    align, outline, model, bg, accent, bgPad);
        }
    }

    @FunctionalInterface
    public interface Source {
        /** Labels for this frame. {@code partialTick} interpolates positions between ticks. */
        List<Label> labels(float partialTick);
    }

    private static final int FULL_BRIGHT = 0xF000F0;
    private static final float GLYPH_PX = 8f;
    /** Icon size in label pixels (two text lines), and its gap to the text. */
    public static final float ICON_PX = 16f, ICON_GAP_PX = 2f;
    private static final Map<String, Identifier> ICON_TEXTURES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final List<Source> SOURCES = new CopyOnWriteArrayList<>();
    /** Sources whose failure was already logged (render thread only). */
    private static final java.util.Set<Class<?>> FAILED_SOURCES = new java.util.HashSet<>();
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
                // once per source, not once per frame
                if (FAILED_SOURCES.add(source.getClass())) Telemetric.LOGGER.error("World label source failed (further failures not logged)", e);
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
                if (l.hasBackground()) {
                    buffers.endBatch(); // earlier labels first, so this panel can't cover their text
                    drawBackground(l, tx, font.width(text), pose, buffers);
                }
                if (l.outline() && l.throughWalls()) {
                    // drawInBatch8xOutline is always depth-tested, so we build the outline from 8 see-through copies instead.
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
                if (l.icon() != null && !l.icon().isEmpty()) drawIcon(l, text, tx, argb, pose, buffers, font);
                pose.popPose();
                drew = true;
            }
        }
        if (drew) buffers.endBatch();
    }

    /**
     * The background panel in the label's pixel space (text spans -8..0), with stepped 2 px corners like the HUD's
     * rounded panels. It sits just behind the text (z -0.01, where vanilla draws its text backgrounds); it's drawn
     * before the text, so see-through labels paint the text over it.
     */
    private static void drawBackground(Label l, float tx, int width, PoseStack pose, MultiBufferSource.BufferSource buffers) {
        int pad = l.bgPad();
        float x0 = tx - pad, x1 = tx + width + pad, y0 = -GLYPH_PX - pad, y1 = pad;
        RenderType type = l.throughWalls() ? RenderTypes.textBackgroundSeeThrough() : RenderTypes.textBackground();
        VertexConsumer vc = buffers.getBuffer(type);
        Matrix4f m = pose.last().pose();
        int bg = l.bg();
        float r = Math.min(2, Math.min(x1 - x0, y1 - y0) / 2);
        if (r >= 2) {
            quad(vc, m, x0 + 2, y0, x1 - 2, y0 + 1, -0.01f, bg);
            quad(vc, m, x0 + 1, y0 + 1, x1 - 1, y0 + 2, -0.01f, bg);
            quad(vc, m, x0, y0 + 2, x1, y1 - 2, -0.01f, bg);
            quad(vc, m, x0 + 1, y1 - 2, x1 - 1, y1 - 1, -0.01f, bg);
            quad(vc, m, x0 + 2, y1 - 1, x1 - 2, y1, -0.01f, bg);
        } else {
            quad(vc, m, x0, y0, x1, y1, -0.01f, bg);
        }
        if ((l.accent() >>> 24) != 0 && y1 - y0 > 4) quad(vc, m, x0, y0 + 2, x0 + 1, y1 - 2, -0.005f, l.accent());
        // Flush the panel now: see-through text ignores depth, so if the panel batch were drawn after the text batch
        // (endBatch order isn't ours to pick) the translucent panel would darken the text.
        buffers.endBatch(type);
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1, float z, int argb) {
        vc.addVertex(m, x0, y0, z).setColor(argb).setLight(FULL_BRIGHT);
        vc.addVertex(m, x0, y1, z).setColor(argb).setLight(FULL_BRIGHT);
        vc.addVertex(m, x1, y1, z).setColor(argb).setLight(FULL_BRIGHT);
        vc.addVertex(m, x1, y0, z).setColor(argb).setLight(FULL_BRIGHT);
    }

    /** The icon quad in the label's pixel space (y grows downward, text spans -8..0), centred over the text. */
    private static void drawIcon(Label l, Component text, float tx, int argb, PoseStack pose,
                                 MultiBufferSource buffers, Font font) {
        Identifier tex = iconTexture(l.icon());
        if (tex == null) return;
        boolean hasText = font.width(text) > 0;
        float cx = hasText ? tx + font.width(text) / 2f : -ICON_PX * (l.align() - 0.5f);
        float x0 = cx - ICON_PX / 2f, x1 = x0 + ICON_PX;
        float y1 = hasText ? -GLYPH_PX - ICON_GAP_PX : 0, y0 = y1 - ICON_PX;
        int color = (argb & 0xFF000000) | 0xFFFFFF; // the picture keeps its own colours, only the fade is applied
        VertexConsumer vc = buffers.getBuffer(l.throughWalls() ? RenderTypes.textSeeThrough(tex) : RenderTypes.text(tex));
        Matrix4f m = pose.last().pose();
        vc.addVertex(m, x0, y0, 0).setColor(color).setUv(0, 0).setLight(FULL_BRIGHT);
        vc.addVertex(m, x0, y1, 0).setColor(color).setUv(0, 1).setLight(FULL_BRIGHT);
        vc.addVertex(m, x1, y1, 0).setColor(color).setUv(1, 1).setLight(FULL_BRIGHT);
        vc.addVertex(m, x1, y0, 0).setColor(color).setUv(1, 0).setLight(FULL_BRIGHT);
    }

    private static Identifier iconTexture(String model) {
        return ICON_TEXTURES.computeIfAbsent(model, id -> {
            int colon = id.indexOf(':');
            String ns = colon < 0 ? "minecraft" : id.substring(0, colon), path = id.substring(colon + 1);
            Identifier out = Identifier.tryBuild(ns, "textures/" + path + ".png");
            if (out == null) Telemetric.debug("World label: bad icon id '{}'", id);
            return out;
        });
    }

    /** The text of a label as the world draws it (badge font glyphs, or plain text with bold / underline). */
    public static Component text(Label l) {
        return component(l);
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
