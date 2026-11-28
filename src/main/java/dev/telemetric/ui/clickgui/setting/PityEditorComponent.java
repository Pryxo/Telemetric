package dev.telemetric.ui.clickgui.setting;

import dev.telemetric.data.LootTable;
import dev.telemetric.data.LootTable.Drop;
import dev.telemetric.data.TelosData;
import dev.telemetric.hud.ShinyColors;
import dev.telemetric.input.InputUtil;
import dev.telemetric.setting.impl.PityEditorSetting;
import dev.telemetric.tracking.LootTracker;
import dev.telemetric.tracking.PityScopes;
import dev.telemetric.tracking.TrackingStore;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.clickgui.Layout;
import dev.telemetric.ui.framework.GuiContext;
import dev.telemetric.ui.framework.Hit;
import dev.telemetric.ui.framework.MouseButton;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * {@link PityEditorSetting}: a header ("Editing: Normal"), then one row per drop (icon, name, pity). Without a search
 * only the drops with pity show; with one, every tracked drop whose name has all the words ("shiny" matches shiny
 * variants). Click a count to type a new one: Enter or clicking elsewhere applies it, Esc cancels, empty = 0.
 */
public class PityEditorComponent extends SettingComponent<PityEditorSetting> {

    private static final int ROW_H = 14, ICON = 10, BOX_W = 36, BOX_H = 11, MAX_ROWS = 80, MAX_DIGITS = 6;
    private static final long REFRESH_MS = 250;

    private final Animation editFocus = new Animation(Durations.HOVER, Easing.OUT_CUBIC);

    /** Every tracked drop, sorted by name (shiny after its normal one), for {@link #allFor} 's table. */
    private List<Drop> all = List.of();
    private LootTable allFor;

    private List<Drop> rows = List.of();
    private int hiddenRows;
    private String rowsQuery;
    private long rowsAt;

    private String editKey;
    private String editText = "";
    /** True until the first key: the box shows the old count, and typing replaces it. */
    private boolean editFresh;
    private long editTime;

    public PityEditorComponent(GuiContext ctx, PityEditorSetting setting) {
        super(ctx, setting);
    }

    @Override
    protected int contentHeight() {
        refresh(false);
        int footer = rows.isEmpty() || hiddenRows > 0 ? ROW_H : 0;
        return Layout.SETTING_HEIGHT + rows.size() * ROW_H + footer + 2;
    }

    private void refresh(boolean force) {
        long now = System.currentTimeMillis();
        String q = setting.query().trim().toLowerCase(Locale.ROOT);
        LootTable loot = TelosData.LOOT.get();
        if (loot != allFor) {
            allFor = loot;
            all = sorted(loot);
            force = true;
        }
        if (!force && q.equals(rowsQuery) && now - rowsAt < REFRESH_MS) return;
        rowsQuery = q;
        rowsAt = now;
        TrackingStore store = LootTracker.get().store();
        String[] words = q.isEmpty() ? new String[0] : q.split("\\s+");
        List<Drop> out = new ArrayList<>();
        int hidden = 0;
        for (Drop d : all) {
            if (words.length == 0 ? store.pity(d.key()) <= 0 && !d.key().equals(editKey) : !matches(d, words)) continue;
            if (out.size() < MAX_ROWS) out.add(d);
            else hidden++;
        }
        rows = out;
        hiddenRows = hidden;
    }

    private static List<Drop> sorted(LootTable loot) {
        LinkedHashMap<String, Drop> unique = new LinkedHashMap<>();
        for (Drop d : loot.byKey().values()) if (d.tracked()) unique.putIfAbsent(d.key(), d);
        List<Drop> list = new ArrayList<>(unique.values());
        list.sort(Comparator.comparing((Drop d) -> d.name().toLowerCase(Locale.ROOT)).thenComparing(Drop::shiny));
        return List.copyOf(list);
    }

    private static boolean matches(Drop d, String[] words) {
        String hay = d.name().toLowerCase(Locale.ROOT) + (d.shiny() ? " shiny" : "");
        for (String w : words) if (!hay.contains(w)) return false;
        return true;
    }

