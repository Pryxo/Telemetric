package dev.telemetric.module.impl.visual;

import dev.telemetric.dungeon.Point;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.ViewAdapter;
import dev.telemetric.game.WorldLabelAdapter;
import dev.telemetric.game.WorldRenderAdapter;
import dev.telemetric.hud.HudModule;
import dev.telemetric.module.Category;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.GroupSetting;
import dev.telemetric.setting.impl.ModeSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.hud.HudOverlay;
import dev.telemetric.ui.render.BadgeText;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;

import java.util.List;
import java.util.Locale;

/**
 * Your health as a bar. There are three render modes: <b>3D World</b> (camera-facing quads above your head, third
 * person), <b>HUD Projection</b> (a flat HUD bar drawn where your head shows up on screen, with an optional fixed spot
 * in first person) and <b>HUD Element</b> (movable in the HUD editor). The bar slides to the new value, flashes on
 * damage, and blends from the high colour to the mid and low colours below the thresholds.
 */
public final class HealthBarModule extends HudModule {

    private static final String WORLD = "3D World", PROJECTION = "HUD Projection", ELEMENT = "HUD Element";
    /** First-person HUD Projection: the bar's centre sits this many GUI units below the crosshair. */
    private static final int FIRST_PERSON_DROP = 30;

    private final ModeSetting mode = add(new ModeSetting("Render Mode",
            "3D World: in the world above your head (third person). HUD Projection: a flat bar drawn where your head "
                    + "is on screen. HUD Element: a HUD element you move in the HUD editor",
            WORLD, WORLD, PROJECTION, ELEMENT));
    private final BooleanSetting firstPerson = add(new BooleanSetting("Show in First Person",
            "HUD Projection: also show the bar in first person (fixed, just below the crosshair)", false)
            .visibleWhen(this::projection));
    private final BooleanSetting throughBlocks = add(new BooleanSetting("Through Blocks",
            "3D World: draw the bar and its text on top of blocks, so it stays visible under or behind them", true)
            .visibleWhen(this::world3D));
    private final GroupSetting position = add(new GroupSetting("Position",
            "Move the bar in every direction, in blocks. Y 0 and up: above your head. Y below 0: under your feet, "
                    + "swinging toward the camera as you look up or down").visibleWhen(() -> !element()));
    private final NumberSetting offsetX = add(position.child(new NumberSetting("Offset X",
            "Move the bar along the world X axis (+ east, - west), in blocks", 0, -3, 3, 0.05).withLabel("X")));
    private final NumberSetting worldOffset = add(position.child(new NumberSetting("Height Offset",
            "0 and up: blocks above your head. Below 0: blocks from your feet, swinging toward the camera as you "
                    + "look up or down (stays in view)", -0.5, -3, 3, 0.05).withLabel("Y")));
    private final NumberSetting offsetZ = add(position.child(new NumberSetting("Offset Z",
            "Move the bar along the world Z axis (+ south, - north), in blocks", 0, -3, 3, 0.05).withLabel("Z")));
    private final ModeSetting style = add(new ModeSetting("Style", "Look of the bar",
            "Gradient", "Gradient", "Solid", "Text Only"));
    private final ModeSetting format = add(new ModeSetting("Health Text", "How the health number is written",
            "Number", "Number", "Decimals", "Percent", "None"));
    private final BooleanSetting showMax = add(new BooleanSetting("Show Max", "Write max health too (15/20)", false)
            .visibleWhen(() -> hasText() && !format.is("Percent")));
    private final ModeSetting textPos = add(new ModeSetting("Text Placement", "Where the health text sits on the bar",
            "Center", "Left", "Center", "Right", "Above", "Off").withLabel("Text Position")
            .visibleWhen(() -> !textOnly() && hasText()));

    private final GroupSetting textGroup = add(new GroupSetting("Text", "Look of the health text")
            .visibleWhen(this::hasText));
    private final ModeSetting textStyle = add(textGroup.child(new ModeSetting("Text Style",
            "Plain, drop shadow or a black outline", "Shadow", "None", "Shadow", "Outline")));
    private final NumberSetting textScale = add(textGroup.child(new NumberSetting("Text Scale", "Size of the health text",
            1.0, 0.5, 3.0, 0.05).withSuffix("x")));
    private final BooleanSetting staticText = add(textGroup.child(new BooleanSetting("Static Text Color",
            "Text Only: use the Text Color (off = the text takes the health colour)", true)
            .visibleWhen(this::textOnly)));
    private final ColorSetting textColor = add(textGroup.child(new ColorSetting("Text Color", "Health text colour",
            0xFFFFFFFF).visibleWhen(() -> !textOnly() || staticText.get())));

