package dev.telemetric.ui.clickgui.setting;

import dev.telemetric.setting.impl.MultiChoiceSetting;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.clickgui.Layout;
import dev.telemetric.ui.framework.GuiContext;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * A multi-select inline dropdown ({@link DropdownComponent}) with a check box per option.
 * Left-click the header to open or close it, right-click it to clear everything. Left-clicking an option (or Enter on
 * the first search match) ticks or unticks it, and the list stays open so you can tick several entries in a row.
 */
public class MultiChoiceComponent extends DropdownComponent<MultiChoiceSetting> {

    private static final int BOX = 7;

    private final List<Animation> optionCheck = new ArrayList<>();

    public MultiChoiceComponent(GuiContext ctx, MultiChoiceSetting setting) {
        super(ctx, setting);
    }

    @Override
    protected List<String> optionLabels() {
        return setting.getOptions();
    }

    @Override
    protected boolean isSelected(int option) {
        return setting.contains(setting.getOptions().get(option));
    }

    @Override
    protected void choose(int option) {
        setting.toggle(setting.getOptions().get(option));
    }

    @Override
    protected void onHeaderRightClick() {
        setting.clear();
    }

    @Override
    protected String headerValue(Render2D r) {
        return r.trim(setting.summary(), width / 2);
    }

    @Override
    protected void renderOption(Render2D r, int option, String label, int rowY, int boxX, double hover,
                                boolean selected, Theme p) {
        while (optionCheck.size() <= option) optionCheck.add(new Animation(Durations.MICRO, Easing.OUT_CUBIC));
        Animation c = optionCheck.get(option);
        c.animateTo(selected ? 1 : 0);

        // check box: an outlined square that fills with the accent when ticked
        int bx = left() + 2;
        int by = rowY + (Layout.OPTION_HEIGHT - BOX) / 2;
        r.outline(bx, by, BOX, BOX, ColorUtil.lerp(p.border(), p.accent(), c.get()));
        if (c.get() > 0.01) {
            int inset = (int) Math.round((1 - c.get()) * 3) + 2;
            int size = BOX - inset * 2;
            if (size > 0) r.rect(bx + inset, by + inset, size, size, ColorUtil.fade(p.accent(), c.get()));
        }

        int textX = bx + BOX + 4 + (int) Math.round(hover * 2);
        r.text(r.trim(label, right() - textX - 4), textX, rowY + Layout.textY(Layout.OPTION_HEIGHT), optionColor(selected, hover, p));
    }
}
