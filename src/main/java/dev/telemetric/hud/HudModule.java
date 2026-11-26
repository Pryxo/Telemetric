package dev.telemetric.hud;

import com.google.gson.JsonObject;
import dev.telemetric.Telemetric;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.module.impl.misc.HudSettingsModule;
import dev.telemetric.setting.Setting;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.GroupSetting;
import dev.telemetric.setting.impl.ModeSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;

/**
 * Base class for every HUD element. A HUD element is a toggleable module in the {@link Category#STATS} panel (unless it names another category). While
 * it's enabled, the HUD overlay draws it in-game, and you can move and resize it in the HUD editor.
 * <p>
 * The position is stored as two fractions (hidden settings {@code X}/{@code Y}) of the free space
 * {@code screen - margins - element size}: 0 is the left/top edge and 1 the right/bottom edge. That keeps elements
 * anchored in a corner in their corner on any window size or GUI scale.
 * The size is the visible {@code Scale} setting (the editor's resize handle changes it too).
 * <p>
 * Every element has a HUD Settings dropdown (Text Align, Mirror Text, and what the element supports: Value
 * Align, Background / Bg Opacity / Accent Bar, Icons, and a Colors section). Misc → HUD changes the shared ones on
 * every element at once.
 * <p>
 * Subclasses only measure and draw their content at the origin in unscaled units. The overlay and editor apply the
 * translation and scale. Elements that don't draw their own panel ({@link #drawsOwnFrame()} false) get the background
 * and accent bar drawn around their content by {@link #render}.
 */
public abstract class HudModule extends Module {

    /** Gap kept between elements and the screen edge (GUI units). */
    public static final int MARGIN = 2;
    public static final double MIN_SCALE = 0.5, MAX_SCALE = 3.0;
    /** Padding between the content and the background panel drawn by {@link #render}. */
    public static final int FRAME_PAD = 3;

    public static final String ALIGN_LEFT = "Left", ALIGN_MID = "Mid", ALIGN_RIGHT = "Right";
    /** Value Align: values pushed to the far edge of the element, or drawn right after their label. */
    public static final String VALUE_EDGE = "Right", VALUE_INLINE = "Next to Label";

    private final boolean enabledByDefault;

    private final NumberSetting posX;
    private final NumberSetting posY;
    public final NumberSetting scale;

    protected final GroupSetting hudSettings;
    private final ModeSetting align;
    private final BooleanSetting mirrorText;

    private ModeSetting valueAlign;
    private BooleanSetting background, accentBar, icons, title;
    private NumberSetting bgOpacity;
    private HudColor bgColor, accentColor;

    private GroupSetting colorGroup;
    private BooleanSetting themeColors;
    private final List<HudColor> colors = new ArrayList<>();

    protected HudModule(String name, String description, double defaultX, double defaultY, boolean enabledByDefault) {
        this(name, description, Category.STATS, defaultX, defaultY, enabledByDefault);
    }

    /** A HUD element listed in another panel (e.g. Dungeon Info). It's still drawn and edited like every HUD element. */
    protected HudModule(String name, String description, Category category, double defaultX, double defaultY,
                        boolean enabledByDefault) {
        this(name, description, category, defaultX, defaultY, enabledByDefault, 1.0);
    }

    /** Same as above, with a default {@code Scale} other than 1× (e.g. large alert text). */
    protected HudModule(String name, String description, Category category, double defaultX, double defaultY,
                        boolean enabledByDefault, double defaultScale) {
        super(name, description, category);
        this.enabledByDefault = enabledByDefault;
        this.posX = add(new NumberSetting("X", "Horizontal position (0 = left, 1 = right)", defaultX, 0, 1, 0.0001)
                .visibleWhen(() -> false));
        this.posY = add(new NumberSetting("Y", "Vertical position (0 = top, 1 = bottom)", defaultY, 0, 1, 0.0001)
                .visibleWhen(() -> false));
        this.scale = add(new NumberSetting("Scale", "Size of this element (also: HUD editor resize handle)",
                Math.clamp(defaultScale, MIN_SCALE, MAX_SCALE), MIN_SCALE, MAX_SCALE, 0.05).withSuffix("x"));
        this.hudSettings = add(new GroupSetting("HUD Settings", "Look of this HUD element: alignment, background, icons and colours"));
        this.align = add(hudSettings.child(new ModeSetting("Text Align", "Rows aligned left, centred or right (HUD editor: M cycles)",
                defaultX > 0.66 ? ALIGN_RIGHT : ALIGN_LEFT, ALIGN_LEFT, ALIGN_MID, ALIGN_RIGHT)));
        this.mirrorText = add(hudSettings.child(new BooleanSetting("Mirror Text",
                "Right align mirrors rows (\"Arcanist :Class <icon>\"); off keeps \"<icon> Class: Arcanist\" (HUD editor: N)", true)
                .visibleWhen(() -> align.is(ALIGN_RIGHT))));
    }