    private final GroupSetting colors = add(new GroupSetting("Colors", "Bar colours and animations"));
    private final ColorSetting high = add(colors.child(new ColorSetting("High Color", "Above the mid threshold", 0xFF4ADE80)));
    private final ColorSetting mid = add(colors.child(new ColorSetting("Mid Color", "At the mid threshold", 0xFFFACC15)));
    private final ColorSetting low = add(colors.child(new ColorSetting("Low Color", "At / below the low threshold", 0xFFEF4444)));
    private final NumberSetting midAt = add(colors.child(new NumberSetting("Mid Threshold", "Mid colour at this health",
            50, 0, 100, 1).withSuffix("%")));
    private final NumberSetting lowAt = add(colors.child(new NumberSetting("Low Threshold", "Low colour at / below this health",
            30, 0, 100, 1).withSuffix("%")));
    private final ColorSetting background = add(colors.child(new ColorSetting("Background", "Empty part of the bar",
            0xAA303036, true).visibleWhen(() -> !textOnly())));
    private final ColorSetting border = add(colors.child(new ColorSetting("Border", "Frame around the bar", 0xCC101014, true)
            .visibleWhen(() -> !textOnly())));
    private final BooleanSetting smooth = add(colors.child(new BooleanSetting("Smooth Health",
            "The bar slides to the new health and the colour blends between thresholds (off = instant, hard colour steps)",
            true)));
    private final BooleanSetting damageFlash = add(colors.child(new BooleanSetting("Damage Flash",
            "Flash the bar when you take damage", true)));
    private final ColorSetting flashColor = add(colors.child(new ColorSetting("Flash Color", "Colour of the damage flash",
            0xFFFFFFFF).visibleWhen(damageFlash::get)));

    private final GroupSetting world = add(new GroupSetting("Above Player", "Size of the bar in the 3D world")
            .withLabel("3D Size").visibleWhen(() -> world3D() && !textOnly()));
    private final NumberSetting worldWidth = add(world.child(new NumberSetting("World Width", "Bar width in blocks",
            1.0, 0.3, 3.0, 0.05).withLabel("Width")));
    private final NumberSetting worldHeight = add(world.child(new NumberSetting("World Height", "Bar height in blocks",
            0.12, 0.03, 0.5, 0.01).withLabel("Height")));

    private final GroupSetting hud = add(new GroupSetting("HUD Size", "Size of the HUD Projection / HUD Element bar")
            .visibleWhen(() -> !world3D() && !textOnly()));
    private final NumberSetting hudWidth = add(hud.child(new NumberSetting("HUD Width", "Bar width in pixels",
            80, 30, 200, 1).withLabel("Width")));
    private final NumberSetting hudHeight = add(hud.child(new NumberSetting("HUD Height", "Bar height in pixels",
            10, 4, 30, 1).withLabel("Height")));

    private final Animation shown = new Animation(Durations.DRAWER, Easing.OUT_CUBIC, 1);
    private final Animation flash = new Animation(Durations.COMPLEX, Easing.OUT_CUBIC, 0);
    private float lastHealth = -1;

    public HealthBarModule() {
        super("Health Bar", "Your health as a bar above your head or on the HUD", Category.VISUAL, 0.5, 0.78, false);
        scale.visibleWhen(() -> !world3D());
        WorldRenderAdapter.addDrawer(this::drawWorld);
        WorldLabelAdapter.addSource(this::worldLabels);
        HudOverlay.addScreenDrawer(this::drawProjected);
    }

    // ================================================================== state

    private boolean world3D() {
        return mode.is(WORLD);
    }

    private boolean projection() {
        return mode.is(PROJECTION);
    }

    private boolean element() {
        return mode.is(ELEMENT);
    }

    private boolean textOnly() {
        return style.is("Text Only");
    }

    private boolean hasText() {
        return !format.is("None");
    }

    private boolean textAbove() {
        return !textOnly() && textPos.is("Above");
    }

    /** Text Position Off hides the text on a bar, and Text Only always shows it. */
    private boolean showText(String t) {
        return !t.isEmpty() && (textOnly() || !textPos.is("Off"));
    }

    /** The health fraction to draw this frame, or -1 if there's no player. */
    private double fraction(boolean preview) {
        PlayerAdapter.Health h = PlayerAdapter.health();
        if (h == null || h.max() <= 0) return preview ? 0.72 : -1;
        if (h.current() != lastHealth) {
            if (lastHealth >= 0 && h.current() < lastHealth && damageFlash.get()) flash.snapTo(1).animateTo(0);
            lastHealth = h.current();
            if (smooth.get()) shown.animateTo(h.fraction());
            else shown.snapTo(h.fraction());
        }
        return smooth.get() ? shown.get() : h.fraction();
    }

