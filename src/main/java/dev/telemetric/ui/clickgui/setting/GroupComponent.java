package dev.telemetric.ui.clickgui.setting;

import dev.telemetric.setting.impl.GroupSetting;
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

/** The dropdown row of a {@link GroupSetting}: a label and a chevron. Left- or right-click opens or closes its sub-settings. */
public class GroupComponent extends SettingComponent<GroupSetting> {

    private final Animation open;

    public GroupComponent(GuiContext ctx, GroupSetting setting) {
        super(ctx, setting);
        this.open = new Animation(Durations.DROPDOWN, Easing.OUT_CUBIC, setting.isOpen() ? 1 : 0);
    }

    @Override
    protected int contentHeight() {
        return Layout.SETTING_HEIGHT;
    }

    @Override
    protected void renderContent(Render2D r, int mouseX, int mouseY, float delta, Theme p) {
        open.animateTo(setting.isOpen() ? 1 : 0);
        double t = open.get();
        r.text(r.trim(setting.getLabel(), width - Layout.PAD * 3 - 10), left(), labelY(), textColor(p));
        Icons.chevron(r, right() - 4, y + Layout.SETTING_HEIGHT / 2, t,
                ColorUtil.lerp(p.textDisabled(), p.textMuted(), Math.max(hover.get(), t)));
    }

    @Override
    protected boolean onClick(double mouseX, double mouseY, int button) {
        if (button != MouseButton.LEFT && button != MouseButton.RIGHT) return false;
        setting.setOpen(!setting.isOpen());
        return true;
    }

    @Override
    public boolean isExpandable() {
        return true;
    }

    @Override
    public boolean isExpanded() {
        return setting.isOpen();
    }

    @Override
    public void setExpanded(boolean expanded, boolean instant) {
        setting.setOpen(expanded);
        if (instant) open.snapTo(expanded ? 1 : 0);
    }
}
