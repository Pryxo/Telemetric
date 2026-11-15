package dev.telemetric.ui.clickgui.setting;

import dev.telemetric.setting.impl.MultiChoiceSetting;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Inline multi-select dropdown (accordion like {@link ChoiceComponent}) with a check box per option.
 * Left-click header: open/close. Right-click header: clear all. Left-click option: tick/untick
 * (the list stays open so several entries can be ticked in a row).
 */
public class MultiChoiceComponent extends SettingComponent<MultiChoiceSetting> {

    private static final int BOX = 7;

    private final Animation open = new Animation(Durations.DROPDOWN, Easing.OUT_CUBIC);
    private final Animation arrow = new Animation(Durations.DROPDOWN, Easing.OUT_CUBIC);
    private final List<Animation> optionHover = new ArrayList<>();
    private final List<Animation> optionCheck = new ArrayList<>();
    private boolean expanded;

    public MultiChoiceComponent(GuiContext ctx, MultiChoiceSetting setting) {
        super(ctx, setting);
    }

    @Override
    protected int contentHeight() {
        int list = setting.getOptions().size() * Layout.OPTION_HEIGHT + 2;
        return Layout.SETTING_HEIGHT + (int) Math.round(list * open.get());
    }

    @Override
    protected void renderContent(Render2D r, int mouseX, int mouseY, float delta, Theme p) {
        open.animateTo(expanded ? 1 : 0);
        arrow.animateTo(expanded ? 1 : 0);
        List<String> options = setting.getOptions();

        // header
        String summary = setting.summary();
        int maxValueW = width / 2;
        summary = r.trim(summary, maxValueW);
        int valueW = r.textWidth(summary);
        r.text(r.trim(setting.getLabel(), width - valueW - Layout.PAD * 3 - 6), left(), labelY(), textColor(p));
        r.textRight(summary, right() - 7, labelY(), ColorUtil.lerp(p.textMuted(), p.accent(), arrow.get()));
        Icons.chevron(r, right() - 4, y + Layout.SETTING_HEIGHT / 2, arrow.get(), p.textMuted());

        double o = open.get();
        if (o <= 0.001) return;
        while (optionHover.size() < options.size()) optionHover.add(new Animation(Durations.HOVER, Easing.OUT_CUBIC));
        while (optionCheck.size() < options.size()) optionCheck.add(new Animation(Durations.MICRO, Easing.OUT_CUBIC));

        int listY = y + Layout.SETTING_HEIGHT;
        int listH = getHeight() - Layout.SETTING_HEIGHT;
        r.pushClip(x, listY, width, listH);
        r.pushAlpha(o);
        r.rect(x + Layout.PAD - 2, listY, width - (Layout.PAD - 2) * 2, options.size() * Layout.OPTION_HEIGHT + 1,
                ColorUtil.fade(p.settingsBg(), 0.9));
        for (int i = 0; i < options.size(); i++) {
            String option = options.get(i);
            int oy = listY + i * Layout.OPTION_HEIGHT;
            boolean selected = setting.contains(option);
            boolean hovered = expanded && mouseX >= x && mouseX < x + width && mouseY >= oy && mouseY < oy + Layout.OPTION_HEIGHT
                    && mouseY < listY + listH;
            Animation h = optionHover.get(i);
            h.animateTo(hovered ? 1 : 0);
            Animation c = optionCheck.get(i);
            c.animateTo(selected ? 1 : 0);
            if (h.get() > 0.01) r.rect(x + Layout.PAD - 2, oy, width - (Layout.PAD - 2) * 2, Layout.OPTION_HEIGHT,
                    ColorUtil.fade(p.surfaceHover(), h.get()));

            // check box: outlined square that fills with the accent when ticked
            int bx = left() + 2;
            int by = oy + (Layout.OPTION_HEIGHT - BOX) / 2;
            r.outline(bx, by, BOX, BOX, ColorUtil.lerp(p.border(), p.accent(), c.get()));
            if (c.get() > 0.01) {
                int inset = (int) Math.round((1 - c.get()) * 3) + 2;
                int size = BOX - inset * 2;
                if (size > 0) r.rect(bx + inset, by + inset, size, size, ColorUtil.fade(p.accent(), c.get()));
            }

            int color = selected ? p.accent() : ColorUtil.lerp(p.textMuted(), p.text(), h.get());
            int textX = bx + BOX + 4 + (int) Math.round(h.get() * 2);
            r.text(r.trim(option, right() - textX - 2), textX, oy + Layout.textY(Layout.OPTION_HEIGHT), color);
        }
        r.popAlpha();
        r.popClip();
    }

    @Override
    protected boolean onClick(double mouseX, double mouseY, int button) {
        if (mouseY < y + Layout.SETTING_HEIGHT) {
            if (button == MouseButton.LEFT) expanded = !expanded;
            else if (button == MouseButton.RIGHT) setting.clear();
            else return false;
            return true;
        }
        if (!expanded || button != MouseButton.LEFT) return expanded;
        int index = (int) ((mouseY - y - Layout.SETTING_HEIGHT) / Layout.OPTION_HEIGHT);
        List<String> options = setting.getOptions();
        if (index >= 0 && index < options.size()) setting.toggle(options.get(index));
        return true;
    }

    @Override
    public boolean isExpandable() {
        return true;
    }

    @Override
    public boolean isExpanded() {
        return expanded;
    }

    @Override
    public void setExpanded(boolean expanded, boolean instant) {
        this.expanded = expanded;
        if (instant) {
            double target = expanded ? 1 : 0;
            open.snapTo(target);
            arrow.snapTo(target);
        }
    }

    @Override
    public void onClose() {
        expanded = false;
    }
}