    private int barColor(double f) {
        double pct = f * 100, m = midAt.get(), lo = lowAt.get();
        int c;
        if (smooth.get()) {
            if (pct >= m) c = ColorUtil.lerp(mid.get(), high.get(), m >= 100 ? 1 : Math.clamp((pct - m) / (100 - m), 0, 1));
            else if (pct >= lo) c = ColorUtil.lerp(low.get(), mid.get(), m <= lo ? 1 : Math.clamp((pct - lo) / (m - lo), 0, 1));
            else c = low.get();
        } else {
            c = pct <= lo ? low.get() : pct <= m ? mid.get() : high.get();
        }
        double fl = flash.get();
        return fl > 0.001 ? ColorUtil.lerp(c, flashColor.get(), fl * 0.7) : c;
    }

    private String text(boolean preview) {
        PlayerAdapter.Health h = PlayerAdapter.health();
        float cur = h == null ? 14.4f : h.current(), max = h == null ? 20 : h.max();
        return switch (format.get()) {
            case "None" -> "";
            case "Percent" -> Math.round(max <= 0 ? 0 : cur / max * 100) + "%";
            case "Decimals" -> String.format(Locale.ROOT, "%.1f", cur)
                    + (showMax.get() ? String.format(Locale.ROOT, "/%.1f", max) : "");
            default -> Math.round(cur) + (showMax.get() ? "/" + Math.round(max) : "");
        };
    }

    private int textArgb(double f) {
        return textOnly() && !staticText.get() ? barColor(f) : textColor.get();
    }

    private Render2D.TextLook look() {
        return switch (textStyle.get()) {
            case "None" -> Render2D.TextLook.NONE;
            case "Outline" -> Render2D.TextLook.OUTLINE;
            default -> Render2D.TextLook.SHADOW;
        };
    }

    // ================================================================== flat bar (HUD Element + HUD Projection)

    private int textW(Render2D r, String t) {
        return (int) Math.ceil(r.textWidth(t) * textScale.get());
    }

    private int textH(Render2D r) {
        return (int) Math.ceil(r.fontHeight() * textScale.get());
    }

    private int flatWidth(Render2D r, String t) {
        return textOnly() ? Math.max(1, textW(r, t)) : hudWidth.getInt();
    }

    private int flatHeight(Render2D r, String t) {
        if (textOnly()) return textH(r);
        return hudHeight.getInt() + (textAbove() && showText(t) ? textH(r) + 1 : 0);
    }

    private void drawText(Render2D r, String t, double x, double y, int color) {
        r.pushTransform(Math.round(x), Math.round(y), textScale.getFloat());
        try {
            r.text(t, 0, 0, color, look());
        } finally {
            r.popTransform();
        }
    }

    /** Draws the bar with its top-left corner at the origin ({@link #flatWidth} × {@link #flatHeight}). */
    private void drawFlat(Render2D r, double f, String t) {
        int w = flatWidth(r, t);
        int col = textArgb(f);
        if (textOnly()) {
            drawText(r, t, 0, 0, col);
            return;
        }
        boolean text = showText(t);
        int y = 0;
        if (text && textAbove()) {
            drawText(r, t, (w - textW(r, t)) / 2.0, 0, col);
            y = textH(r) + 1;
        }
        int h = hudHeight.getInt();
        r.rect(0, y, w, h, border.get());
        r.rect(1, y + 1, w - 2, h - 2, background.get());
        int fill = (int) Math.round((w - 2) * f);
        int c = barColor(f);
        if (fill > 0) {
            if (style.is("Gradient")) r.gradientV(1, y + 1, fill, h - 2, ColorUtil.shade(c, 0.2), ColorUtil.shade(c, -0.2));
            else r.rect(1, y + 1, fill, h - 2, c);
        }
        if (text && !textAbove()) {
            r.nextLayer();
            int tw = textW(r, t);
            double tx = switch (textPos.get()) {
                case "Left" -> 2;
                case "Right" -> w - 2 - tw;
                default -> (w - tw) / 2.0;
            };
            drawText(r, t, tx, y + Math.floor((h - r.fontHeight() * textScale.get()) / 2) + textScale.get(), col);
        }
    }

    // ================================================================== HUD Element

    @Override
    public boolean isShown() {
        return isEnabled() && element();
    }

    @Override
    public int contentWidth(Render2D r, boolean preview) {
        return flatWidth(r, text(preview));
    }

    @Override
    public int contentHeight(Render2D r, boolean preview) {
        return flatHeight(r, text(preview));
    }

    @Override
    public void draw(Render2D r, boolean preview, boolean alignRight) {
        double f = fraction(preview);
        if (f >= 0) drawFlat(r, f, text(preview));
    }

    // ================================================================== HUD Projection

