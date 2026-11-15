package dev.telemetric.ui.clickgui.setting;

import dev.telemetric.setting.impl.GroupToggleSetting;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.clickgui.Layout;
import dev.telemetric.ui.framework.GuiContext;
import dev.telemetric.ui.framework.MouseButton;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Icons;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

/**
 * Toggle row of a {@link GroupToggleSetting}: left-click toggles, right-click opens/closes its sub-settings
 * (chevron left of the switch). The children are laid out by the module accordion, indented.
 */
public class GroupToggleComponent extends BooleanComponent {

    private final GroupToggleSetting group;
    private final Animation open;

    public GroupToggleComponent(GuiContext ctx, GroupToggleSetting setting) {
        super(ctx, setting);
        this.group = setting;
        this.open = new Animation(Durations.DROPDOWN, Easing.OUT_CUBIC, setting.isOpen() ? 1 : 0);
    }

    @Override
    protected int labelReserve() {
        return 10;
    }

    @Override
    protected void renderContent(Render2D r, int mouseX, int mouseY, float delta, Theme p) {
        super.renderContent(r, mouseX, mouseY, delta, p);
        open.animateTo(group.isOpen() ? 1 : 0);
        double t = open.get();
        int cx = right() - TRACK_W - 7;
        Icons.chevron(r, cx, y + Layout.SETTING_HEIGHT / 2, t,
                ColorUtil.lerp(p.textDisabled(), p.textMuted(), Math.max(hover.get(), t)));
    }

    @Override
    protected boolean onClick(double mouseX, double mouseY, int button) {
        if (button == MouseButton.RIGHT) {
            group.setOpen(!group.isOpen());
            return true;
        }
        return super.onClick(mouseX, mouseY, button);
    }

    @Override
    public boolean isExpandable() {
        return true;
    }

    @Override
    public boolean isExpanded() {
        return group.isOpen();
    }

    @Override
    public void setExpanded(boolean expanded, boolean instant) {
        group.setOpen(expanded);
        if (instant) open.snapTo(expanded ? 1 : 0);
    }
}
