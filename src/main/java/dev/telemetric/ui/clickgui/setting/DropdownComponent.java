package dev.telemetric.ui.clickgui.setting;

import dev.telemetric.setting.Setting;
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
 * The shared inline dropdown behind {@link ChoiceComponent} and {@link MultiChoiceComponent}. It expands in place
 * like an accordion, so it never overlaps other panels: a header row (label, value, chevron) and a list of option rows
 * with a hover highlight. Left-click on the header opens or closes the list.
 * <p>
 * Searchable settings ({@code .searchable()}) get a search field on top of the list (focused on open; Enter
 * {@linkplain #choose chooses} the first match, Esc clears or unfocuses) and show at most
 * {@link DropdownSearch#MAX_ROWS} rows (scroll wheel for more).
 * <p>
 * Subclasses provide the option labels, which options are selected, what a click does and how one row's marker and text
 * look.
 */
public abstract class DropdownComponent<S extends Setting<?>> extends SettingComponent<S> {

    private final Animation open = new Animation(Durations.DROPDOWN, Easing.OUT_CUBIC);
    private final List<Animation> optionHover = new ArrayList<>();
    private final DropdownSearch search = new DropdownSearch();
    protected boolean expanded;

    protected DropdownComponent(GuiContext ctx, S setting) {
        super(ctx, setting);
    }

    /** The option labels right now (options can be dynamic, so this is asked again each time). */
    protected abstract List<String> optionLabels();

    protected abstract boolean isSelected(int option);

    /** A left-click on option {@code option}, or Enter on the first search match. */
    protected abstract void choose(int option);

    /** A right-click on the header. */
    protected abstract void onHeaderRightClick();

    /** The value text on the right of the header, already trimmed if needed. */
    protected abstract String headerValue(Render2D r);

    /**
     * Draws one option row's marker and text (the hover highlight is already drawn). {@code hover} is the row's hover
     * animation value 0..1; {@link #optionColor} gives the usual text colour.
     */
    protected abstract void renderOption(Render2D r, int option, String label, int rowY, int boxX, double hover,
                                         boolean selected, Theme p);

    protected static int optionColor(boolean selected, double hover, Theme p) {
        return selected ? p.accent() : ColorUtil.lerp(p.textMuted(), p.text(), hover);
    }

    protected boolean searchable() {
        return setting.isSearchable();
    }

    /** The option indices listed right now (filtered for searchable settings). */
    private List<Integer> matches(List<String> labels) {
        if (searchable()) return search.matches(labels);
        List<Integer> all = new ArrayList<>(labels.size());
        for (int i = 0; i < labels.size(); i++) all.add(i);
        return all;
    }

    /** Top of the first option row. */
    private int rowsY() {
        return y + Layout.SETTING_HEIGHT + (searchable() ? DropdownSearch.FIELD_H : 0);
    }

    @Override
    protected int contentHeight() {
        List<String> labels = optionLabels();
        int list = searchable()
                ? search.listHeight(matches(labels).size())
                : labels.size() * Layout.OPTION_HEIGHT + 2;
        return Layout.SETTING_HEIGHT + (int) Math.round(list * open.get());
    }

    @Override
    protected void renderContent(Render2D r, int mouseX, int mouseY, float delta, Theme p) {
        open.animateTo(expanded ? 1 : 0);
        List<String> labels = optionLabels();

        // header
        String value = headerValue(r);
        int valueW = r.textWidth(value);
        r.text(r.trim(setting.getLabel(), width - valueW - Layout.PAD * 3 - 6), left(), labelY(), textColor(p));
        r.textRight(value, right() - 7, labelY(), ColorUtil.lerp(p.textMuted(), p.accent(), open.get()));
        Icons.chevron(r, right() - 4, y + Layout.SETTING_HEIGHT / 2, open.get(), p.textMuted());

        // options
        double o = open.get();
        if (o <= 0.001) return;
        while (optionHover.size() < labels.size()) optionHover.add(new Animation(Durations.HOVER, Easing.OUT_CUBIC));

        List<Integer> matches = matches(labels);
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
            int oy = rowsY + (k - first) * Layout.OPTION_HEIGHT;
            boolean hovered = expanded && mouseX >= x && mouseX < x + width && mouseY >= oy && mouseY < oy + Layout.OPTION_HEIGHT
                    && mouseY < listY + listH;
            Animation h = optionHover.get(i);
            h.animateTo(hovered ? 1 : 0);
            if (h.get() > 0.01) r.rect(boxX, oy, boxW, Layout.OPTION_HEIGHT, ColorUtil.fade(p.surfaceHover(), h.get()));
            renderOption(r, i, labels.get(i), oy, boxX, h.get(), isSelected(i), p);
        }
        if (searchable()) search.renderScrollbar(r, boxX + boxW - 2, rowsY, matches.size(), p);
        r.popAlpha();
        r.popClip();
    }

    @Override
    protected boolean onClick(double mouseX, double mouseY, int button) {
        if (mouseY < y + Layout.SETTING_HEIGHT) {
            if (button == MouseButton.LEFT) setOpen(!expanded);
            else if (button == MouseButton.RIGHT) onHeaderRightClick();
            else return false;
            return true;
        }
        if (!expanded || button != MouseButton.LEFT) return expanded;
        if (searchable() && mouseY < rowsY()) {
            focusSearch();
            return true;
        }
        List<Integer> matches = matches(optionLabels());
        int first = searchable() ? search.first(matches.size()) : 0;
        int k = first + (int) ((mouseY - rowsY()) / Layout.OPTION_HEIGHT);
        if (k >= first && k < matches.size()) choose(matches.get(k));
        return true;
    }

    protected void setOpen(boolean open) {
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
        return search.scroll(amount, matches(optionLabels()).size());
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        if (!searchable() || !ctx.isFocused(this)) return false;
        switch (search.keyPressed(key, modifiers)) {
            case ENTER -> {
                List<Integer> matches = matches(optionLabels());
                if (!matches.isEmpty()) choose(matches.getFirst());
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
        if (instant) open.snapTo(expanded ? 1 : 0);
    }

    @Override
    public void onClose() {
        setExpanded(false, false);
    }
}
