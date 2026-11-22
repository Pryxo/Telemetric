package dev.telemetric.ui.clickgui;

import dev.telemetric.config.GuiLayout;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.framework.GuiContext;
import dev.telemetric.ui.framework.MouseButton;
import dev.telemetric.ui.framework.UIComponent;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Icons;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * A draggable, collapsible, scrollable panel for one {@link Category}.
 * On the header, left-drag moves it and right-click collapses or expands it (accordion). The wheel scrolls tall panels.
 * The position and collapsed state live in {@link GuiLayout.PanelState}, so they persist.
 */
public class Panel extends UIComponent {

    private final Category category;
    private final GuiLayout.PanelState state;
    private final List<ModuleComponent> modules = new ArrayList<>();
    /** The modules listed right now (all of them, unless the ClickGUI search is filtering). */
    private final List<ModuleComponent> shown = new ArrayList<>();
    /** Fades the whole panel out while a search has no match in it. */
    private final Animation searchFade = new Animation(Durations.STANDARD, Easing.OUT_CUBIC, 1);
    private boolean searching;
    private double scrollBeforeSearch;

    private final Animation expand;
    private final Animation appear = new Animation(Durations.SCREEN, Easing.OUT_CUBIC, 0);
    private final Animation headerHover = new Animation(Durations.HOVER, Easing.OUT_CUBIC);
    private final Animation scroll = new Animation(Durations.STANDARD, Easing.OUT_CUBIC);
    private final Animation scrollbar = new Animation(Durations.HOVER, Easing.OUT_CUBIC);

    private double scrollTarget;
    private int maxScroll;
    private int bodyY, bodyH;

    private boolean dragging;
    private double dragOffX, dragOffY;

    public Panel(GuiContext ctx, Category category, List<Module> categoryModules, GuiLayout.PanelState state) {
        super(ctx);
        this.category = category;
        this.state = state;
        this.expand = new Animation(Durations.ACCORDION, Easing.IN_OUT_CUBIC, state.collapsed ? 0 : 1);
        for (Module module : categoryModules) {
            if (!module.isVisibleInGui()) continue;
            String name = module.getName();
            modules.add(new ModuleComponent(ctx, module, state.isModuleOpen(name), state.openSettingsOf(name)));
        }
        shown.addAll(modules);
        // restore the scroll; it gets clamped against the real content height on the first frame
        this.scrollTarget = state.scroll;
        this.scroll.snapTo(state.scroll);
    }

    /**
     * Applies the ClickGUI search. Only matching modules stay (see {@link ModuleComponent#applySearch}), and a panel with
     * no match fades out. The scroll offset from before the search comes back once it's cleared.
     */
    public void setSearch(String query) {
        boolean was = searching;
        searching = !query.isBlank();
        if (searching && !was) scrollBeforeSearch = scrollTarget;
        shown.clear();
        for (ModuleComponent m : modules) if (m.applySearch(query)) shown.add(m);
        if (searching) scrollTarget = 0;
        else if (was) scrollTarget = scrollBeforeSearch;
        searchFade.animateTo(hiddenBySearch() ? 0 : 1);
    }

    private boolean hiddenBySearch() {
        return searching && shown.isEmpty();
    }

    /** Writes the open accordions and dropdowns and the scroll offset back into the persistent {@link GuiLayout.PanelState}. */
    public void saveState() {
        state.scroll = (int) Math.round(searching ? scrollBeforeSearch : scrollTarget);
        for (ModuleComponent m : modules) {
            state.setModuleState(m.getModule().getName(), m.isExpanded(), m.openSettingNames());
        }
    }

    /** Starts the open animation after {@code delayMs} (staggered per panel). */
    public void playAppear(int delayMs) {
        appear.snapTo(0).animateTo(1, delayMs);
    }

    public boolean isEmpty() {
        return modules.isEmpty();
    }

    public GuiLayout.PanelState getState() {
        return state;
    }

    public String getId() {
        return category.getName();
    }

    // ================================================================== render