    @Override
    protected void renderContent(Render2D r, int mouseX, int mouseY, float delta, Theme p) {
        TrackingStore store = LootTracker.get().store();
        editFocus.animateTo(editKey != null && ctx.isFocused(this) ? 1 : 0);

        int ty = y + Layout.textY(Layout.SETTING_HEIGHT);
        r.text("Editing: ", left(), ty, p.textMuted());
        r.text(PityScopes.label(store.pityScope(), store), left() + r.textWidth("Editing: "), ty, p.accent());
        if (!rows.isEmpty()) r.textRight(rows.size() + hiddenRows + (rows.size() + hiddenRows == 1 ? " item" : " items"),
                right(), ty, p.textDisabled());

        int ry = y + Layout.SETTING_HEIGHT;
        for (Drop d : rows) {
            boolean rowHover = isActive() && Hit.inside(mouseX, mouseY, x, ry, width, ROW_H);
            if (rowHover) r.rect(x, ry, width, ROW_H, ColorUtil.fade(p.surfaceHover(), 0.6));
            int ix = left();
            if (!d.model().isEmpty()) r.itemModel(d.model(), ix, ry + (ROW_H - ICON) / 2, ICON);
            int nx = ix + ICON + 4, bx = right() - BOX_W;
            String name = d.name() + (d.shiny() ? " (Shiny)" : "");
            int nameColor = d.shiny() ? 0xFF000000 | ShinyColors.rgbNow(0)
                    : rowHover ? p.text() : ColorUtil.lerp(p.textMuted(), p.text(), 0.5);
            r.text(r.trim(name, bx - nx - 4), nx, ry + Layout.textY(ROW_H), nameColor);
            drawCount(r, p, d, store, bx, ry + (ROW_H - BOX_H) / 2, mouseX, mouseY);
            ry += ROW_H;
        }
        if (rows.isEmpty()) {
            String msg = rowsQuery == null || rowsQuery.isEmpty() ? "No pity yet. Search an item to set one."
                    : "No item matches \"" + rowsQuery + "\"";
            r.text(r.trim(msg, right() - left()), left(), ry + Layout.textY(ROW_H), p.textDisabled());
        } else if (hiddenRows > 0) {
            r.text(r.trim("+" + hiddenRows + " more. Type more to narrow it down.", right() - left()), left(),
                    ry + Layout.textY(ROW_H), p.textDisabled());
        }
    }

    private void drawCount(Render2D r, Theme p, Drop d, TrackingStore store, int bx, int by, int mouseX, int mouseY) {
        boolean editing = d.key().equals(editKey) && ctx.isFocused(this);
        boolean hovered = isActive() && Hit.inside(mouseX, mouseY, bx, by, BOX_W, BOX_H);
        r.roundedRect(bx, by, BOX_W, BOX_H, 3, hovered || editing ? ColorUtil.lerp(p.control(), p.accent(), 0.18) : p.control());
        int textY = by + Layout.textY(BOX_H);
        if (editing) {
            int lineW = (int) Math.round(BOX_W * editFocus.get());
            if (lineW > 0) r.rect(bx + (BOX_W - lineW) / 2, by + BOX_H - 1, lineW, 1, p.accent());
            r.textRight(editText, bx + BOX_W - 3, textY, editFresh ? p.textMuted() : p.text());
            boolean caretOn = ((System.currentTimeMillis() - editTime) / 530) % 2 == 0;
            if (caretOn) r.rect(bx + BOX_W - 2, by + 2, 1, BOX_H - 4, p.accent());
        } else {
            int n = store.pity(d.key());
            r.textRight(Integer.toString(n), bx + BOX_W - 3, textY, n > 0 ? p.text() : p.textDisabled());
        }
        if (hovered && !editing) ctx.requestTooltip(this, d.name(), "Click to type a new pity");
    }

    @Override
    protected boolean onClick(double mouseX, double mouseY, int button) {
        int ry = y + Layout.SETTING_HEIGHT;
        if (mouseY < ry) return false;
        int i = (int) ((mouseY - ry) / ROW_H);
        if (i < 0 || i >= rows.size()) {
            commit();
            return false;
        }
        if (button != MouseButton.LEFT) return false;
        Drop d = rows.get(i);
        commit();
        editKey = d.key();
        editText = Integer.toString(LootTracker.get().store().pity(d.key()));
        editFresh = true;
        editTime = System.currentTimeMillis();
        ctx.focus(this);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        if (!ctx.isFocused(this) || editKey == null) return false;
        editTime = System.currentTimeMillis();
        if (key == InputUtil.KEY_BACKSPACE) {
            if (editFresh) editText = "";
            else if (!editText.isEmpty()) editText = editText.substring(0, editText.length() - 1);
            editFresh = false;
            return true;
        }
        if (key == InputUtil.KEY_ENTER) {
            ctx.clearFocus(); // onFocusLost applies it
            return true;
        }
        if (key == InputUtil.KEY_ESCAPE) {
            editKey = null;
            ctx.clearFocus();
            return true;
        }
        return true; // swallow every other key so typing never triggers game or GUI shortcuts
    }

    @Override
    public boolean charTyped(int codepoint, int modifiers) {
        if (!ctx.isFocused(this) || editKey == null) return false;
        if (codepoint >= '0' && codepoint <= '9' && (editFresh || editText.length() < MAX_DIGITS)) {
            editText = editFresh || editText.equals("0") ? Character.toString(codepoint) : editText + (char) codepoint;
            editFresh = false;
            editTime = System.currentTimeMillis();
        }
        return true;
    }

    /** Writes the typed count (empty = 0) and ends editing. Nothing typed = no change. */
    private void commit() {
        String key = editKey;
        editKey = null;
        if (key == null || editFresh) return;
        int n = editText.isEmpty() ? 0 : Integer.parseInt(editText);
        LootTracker.get().store().setPity(key, n);
        refresh(true);
    }

    @Override
    public void onFocusLost() {
        commit();
        editFocus.animateTo(0);
    }

    @Override
    public void onClose() {
        ctx.releaseFocus(this);
    }
}
