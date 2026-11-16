package dev.telemetric.hud;

import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ModeSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.render.Render2D;

/**
 * Base class for every HUD element. A HUD element is a toggleable module in the {@link Category#HUD}
 * panel; while enabled it is drawn in-game by the HUD overlay and can be moved / resized in the HUD editor.
 * <p>
 * <b>Position</b> is stored as two fractions (hidden settings {@code X}/{@code Y}) of the free space
 * {@code screen - margins - element size}: 0 = left/top edge, 1 = right/bottom edge. That keeps
 * corner-anchored elements in their corner on any window size or GUI scale.
 * <b>Size</b> is the visible {@code Scale} setting (also changed by the editor's resize handle).
 * <p>
 * Subclasses only measure and draw their content at the origin in unscaled units; the overlay /
 * editor applies translation and scale.
 */
public abstract class HudModule extends Module {

    /** Gap kept between elements and the screen edge (GUI units). */
    public static final int MARGIN = 2;
    public static final double MIN_SCALE = 0.5, MAX_SCALE = 3.0;

    private final boolean enabledByDefault;

    private final NumberSetting posX;
    private final NumberSetting posY;
    public final NumberSetting scale;

    protected HudModule(String name, String description, double defaultX, double defaultY, boolean enabledByDefault) {
        this(name, description, Category.HUD, defaultX, defaultY, enabledByDefault);
    }

    /** HUD element listed in another panel (e.g. the Dungeon minimap); still drawn / edited like every HUD element. */
    protected HudModule(String name, String description, Category category, double defaultX, double defaultY,
                        boolean enabledByDefault) {
        this(name, description, category, defaultX, defaultY, enabledByDefault, 1.0);
    }

    /** Like above, with a default {@code Scale} other than 1× (e.g. large alert text). */
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
        this.align = add(new ModeSetting("Text Align", "Rows aligned left, centred or right (HUD editor: M cycles)",
                defaultX > 0.66 ? ALIGN_RIGHT : ALIGN_LEFT, ALIGN_LEFT, ALIGN_MID, ALIGN_RIGHT));
    }

    public static final String ALIGN_LEFT = "Left", ALIGN_MID = "Mid", ALIGN_RIGHT = "Right";

    private final ModeSetting align;

    /** Right-aligned layout: text right-aligned, icons in a column at the right edge. */
    public boolean isMirrored() {
        return align.is(ALIGN_RIGHT);
    }

    /** Centred layout: each row centred in the element (icons stay in front of their row). */
    public boolean isCentered() {
        return align.is(ALIGN_MID);
    }

    /** HUD editor M: Left → Mid → Right → Left. */
    public void cycleAlign() {
        align.set(isMirrored() ? ALIGN_LEFT : isCentered() ? ALIGN_RIGHT : ALIGN_MID);
    }

    @Override
    protected boolean isEnabledByDefault() {
        return enabledByDefault;
    }

    /** Drawn in-game / in the HUD editor (default: enabled). Split elements (Performance HUD parts) override this. */
    public boolean isShown() {
        return isEnabled();
    }

    /** HUD editor "Del": hides the element (default: turns the module off). */
    public void hide() {
        setEnabled(false);
    }

    // ================================================================== content

    /** Unscaled content width. */
    public abstract int contentWidth(Render2D r, boolean preview);

    /** Unscaled content height. */
    public abstract int contentHeight(Render2D r, boolean preview);

    /**
     * Draws the content with its top-left corner at (0, 0).
     *
     * @param preview     true in the HUD editor: show placeholder data when the real data is missing
     * @param alignRight  right-aligned layout ({@link #isMirrored()}) — right-align text, icons on the right;
     *                    centred ({@link #isCentered()}) is read by the element itself
     */
    public abstract void draw(Render2D r, boolean preview, boolean alignRight);

    /** False hides the element in-game (e.g. no Tab-list data on this server). The editor always shows it. */
    public boolean hasData() {
        return true;
    }

    // ================================================================== geometry (GUI units)

    public float getScale() {
        return scale.getFloat();
    }

    public void setScale(double value) {
        scale.set(value);
    }

    public int scaledWidth(Render2D r, boolean preview) {
        return Math.round(contentWidth(r, preview) * getScale());
    }

    public int scaledHeight(Render2D r, boolean preview) {
        return Math.round(contentHeight(r, preview) * getScale());
    }

    public int left(int screenWidth, int width) {
        return MARGIN + (int) Math.round(posX.get() * Math.max(0, screenWidth - 2 * MARGIN - width));
    }

    public int top(int screenHeight, int height) {
        return MARGIN + (int) Math.round(posY.get() * Math.max(0, screenHeight - 2 * MARGIN - height));
    }

    /** Stores a new top-left corner (clamped to the screen). */
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
    }
}
