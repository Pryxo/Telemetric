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
import dev.telemetric.ui.render.BadgeText;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;

import java.util.List;
import java.util.Locale;

/**
 * Your health as a bar — floating above your head (third person) or as a movable HUD element. The bar eases to the
 * new value, flashes on damage and turns from the high to the mid / low colour below the thresholds.
 */
public final class HealthBarModule extends HudModule {

    private static final String ABOVE = "Above Player", HUD = "HUD Element";

    private final ModeSetting placement = add(new ModeSetting("Placement",
            "Above your head in the world (third person) or a HUD element you move in the HUD editor", ABOVE, ABOVE, HUD));
    private final ModeSetting style = add(new ModeSetting("Style", "Look of the bar",
            "Gradient", "Gradient", "Solid", "Text Only"));
    private final ModeSetting format = add(new ModeSetting("Health Text", "How the health number is written",
            "Number", "Number", "Decimals", "Percent", "None"));
    private final BooleanSetting showMax = add(new BooleanSetting("Show Max", "Write max health too (15/20)", false)
            .visibleWhen(() -> !format.get().equals("Percent") && !format.get().equals("None")));
    private final ModeSetting textPos = add(new ModeSetting("Text Position", "Where the health text sits",
            "Inside", "Inside", "Above").visibleWhen(() -> !style.get().equals("Text Only") && !format.get().equals("None")));

    private final GroupSetting colors = add(new GroupSetting("Colors", "Bar and text colours"));
    private final ColorSetting high = add(colors.child(new ColorSetting("High Color", "Above the mid threshold", 0xFF4ADE80)));
    private final ColorSetting mid = add(colors.child(new ColorSetting("Mid Color", "Below the mid threshold", 0xFFFACC15)));
    private final ColorSetting low = add(colors.child(new ColorSetting("Low Color", "Below the low threshold", 0xFFEF4444)));
    private final NumberSetting midAt = add(colors.child(new NumberSetting("Mid Threshold", "Switch to the mid colour below",
            50, 0, 100, 1).withSuffix("%")));
    private final NumberSetting lowAt = add(colors.child(new NumberSetting("Low Threshold", "Switch to the low colour below",
            30, 0, 100, 1).withSuffix("%")));
    private final ColorSetting background = add(colors.child(new ColorSetting("Background", "Empty part of the bar",
            0xAA303036, true)));
    private final ColorSetting border = add(colors.child(new ColorSetting("Border", "Frame around the bar", 0xCC101014, true)));
    private final ColorSetting textColor = add(colors.child(new ColorSetting("Text Color",
            "Health text colour (Text Only: the bar colour is used when this is white)", 0xFFFFFFFF)));
    private final BooleanSetting damageFlash = add(colors.child(new BooleanSetting("Damage Flash",
            "Flash the bar when you take damage", true)));
    private final ColorSetting flashColor = add(colors.child(new ColorSetting("Flash Color", "Colour of the damage flash",
            0xFFFFFFFF)));

    private final GroupSetting world = add(new GroupSetting("Above Player", "Size and height of the bar over your head"));
    private final NumberSetting worldWidth = add(world.child(new NumberSetting("World Width", "Bar width in blocks",
            1.0, 0.3, 3.0, 0.05).withLabel("Width")));
    private final NumberSetting worldHeight = add(world.child(new NumberSetting("World Height", "Bar height in blocks",
            0.12, 0.03, 0.5, 0.01).withLabel("Height")));
    private final NumberSetting worldOffset = add(world.child(new NumberSetting("Height Offset",
            "Blocks above your head", 0.45, 0, 2, 0.05)));

    private final GroupSetting hud = add(new GroupSetting("HUD Size", "Size of the HUD element bar"));
    private final NumberSetting hudWidth = add(hud.child(new NumberSetting("HUD Width", "Bar width in pixels",
            80, 30, 200, 1).withLabel("Width")));
    private final NumberSetting hudHeight = add(hud.child(new NumberSetting("HUD Height", "Bar height in pixels",
            10, 4, 30, 1).withLabel("Height")));

    private final Animation shown = new Animation(Durations.DRAWER, Easing.OUT_CUBIC, 1);
    private final Animation flash = new Animation(Durations.COMPLEX, Easing.OUT_CUBIC, 0);
    private float lastHealth = -1;

    public HealthBarModule() {
        super("Health Bar", "Your health as a bar above your head or on the HUD", Category.VISUAL, 0.5, 0.78, false);
        WorldRenderAdapter.addDrawer(this::drawWorld);
        WorldLabelAdapter.addSource(this::worldLabels);
    }

    // ================================================================== state

