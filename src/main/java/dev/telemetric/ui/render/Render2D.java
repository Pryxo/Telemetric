package dev.telemetric.ui.render;

import dev.telemetric.Telemetric;
import dev.telemetric.game.GameItem;
import dev.telemetric.game.ItemIcons;
import dev.telemetric.game.NametagAdapter;
import dev.telemetric.hud.HudIcon;
import dev.telemetric.hud.TextRun;
import dev.telemetric.mixin.GuiGraphicsExtractorAccessor;
import dev.telemetric.setting.impl.ColorRamp;
import dev.telemetric.setting.impl.ColorSetting;
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
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The only class that draws. It wraps {@link GuiGraphicsExtractor} (26.1's renamed {@code GuiGraphics}) and
 * adds rounded shapes, anti-aliased corners, gradients, soft shadows and a global alpha stack (used to fade whole
 * subtrees in and out).
 * <p>
 * All coordinates are GUI units. Colours are ARGB, and the alpha matters: 0xRRGGBB is invisible.
 * <p>
 * Layering: in 26.1 the GUI is collected into strata, and inside one stratum text is drawn after all fills.
 * Call {@link #nextLayer()} before drawing anything that has to cover earlier text (overlapping panels, tooltips,
 * toasts).
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

    public void begin(GuiGraphicsExtractor graphics, boolean textShadow) {
        this.g = graphics;
        this.font = Minecraft.getInstance().font;
        this.alpha = 1f;
        this.alphaStack.clear();
        this.textShadow = textShadow;
        this.quadCount = 0;
        this.batchDepth = 0;
        this.autoRamps = List.of();
        clearTextStyle();
        drawPass++;
        ColorSetting.tickFrame();
    }

    // ---- automatic gradient text (HUD elements) ---------------------------------------------------------------------

    private List<ColorSetting> autoRamps = List.of();
    private int autoSpanX, autoSpanW = 1;

    /**
     * While set, plain text whose colour is exactly the current colour of one of {@code ramps} (a gradient setting's
     * {@link ColorSetting#get()}) is drawn as that gradient, spread over {@code spanX .. spanX + spanW}. HudModule sets
     * this around each element so every HUD text picks up its gradient settings without per-feature code.
     */
    public void setAutoRamps(List<ColorSetting> ramps, int spanX, int spanW) {
        autoRamps = ramps == null ? List.of() : ramps;
        autoSpanX = spanX;
        autoSpanW = Math.max(1, spanW);
    }

    public void clearAutoRamps() {
        autoRamps = List.of();
    }

    /** Bold / italic / underline / strikethrough applied to all text while set (a HUD element's Text Style). */
    private boolean bold, italic, underline, strike;

    public void setTextStyle(boolean bold, boolean italic, boolean underline, boolean strikethrough) {
        this.bold = bold;
        this.italic = italic;
        this.underline = underline;
        this.strike = strikethrough;
    }

    public void clearTextStyle() {
        bold = italic = underline = strike = false;
    }

    /** The current text style as bits (to restore it after a nested change). */
    public int textStyleBits() {
        return (bold ? 1 : 0) | (italic ? 2 : 0) | (underline ? 4 : 0) | (strike ? 8 : 0);
    }

    public void setTextStyleBits(int bits) {
        setTextStyle((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0);
    }

    private boolean styled() {
        return bold || italic || underline || strike;
    }

    private Style scopeStyle(Style st) {
        if (!styled()) return st;
        if (bold) st = st.withBold(true);
        if (italic) st = st.withItalic(true);
        if (underline) st = st.withUnderlined(true);
        if (strike) st = st.withStrikethrough(true);
        return st;
    }

    private ColorRamp autoRamp(int color) {
        if (autoRamps.isEmpty()) return null;
        for (ColorSetting cs : autoRamps) if (cs.isGradient() && cs.get() == color) return cs;
        return null;
    }

    private static long drawPass;

    /**
     * Changes on every {@link #begin} and {@link #end} (of any Render2D), so per-frame caches (e.g. HUD rows) keyed on
     * it are rebuilt once per draw pass and never reused outside one.
     */
    public static long drawPass() {
        return drawPass;
    }

    public void end() {
        this.g = null;
        drawPass++;
    }

    /** Multiplies the alpha of everything drawn until {@link #popAlpha()}. */
    public void pushAlpha(double factor) {
        alphaStack.push(alpha);
        alpha *= (float) Math.clamp(factor, 0, 1);
    }

    public void popAlpha() {
        alpha = alphaStack.isEmpty() ? 1f : alphaStack.pop();
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
        return Math.max(1, Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
    }

    /** Reference canvas for "Fit to Screen": 4K at GUI scale 3 (1280×720 GUI pixels). */
    public static final float REF_WIDTH = 1280f, REF_HEIGHT = 720f;

    /**
     * Factor that makes something sized for the reference canvas cover the same share of the current screen, whatever
     * the resolution or GUI scale (1 at 4K / GUI scale 3, 0.5 at 4K / GUI scale 6 …).
     */
    public static float screenFit() {
        var window = Minecraft.getInstance().getWindow();
        int w = window.getGuiScaledWidth(), h = window.getGuiScaledHeight();
        if (w <= 0 || h <= 0) return 1f;
        return Math.min(w / REF_WIDTH, h / REF_HEIGHT);
    }

    /**
     * The largest scale ≤ {@code fit} at which one GUI unit is a whole number of window pixels, so items and textures
     * render pixel-exact like vanilla (no atlas bleed or uneven texels). Returns {@code fit} itself if even one window
     * pixel per unit doesn't fit.
     */
    public static float pixelScale(float fit) {
        int gs = guiScale();
        int px = (int) Math.floor(fit * gs + 1e-4f);
        return px >= 1 ? (float) px / gs : fit;
    }

    /**
     * {@code scale} rounded to the nearest scale at which one GUI unit is a whole number of window pixels (at least one),
     * so the pixel font, 1 px lines and the layout grid land exactly on screen pixels instead of being resampled.
     */
    public static float nearestPixelScale(float scale) {
        int gs = guiScale();
        int px = Math.max(1, Math.round(scale * gs));
        return (float) px / gs;
    }

    /** {@code v} (GUI units) rounded down to a whole window pixel. */
    public static float snapToPixel(float v) {
        int gs = guiScale();
        return (float) Math.floor(v * gs) / gs;
    }

    public boolean isActive() {
        return g != null;
    }

    /** Clips to a rectangle (intersected with any outer clip). Has to be balanced with {@link #popClip()}. */
    public void pushClip(int x, int y, int w, int h) {
        g.enableScissor(x, y, x + Math.max(0, w), y + Math.max(0, h));
    }

    public void popClip() {
        g.disableScissor();
    }

    /** Starts a new render stratum, so everything after this covers everything before (text included). */
    public void nextLayer() {
        g.nextStratum();
    }

    private int c(int argb) {
        return alpha >= 0.999f ? argb : ColorUtil.fade(argb, alpha);
    }

    public void rect(int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0 || ColorUtil.alpha(color) == 0) return;
        int col = c(color);
        if (batchDepth > 0) quad(x, y, x + w, y + h, col, col, col, col);
        else g.fill(x, y, x + w, y + h, col);
    }

    public void outline(int x, int y, int w, int h, int color) {
        beginBatch();
        rect(x, y, w, 1, color);
        rect(x, y + h - 1, w, 1, color);
        rect(x, y + 1, 1, h - 2, color);
        rect(x + w - 1, y + 1, 1, h - 2, color);
        endBatch();
    }

    public void gradientV(int x, int y, int w, int h, int top, int bottom) {
        if (w <= 0 || h <= 0) return;
        g.fillGradient(x, y, x + w, y + h, c(top), c(bottom));
    }

    /** A horizontal gradient: one quad with left/right vertex colours. */
    public void gradientH(int x, int y, int w, int h, int left, int right) {
        if (w <= 0 || h <= 0) return;
        int l = c(left), r = c(right);
        beginBatch();
        quad(x, y, x + w, y + h, l, l, r, r);
        endBatch();
    }

    /** A rounded rectangle with anti-aliased corners. {@code corners} is a bitmask of CORNER_* flags. */
    public void roundedRect(int x, int y, int w, int h, int radius, int color, int corners) {
        if (w <= 0 || h <= 0) return;
        int r = Math.min(radius, Math.min(w, h) / 2);
        if (r <= 0 || corners == 0) {
            rect(x, y, w, h, color);
            return;
        }
        boolean top = (corners & CORNERS_TOP) != 0, bottom = (corners & CORNERS_BOTTOM) != 0;
        int topRows = top ? r : 0, bottomRows = bottom ? r : 0;
        beginBatch();
        rect(x, y + topRows, w, h - topRows - bottomRows, color);
        for (int i = 0; i < r; i++) {
            double inset = cornerInset(r, i);
            if (top) row(x, y + i, w, inset, (corners & CORNER_TL) != 0, (corners & CORNER_TR) != 0, color);
            if (bottom) row(x, y + h - 1 - i, w, inset, (corners & CORNER_BL) != 0, (corners & CORNER_BR) != 0, color);
        }
        endBatch();
    }

    public void roundedRect(int x, int y, int w, int h, int radius, int color) {
        roundedRect(x, y, w, h, radius, color, CORNERS_ALL);
    }

    /** A filled, anti-aliased circle centred on (cx, cy). */
    public void circle(double cx, double cy, double radius, int color) {
        circle(cx, cy, radius, color, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /** A filled circle, but only its pixel rows from {@code minY} (inclusive) to {@code maxY} (exclusive), e.g. a ripple inside a row. */
    public void circle(double cx, double cy, double radius, int color, int minY, int maxY) {
        int top = Math.max(minY, (int) Math.floor(cy - radius)), bottom = Math.min(maxY, (int) Math.ceil(cy + radius));
        beginBatch();
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
        endBatch();
    }

    /** A pill (fully rounded ends), for switch tracks and progress bars. */
    public void pill(int x, int y, int w, int h, int color) {
        roundedRect(x, y, w, h, h / 2, color);
    }

    /**
     * A rounded rect filled with a horizontal multi-stop gradient: {@code colorAt} gives the colour at any x, sampled
     * every {@code step} units (each step is one linear gradient quad). Corners are anti-aliased like
     * {@link #roundedRect}.
     */
    public void fillH(int x, int y, int w, int h, int radius, int corners, java.util.function.IntUnaryOperator colorAt, int step) {
        if (w <= 0 || h <= 0) return;
        int rad = corners == 0 ? 0 : Math.min(radius, Math.min(w, h) / 2);
        boolean top = (corners & CORNERS_TOP) != 0, bottom = (corners & CORNERS_BOTTOM) != 0;
        int topRows = top && rad > 0 ? rad : 0, bottomRows = bottom && rad > 0 ? rad : 0;
        int st = Math.max(1, step);
        beginBatch();
        stripH(x, x + w, y + topRows, h - topRows - bottomRows, colorAt, st);
        for (int i = 0; i < rad; i++) {
            double inset = cornerInset(rad, i);
            if (top) gradientRow(x, y + i, w, inset, (corners & CORNER_TL) != 0, (corners & CORNER_TR) != 0, colorAt, st);
            if (bottom) gradientRow(x, y + h - 1 - i, w, inset, (corners & CORNER_BL) != 0, (corners & CORNER_BR) != 0, colorAt, st);
        }
        endBatch();
    }

    private void stripH(int x0, int x1, int y, int h, java.util.function.IntUnaryOperator colorAt, int step) {
        if (h <= 0 || x1 <= x0) return;
        for (int sx = x0; sx < x1; sx += step) {
            int ex = Math.min(x1, sx + step);
            int l = c(colorAt.applyAsInt(sx)), rr = c(colorAt.applyAsInt(ex));
            if (ColorUtil.alpha(l) == 0 && ColorUtil.alpha(rr) == 0) continue;
            quad(sx, y, ex, y + h, l, l, rr, rr);
        }
    }

    private void gradientRow(int x, int y, int w, double inset, boolean left, boolean right,
                             java.util.function.IntUnaryOperator colorAt, int step) {
        int full = (int) Math.ceil(inset);
        double coverage = full - inset;
        int l = left ? full : 0, rr = right ? full : 0;
        stripH(x + l, x + w - rr, y, 1, colorAt, step);
        if (coverage > 0.02 && full > 0) {
            if (left) rect(x + full - 1, y, 1, 1, ColorUtil.fade(colorAt.applyAsInt(x + full - 1), coverage));
            if (right) rect(x + w - full, y, 1, 1, ColorUtil.fade(colorAt.applyAsInt(x + w - full), coverage));
        }
    }

    /**
     * A soft glow of {@code color} all around a rounded rect ({@code size} units wide), e.g. behind an enabled switch.
     * Unlike {@link #shadow} it isn't offset downwards. {@code color}'s alpha is the strength next to the edge.
     */
    public void glow(int x, int y, int w, int h, int radius, int color, int size) {
        int baseAlpha = ColorUtil.alpha(color);
        if (size <= 0 || baseAlpha < 3) return;
        beginBatch();
        for (int i = size; i >= 1; i--) {
            double t = 1.0 - (double) (i - 1) / size;
            int a = (int) Math.round(baseAlpha * t * t / size * 1.8);
            if (a > 0) roundedRect(x - i, y - i, w + i * 2, h + i * 2, radius + i, ColorUtil.withAlpha(color, a));
        }
        endBatch();
    }

    /** A soft drop shadow made of stacked, expanding, translucent rounded rects. */
    public void shadow(int x, int y, int w, int h, int radius, int color, int size) {
        int baseAlpha = ColorUtil.alpha(color);
        beginBatch();
        for (int i = size; i >= 1; i--) {
            double t = 1.0 - (double) i / (size + 1);
            int a = (int) Math.round(baseAlpha * t * t / size * 1.6);
            roundedRect(x - i, y - i + 1, w + i * 2, h + i * 2, radius + i, ColorUtil.withAlpha(color, a));
        }
        endBatch();
    }

    private void row(int x, int y, int w, double inset, boolean left, boolean right, int color) {
        int full = (int) Math.ceil(inset);
        double coverage = full - inset; // the fraction of the boundary pixel that's inside the shape
        int l = left ? full : 0, rr = right ? full : 0;
        rect(x + l, y, w - l - rr, 1, color);
        if (coverage > 0.02) {
            int edge = ColorUtil.fade(color, coverage);
            if (left && full > 0) rect(x + full - 1, y, 1, 1, edge);
            if (right && full > 0) rect(x + w - full, y, 1, 1, edge);
        }
    }

    /** The horizontal inset of pixel row {@code i} (0 is the outermost) for a corner of radius {@code r}. */
    private static double cornerInset(int r, int i) {
        double dy = r - i - 0.5;
        return r - Math.sqrt(Math.max(0, r * r - dy * dy));
    }

    /*
     * Every GUI element costs a search through the elements already on screen (GuiRenderState.hasIntersection), so
     * a shape made of many 1-pixel fills (gradients, anti-aliased corners, circles) gets slow fast. Between
     * beginBatch/endBatch, rect() collects quads here and endBatch() adds them as ONE element, drawn in order.
     */
    private int[] quads = new int[64 * 8];
    private int quadCount, batchDepth;

    private void beginBatch() {
        batchDepth++;
    }

    private void endBatch() {
        if (--batchDepth > 0) return;
        batchDepth = 0;
        if (quadCount == 0) return;
        int[] data = java.util.Arrays.copyOf(quads, quadCount * 8);
        quadCount = 0;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (int i = 0; i < data.length; i += 8) {
            minX = Math.min(minX, data[i]);
            minY = Math.min(minY, data[i + 1]);
            maxX = Math.max(maxX, data[i + 2]);
            maxY = Math.max(maxY, data[i + 3]);
        }
        org.joml.Matrix3x2f pose = new org.joml.Matrix3x2f(g.pose());
        var bounds = new net.minecraft.client.gui.navigation.ScreenRectangle(minX, minY, maxX - minX, maxY - minY)
                .transformMaxBounds(pose);
        var scissor = g.scissorStack.peek();
        if (scissor != null) {
            bounds = scissor.intersection(bounds);
            if (bounds == null) return;
        }
        g.guiRenderState.addGuiElement(new ShapeState(pose, data, scissor, bounds));
    }

    /** One quad with a colour per corner: (x0,y0), (x0,y1), (x1,y1), (x1,y0). Colours already faded by {@link #c}. */
    private void quad(int x0, int y0, int x1, int y1, int c00, int c01, int c11, int c10) {
        if (batchDepth == 0) {
            beginBatch();
            quad(x0, y0, x1, y1, c00, c01, c11, c10);
            endBatch();
            return;
        }
        if ((quadCount + 1) * 8 > quads.length) quads = java.util.Arrays.copyOf(quads, quads.length * 2);
        int i = quadCount++ * 8;
        quads[i] = x0;
        quads[i + 1] = y0;
        quads[i + 2] = x1;
        quads[i + 3] = y1;
        quads[i + 4] = c00;
        quads[i + 5] = c01;
        quads[i + 6] = c11;
        quads[i + 7] = c10;
    }

    /** The GUI element behind a batched shape: untextured quads in the GUI pipeline (same as vanilla fills). */
    private record ShapeState(org.joml.Matrix3x2f pose, int[] data,
                              net.minecraft.client.gui.navigation.ScreenRectangle scissorArea,
                              net.minecraft.client.gui.navigation.ScreenRectangle bounds)
            implements net.minecraft.client.renderer.state.gui.GuiElementRenderState {
        @Override
        public void buildVertices(com.mojang.blaze3d.vertex.VertexConsumer vc) {
            for (int i = 0; i < data.length; i += 8) {
                float x0 = data[i], y0 = data[i + 1], x1 = data[i + 2], y1 = data[i + 3];
                vc.addVertexWith2DPose(pose, x0, y0).setColor(data[i + 4]);
                vc.addVertexWith2DPose(pose, x0, y1).setColor(data[i + 5]);
                vc.addVertexWith2DPose(pose, x1, y1).setColor(data[i + 6]);
                vc.addVertexWith2DPose(pose, x1, y0).setColor(data[i + 7]);
            }
        }

        @Override
        public com.mojang.blaze3d.pipeline.RenderPipeline pipeline() {
            return RenderPipelines.GUI;
        }

        @Override
        public net.minecraft.client.gui.render.TextureSetup textureSetup() {
            return net.minecraft.client.gui.render.TextureSetup.noTexture();
        }
    }

    public void text(String s, int x, int y, int color) {
        text(s, x, y, color, null);
    }

    /** A text look picked by a feature instead of the theme's shadow setting. */
    public enum TextLook { NONE, SHADOW, OUTLINE }

    /** Text with an explicit look: plain, a drop shadow, or a 1 px black outline ({@code null}: the theme's shadow). */
    public void text(String s, int x, int y, int color, TextLook look) {
        if (s == null || s.isEmpty() || ColorUtil.alpha(c(color)) < 5) return;
        ColorRamp auto = autoRamp(color);
        if (auto != null) {
            text(s, x, y, auto, look, autoSpanX, autoSpanW);
            return;
        }
        int col = c(color);
        boolean shadow = look == null ? textShadow : look == TextLook.SHADOW;
        if (!styled()) {
            if (look == TextLook.OUTLINE) outline(s, x, y, col);
            g.text(font, s, x, y, col, shadow);
            return;
        }
        Component comp = Component.literal(s).withStyle(scopeStyle(Style.EMPTY));
        if (look == TextLook.OUTLINE) outline(comp, x, y, col);
        g.text(font, comp, x, y, col, shadow);
    }

    private void outline(String s, int x, int y, int col) {
        outline(Component.literal(s).withStyle(scopeStyle(Style.EMPTY)), x, y, col);
    }

    /** All 8 neighbours: with only the 4 straight ones, diagonal glyph corners were left without black. */
    private void outline(Component comp, int x, int y, int col) {
        int outline = col & 0xFF000000;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx != 0 || dy != 0) g.text(font, comp, x + dx, y + dy, outline, false);
            }
        }
    }

    /** Text in a colour ramp spread over the text's own width. */
    public void text(String s, int x, int y, ColorRamp ramp) {
        text(s, x, y, ramp, null, x, textWidth(s));
    }

    /**
     * Text coloured by {@code ramp}, where the ramp runs from {@code spanX} over {@code spanW} units: give a whole HUD
     * element's span so its lines share one gradient. The gradient is continuous (it runs through each glyph too, not
     * one colour per letter). {@code look == null} uses the theme's shadow setting. A uniform ramp draws as plain text.
     */
    public void text(String s, int x, int y, ColorRamp ramp, TextLook look, int spanX, int spanW) {
        if (s == null || s.isEmpty()) return;
        if (ramp.isUniform()) {
            text(s, x, y, ramp.at(0), look);
            return;
        }
        int base = c(ramp.at(0.5f));
        if (ColorUtil.alpha(base) < 5) return;
        Component whole = Component.literal(s).withStyle(scopeStyle(Style.EMPTY));
        if (look == TextLook.OUTLINE) outline(whole, x, y, base);
        boolean shadow = look == null ? textShadow : look == TextLook.SHADOW;
        if (shadow) g.text(font, whole, x + 1, y + 1, shadowOf(base), false);
        gradientGlyphs(s, scopeStyle(Style.EMPTY), x, y, ramp, spanX, spanW);
    }

    /**
     * Draws {@code s} glyph by glyph, each glyph several times through thin vertical clips with the ramp's colour at
     * that column, so the colour changes smoothly across (and inside) the letters. Returns the end x.
     */
    private int gradientGlyphs(String s, Style style, int x, int y, ColorRamp ramp, int spanX, int spanW) {
        float span = Math.max(1, spanW);
        int top = y - 1, h = font.lineHeight + 2;
        int cx = x;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            Component glyph = Component.literal(new String(Character.toChars(cp))).withStyle(style);
            int gw = font.width(glyph);
            if (cp != ' ' && gw > 0) {
                // a little past the advance on both sides: italic / bold glyphs overhang it
                int gx = cx;
                sliced(cx - 1, cx + gw + 2, top, top + h, ramp, spanX, span,
                        col -> g.text(font, glyph, gx, y, c(col), false));
            }
            cx += gw;
        }
        return cx;
    }

    /**
     * Calls {@code draw} once per screen column (whole GUI pixels after the current transform, so scaled previews get
     * no gaps or doubled columns) of the local box, clipped to that column, with the ramp colour at its centre.
     * Assumes a transform without rotation (translate / scale only).
     */
    private void sliced(float lx0, float lx1, float ly0, float ly1, ColorRamp ramp, float spanX, float span,
                        java.util.function.IntConsumer draw) {
        org.joml.Matrix3x2f m = new org.joml.Matrix3x2f(g.pose());
        org.joml.Vector2f a = m.transformPosition(lx0, ly0, new org.joml.Vector2f());
        org.joml.Vector2f b = m.transformPosition(lx1, ly1, new org.joml.Vector2f());
        int sx0 = (int) Math.floor(Math.min(a.x, b.x)), sx1 = (int) Math.ceil(Math.max(a.x, b.x));
        int sy0 = (int) Math.floor(Math.min(a.y, b.y)), sy1 = (int) Math.ceil(Math.max(a.y, b.y));
        if (m.m00 == 0) return;
        for (int sx = sx0; sx < sx1; sx++) {
            float local = (sx + 0.5f - m.m20) / m.m00;
            int col = ramp.at(Math.clamp((local - spanX) / span, 0f, 1f));
            // the scissor box is given in screen units: set it with no transform, then draw with the real one
            g.pose().pushMatrix();
            g.pose().identity();
            g.enableScissor(sx, sy0, sx + 1, sy1);
            g.pose().popMatrix();
            draw.accept(col);
            g.disableScissor();
        }
    }

    /** Vanilla's drop-shadow colour (RGB at 25 %). */
    private static int shadowOf(int argb) {
        return (argb & 0xFF000000) | ((argb >> 2) & 0x3F3F3F);
    }

    /**
     * A rounded rect filled with a colour ramp running left to right (a gradient setting as a horizontal gradient),
     * faded by {@code opacity}. A uniform ramp is one plain rounded rect.
     */
    public void fillRamp(int x, int y, int w, int h, int radius, int corners, ColorRamp ramp, double opacity) {
        if (w <= 0 || h <= 0) return;
        if (ramp.isUniform()) {
            roundedRect(x, y, w, h, radius, ColorUtil.fade(ramp.at(0), opacity), corners);
            return;
        }
        float span = Math.max(1, w);
        fillH(x, y, w, h, radius, corners, px -> ColorUtil.fade(ramp.at(Math.clamp((px - x) / span, 0f, 1f)), opacity), 2);
    }

    /** A plain rect filled with a colour ramp running top to bottom. */
    public void fillRampV(int x, int y, int w, int h, ColorRamp ramp) {
        if (w <= 0 || h <= 0) return;
        if (ramp.isUniform()) {
            rect(x, y, w, h, ramp.at(0));
            return;
        }
        int step = 2;
        for (int sy = 0; sy < h; sy += step) {
            int eh = Math.min(step, h - sy);
            gradientV(x, y + sy, w, eh, ramp.at(sy / (float) h), ramp.at((sy + eh) / (float) h));
        }
    }

    public void textCentered(String s, int centerX, int y, int color) {
        text(s, centerX - textWidth(s) / 2, y, color);
    }

    public void textRight(String s, int rightX, int y, int color) {
        text(s, rightX - textWidth(s), y, color);
    }

    public int textWidth(String s) {
        if (s == null) return 0;
        return styled() ? font.width(Component.literal(s).withStyle(scopeStyle(Style.EMPTY))) : font.width(s);
    }

    public int fontHeight() {
        return font.lineHeight;
    }

    /** Draws server-styled runs one after another. Runs with colour 0 use {@code defaultColor}, and colourless runs keep their font. */
    public void runs(List<TextRun> runs, int x, int y, int defaultColor, boolean useRunColors) {
        if (runs == null || runs.isEmpty() || ColorUtil.alpha(c(defaultColor)) < 5) return;
        boolean anyRamp = false;
        if (!autoRamps.isEmpty()) {
            for (TextRun run : runs) anyRamp |= autoRamp(useRunColors && run.color() != 0 ? run.color() : defaultColor) != null;
        }
        if (!anyRamp) {
            g.text(font, component(runs, useRunColors), x, y, c(defaultColor), textShadow);
            return;
        }
        // some runs are in a gradient colour: draw run by run, those as smooth gradients
        int cx = x;
        for (TextRun run : runs) {
            int col = useRunColors && run.color() != 0 ? run.color() : defaultColor;
            ColorRamp ramp = autoRamp(col);
            Style st = scopeStyle(style(useRunColors ? run.color() : 0, run.font()));
            Component part = Component.literal(run.text()).withStyle(st);
            if (ramp == null) {
                g.text(font, part, cx, y, c(defaultColor), textShadow);
                cx += font.width(part);
                continue;
            }
            int base = c(ramp.at(0.5f));
            if (textShadow) g.text(font, part, cx + 1, y + 1, shadowOf(base), false);
            cx = gradientGlyphs(run.text(), st.withColor((net.minecraft.network.chat.TextColor) null), cx, y, ramp, autoSpanX, autoSpanW);
        }
    }

    public int runsWidth(List<TextRun> runs) {
        return runs == null || runs.isEmpty() ? 0 : font.width(component(runs, false));
    }

    /** Draws a resource-pack glyph (no shadow, no tint). */
    public void glyph(HudIcon.Glyph glyph, int x, int y) {
        glyph(glyph, x, y, 0xFFFFFFFF);
    }

    /** Draws a resource-pack glyph multiplied by {@code tint} (ARGB; white means no tint). */
    public void glyph(HudIcon.Glyph glyph, int x, int y, int tint) {
        if (ColorUtil.alpha(c(tint)) < 5) return;
        g.text(font, Component.literal(glyph.text()).withStyle(style(0, glyph.font())), x, y, c(tint), false);
    }

    /** A Status Title line the way it looks in a name tag (animated gradient, wave, icon), with no shadow, like a text display. */
    public void statusTitle(NametagAdapter.TitleLine line, int x, int y) {
        g.text(font, NametagAdapter.titleComponent(line), x, y, c(0xFFFFFFFF), false);
    }

    public int statusTitleWidth(NametagAdapter.TitleLine line) {
        return font.width(NametagAdapter.titleComponent(line));
    }

    /** One line of a name tag preview ({@link NametagAdapter#previewLines}), white base colour, no shadow. */
    public void nametagLine(NametagAdapter.PreviewLine line, int x, int y) {
        g.text(font, line.text(), x, y, c(0xFFFFFFFF), false);
    }

    public int nametagLineWidth(NametagAdapter.PreviewLine line) {
        return font.width(line.text());
    }

    /**
     * Your player model in a box, turned by {@code yaw} / {@code pitch} (radians), for the Settings window's 3D
     * preview ({@link dev.telemetric.game.EntityPreviewAdapter}). Returns false without a player.
     */
    public boolean playerModel(int x, int y, int w, int h, float pxPerBlock, float shiftX, float lift, float yaw, float pitch) {
        return dev.telemetric.game.EntityPreviewAdapter.drawLocalPlayer(g, x, y, x + w, y + h, pxPerBlock, shiftX, lift, yaw, pitch);
    }

    /** True if the 3D preview can draw one depth-tested scene ({@link #previewScene}); else it uses the flat path. */
    public static boolean sceneAvailable() {
        return dev.telemetric.game.ScenePreviewAdapter.available();
    }

    /**
     * The 3D preview's blocks, models and shapes as one depth-tested picture
     * ({@link dev.telemetric.game.ScenePreviewAdapter#draw}). Returns false outside a world.
     */
    public boolean previewScene(int x, int y, int w, int h, float pxPerBlock, float shiftX, float lift, float yaw,
                                float pitch, dev.telemetric.game.ScenePreviewAdapter.Request request) {
        return dev.telemetric.game.ScenePreviewAdapter.draw(g, x, y, x + w, y + h, pxPerBlock, shiftX, lift, yaw,
                pitch, request);
    }

    /**
     * A world label ({@link dev.telemetric.game.WorldLabelAdapter.Label}) drawn flat at (x, y) in its own pixels
     * (badges are 8 px tall), the way the world draws it: badge font or plain text, its colour and shadow.
     */
    public void worldLabel(dev.telemetric.game.WorldLabelAdapter.Label label, int x, int y) {
        int argb = (label.argb() >>> 24) == 0 ? label.argb() | 0xFF000000 : label.argb();
        Component text = dev.telemetric.game.WorldLabelAdapter.text(label);
        ColorRamp ramp = label.textRamp();
        if (ramp == null) {
            g.text(font, text, x, y, c(argb), label.shadow());
            return;
        }
        // a gradient label (Settings window preview): the same thin clipped slices as gradient HUD text
        int w = Math.max(1, font.width(text));
        if (label.shadow()) g.text(font, text, x + 1, y + 1, shadowOf(c(ramp.at(0.5f))), false);
        sliced(x - 1, x + w + 2, y - 1, y + font.lineHeight + 1, ramp, x, w,
                col -> g.text(font, text, x, y, c(col), false));
    }

    public int worldLabelWidth(dev.telemetric.game.WorldLabelAdapter.Label label) {
        return font.width(dev.telemetric.game.WorldLabelAdapter.text(label));
    }

    /**
     * Block faces for the 3D previews ({@link dev.telemetric.game.WorldPreviewAdapter}): {@code count} quads from the
     * block atlas, 8 floats of GUI {x, y} and 8 of atlas {u, v} per quad, one ARGB per quad. They're one GUI element,
     * so they're drawn in exactly this order (painter's order: far first) and clipped to the current clip.
     */
    public void blockFaces(float[] xy, float[] uv, int[] argb, int count) {
        if (count <= 0) return;
        org.joml.Matrix3x2f pose = new org.joml.Matrix3x2f(g.pose());
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        org.joml.Vector2f v = new org.joml.Vector2f();
        for (int i = 0; i < count * 4; i++) {
            pose.transformPosition(xy[i * 2], xy[i * 2 + 1], v);
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            maxX = Math.max(maxX, v.x);
            maxY = Math.max(maxY, v.y);
        }
        var bounds = new net.minecraft.client.gui.navigation.ScreenRectangle((int) Math.floor(minX), (int) Math.floor(minY),
                Math.max(1, (int) Math.ceil(maxX - minX) + 1), Math.max(1, (int) Math.ceil(maxY - minY) + 1));
        var scissor = g.scissorStack.peek();
        if (scissor != null) {
            bounds = scissor.intersection(bounds);
            if (bounds == null) return;
        }
        int[] colors = new int[count];
        for (int i = 0; i < count; i++) colors[i] = c(argb[i]);
        var texture = Minecraft.getInstance().getTextureManager().getTexture(dev.telemetric.game.WorldPreviewAdapter.atlas());
        g.guiRenderState.addGuiElement(new FacesState(net.minecraft.client.gui.render.TextureSetup.singleTexture(
                texture.getTextureView(), texture.getSampler()), pose, xy, uv, colors, count, scissor, bounds));
    }

    /** The GUI element behind {@link #blockFaces}: textured quads in the GUI_TEXTURED pipeline. */
    private record FacesState(net.minecraft.client.gui.render.TextureSetup textureSetup, org.joml.Matrix3x2f pose,
                              float[] xy, float[] uv, int[] colors, int count,
                              net.minecraft.client.gui.navigation.ScreenRectangle scissorArea,
                              net.minecraft.client.gui.navigation.ScreenRectangle bounds)
            implements net.minecraft.client.renderer.state.gui.GuiElementRenderState {
        @Override
        public void buildVertices(com.mojang.blaze3d.vertex.VertexConsumer vc) {
            for (int q = 0; q < count; q++) {
                for (int k = 0; k < 4; k++) {
                    int i = q * 4 + k;
                    vc.addVertexWith2DPose(pose, xy[i * 2], xy[i * 2 + 1]).setUv(uv[i * 2], uv[i * 2 + 1]).setColor(colors[q]);
                }
            }
        }

        @Override
        public com.mojang.blaze3d.pipeline.RenderPipeline pipeline() {
            return RenderPipelines.GUI_TEXTURED;
        }
    }

    /**
     * A filled convex polygon of {@code n} corners ({@code xy}: x0, y0, x1, y1, …) in float GUI units, so it can sit
     * between pixels, be rotated and keep its exact shape (map arrows, minimap markers).
     */
    public void polygon(float[] xy, int n, int color) {
        if (n < 3 || ColorUtil.alpha(c(color)) == 0) return;
        addPolygon(net.minecraft.client.gui.render.TextureSetup.noTexture(), RenderPipelines.GUI, xy, null, n, c(color));
    }

    /**
     * A convex polygon filled with a texture ({@code "namespace:path"} or a path in {@code assets/telemetric/}):
     * {@code uv} holds a normalised (0..1) u, v per corner. Float GUI units like {@link #polygon}, so a map can be
     * panned, zoomed and rotated smoothly and clipped to any convex shape (a round minimap).
     */
    public void texturedPolygon(String path, float[] xy, float[] uv, int n, int tint) {
        if (n < 3 || ColorUtil.alpha(c(tint)) < 5) return;
        var texture = Minecraft.getInstance().getTextureManager().getTexture(textureId(path));
        addPolygon(net.minecraft.client.gui.render.TextureSetup.singleTexture(texture.getTextureView(), texture.getSampler()),
                RenderPipelines.GUI_TEXTURED, xy, uv, n, c(tint));
    }

    private void addPolygon(net.minecraft.client.gui.render.TextureSetup setup, com.mojang.blaze3d.pipeline.RenderPipeline pipeline,
                            float[] xy, float[] uv, int n, int color) {
        org.joml.Matrix3x2f pose = new org.joml.Matrix3x2f(g.pose());
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        org.joml.Vector2f v = new org.joml.Vector2f();
        for (int i = 0; i < n; i++) {
            pose.transformPosition(xy[i * 2], xy[i * 2 + 1], v);
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            maxX = Math.max(maxX, v.x);
            maxY = Math.max(maxY, v.y);
        }
        var bounds = new net.minecraft.client.gui.navigation.ScreenRectangle((int) Math.floor(minX), (int) Math.floor(minY),
                Math.max(1, (int) Math.ceil(maxX - minX) + 1), Math.max(1, (int) Math.ceil(maxY - minY) + 1));
        var scissor = g.scissorStack.peek();
        if (scissor != null) {
            bounds = scissor.intersection(bounds);
            if (bounds == null) return;
        }
        // same winding as vanilla fills (counter-clockwise on screen), so back-face culling can never drop it
        double area = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            area += xy[i * 2] * xy[j * 2 + 1] - xy[j * 2] * xy[i * 2 + 1];
        }
        float[] pxy = new float[n * 2], puv = uv == null ? null : new float[n * 2];
        for (int i = 0; i < n; i++) {
            int src = area > 0 ? n - 1 - i : i;
            pxy[i * 2] = xy[src * 2];
            pxy[i * 2 + 1] = xy[src * 2 + 1];
            if (puv != null) {
                puv[i * 2] = uv[src * 2];
                puv[i * 2 + 1] = uv[src * 2 + 1];
            }
        }
        g.guiRenderState.addGuiElement(new PolygonState(pipeline, setup, pose, pxy, puv, n, color, scissor, bounds));
    }

    /** The GUI element behind {@link #polygon} / {@link #texturedPolygon}: a triangle fan sent as quads. */
    private record PolygonState(com.mojang.blaze3d.pipeline.RenderPipeline pipeline,
                                net.minecraft.client.gui.render.TextureSetup textureSetup, org.joml.Matrix3x2f pose,
                                float[] xy, float[] uv, int n, int color,
                                net.minecraft.client.gui.navigation.ScreenRectangle scissorArea,
                                net.minecraft.client.gui.navigation.ScreenRectangle bounds)
            implements net.minecraft.client.renderer.state.gui.GuiElementRenderState {
        @Override
        public void buildVertices(com.mojang.blaze3d.vertex.VertexConsumer vc) {
            for (int i = 1; i + 1 < n; i++) {
                vertex(vc, 0);
                vertex(vc, i);
                vertex(vc, i + 1);
                vertex(vc, i + 1);
            }
        }

        private void vertex(com.mojang.blaze3d.vertex.VertexConsumer vc, int i) {
            var out = vc.addVertexWith2DPose(pose, xy[i * 2], xy[i * 2 + 1]);
            if (uv != null) out = out.setUv(uv[i * 2], uv[i * 2 + 1]);
            out.setColor(color);
        }
    }

    public int glyphWidth(HudIcon.Glyph glyph) {
        return font.width(Component.literal(glyph.text()).withStyle(style(0, glyph.font())));
    }

    /**
     * The texture id for {@code path}: either a full {@code "namespace:path"} id (e.g. a server-pack texture
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

    /** Like {@link #texture(String, int, int, int, int, int, int)}, but multiplied by {@code tint} (ARGB). */
    public void texture(String path, int x, int y, int w, int h, int texW, int texH, int tint) {
        if (w <= 0 || h <= 0 || ColorUtil.alpha(c(tint)) < 5) return;
        Identifier id = textureId(path);
        g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, w, h, texW, texH, texW, texH, c(tint));
    }

    /** Smallest / largest pre-rasterized Lucide icon size in window pixels (see {@code textures/gui/icons/}). */
    private static final int ICON_MIN = 8, ICON_MAX = 48, ICON_STRIP_W = (ICON_MAX * (ICON_MAX + 1) - ICON_MIN * (ICON_MIN - 1)) / 2;

    /**
     * Draws a Lucide icon ({@code textures/gui/icons/<name>.png}) centred on (cx, cy), {@code size} GUI units wide,
     * rotated by {@code rotation} radians and tinted. Each icon file is a strip of the icon rasterized at every pixel
     * size from 8 to 48; the one matching the real on-screen size is drawn 1:1 so the strokes stay crisp.
     */
    public void icon(String name, double cx, double cy, double size, int tint, double rotation) {
        if (size <= 0 || ColorUtil.alpha(c(tint)) < 5) return;
        var m = g.pose();
        double poseScale = Math.hypot(m.m00(), m.m01());
        int px = (int) Math.round(size * poseScale * guiScale());
        px = Math.max(ICON_MIN, Math.min(ICON_MAX, px));
        int u = (px * (px - 1) - ICON_MIN * (ICON_MIN - 1)) / 2;
        float unit = (float) (size / px);
        g.pose().pushMatrix();
        g.pose().translate((float) cx, (float) cy);
        if (rotation != 0) g.pose().rotate((float) rotation);
        g.pose().scale(unit, unit);
        int half = px / 2;
        g.blit(RenderPipelines.GUI_TEXTURED, textureId("textures/gui/icons/" + name + ".png"), -half, -half, u, 0,
                px, px, px, px, ICON_STRIP_W, ICON_MAX, c(tint));
        g.pose().popMatrix();
    }

    public void icon(String name, double cx, double cy, double size, int tint) {
        icon(name, cx, cy, size, tint, 0);
    }

    /** Draws an item (model plus count/durability decorations) at 16x16 GUI units. */
    public void item(GameItem item, int x, int y) {
        if (item == null) return;
        g.item(item.stack(), x, y);
        g.itemDecorations(font, item.stack(), x, y);
    }

    /** The Item Rarity module's shape behind an item, exactly as in inventory slots (nothing when that module is off). */
    public void itemRarity(GameItem item, int x, int y) {
        if (item != null) dev.telemetric.game.ItemRarityAdapter.drawSlot(g, item.stack(), x, y, false);
    }

    /** {@link #itemRarity(GameItem, int, int)}, an item with no rarity drawn in {@code fallbackRgb} (0xRRGGBB, -1 = none). */
    public void itemRarity(GameItem item, int x, int y, int fallbackRgb) {
        if (item != null) dev.telemetric.game.ItemRarityAdapter.drawSlot(g, item.stack(), x, y, false, fallbackRgb);
    }

    /** Draws only the item model (no count, durability or vanilla cooldown overlay) at 16x16 GUI units. */
    public void itemIcon(GameItem item, int x, int y) {
        if (item != null) g.item(item.stack(), x, y);
    }

    /** Draws the item with this item model id ({@code telos:material/...}), scaled to {@code size} x {@code size}. */
    public void itemModel(String model, int x, int y, int size) {
        itemModel(model, (float) x, (float) y, (float) size);
    }

    /** {@link #itemModel(String, int, int, int)} at a sub-pixel position (for markers on a smoothly moving map). */
    public void itemModel(String model, float x, float y, float size) {
        GameItem item = ItemIcons.of(model);
        if (item == null || size <= 0) return;
        pushTransform(x, y, size / 16f);
        g.item(item.stack(), 0, 0);
        popTransform();
    }

    /** Shows the item's normal (server-styled) tooltip at the mouse, drawn on top at the end of the frame. */
    public void itemTooltip(GameItem item, int mouseX, int mouseY) {
        if (item != null) g.setTooltipForNextFrame(font, item.stack(), mouseX, mouseY);
    }

    /**
     * Draws a queued tooltip right now, on top. This is needed after the screen's own deferred pass has already run
     * (e.g. in {@code ScreenEvents.afterExtract}), where a queued tooltip would otherwise get dropped.
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

    private Component component(List<TextRun> runs, boolean useRunColors) {
        MutableComponent out = Component.empty();
        for (TextRun run : runs) {
            out.append(Component.literal(run.text()).withStyle(scopeStyle(style(useRunColors ? run.color() : 0, run.font()))));
        }
        return out;
    }

    private static Style style(int argb, String fontId) {
        Style style = Style.EMPTY;
        if (argb != 0) style = style.withColor(argb & 0xFFFFFF);
        if (fontId != null) {
            FontDescription font = FONTS.computeIfAbsent(fontId, f -> {
                Identifier id = Identifier.tryParse(f);
                return id == null ? NO_FONT : new FontDescription.Resource(id);
            });
            if (font != NO_FONT) style = style.withFont(font);
        }
        return style;
    }

    /** Parsed font ids of text runs (a handful of server fonts), so a HUD frame doesn't re-parse them per run. */
    private static final Map<String, FontDescription> FONTS = new java.util.concurrent.ConcurrentHashMap<>();
    /** Marks an unparsable font id in {@link #FONTS} (the map takes no nulls). */
    private static final FontDescription NO_FONT = FontDescription.DEFAULT;

    /** Word-wraps {@code text} to lines no wider than {@code maxWidth}. A {@code \n} starts a new line; a blank line stays. */
    public List<String> wrap(String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                if (!line.isEmpty() && textWidth(line + " " + word) > maxWidth) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                if (!line.isEmpty()) line.append(' ');
                line.append(word);
            }
            out.add(para.isBlank() ? "" : line.toString());
        }
        return out;
    }

    /** Cuts {@code s} off with an ellipsis so it fits {@code maxWidth}. */
    public String trim(String s, int maxWidth) {
        if (textWidth(s) <= maxWidth) return s;
        String ellipsis = "…";
        int budget = maxWidth - textWidth(ellipsis);
        // the longest prefix that fits, by binary search (prefix widths only grow), not one measurement per character
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (textWidth(s.substring(0, mid)) <= budget) lo = mid;
            else hi = mid - 1;
        }
        if (lo > 0 && Character.isHighSurrogate(s.charAt(lo - 1))) lo--; // never split a surrogate pair
        return s.substring(0, lo) + ellipsis;
    }
}
