package dev.telemetric.ui.clickgui;

import dev.telemetric.input.InputUtil;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.framework.GuiContext;
import dev.telemetric.ui.framework.MouseButton;
import dev.telemetric.ui.framework.UIComponent;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.function.Consumer;

/**
 * ClickGUI search field, centred at the bottom of the screen. Only takes input after it is clicked. Every change calls the listener, which filters the panels (see {@link Panel#setSearch}).
 * Backspace deletes, Ctrl+Backspace / the × clears, Ctrl+V pastes, Enter keeps the filter and unfocuses,
 * Esc clears and unfocuses. Not persisted: every ClickGUI opens unfiltered.
 */
public class SearchBar extends UIComponent {

    private static final int WIDTH = 170, HEIGHT = 16, BOTTOM_MARGIN = 12, MAX_LENGTH = 48;
    private static final int CTRL_MASK = 0x0002;

    private final Consumer<String> onChange;
    private final Animation appear = new Animation(Durations.SCREEN, Easing.OUT_CUBIC, 0);
    private final Animation focus = new Animation(Durations.HOVER, Easing.OUT_CUBIC);
    private final Animation hover = new Animation(Durations.HOVER, Easing.OUT_CUBIC);
    private String query = "";
    private long caretTime;

    public SearchBar(GuiContext ctx, Consumer<String> onChange) {
        super(ctx);
        this.onChange = onChange;
    }

    /** Slides up with the panels' open sequence. */
    public void playAppear(int delayMs) {
        appear.snapTo(0).animateTo(1, delayMs);
    }

    /** Positions the bar bottom-centre for a {@code screenW × screenH} ClickGUI area, then renders it. */
    public void render(Render2D r, int mouseX, int mouseY, float delta, int screenW, int screenH) {
        double a = appear.get();
        setBounds((screenW - WIDTH) / 2, screenH - HEIGHT - BOTTOM_MARGIN + (int) Math.round((1 - a) * 10), WIDTH, HEIGHT);
        r.pushAlpha(a);
        render(r, mouseX, mouseY, delta);
        r.popAlpha();
    }

    @Override
    public void render(Render2D r, int mouseX, int mouseY, float delta) {
        Theme p = ctx.palette();
        boolean focused = ctx.isFocused(this);
        focus.animateTo(focused ? 1 : 0);
        hover.animateTo(isHovered(mouseX, mouseY) ? 1 : 0);
        double f = focus.get(), h = hover.get();
        int radius = ctx.radius();

        if (ctx.themes().shadows()) r.shadow(x, y, width, height, radius, p.shadow(), Layout.SHADOW_SIZE);
        r.roundedRect(x, y, width, height, radius, ColorUtil.lerp(p.panel(), p.surfaceHover(), h * 0.5));
        r.outline(x, y, width, height, ColorUtil.lerp(p.border(), p.accent(), Math.max(f, query.isEmpty() ? 0 : 0.5)));
        // accent underline grows from the centre while focused
        int lineW = (int) Math.round((width - 4) * f);
        if (lineW > 0) r.gradientH(x + (width - lineW) / 2, y + height - 1, lineW, 1, p.accent(), p.accentAlt());

        int iconColor = ColorUtil.lerp(p.textMuted(), p.accent(), Math.max(f, query.isEmpty() ? 0 : 1));
        magnifier(r, x + 9, y + height / 2, iconColor, p.panel());

        int tx = x + 18, ty = y + Layout.textY(height);
        int innerW = width - 18 - 14;
        if (query.isEmpty()) {
            r.text(focused ? "" : "Search features…", tx, ty, p.textDisabled());
        } else {
            String shown = query;
            while (!shown.isEmpty() && r.textWidth(shown) > innerW) shown = shown.substring(1); // keep the tail visible
            r.text(shown, tx, ty, p.text());
            tx += r.textWidth(shown);
            // clear button
            boolean overClear = overClear(mouseX, mouseY);
            r.text("×", x + width - 10, ty, overClear ? p.text() : p.textMuted());
            if (overClear) ctx.requestTooltip(this, "Clear search");
        }
        boolean caretOn = focused && ((System.currentTimeMillis() - caretTime) / Durations.CARET_BLINK) % 2 == 0;
        if (caretOn) r.rect(tx + (query.isEmpty() ? 0 : 1), ty - 1, 1, 9, p.accent());
    }

    /** Small magnifying glass: a ring plus a short handle to the bottom-right. */
    private static void magnifier(Render2D r, int cx, int cy, int color, int hole) {
        r.circle(cx - 1, cy - 1, 3.5, color);
        r.circle(cx - 1, cy - 1, 2.2, hole);
        r.rect(cx + 1, cy + 1, 1, 1, color);
        r.rect(cx + 2, cy + 2, 1, 1, color);
        r.rect(cx + 3, cy + 3, 1, 1, color);
    }

    private boolean overClear(double mouseX, double mouseY) {
        return !query.isEmpty() && mouseX >= x + width - 13 && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /** Focuses the field (and starts the caret blink on). */
    public void focus() {
        ctx.focus(this);
        caretTime = System.currentTimeMillis();
    }

    private void setQuery(String q) {
        if (q.equals(query)) return;
        query = q;
        caretTime = System.currentTimeMillis();
        onChange.accept(query);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isHovered(mouseX, mouseY)) return false;
        if (button == MouseButton.RIGHT) {
            setQuery("");
            return true;
        }
        if (button != MouseButton.LEFT) return true;
        if (overClear(mouseX, mouseY)) setQuery("");
        focus();
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        if (!ctx.isFocused(this)) return false;
        caretTime = System.currentTimeMillis();
        boolean ctrl = (modifiers & CTRL_MASK) != 0;
        if (key == InputUtil.KEY_BACKSPACE) {
            if (ctrl) setQuery("");
            else if (!query.isEmpty()) setQuery(query.substring(0, query.offsetByCodePoints(query.length(), -1)));
            return true;
        }
        if (key == InputUtil.KEY_ESCAPE) {
            setQuery("");
            ctx.clearFocus();
            return true;
        }
        if (key == InputUtil.KEY_ENTER) {
            ctx.clearFocus();
            return true;
        }
        if (ctrl && key == InputUtil.KEY_V) {
            append(InputUtil.getClipboard().replaceAll("[\\r\\n\\t]", " "));
            return true;
        }
        return true; // swallow every other key so typing never triggers GUI shortcuts
    }

    @Override
    public boolean charTyped(int codepoint, int modifiers) {
        if (!ctx.isFocused(this)) return false;
        if (!Character.isISOControl(codepoint)) append(new String(Character.toChars(codepoint)));
        return true;
    }

    private void append(String text) {
        String next = query + text;
        if (next.codePointCount(0, next.length()) > MAX_LENGTH) {
            next = next.substring(0, next.offsetByCodePoints(0, MAX_LENGTH));
        }
        setQuery(next);
    }

    @Override
    public void onClose() {
        ctx.releaseFocus(this);
    }
}