    /** Renders the panel. {@code screenW/H} are in ClickGUI units. */
    public void render(Render2D r, int mouseX, int mouseY, float delta, int screenW, int screenH) {
        double fade = searchFade.get();
        if (fade <= 0.001) return;
        Theme fixed = ctx.themes().fixedPalette(category);
        Theme previous = ctx.overridePalette(fixed); // null means follow the theme
        r.pushAlpha(fade);
        try {
            renderPanel(r, mouseX, mouseY, delta, screenW, screenH);
        } finally {
            r.popAlpha();
            ctx.overridePalette(previous);
        }
    }

    private void renderPanel(Render2D r, int mouseX, int mouseY, float delta, int screenW, int screenH) {
        Theme p = ctx.palette();
        int radius = ctx.radius();
        width = ctx.ui().panelWidth.getInt();

        if (dragging) {
            state.x = (int) Math.round(mouseX - dragOffX);
            state.y = (int) Math.round(mouseY - dragOffY);
        }
        state.x = Math.clamp(state.x, -width + 24, Math.max(0, screenW - 24));
        state.y = Math.clamp(state.y, 0, Math.max(0, screenH - Layout.HEADER_HEIGHT));

        double a = appear.get();
        int slide = (int) Math.round((1 - a) * 12);
        x = state.x;
        y = state.y + slide;

        // ---- measure
        expand.animateTo(state.collapsed ? 0 : 1);
        double ex = expand.get();
        int content = 0;
        for (ModuleComponent m : shown) content += m.getHeight();
        int maxBody = Math.max(Layout.MODULE_HEIGHT * 2,
                (int) (screenH * ctx.ui().maxPanelHeight.get() / 100.0) - Layout.HEADER_HEIGHT - Layout.PANEL_FOOTER - state.y);
        int visibleBody = Math.min(content, maxBody);
        maxScroll = Math.max(0, content - visibleBody);
        scrollTarget = Math.clamp(scrollTarget, 0, maxScroll);
        scroll.animateTo(scrollTarget);

        bodyY = y + Layout.HEADER_HEIGHT;
        bodyH = (int) Math.round(visibleBody * ex);
        int footer = (int) Math.round(Layout.PANEL_FOOTER * ex);
        height = Layout.HEADER_HEIGHT + bodyH + footer;

        r.pushAlpha(a);

        // ---- frame
        if (ctx.themes().shadows()) r.shadow(x, y, width, height, radius, p.shadow(), Layout.SHADOW_SIZE);
        drawFrame(r, radius, p.panel());

        boolean overHeader = isOverHeader(mouseX, mouseY);
        headerHover.animateTo(overHeader || dragging ? 1 : 0);
        int headerColor = ColorUtil.lerp(p.header(), ColorUtil.shade(p.header(), 0.06), headerHover.get());
        r.roundedRect(x, y, width, Layout.HEADER_HEIGHT, radius, headerColor,
                ex < 0.02 ? Render2D.CORNERS_ALL : Render2D.CORNERS_TOP);

        // accent line under the header, which grows with the expand animation
        if (ctx.themes().headerGradient() && ex > 0.01) {
            int lineW = (int) Math.round(width * ex);
            r.gradientH(x + (width - lineW) / 2, y + Layout.HEADER_HEIGHT - 1, lineW, 1,
                    ColorUtil.fade(p.accent(), 0.85), ColorUtil.fade(p.accentAlt(), 0.85));
        }

        // title, count and chevron
        int ty = y + Layout.textY(Layout.HEADER_HEIGHT);
        r.text(category.getName(), x + Layout.PAD, ty, p.text());
        String count = String.valueOf(shown.size());
        r.textRight(count, x + width - Layout.PAD - 10, ty, p.textDisabled());
        Icons.chevron(r, x + width - Layout.PAD - 3, y + Layout.HEADER_HEIGHT / 2, ex, p.textMuted());

        // ---- body
        if (bodyH > 0) {
            r.pushClip(x, bodyY, width, bodyH);
            boolean mouseInBody = ctx.hasCapture() || (mouseX >= x && mouseX < x + width && mouseY >= bodyY && mouseY < bodyY + bodyH);
            int childMouseY = mouseInBody ? mouseY : Integer.MIN_VALUE / 2;
            int cy = bodyY - (int) Math.round(scroll.get());
            for (ModuleComponent m : shown) {
                int mh = m.getHeight();
                m.setBounds(x, cy, width, mh);
                if (cy + mh > bodyY && cy < bodyY + bodyH) m.render(r, mouseX, childMouseY, delta);
                cy += mh;
            }
            r.popClip();

            // scrollbar
            if (maxScroll > 0) {
                boolean hot = mouseInBody && isHovered(mouseX, mouseY);
                scrollbar.animateTo(hot ? 1 : 0.35);
                int trackH = bodyH - 4;
                int thumbH = Math.max(10, (int) (trackH * (visibleBody / (double) content)));
                int thumbY = bodyY + 2 + (int) Math.round((trackH - thumbH) * (scroll.get() / maxScroll));
                r.pill(x + width - 3, thumbY, 2, thumbH, ColorUtil.fade(p.accent(), scrollbar.get()));
            }
        }

        r.popAlpha();
    }