    /**
     * Adds a setting to the HUD Settings dropdown, in front of its Colors section. Use it in constructors and field
     * initialisers, like {@code add}.
     */
    protected final <S extends Setting<?>> S addHudSetting(S setting) {
        hudSettings.child(setting);
        return colorGroup == null ? add(setting) : addBefore(colorGroup, setting);
    }

    /** Adds Value Align (rows with a label: value at the far edge, or right after the label). */
    protected final void addValueAlign() {
        if (valueAlign != null) return;
        valueAlign = addHudSetting(new ModeSetting("Value Align",
                "Right: values line up at the far edge of the element (left edge for mirrored rows); "
                        + "Next to Label: each value follows its label", VALUE_EDGE, VALUE_EDGE, VALUE_INLINE)
                .visibleWhen(() -> !isCentered() && (!isMirrored() || isTextMirrored())));
    }

    /** Adds Background, Bg Opacity and Accent Bar, plus their colours. */
    protected final void addFrameSettings(boolean backgroundByDefault) {
        if (background != null) return;
        background = addHudSetting(new BooleanSetting("HUD Background", "Draw a panel behind this element",
                backgroundByDefault).withLabel("Background"));
        bgOpacity = addHudSetting(new NumberSetting("HUD Bg Opacity", "Opacity of this element's background", 70, 0, 100, 1)
                .withSuffix("%").withLabel("Bg Opacity").visibleWhen(background::get));
        accentBar = addHudSetting(new BooleanSetting("HUD Accent Bar", "Thin accent-coloured edge on this element", true)
                .withLabel("Accent Bar").visibleWhen(background::get));
        bgColor = paletteColor("Background", "Panel behind the element", Theme::panel);
        accentColor = paletteColor("Accent Bar", "The thin edge of the panel", Theme::accent);
    }

    /** Adds Show Title (the element's title row can be turned off). */
    protected final void addTitleSetting() {
        if (title != null) return;
        title = addHudSetting(new BooleanSetting("HUD Show Title", "Show this element's title row", true)
                .withLabel("Show Title").visibleWhen(this::hasTitle));
    }

    /** Whether this element has a title row (Show Title is hidden otherwise). */
    protected boolean hasTitle() {
        return true;
    }

    /** Show Title on (true for elements without the toggle). */
    public boolean showTitle() {
        HudModule o = styleOwner();
        return o.title == null || o.title.get();
    }

    /** Adds Icons (stat / row icons in front of the text). */
    protected final void addIconSetting() {
        if (icons != null) return;
        icons = addHudSetting(new BooleanSetting("HUD Icons", "Show icons in front of this element's rows", true)
                .withLabel("Icons"));
    }

    /** The Colors section (made on first use). */
    protected final GroupSetting colorGroup() {
        if (colorGroup == null) {
            colorGroup = add(hudSettings.child(new GroupSetting("HUD Colors", "Colours of this element").withLabel("Colors")));
            themeColors = add(colorGroup.child(new BooleanSetting("HUD Theme Colors",
                    "On: the colours below follow the Theme (and the server colours); off: your own colours", true)
                    .withLabel("Theme Colors")));
            // switching to own colours starts from what's on screen instead of the defaults
            themeColors.onChange(on -> {
                if (on) return;
                for (HudColor c : colors) if (c.setting.get().equals(c.setting.getDefault())) c.setting.set(c.themeColor());
            });
        }
        return colorGroup;
    }

    /** A theme-derived colour in the Colors section (shown while Theme Colors is off). */
    protected final HudColor hudColor(String label, String description, IntSupplier themeColor) {
        GroupSetting group = colorGroup();
        ColorSetting setting = add(group.child(new ColorSetting("HUD " + label + " Color", description,
                0xFFFFFFFF, false).withLabel(label).visibleWhen(() -> !themeColors.get())));
        HudColor color = new HudColor(setting, themeColor, () -> !themeColors.get());
        colors.add(color);
        return color;
    }

