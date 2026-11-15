package dev.telemetric.ui.clickgui.setting;

import dev.telemetric.setting.Setting;
import dev.telemetric.ui.clickgui.Layout;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.framework.GuiContext;
import dev.telemetric.ui.framework.UIComponent;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.function.BooleanSupplier;

/**
 * Base for the UI of one {@link Setting}. Handles the shared behaviour so subclasses only draw
 * their control and react to input:
 * <ul>
 *   <li>animated show/hide driven by {@link Setting#isVisible()} (height collapses smoothly),</li>
 *   <li>hover highlight of the row,</li>
 *   <li>tooltip with the setting description.</li>
 * </ul>
 * Subclasses implement {@link #contentHeight()} (full, un-collapsed height — may itself be animated)
 * and {@link #renderContent}.
 */
public abstract class SettingComponent<S extends Setting<?>> extends UIComponent {

    protected final S setting;
    protected final Animation visibility;
    protected final Animation hover = new Animation(Durations.HOVER, Easing.OUT_CUBIC);
    private BooleanSupplier searchVisibility;

    protected SettingComponent(GuiContext ctx, S setting) {
        super(ctx);
        this.setting = setting;
        this.visibility = new Animation(Durations.DROPDOWN, Easing.OUT_CUBIC, setting.isVisible() ? 1 : 0);
    }

    /** Full height when visible. */
    protected abstract int contentHeight();

    /** Draw the control. Bounds are set; clip is active. {@code p} = current palette. */
    protected abstract void renderContent(Render2D r, int mouseX, int mouseY, float delta, Theme p);

    /** Height of the top "label row" used for hover highlight + tooltip. Override for multi-row controls. */
    protected int rowHeight() {
        return Layout.SETTING_HEIGHT;
    }

    @Override
    public final int getHeight() {
        return (int) Math.round(contentHeight() * visibility.get());
    }

    /** Called by the parent before layout so height is current this frame. */
    public void updateVisibility() {
        visibility.animateTo(visibleNow() ? 1 : 0);
    }

    /**
     * ClickGUI search override: {@code null} = normal visibility ({@link Setting#isVisible()}), otherwise this rule
     * decides (e.g. a matching child shows even while its group toggle is closed).
     */
    public void setSearchVisibility(BooleanSupplier rule) {
        this.searchVisibility = rule;
    }

    private boolean visibleNow() {
        return searchVisibility != null ? searchVisibility.getAsBoolean() : setting.isVisible();
    }

    /** Show/hide progress (0 = hidden, 1 = fully shown), e.g. to fade decorations drawn by the parent. */
    public double shown() {
        return visibility.get();
    }

    /** Only fully visible settings take input. */
    protected boolean isActive() {
        return visibleNow() && visibility.get() > 0.9;
    }

    @Override
    public final void render(Render2D r, int mouseX, int mouseY, float delta) {
        if (getHeight() <= 0) return;
        Theme p = ctx.palette();
        boolean rowHovered = isActive() && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + rowHeight();
        hover.animateTo(rowHovered ? 1 : 0);
        double h = hover.get();
        if (h > 0.01) r.rect(x, y, width, rowHeight(), ColorUtil.fade(p.surfaceHover(), h * 0.6));
        if (rowHovered) ctx.requestTooltip(setting, setting.getDescription());

        r.pushAlpha(visibility.get());
        renderContent(r, mouseX, mouseY, delta, p);
        r.popAlpha();
    }

    @Override
    public final boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isActive() || !isHovered(mouseX, mouseY)) return false;
        return onClick(mouseX, mouseY, button);
    }

    @Override
    public boolean isHovered(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + getHeight();
    }

    /** Click inside this component's bounds while active. */
    protected abstract boolean onClick(double mouseX, double mouseY, int button);

    public S getSetting() {
        return setting;
    }

    // ------------------------------------------------------------------ open state (remembered in GuiLayout)

    /** True for controls that open in place (dropdowns, colour pickers). */
    public boolean isExpandable() {
        return false;
    }

    public boolean isExpanded() {
        return false;
    }

    /** Opens/closes the control. {@code instant} skips the animation (used when restoring the layout). */
    public void setExpanded(boolean expanded, boolean instant) {}

    // ------------------------------------------------------------------ helpers for subclasses

    protected int textColor(Theme p) {
        return ColorUtil.lerp(p.textMuted(), p.text(), 0.55 + hover.get() * 0.45);
    }

    protected int labelY() {
        return y + Layout.textY(rowHeight());
    }

    protected int left() {
        return x + Layout.PAD;
    }

    protected int right() {
        return x + width - Layout.PAD;
    }
}
