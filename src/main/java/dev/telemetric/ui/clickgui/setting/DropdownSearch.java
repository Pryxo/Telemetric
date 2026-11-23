package dev.telemetric.ui.clickgui.setting;

import dev.telemetric.input.InputUtil;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.clickgui.Layout;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The search field and scrolling row window shared by the searchable dropdowns ({@link ChoiceComponent}, and
 * {@link MultiChoiceComponent} when {@code Setting#isSearchable()}). The owning component takes keyboard focus and
 * forwards keys and chars here. Matching is a case-insensitive "contains" on the option label.
 */
final class DropdownSearch {

    /** The height of the search field row at the top of the open list. */
    static final int FIELD_H = 14;
    /** How many rows show at once. The rest scroll. */
    static final int MAX_ROWS = 10;
    private static final int MAX_LENGTH = 32;
    private static final int CTRL_MASK = 0x0002;
    private static final int SCROLL_ROWS = 2;

    enum Key { EDITED, ENTER, ESCAPE }

    private final Animation focus = new Animation(Durations.HOVER, Easing.OUT_CUBIC);
    private String query = "";
    private int scroll;
    private long caretTime;

    String query() {
        return query;
    }

    void reset() {
        query = "";
        scroll = 0;
    }

    void restartCaret() {
        caretTime = System.currentTimeMillis();
    }

    /** Indices into {@code labels} whose text contains the query (all of them for an empty query). */
    List<Integer> matches(List<String> labels) {
        List<Integer> out = new ArrayList<>(labels.size());
        String q = query.strip().toLowerCase(Locale.ROOT);
        for (int i = 0; i < labels.size(); i++) {
            if (q.isEmpty() || labels.get(i).toLowerCase(Locale.ROOT).contains(q)) out.add(i);
        }
        return out;
    }

    /** The rows drawn below the field (at least one, for the "No matches" line). */
    int visibleRows(int matchCount) {
        return Math.max(1, Math.min(MAX_ROWS, matchCount));
    }

    /** The full list height below the dropdown header (field, rows and bottom gap). */
    int listHeight(int matchCount) {
        return FIELD_H + visibleRows(matchCount) * Layout.OPTION_HEIGHT + 2;
    }

    /** The first match shown (clamped to the current match count). */
    int first(int matchCount) {
        scroll = Math.clamp(scroll, 0, Math.max(0, matchCount - MAX_ROWS));
        return scroll;
    }

    /** Scrolls the window. Returns false if everything already fits. */
    boolean scroll(double amount, int matchCount) {
        if (matchCount <= MAX_ROWS || amount == 0) return false;
        scroll = Math.clamp(scroll - (int) Math.signum(amount) * SCROLL_ROWS, 0, matchCount - MAX_ROWS);
        return true;
    }

    /** The field background, query or placeholder, caret, and focus underline. */
    void renderField(Render2D r, int x, int y, int w, boolean focused, Theme p) {
        focus.animateTo(focused ? 1 : 0);
        int fy = y + 1, fh = FIELD_H - 2;
        r.roundedRect(x, fy, w, fh, 4, p.control());
        int lineW = (int) Math.round(w * focus.get());
        if (lineW > 0) r.rect(x + (w - lineW) / 2, fy + fh - 1, lineW, 1, p.accent());

        int tx = x + 3, ty = fy + Layout.textY(fh);
        if (query.isEmpty()) {
            if (!focused) r.text("Type to search…", tx, ty, p.textDisabled());
        } else {
            String shown = query;
            while (!shown.isEmpty() && r.textWidth(shown) > w - 8) shown = shown.substring(1); // keep the tail visible
            r.text(shown, tx, ty, p.text());
            tx += r.textWidth(shown) + 1;
        }
        boolean caretOn = focused && ((System.currentTimeMillis() - caretTime) / Durations.CARET_BLINK) % 2 == 0;
        if (caretOn) r.rect(tx, ty - 1, 1, 9, p.accent());
    }

    /** A thin scrollbar on the right edge of the row window (only when there are more matches than fit). */
    void renderScrollbar(Render2D r, int x, int rowsY, int matchCount, Theme p) {
        if (matchCount <= MAX_ROWS) return;
        int trackH = MAX_ROWS * Layout.OPTION_HEIGHT;
        int thumbH = Math.max(6, trackH * MAX_ROWS / matchCount);
        int thumbY = rowsY + (trackH - thumbH) * first(matchCount) / (matchCount - MAX_ROWS);
        r.rect(x, rowsY, 1, trackH, ColorUtil.fade(p.border(), 0.6));
        r.rect(x, thumbY, 1, thumbH, p.accent());
    }

    /** Edits the query. Every key is consumed so typing never triggers GUI shortcuts. */
    Key keyPressed(int key, int modifiers) {
        caretTime = System.currentTimeMillis();
        boolean ctrl = (modifiers & CTRL_MASK) != 0;
        if (key == InputUtil.KEY_BACKSPACE) {
            if (ctrl) setQuery("");
            else if (!query.isEmpty()) setQuery(query.substring(0, query.offsetByCodePoints(query.length(), -1)));
            return Key.EDITED;
        }
        if (ctrl && key == InputUtil.KEY_V) {
            append(InputUtil.getClipboard().replaceAll("[\\r\\n\\t]", " "));
            return Key.EDITED;
        }
        if (key == InputUtil.KEY_ENTER) return Key.ENTER;
        if (key == InputUtil.KEY_ESCAPE) {
            if (query.isEmpty()) return Key.ESCAPE;
            setQuery("");
        }
        return Key.EDITED;
    }

    void charTyped(int codepoint) {
        if (!Character.isISOControl(codepoint)) append(new String(Character.toChars(codepoint)));
    }

    private void append(String text) {
        String next = query + text;
        if (next.codePointCount(0, next.length()) > MAX_LENGTH) next = next.substring(0, next.offsetByCodePoints(0, MAX_LENGTH));
        setQuery(next);
    }

    private void setQuery(String q) {
        if (q.equals(query)) return;
        query = q;
        scroll = 0;
        caretTime = System.currentTimeMillis();
    }
}