    /** Same, with the colour taken from the current theme palette. */
    protected final HudColor paletteColor(String label, String description, ToIntFunction<Theme> paletteColor) {
        return hudColor(label, description, () -> paletteColor.applyAsInt(palette()));
    }

    /** A fixed-default colour of this element (e.g. map rooms) in the Colors section, always shown. */
    protected final ColorSetting addHudColor(ColorSetting setting) {
        return add(colorGroup().child(setting));
    }

    protected static Theme palette() {
        return Telemetric.get().theme().palette();
    }


    /** The element whose HUD Settings this one follows (split-off parts follow their owner). */
    protected HudModule styleOwner() {
        return this;
    }

    /** True for elements that draw their own background panel ({@link LinesHudModule}, DPS Log). */
    protected boolean drawsOwnFrame() {
        return false;
    }

    public boolean hasBackground() {
        HudModule o = styleOwner();
        return o.background != null && o.background.get();
    }

    /** The background colour with its opacity applied. */
    public int backgroundColor() {
        HudModule o = styleOwner();
        int c = o.bgColor == null ? palette().panel() : o.bgColor.get();
        double opacity = o.bgOpacity == null ? 0.7 : o.bgOpacity.get() / 100.0;
        return ColorUtil.fade(c, opacity);
    }

    public boolean hasAccentBar() {
        HudModule o = styleOwner();
        return hasBackground() && o.accentBar != null && o.accentBar.get();
    }

    public int accentBarColor() {
        HudModule o = styleOwner();
        return o.accentColor == null ? palette().accent() : o.accentColor.get();
    }

    /** Row icons on (elements without their own Icons toggle follow Misc → HUD → Stat Icons). */
    public boolean showIcons() {
        HudModule o = styleOwner();
        if (o.icons != null) return o.icons.get();
        HudSettingsModule global = Telemetric.get().modules().get(HudSettingsModule.class);
        return global == null || global.statIcons.get();
    }

    /** Value Align = Right: values pushed to the far edge. */
    public boolean valuesAtEdge() {
        HudModule o = styleOwner();
        return o.valueAlign != null && o.valueAlign.is(VALUE_EDGE);
    }

    /** Draws the background panel and the accent bar (on the left, the right when right-aligned, none when centred). */
    protected final void drawFrame(Render2D r, int w, int h, boolean alignRight) {
        if (!hasBackground()) return;
        r.roundedRect(0, 0, w, h, 3, backgroundColor());
        if (hasAccentBar() && (alignRight || !isCentered())) r.rect(alignRight ? w - 1 : 0, 2, 1, h - 4, accentBarColor());
    }


    /** Misc → HUD changed a shared setting: copy it onto this element. */
    public void applyGlobalBackground(boolean on) {
        if (background != null) background.set(on);
    }

    public void applyGlobalBgOpacity(double value) {
        if (bgOpacity != null) bgOpacity.set(value);
    }

    public void applyGlobalAccentBar(boolean on) {
        if (accentBar != null) accentBar.set(on);
    }

    public void applyGlobalIcons(boolean on) {
        if (icons != null) icons.set(on);
    }

    /** Profiles from before the per-element HUD Settings take the old shared Misc → HUD values. */
    @Override
    public void fromJson(JsonObject json) {
        super.fromJson(json);
        JsonObject values = json.has("settings") && json.get("settings").isJsonObject()
                ? json.getAsJsonObject("settings") : new JsonObject();
        HudSettingsModule global = Telemetric.get().modules().get(HudSettingsModule.class);
        if (global == null) return;
        if (drawsOwnFrame() && background != null && !values.has(background.getName())) {
            background.set(global.background.get());
            bgOpacity.set(global.backgroundOpacity.get());
            accentBar.set(global.accentBar.get());
        }
        if (icons != null && !values.has(icons.getName())) icons.set(global.statIcons.get());
    }


    /** Right-aligned rows drawn mirrored ("Value :Label <icon>") instead of in normal reading order. */
    public boolean isTextMirrored() {
        return mirrorText.get();
    }

    /** HUD editor N: toggles mirrored text for this element. */
    public void toggleMirrorText() {
        mirrorText.set(!mirrorText.get());
    }

    /** Right-aligned layout: text right-aligned, icons in a column at the right edge. */
    public boolean isMirrored() {
        return align.is(ALIGN_RIGHT);
    }

    /** Centred layout: each row is centred in the element (icons stay in front of their row). */
    public boolean isCentered() {
        return align.is(ALIGN_MID);
    }