    /** Bottom-centre of the bar at your head's screen position (or fixed below the crosshair in first person). */
    private void drawProjected(Render2D r, int sw, int sh) {
        if (!isEnabled() || !projection()) return;
        boolean first = !ViewAdapter.isThirdPerson();
        if (first && !firstPerson.get()) return;
        double f = fraction(false);
        if (f < 0) return;
        String t = text(false);
        float s = getScale();
        int w = flatWidth(r, t), h = flatHeight(r, t);
        double cx, bottom;
        if (first) {
            cx = sw / 2.0;
            bottom = sh / 2.0 + FIRST_PERSON_DROP + h * s / 2.0;
        } else {
            Point p = anchor(PlayerAdapter.partialTick(), 0);
            if (p == null) return;
            double[] screen = WorldRenderAdapter.toScreen(p.x(), p.y(), p.z());
            if (screen == null) return;
            cx = screen[0];
            bottom = screen[1];
        }
        r.pushTransform(Math.round(cx - w * s / 2.0), Math.round(bottom - h * s), s);
        try {
            drawFlat(r, f, t);
        } finally {
            r.popTransform();
        }
    }

    // ================================================================== 3D World

    private boolean worldShown() {
        return isEnabled() && world3D() && ViewAdapter.isThirdPerson();
    }

    private void drawWorld(WorldRenderAdapter.LineSink sink) {
        if (!worldShown() || textOnly()) return;
        double f = fraction(false);
        double w = worldWidth.get() / 2, h = worldHeight.get(), b = h * 0.12;
        Point p = anchor(PlayerAdapter.partialTick(), h);
        if (f < 0 || p == null) return;
        double y = p.y();
        boolean through = throughBlocks.get();
        sink.billboard(p.x(), y, p.z(), -w - b, -b, w + b, h + b, border.get(), through);
        sink.billboard(p.x(), y, p.z(), -w, 0, w, h, background.get(), through);
        if (f > 0) {
            int c = barColor(f);
            double right = -w + 2 * w * f;
            if (style.is("Gradient")) {
                sink.billboard(p.x(), y, p.z(), -w, h / 2, right, h, ColorUtil.shade(c, 0.2), through);
                sink.billboard(p.x(), y, p.z(), -w, 0, right, h / 2, ColorUtil.shade(c, -0.2), through);
            } else {
                sink.billboard(p.x(), y, p.z(), -w, 0, right, h, c, through);
            }
        }
    }

    /**
     * Bottom-centre of the bar this frame (world). With Y ≥ 0 it's Y blocks above your head. With Y < 0 it's |Y| blocks
     * from your feet on an arc toward the camera: straight below when looking level, and behind or in front of you
     * when looking down or up. The bar is centred on that point ({@code barHeight} in blocks, 0 for HUD Projection).
     */
    private Point anchor(float partialTick, double barHeight) {
        double y = worldOffset.get();
        if (y >= 0) {
            Point p = PlayerAdapter.headTop(partialTick);
            return p == null ? null : new Point(p.x() + offsetX.get(), p.y() + y, p.z() + offsetZ.get());
        }
        Point p = PlayerAdapter.feet(partialTick);
        if (p == null) return null;
        double pitchRatio = Math.clamp((WorldRenderAdapter.cameraPitch() + 90) / 180.0, 0, 1);
        double arc = (1 - pitchRatio) * Math.PI;
        double horizontal = -y * Math.cos(arc), vertical = -y * Math.sin(arc);
        double yaw = Math.toRadians(WorldRenderAdapter.cameraYaw());
        // The camera looks along (-sin yaw, cos yaw), and the bar swings the other way, toward the camera.
        return new Point(p.x() + Math.sin(yaw) * horizontal + offsetX.get(), p.y() - vertical - barHeight / 2,
                p.z() - Math.cos(yaw) * horizontal + offsetZ.get());
    }

    private List<WorldLabelAdapter.Label> worldLabels(float partialTick) {
        if (!worldShown()) return List.of();
        String t = text(false);
        if (!showText(t)) return List.of();
        double f = fraction(false);
        double w = worldWidth.get() / 2, h = worldHeight.get();
        Point p = anchor(partialTick, h);
        if (f < 0 || p == null) return List.of();
        double y = p.y();
        double textH = (textOnly() ? 0.25 : Math.max(0.08, h * 0.8)) * textScale.get();
        double ty = textOnly() ? y : textAbove() ? y + h + h * 0.3 : y + (h - textH) / 2;
        double pad = h * 0.25;
        double side = 0;
        float align = 0.5f;
        if (!textOnly() && textPos.is("Left")) {
            side = -w + pad;
            align = 0;
        } else if (!textOnly() && textPos.is("Right")) {
            side = w - pad;
            align = 1;
        }
        Render2D.TextLook look = look();
        return List.of(new WorldLabelAdapter.Label(p.x(), ty, p.z(), t, BadgeText.Style.PLAIN, textH,
                textArgb(f), throughBlocks.get()).aligned(side, align)
                .look(look == Render2D.TextLook.SHADOW, look == Render2D.TextLook.OUTLINE));
    }
}