    /**
     * The panel background, skipping open module settings areas (they draw their own translucent background).
     * It's drawn as horizontal bands of the full rounded rect so the corners stay round.
     */
    private void drawFrame(Render2D r, int radius, int color) {
        int top = y;
        int bodyEnd = bodyY + bodyH;
        int cy = bodyY - (int) Math.round(scroll.get());
        for (ModuleComponent m : shown) {
            int holeY = Math.max(cy + Layout.MODULE_HEIGHT, bodyY);
            int holeEnd = Math.min(cy + m.getHeight(), bodyEnd);
            cy += m.getHeight();
            if (holeEnd <= holeY) continue;
            band(r, top, holeY, radius, color);
            top = holeEnd;
        }
        band(r, top, y + height, radius, color);
    }

    private void band(Render2D r, int from, int to, int radius, int color) {
        if (to <= from) return;
        r.pushClip(x, from, width, to - from);
        r.roundedRect(x, y, width, height, radius, color);
        r.popClip();
    }

    @Override
    public void render(Render2D r, int mouseX, int mouseY, float delta) {
        throw new UnsupportedOperationException("Use render(r, mx, my, delta, screenW, screenH)");
    }

    // ================================================================== input

    @Override
    public boolean isHovered(double mouseX, double mouseY) {
        return !hiddenBySearch() && super.isHovered(mouseX, mouseY);
    }

    private boolean isOverHeader(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + Layout.HEADER_HEIGHT;
    }

    private boolean isOverBody(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= bodyY && mouseY < bodyY + bodyH;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (hiddenBySearch()) return false;
        if (isOverHeader(mouseX, mouseY)) {
            if (button == MouseButton.LEFT) {
                dragging = true;
                dragOffX = mouseX - state.x;
                dragOffY = mouseY - state.y;
                return true;
            }
            if (button == MouseButton.RIGHT) {
                state.collapsed = !state.collapsed;
                return true;
            }
            return true;
        }
        if (isOverBody(mouseX, mouseY)) {
            if (expand.get() > 0.95) {
                for (ModuleComponent m : shown) {
                    if (m.isHovered(mouseX, mouseY) && m.mouseClicked(mouseX, mouseY, button)) return true;
                }
            }
            return true; // swallow clicks on the panel body
        }
        return isHovered(mouseX, mouseY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = false;
        if (dragging && button == MouseButton.LEFT) {
            dragging = false;
            state.placed = true;
            handled = true;
        }
        for (ModuleComponent m : modules) handled |= m.mouseReleased(mouseX, mouseY, button);
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!isOverBody(mouseX, mouseY)) return false;
        for (ModuleComponent m : modules) if (m.mouseScrolled(mouseX, mouseY, amount)) return true; // e.g. a long dropdown
        if (maxScroll <= 0) return false;
        scrollTarget = Math.clamp(scrollTarget - amount * Layout.SCROLL_STEP, 0, maxScroll);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        for (ModuleComponent m : modules) if (m.keyPressed(key, scancode, modifiers)) return true;
        return false;
    }

    @Override
    public boolean charTyped(int codepoint, int modifiers) {
        for (ModuleComponent m : modules) if (m.charTyped(codepoint, modifiers)) return true;
        return false;
    }

    @Override
    public void onClose() {
        if (dragging) state.placed = true;
        dragging = false;
        saveState(); // before the children reset their transient state
        modules.forEach(ModuleComponent::onClose);
    }
}