    /** Health fraction to draw this frame (eased), or -1 without a player. */
    private double fraction(boolean preview) {
        PlayerAdapter.Health h = PlayerAdapter.health();
        if (h == null || h.max() <= 0) return preview ? 0.72 : -1;
        if (h.current() != lastHealth) {
            if (lastHealth >= 0 && h.current() < lastHealth && damageFlash.get()) flash.snapTo(1).animateTo(0);
            lastHealth = h.current();
            shown.animateTo(h.fraction());
        }
        return shown.get();
    }

    private int barColor(double f) {
        double pct = f * 100;
        int c = pct <= lowAt.get() ? low.get() : pct <= midAt.get() ? mid.get() : high.get();
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

    private boolean textOnly() {
        return style.get().equals("Text Only");
    }

    private boolean textAbove() {
        return textOnly() || textPos.get().equals("Above");
    }

    private int textArgb(double f) {
        int t = textColor.get();
        return textOnly() && (t & 0xFFFFFF) == 0xFFFFFF ? barColor(f) : t;
    }

    // ================================================================== HUD element

    @Override
    public boolean isShown() {
        return isEnabled() && placement.get().equals(HUD);
    }

    @Override
    public int contentWidth(Render2D r, boolean preview) {
        return textOnly() ? Math.max(1, r.textWidth(text(preview))) : hudWidth.getInt();
    }

    @Override
    public int contentHeight(Render2D r, boolean preview) {
        if (textOnly()) return r.fontHeight();
        boolean above = textAbove() && !format.get().equals("None");
        return hudHeight.getInt() + (above ? r.fontHeight() + 1 : 0);
    }

    @Override
    public void draw(Render2D r, boolean preview, boolean alignRight) {
        double f = fraction(preview);
        if (f < 0) return;
        String t = text(preview);
        int w = contentWidth(r, preview);
        if (textOnly()) {
            r.text(t, 0, 0, textArgb(f));
            return;
        }
        int y = 0;
        if (textAbove() && !t.isEmpty()) {
            r.textCentered(t, w / 2, 0, textArgb(f));
            y = r.fontHeight() + 1;
        }
        int h = hudHeight.getInt();
        r.rect(0, y, w, h, border.get());
        r.rect(1, y + 1, w - 2, h - 2, background.get());
        int fill = (int) Math.round((w - 2) * f);
        int c = barColor(f);
        if (fill > 0) {
            if (style.get().equals("Gradient")) r.gradientV(1, y + 1, fill, h - 2, ColorUtil.shade(c, 0.2), ColorUtil.shade(c, -0.2));
            else r.rect(1, y + 1, fill, h - 2, c);
        }
        if (!textAbove() && !t.isEmpty()) {
            r.nextLayer();
            r.textCentered(t, w / 2, y + (h - r.fontHeight()) / 2 + 1, textArgb(f));
        }
    }

    // ================================================================== above the player

    private boolean worldShown() {
        return isEnabled() && placement.get().equals(ABOVE) && ViewAdapter.isThirdPerson();
    }

    private void drawWorld(WorldRenderAdapter.LineSink sink) {
        if (!worldShown() || textOnly()) return;
        double f = fraction(false);
        Point p = PlayerAdapter.headTop(PlayerAdapter.partialTick());
        if (f < 0 || p == null) return;
        double w = worldWidth.get() / 2, h = worldHeight.get(), y = p.y() + worldOffset.get(), b = h * 0.12;
        sink.billboard(p.x(), y, p.z(), -w - b, -b, w + b, h + b, border.get(), false);
        sink.billboard(p.x(), y, p.z(), -w, 0, w, h, background.get(), false);
        if (f > 0) {
            int c = barColor(f);
            double right = -w + 2 * w * f;
            if (style.get().equals("Gradient")) {
                sink.billboard(p.x(), y, p.z(), -w, h / 2, right, h, ColorUtil.shade(c, 0.2), false);
                sink.billboard(p.x(), y, p.z(), -w, 0, right, h / 2, ColorUtil.shade(c, -0.2), false);
            } else {
                sink.billboard(p.x(), y, p.z(), -w, 0, right, h, c, false);
            }
        }
    }

    private List<WorldLabelAdapter.Label> worldLabels(float partialTick) {
        if (!worldShown() || format.get().equals("None")) return List.of();
        double f = fraction(false);
        Point p = PlayerAdapter.headTop(partialTick);
        if (f < 0 || p == null) return List.of();
        double h = worldHeight.get(), y = p.y() + worldOffset.get();
        double textH = textOnly() ? 0.25 : Math.max(0.08, h * 0.8);
        double ty = textAbove() ? y + (textOnly() ? 0 : h + h * 0.3) : y + (h - textH) / 2;
        return List.of(new WorldLabelAdapter.Label(p.x(), ty, p.z(), text(false), BadgeText.Style.PLAIN, textH,
                textArgb(f), false));
    }
}
