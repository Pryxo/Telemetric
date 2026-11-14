package dev.telemetric.ui.render;

import dev.telemetric.Telemetric;
import dev.telemetric.game.GameItem;
import dev.telemetric.hud.HudIcon;
import dev.telemetric.hud.TextRun;
import dev.telemetric.mixin.GuiGraphicsExtractorAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MC ADAPTER — the only class that draws. Wraps {@link GuiGraphicsExtractor} (26.1's renamed
 * {@code GuiGraphics}) and adds rounded shapes, anti-aliased corners, gradients, soft shadows and a
 * global alpha stack (used for fade-in/out of whole subtrees).
 * <p>
 * All coordinates are GUI units. Colours are ARGB (alpha matters — 0xRRGGBB is invisible).
 * <p>
 * <b>Layering:</b> in 26.1 the GUI is collected into strata; inside one stratum text is drawn after
 * all fills. Call {@link #nextLayer()} before drawing anything that must cover earlier text
 * (overlapping panels, tooltips, toasts).
 */
public final class Render2D {

    public static final int CORNER_TL = 1, CORNER_TR = 2, CORNER_BL = 4, CORNER_BR = 8;
    public static final int CORNERS_ALL = 15, CORNERS_TOP = CORNER_TL | CORNER_TR, CORNERS_BOTTOM = CORNER_BL | CORNER_BR;

    private GuiGraphicsExtractor g;
    private Font font;
    private float alpha = 1f;
    private final Deque<Float> alphaStack = new ArrayDeque<>();
    private boolean textShadow;
    private final Map<String, Identifier> textures = new HashMap<>();

    // ================================================================== frame

    public void begin(GuiGraphicsExtractor graphics, boolean textShadow) {
        this.g = graphics;
        this.font = Minecraft.getInstance().font;
        this.alpha = 1f;
        this.alphaStack.clear();
        this.textShadow = textShadow;
    }

    public void end() {
        this.g = null;
    }

    // ================================================================== state

    /** Multiplies the alpha of everything drawn until {@link #popAlpha()}. */
    public void pushAlpha(double factor) {
        alphaStack.push(alpha);
        alpha *= (float) Math.clamp(factor, 0, 1);
    }

    public void popAlpha() {
        alpha = alphaStack.isEmpty() ? 1f : alphaStack.pop();
    }

    public float currentAlpha() {
        return alpha;
    }

    public void pushScale(float scale) {
        g.pose().pushMatrix();
        g.pose().scale(scale, scale);
    }

    public void popScale() {
        g.pose().popMatrix();
    }

    /** Moves the origin to (x, y) and scales around it. Balance with {@link #popTransform()}. */
    public void pushTransform(float x, float y, float scale) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
    }

    public void popTransform() {
        g.pose().popMatrix();
    }

    /** Window pixels per GUI unit (vanilla's GUI scale, e.g. 2). */
    public static int guiScale() {
        return Math.max(1, (int) Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
    }

    /**
     * Largest scale ≤ {@code fit} at which one GUI unit is a whole number of window pixels, so items and
     * textures render pixel-exact like vanilla (no atlas bleed or uneven texels); {@code fit} itself if even one
     * window pixel per unit does not fit.
     */
    public static float pixelScale(float fit) {
        int gs = guiScale();
        int px = (int) Math.floor(fit * gs + 1e-4f);
        return px >= 1 ? (float) px / gs : fit;
    }

    /** {@code v} (GUI units) rounded down to a whole window pixel. */
    public static float snapToPixel(float v) {
        int gs = guiScale();
        return (float) Math.floor(v * gs) / gs;
    }

    /** True between {@link #begin} and {@link #end}. */
    public boolean isActive() {
        return g != null;
    }

    /** Clip to a rectangle (intersects with any outer clip). Must be balanced with {@link #popClip()}. */
    public void pushClip(int x, int y, int w, int h) {
        g.enableScissor(x, y, x + Math.max(0, w), y + Math.max(0, h));
    }

    public void popClip() {
        g.disableScissor();
    }

    /** Starts a new render stratum: everything after this covers everything before (text included). */
    public void nextLayer() {
        g.nextStratum();
    }

    private int c(int argb) {
        return alpha >= 0.999f ? argb : ColorUtil.fade(argb, alpha);
    }

    // ================================================================== primitives

    public void rect(int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0 || ColorUtil.alpha(color) == 0) return;
        g.fill(x, y, x + w, y + h, c(color));
    }

    public void outline(int x, int y, int w, int h, int color) {
        rect(x, y, w, 1, color);
        rect(x, y + h - 1, w, 1, color);
        rect(x, y + 1, 1, h - 2, color);
        rect(x + w - 1, y + 1, 1, h - 2, color);
    }

    public void gradientV(int x, int y, int w, int h, int top, int bottom) {
        if (w <= 0 || h <= 0) return;
        g.fillGradient(x, y, x + w, y + h, c(top), c(bottom));
    }

    /** Horizontal gradient drawn as 1-unit columns (fine for thin bars / small widths). */
    public void gradientH(int x, int y, int w, int h, int left, int right) {
        if (w <= 0 || h <= 0) return;
        if (w == 1) {
            rect(x, y, 1, h, left);
            return;
        }
        for (int i = 0; i < w; i++) rect(x + i, y, 1, h, ColorUtil.lerp(left, right, i / (double) (w - 1)));
    }

    /** Rounded rectangle with anti-aliased corners. {@code corners} = bitmask of CORNER_* flags. */
    public void roundedRect(int x, int y, int w, int h, int radius, int color, int corners) {
        if (w <= 0 || h <= 0) return;
        int r = Math.min(radius, Math.min(w, h) / 2);
        if (r <= 0 || corners == 0) {
            rect(x, y, w, h, color);
            return;
        }
        boolean top = (corners & CORNERS_TOP) != 0, bottom = (corners & CORNERS_BOTTOM) != 0;
        int topRows = top ? r : 0, bottomRows = bottom ? r : 0;
        rect(x, y + topRows, w, h - topRows - bottomRows, color);
        for (int i = 0; i < r; i++) {
            double inset = cornerInset(r, i);
            if (top) row(x, y + i, w, inset, (corners & CORNER_TL) != 0, (corners & CORNER_TR) != 0, color);
            if (bottom) row(x, y + h - 1 - i, w, inset, (corners & CORNER_BL) != 0, (corners & CORNER_BR) != 0, color);
        }
    }

    public void roundedRect(int x, int y, int w, int h, int radius, int color) {
        roundedRect(x, y, w, h, radius, color, CORNERS_ALL);
    }

    /** Filled anti-aliased circle centred on (cx, cy). */
    public void circle(double cx, double cy, double radius, int color) {
        int top = (int) Math.floor(cy - radius), bottom = (int) Math.ceil(cy + radius);
        for (int py = top; py < bottom; py++) {
            double dy = (py + 0.5) - cy;
            double half = radius * radius - dy * dy;
            if (half <= 0) continue;
            double span = Math.sqrt(half);
            double left = cx - span, right = cx + span;
            int innerL = (int) Math.ceil(left), innerR = (int) Math.floor(right);
            if (innerR > innerL) rect(innerL, py, innerR - innerL, 1, color);
            double covL = innerL - left, covR = right - innerR;
            if (covL > 0.02) rect(innerL - 1, py, 1, 1, ColorUtil.fade(color, covL));
            if (covR > 0.02) rect(innerR, py, 1, 1, ColorUtil.fade(color, covR));
        }
    }

    /** Pill (fully rounded ends) — switch tracks, progress bars. */
    public void pill(int x, int y, int w, int h, int color) {
        roundedRect(x, y, w, h, h / 2, color);
    }

    /** Soft drop shadow: stacked, expanding translucent rounded rects. */
    public void shadow(int x, int y, int w, int h, int radius, int color, int size) {
        int baseAlpha = ColorUtil.alpha(color);
        for (int i = size; i >= 1; i--) {
            double t = 1.0 - (double) i / (size + 1);
            int a = (int) Math.round(baseAlpha * t * t / size * 1.6);
            roundedRect(x - i, y - i + 1, w + i * 2, h + i * 2, radius + i, ColorUtil.withAlpha(color, a));
        }
    }

    private void row(int x, int y, int w, double inset, boolean left, boolean right, int color) {
        int full = (int) Math.ceil(inset);
        double coverage = full - inset; // fraction of the boundary pixel that is inside the shape
        int l = left ? full : 0, rr = right ? full : 0;
        rect(x + l, y, w - l - rr, 1, color);
        if (coverage > 0.02) {
            int edge = ColorUtil.fade(color, coverage);
            if (left && full > 0) rect(x + full - 1, y, 1, 1, edge);
            if (right && full > 0) rect(x + w - full, y, 1, 1, edge);
        }
    }

    /** Horizontal inset of pixel row {@code i} (0 = outermost) for a corner of radius {@code r}. */
    private static double cornerInset(int r, int i) {
        double dy = r - i - 0.5;
        return r - Math.sqrt(Math.max(0, r * r - dy * dy));
    }

    // ================================================================== text

    public void text(String s, int x, int y, int color) {
        if (s == null || s.isEmpty() || ColorUtil.alpha(c(color)) < 5) return;
        g.text(font, s, x, y, c(color), textShadow);
    }

    public void textCentered(String s, int centerX, int y, int color) {
        text(s, centerX - textWidth(s) / 2, y, color);
    }

    public void textRight(String s, int rightX, int y, int color) {
        text(s, rightX - textWidth(s), y, color);
    }

    public int textWidth(String s) {
        return s == null ? 0 : font.width(s);
    }

    public int fontHeight() {
        return font.lineHeight;
    }

    // ================================================================== styled text, glyphs, textures

    /** Draws server-styled runs in sequence. Runs with colour 0 use {@code defaultColor}; colourless runs keep their font. */
    public void runs(List<TextRun> runs, int x, int y, int defaultColor, boolean useRunColors) {
        if (runs == null || runs.isEmpty() || ColorUtil.alpha(c(defaultColor)) < 5) return;
        g.text(font, component(runs, useRunColors), x, y, c(defaultColor), textShadow);
    }

    public int runsWidth(List<TextRun> runs) {
        return runs == null || runs.isEmpty() ? 0 : font.width(component(runs, false));
    }

    /** Draws a resource-pack glyph (no shadow, untinted). */
    public void glyph(HudIcon.Glyph glyph, int x, int y) {
        glyph(glyph, x, y, 0xFFFFFFFF);
    }

    /** Draws a resource-pack glyph multiplied by {@code tint} (ARGB; white = untinted). */
    public void glyph(HudIcon.Glyph glyph, int x, int y, int tint) {
        if (ColorUtil.alpha(c(tint)) < 5) return;
        g.text(font, Component.literal(glyph.text()).withStyle(style(0, glyph.font())), x, y, c(tint), false);
    }

    public int glyphWidth(HudIcon.Glyph glyph) {
        return font.width(Component.literal(glyph.text()).withStyle(style(0, glyph.font())));
    }

    /**
     * Texture id for {@code path}: a full {@code "namespace:path"} id (e.g. a server-pack texture
     * {@code telos:textures/...}) or a bare path inside {@code assets/telemetric/}.
     */
    private Identifier textureId(String path) {
        return textures.computeIfAbsent(path, p -> {
            Identifier id = p.indexOf(':') >= 0 ? Identifier.tryParse(p) : null;
            return id != null ? id : Identifier.fromNamespaceAndPath(Telemetric.MOD_ID, p);
        });
    }

    /** Draws a whole texture ({@code "namespace:path"} or a path in {@code assets/telemetric/}), stretched to {@code w x h}. */
    public void texture(String path, int x, int y, int w, int h, int texW, int texH) {
        texture(path, x, y, w, h, texW, texH, 0xFFFFFFFF);
    }

    /** Like {@link #texture(String, int, int, int, int, int, int)}, multiplied by {@code tint} (ARGB). */
    public void texture(String path, int x, int y, int w, int h, int texW, int texH, int tint) {
        if (w <= 0 || h <= 0 || ColorUtil.alpha(c(tint)) < 5) return;
        Identifier id = textureId(path);
        g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, w, h, texW, texH, texW, texH, c(tint));
    }

    /** Draws an item (model + count/durability decorations) at 16x16 GUI units. */
    public void item(GameItem item, int x, int y) {
        if (item == null) return;
        g.item(item.stack(), x, y);
        g.itemDecorations(font, item.stack(), x, y);
    }

    /** Shows the item's normal (server-styled) tooltip at the mouse, drawn on top at the end of the frame. */
    public void itemTooltip(GameItem item, int mouseX, int mouseY) {
        if (item != null) g.setTooltipForNextFrame(font, item.stack(), mouseX, mouseY);
    }

    /**
     * Draws a queued tooltip right now, on top. Needed after the screen's own deferred pass already ran
     * (e.g. in {@code ScreenEvents.afterExtract}), where a queued tooltip would otherwise be dropped.
     */
    public void flushTooltip() {
        GuiGraphicsExtractorAccessor acc = (GuiGraphicsExtractorAccessor) g;
        Runnable tooltip = acc.telemetric$getDeferredTooltip();
        if (tooltip == null) return;
        acc.telemetric$setDeferredTooltip(null);
        g.nextStratum();
        tooltip.run();
    }

    /** Draws the region {@code (u, v, regionW, regionH)} of a texture, stretched to {@code w x h}, multiplied by {@code tint}. */
    public void texture(String path, int x, int y, int w, int h, int u, int v, int regionW, int regionH,
                        int texW, int texH, int tint) {
        if (w <= 0 || h <= 0 || ColorUtil.alpha(c(tint)) < 5) return;
        Identifier id = textureId(path);
        g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, u, v, w, h, regionW, regionH, texW, texH, c(tint));
    }

    private static Component component(List<TextRun> runs, boolean useRunColors) {
        MutableComponent out = Component.empty();
        for (TextRun run : runs) {
            out.append(Component.literal(run.text()).withStyle(style(useRunColors ? run.color() : 0, run.font())));
        }
        return out;
    }

    private static Style style(int argb, String fontId) {
        Style style = Style.EMPTY;
        if (argb != 0) style = style.withColor(argb & 0xFFFFFF);
        if (fontId != null) {
            Identifier id = Identifier.tryParse(fontId);
            if (id != null) style = style.withFont(new FontDescription.Resource(id));
        }
        return style;
    }

    /** Cuts {@code s} with an ellipsis so it fits {@code maxWidth}. */
    public String trim(String s, int maxWidth) {
        if (textWidth(s) <= maxWidth) return s;
        String ellipsis = "…";
        int budget = maxWidth - textWidth(ellipsis);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (textWidth(out.toString() + s.charAt(i)) > budget) break;
            out.append(s.charAt(i));
        }
        return out + ellipsis;
    }
}
