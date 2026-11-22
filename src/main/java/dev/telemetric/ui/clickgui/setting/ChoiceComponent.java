package dev.telemetric.ui.clickgui.setting;

import dev.telemetric.setting.impl.ChoiceSetting;
import dev.telemetric.ui.clickgui.Layout;
import dev.telemetric.ui.framework.GuiContext;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * A single-choice inline dropdown ({@link DropdownComponent}). Left-click the header to open or close it, right-click
 * the header to cycle to the next option without opening it. Picking an option closes the list.
 */
public class ChoiceComponent<T> extends DropdownComponent<ChoiceSetting<T>> {

    public ChoiceComponent(GuiContext ctx, ChoiceSetting<T> setting) {
        super(ctx, setting);
    }

    @Override
    protected List<String> optionLabels() {
        List<T> options = setting.getOptions();
        List<String> labels = new ArrayList<>(options.size());
        for (T option : options) labels.add(setting.display(option));
        return labels;
    }

    @Override
    protected boolean isSelected(int option) {
        return setting.getOptions().get(option).equals(setting.get());
    }

    @Override
    protected void choose(int option) {
        setting.set(setting.getOptions().get(option));
        setOpen(false);
    }

    @Override
    protected void onHeaderRightClick() {
        setting.cycle(1);
    }

    @Override
    protected String headerValue(Render2D r) {
        return setting.display(setting.get());
    }

    @Override
    protected void renderOption(Render2D r, int option, String label, int rowY, int boxX, double hover,
                                boolean selected, Theme p) {
        if (selected) r.rect(boxX, rowY + 2, 2, Layout.OPTION_HEIGHT - 4, p.accent());
        r.text(r.trim(label, width - Layout.PAD * 3 - 3), left() + 4 + (int) Math.round(hover * 2),
                rowY + Layout.textY(Layout.OPTION_HEIGHT), optionColor(selected, hover, p));
    }
}