    /** HUD editor M: cycles Left → Mid → Right → Left. */
    public void cycleAlign() {
        align.set(isMirrored() ? ALIGN_LEFT : isCentered() ? ALIGN_RIGHT : ALIGN_MID);
    }

    @Override
    protected boolean isEnabledByDefault() {
        return enabledByDefault;
    }

    /** Whether it's drawn in-game and in the HUD editor (default: when enabled). Split elements (Performance HUD parts) override this. */
    public boolean isShown() {
        return isEnabled();
    }

    /** HUD editor "Del": hides the element (by default that turns the module off). */
    public void hide() {
        setEnabled(false);
    }


    /** Unscaled content width. */
    public abstract int contentWidth(Render2D r, boolean preview);

    /** Unscaled content height. */
    public abstract int contentHeight(Render2D r, boolean preview);

    /**
     * Draws the content with its top-left corner at (0, 0).
     *
     * @param preview     true in the HUD editor, where placeholder data is shown when the real data is missing
     * @param alignRight  right-aligned layout ({@link #isMirrored()}): text right-aligned, icons on the right. The
     *                    centred layout ({@link #isCentered()}) is read by the element itself
     */
    public abstract void draw(Render2D r, boolean preview, boolean alignRight);

    /** False hides the element in-game (e.g. this server has no Tab-list data). The editor always shows it. */
    public boolean hasData() {
        return true;
    }

    /** Padding added around the content for the background {@link #render} draws (0 without one). */
    private int framePad() {
        return !drawsOwnFrame() && hasBackground() ? FRAME_PAD : 0;
    }

    /** Unscaled width including the background {@link #render} draws. */
    public final int frameWidth(Render2D r, boolean preview) {
        return contentWidth(r, preview) + framePad() * 2;
    }

    /** Unscaled height including the background {@link #render} draws. */
    public final int frameHeight(Render2D r, boolean preview) {
        return contentHeight(r, preview) + framePad() * 2;
    }

    /** Draws the element: its background (if it doesn't draw its own) and then its content. */
    public final void render(Render2D r, boolean preview, boolean alignRight) {
        int pad = framePad();
        if (pad == 0) {
            draw(r, preview, alignRight);
            return;
        }
        drawFrame(r, frameWidth(r, preview), frameHeight(r, preview), alignRight);
        r.pushTransform(pad, pad, 1f);
        try {
            draw(r, preview, alignRight);
        } finally {
            r.popTransform();
        }
    }


    public float getScale() {
        return scale.getFloat();
    }

    public void setScale(double value) {
        scale.set(value);
    }

    /**
     * Misc → HUD → Fit to Screen: factor applied on top of every element's own Scale so the HUD covers the same share of
     * the screen on every resolution / GUI scale (same reference canvas as the ClickGUI). 1 when off.
     */
    public static float screenFit() {
        HudSettingsModule global = Telemetric.get().modules().get(HudSettingsModule.class);
        return global != null && global.fitToScreen.get() ? Render2D.screenFit() : 1f;
    }

    /** The scale the element is actually drawn at: its own Scale × {@link #screenFit()}. */
    public float effectiveScale() {
        return getScale() * screenFit();
    }

    public int scaledWidth(Render2D r, boolean preview) {
        return Math.round(frameWidth(r, preview) * effectiveScale());
    }

    public int scaledHeight(Render2D r, boolean preview) {
        return Math.round(frameHeight(r, preview) * effectiveScale());
    }

    public int left(int screenWidth, int width) {
        return MARGIN + (int) Math.round(posX.get() * Math.max(0, screenWidth - 2 * MARGIN - width));
    }

    public int top(int screenHeight, int height) {
        return MARGIN + (int) Math.round(posY.get() * Math.max(0, screenHeight - 2 * MARGIN - height));
    }

    /** Stores a new top-left corner, clamped to the screen. */
    public void setTopLeft(double left, double top, int screenWidth, int screenHeight, int width, int height) {
        double freeX = screenWidth - 2.0 * MARGIN - width;
        double freeY = screenHeight - 2.0 * MARGIN - height;
        posX.set(freeX <= 0 ? 0 : Math.clamp((left - MARGIN) / freeX, 0, 1));
        posY.set(freeY <= 0 ? 0 : Math.clamp((top - MARGIN) / freeY, 0, 1));
    }

    public boolean isRightSide() {
        return posX.get() > 0.66;
    }

    public void resetPosition() {
        posX.reset();
        posY.reset();
        scale.reset();
        align.reset();
        mirrorText.reset();
    }
}
