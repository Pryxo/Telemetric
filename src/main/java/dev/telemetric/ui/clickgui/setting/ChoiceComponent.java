package dev.telemetric.ui.clickgui.setting;

import dev.telemetric.setting.impl.ChoiceSetting;
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
 * Inline dropdown (expands in place like an accordion, so it never overlaps other panels).
 * Left-click header: open/close. Right-click header: cycle to next option without opening.
 * <p>
 * Searchable settings ({@code .searchable()}) get a search field on top of the list (focused on open; Enter picks the
 * first match, Esc clears / unfocuses) and show at most {@link DropdownSearch#MAX_ROWS} rows (scroll wheel for more).
 */
public class ChoiceComponent<T> extends SettingComponent<ChoiceSetting<T>> {

    private final Animation open = new Animation(Durations.DROPDOWN, Easing.OUT_CUBIC);
    private final Animation arrow = new Animation(Durations.DROPDOWN, Easing.OUT_CUBIC);
    private final List<Animation> optionHover = new ArrayList<>();
    private final DropdownSearch search = new DropdownSearch();
    private boolean expanded;

    public ChoiceComponent(GuiContext ctx, ChoiceSetting<T> setting) {
        super(ctx, setting);
    }

    private boolean searchable() {
        return setting.isSearchable();
    }

    private List<String> labels(List<T> options) {
        List<String> labels = new ArrayList<>(options.size());
        for (T option : options) labels.add(setting.display(option));
        return labels;
    }

    /** Option indices currently listed (filtered for searchable settings). */
    private List<Integer> matches(List<T> options) {
        if (searchable()) return search.matches(labels(options));
        List<Integer> all = new ArrayList<>(options.size());
        for (int i = 0; i < options.size(); i++) all.add(i);
        return all;
    }

    /** Top of the first option row. */
    private int rowsY() {
        return y + Layout.SETTING_HEIGHT + (searchable() ? DropdownSearch.FIELD_H : 0);
    }

    @Override
    protected int contentHeight() {
        int list = searchable()
                ? search.listHeight(matches(setting.getOptions()).size())
                : setting.getOptions().size() * Layout.OPTION_HEIGHT + 2;
        return Layout.SETTING_HEIGHT + (int) Math.round(list * open.get());
    }

    @Override
    protected void renderContent(Render2D r, int mouseX, int mouseY, float delta, Theme p) {
        open.animateTo(expanded ? 1 : 0);
        arrow.animateTo(expanded ? 1 : 0);
        List<T> options = setting.getOptions();

        // header
        String current = setting.display(setting.get());
        int valueW = r.textWidth(current);
        r.text(r.trim(setting.getLabel(), width - valueW - Layout.PAD * 3 - 6), left(), labelY(), textColor(p));
        r.textRight(current, right() - 7, labelY(), ColorUtil.lerp(p.textMuted(), p.accent(), arrow.get()));
        Icons.chevron(r, right() - 4, y + Layout.SETTING_HEIGHT / 2, arrow.get(), p.textMuted());

        // options
        double o = open.get();
        if (o <= 0.001) return;
        while (optionHover.size() < options.size()) optionHover.add(new Animation(Durations.HOVER, Easing.OUT_CUBIC));

        List<Integer> matches = matches(options);
        int first = searchable() ? search.first(matches.size()) : 0;
        int rows = searchable() ? search.visibleRows(matches.size()) : matches.size();
        int listY = y + Layout.SETTING_HEIGHT;
        int listH = getHeight() - Layout.SETTING_HEIGHT;
        int rowsY = rowsY();
        int boxX = x + Layout.PAD - 2, boxW = width - (Layout.PAD - 2) * 2;
        r.pushClip(x, listY, width, listH);
        r.pushAlpha(o);
        r.rect(boxX, listY, boxW, rowsY - listY + rows * Layout.OPTION_HEIGHT + 1, ColorUtil.fade(p.settingsBg(), 0.9));
        if (searchable()) {
            search.renderField(r, left(), listY, right() - left(), ctx.isFocused(this), p);
            if (matches.isEmpty()) r.text("No matches", left() + 4, rowsY + Layout.textY(Layout.OPTION_HEIGHT), p.textDisabled());
        }
        int shownEnd = Math.min(matches.size(), first + rows);
        for (int k = first; k < shownEnd; k++) {
            int i = matches.get(k);
            T option = options.get(i);
            int oy = rowsY + (k - first) * Layout.OPTION_HEIGHT;
            boolean selected = option.equals(setting.get());
            boolean hovered = expanded && mouseX >= x && mouseX < x + width && mouseY >= oy && mouseY < oy + Layout.OPTION_HEIGHT
                    && mouseY < listY + listH;
            Animation h = optionHover.get(i);
            h.animateTo(hovered ? 1 : 0);
            if (h.get() > 0.01) r.rect(boxX, oy, boxW, Layout.OPTION_HEIGHT, ColorUtil.fade(p.surfaceHover(), h.get()));
            if (selected) r.rect(boxX, oy + 2, 2, Layout.OPTION_HEIGHT - 4, p.accent());
            int color = selected ? p.accent() : ColorUtil.lerp(p.textMuted(), p.text(), h.get());
            r.text(r.trim(setting.display(option), width - Layout.PAD * 3 - 3), left() + 4 + (int) Math.round(h.get() * 2),
                    oy + Layout.textY(Layout.OPTION_HEIGHT), color);
        }
        if (searchable()) search.renderScrollbar(r, boxX + boxW - 2, rowsY, matches.size(), p);
        r.popAlpha();
        r.popClip();
    }

    @Override
    protected boolean onClick(double mouseX, double mouseY, int button) {
        if (mouseY < y + Layout.SETTING_HEIGHT) {
            if (button == MouseButton.LEFT) setOpen(!expanded);
            else if (button == MouseButton.RIGHT) setting.cycle(1);
            else return false;
            return true;
        }
        if (!expanded || button != MouseButton.LEFT) return expanded;
        if (searchable() && mouseY < rowsY()) {
            focusSearch();
            return true;
        }
        List<T> options = setting.getOptions();
        List<Integer> matches = matches(options);
        int first = searchable() ? search.first(matches.size()) : 0;
        int k = first + (int) ((mouseY - rowsY()) / Layout.OPTION_HEIGHT);
        if (k >= first && k < matches.size()) pick(options.get(matches.get(k)));
        return true;
    }

    private void pick(T option) {
        setting.set(option);
        setOpen(false);
    }

    private void setOpen(boolean open) {
        expanded = open;
        if (!searchable()) return;
        if (open) focusSearch();
        else closeSearch();
    }

    private void focusSearch() {
        ctx.focus(this);
        search.restartCaret();
    }

    private void closeSearch() {
        search.reset();
        ctx.releaseFocus(this);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!searchable() || !expanded || !isActive() || !isHovered(mouseX, mouseY) || mouseY < rowsY()) return false;
        return search.scroll(amount, matches(setting.getOptions()).size());
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        if (!searchable() || !ctx.isFocused(this)) return false;
        switch (search.keyPressed(key, modifiers)) {
            case ENTER -> {
                List<T> options = setting.getOptions();
                List<Integer> matches = matches(options);
                if (!matches.isEmpty()) pick(options.get(matches.getFirst()));
                else ctx.clearFocus();
            }
            case ESCAPE -> ctx.clearFocus();
            case EDITED -> { }
        }
        return true;
    }

    @Override
    public boolean charTyped(int codepoint, int modifiers) {
        if (!searchable() || !ctx.isFocused(this)) return false;
        search.charTyped(codepoint);
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
        if (!expanded && searchable()) closeSearch();
        if (instant) {
            double target = expanded ? 1 : 0;
            open.snapTo(target);
            arrow.snapTo(target);
        }
    }

    @Override
    public void onClose() {
        expanded = false;
        if (searchable()) closeSearch();
    }
}
