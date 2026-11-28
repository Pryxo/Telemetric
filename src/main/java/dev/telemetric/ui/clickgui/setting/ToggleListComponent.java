package dev.telemetric.ui.clickgui.setting;

import dev.telemetric.setting.impl.ToggleListSetting;
import dev.telemetric.setting.impl.ToggleListSetting.Row;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.clickgui.Layout;
import dev.telemetric.ui.framework.GuiContext;
import dev.telemetric.ui.framework.Hit;
import dev.telemetric.ui.framework.MouseButton;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Fx;
import dev.telemetric.ui.render.Icons;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@link ToggleListSetting}: a bar ("12 of 40 on", All / None for what's listed), then one collapsible group per
 * {@link Row#group()} (chevron, name, on/total, a switch for the whole group: on, off or mixed) with its rows
 * (icon, name, muted meta, switch). Groups start closed; a search opens every group that has a match.
 */
public class ToggleListComponent extends SettingComponent<ToggleListSetting> {

    private static final int GROUP_H = 14, ROW_H = 14, ICON = 10, TRACK_W = 16, TRACK_H = 8, CHIP_H = 11, CHIP_PAD = 4;
    /** Text start of group names and row icons (chevron 8 wide + gap 4). */
    private static final int INDENT = 12;
    private static final long REFRESH_MS = 250;

    private record Group(String name, List<Row> rows) {}

    private List<Group> groups = List.of();
    private int total;
    private String groupsQuery;
    private List<Row> groupsFrom;
    private long groupsAt;

    /** Open groups (UI state only). */
    private final Set<String> expanded = new HashSet<>();
    private final Map<String, Animation> open = new HashMap<>();
    private final Map<String, Animation> switches = new HashMap<>();

    /** The All / None chips as last drawn (click hit boxes). */
    private int allX, allW, noneX, noneW, chipY;

    public ToggleListComponent(GuiContext ctx, ToggleListSetting setting) {
        super(ctx, setting);
    }

    private boolean searching() {
        return groupsQuery != null && !groupsQuery.isEmpty();
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        String q = setting.query().trim().toLowerCase(Locale.ROOT);
        List<Row> all = setting.rows();
        if (q.equals(groupsQuery) && all == groupsFrom && now - groupsAt < REFRESH_MS) return;
        groupsQuery = q;
        groupsFrom = all;
        groupsAt = now;
        total = all.size();
        String[] words = q.isEmpty() ? new String[0] : q.split("\\s+");
        List<Group> out = new ArrayList<>();
        Group current = null;
        for (Row r : all) {
            if (!matches(r, words)) continue;
            if (current == null || !current.name.equals(r.group())) {
                current = new Group(r.group(), new ArrayList<>());
                out.add(current);
            }
            current.rows.add(r);
        }
        groups = out;
    }

    private static boolean matches(Row r, String[] words) {
        if (words.length == 0) return true;
        String hay = (r.label() + " " + r.group() + " " + r.meta()).toLowerCase(Locale.ROOT);
        for (String w : words) if (!hay.contains(w)) return false;
        return true;
    }

    private Animation openAnim(String group) {
        boolean target = searching() || expanded.contains(group);
        Animation a = open.computeIfAbsent(group, g -> new Animation(Durations.ACCORDION, Easing.OUT_CUBIC, target ? 1 : 0));
        a.animateTo(target ? 1 : 0);
        return a;
    }

    private int rowsHeight(Group g) {
        return (int) Math.round(g.rows.size() * ROW_H * openAnim(g.name).get());
    }

    @Override
    protected int contentHeight() {
        refresh();
        int h = Layout.SETTING_HEIGHT;
        for (Group g : groups) h += GROUP_H + rowsHeight(g);
        if (groups.isEmpty()) h += ROW_H;
        return h + 2;
    }

    @Override
    protected void renderContent(Render2D r, int mouseX, int mouseY, float delta, Theme p) {
        refresh();
        boolean active = isActive();

        // bar: count + All / None
        int on = 0;
        for (Row row : setting.rows()) if (setting.isOn(row)) on++;
        int ty = y + Layout.textY(Layout.SETTING_HEIGHT);
        chipY = y + (Layout.SETTING_HEIGHT - CHIP_H) / 2;
        noneW = r.textWidth("None") + CHIP_PAD * 2;
        allW = r.textWidth("All") + CHIP_PAD * 2;
        noneX = right() - noneW;
        allX = noneX - 4 - allW;
        boolean anyListed = !groups.isEmpty();
        chip(r, p, "All", allX, allW, anyListed, active, mouseX, mouseY);
        chip(r, p, "None", noneX, noneW, anyListed, active, mouseX, mouseY);
        String count = on + " of " + total + " on";
        r.text(r.trim(count, allX - 6 - left()), left(), ty, p.textMuted());

        int gy = y + Layout.SETTING_HEIGHT;
        for (Group g : groups) {
            int gOn = 0;
            for (Row row : g.rows) if (setting.isOn(row)) gOn++;
            boolean hover = active && Hit.inside(mouseX, mouseY, x, gy, width, GROUP_H);
            if (hover) r.rect(x, gy, width, GROUP_H, ColorUtil.fade(p.surfaceHover(), 0.6));
            Animation oa = openAnim(g.name);
            int gty = gy + Layout.textY(GROUP_H);
            Icons.chevron(r, left() + 4, gy + GROUP_H / 2, oa.get(), p.textMuted());
            int lx = left() + INDENT, sx = right() - TRACK_W;
            String counter = gOn + "/" + g.rows.size();
            int counterW = r.textWidth(counter);
            String name = r.trim(g.name, sx - 6 - counterW - 4 - lx);
            r.text(name, lx, gty, p.text());
            r.text(counter, lx + r.textWidth(name) + 4, gty, p.textDisabled());
            double target = gOn == g.rows.size() ? 1 : gOn == 0 ? 0 : 0.5;
            drawSwitch(r, p, "group:" + g.name, sx, gy + (GROUP_H - TRACK_H) / 2, target);
            if (hover && mouseX >= sx - 2) {
                ctx.requestTooltip(this, g.name, gOn == g.rows.size() ? "Click to switch all off" : "Click to switch all on");
            }
            gy += GROUP_H;

            int rowsH = rowsHeight(g);
            if (rowsH <= 0) continue;
            r.pushClip(x, gy, width, rowsH);
            int ry = gy;
            for (Row row : g.rows) {
                if (ry >= gy + rowsH) break;
                renderRow(r, p, row, ry, active && mouseY < gy + rowsH, mouseX, mouseY);
                ry += ROW_H;
            }
            r.popClip();
            gy += rowsH;
        }
        if (groups.isEmpty()) {
            String msg = total == 0 ? "Nothing to list" : "No matches";
            r.text(msg, left(), gy + Layout.textY(ROW_H), p.textDisabled());
        }
    }

    private void renderRow(Render2D r, Theme p, Row row, int ry, boolean active, int mouseX, int mouseY) {
        boolean on = setting.isOn(row);
        boolean hover = active && Hit.inside(mouseX, mouseY, x, ry, width, ROW_H);
        if (hover) r.rect(x, ry, width, ROW_H, ColorUtil.fade(p.surfaceHover(), 0.6));
        int ix = left() + INDENT;
        if (row.icon() != null) r.itemModel(row.icon(), ix, ry + (ROW_H - ICON) / 2, ICON);
        else r.circle(ix + ICON / 2.0, ry + ROW_H / 2.0, 2, p.textDisabled());
        int nx = ix + ICON + 4, sx = right() - TRACK_W;
        int ty = ry + Layout.textY(ROW_H);
        int metaW = 0;
        if (!row.meta().isEmpty()) {
            String meta = r.trim(row.meta(), Math.max(0, (sx - 6 - nx) / 2));
            metaW = r.textWidth(meta);
            r.textRight(meta, sx - 6, ty, p.textDisabled());
        }
        int nameColor = on ? p.text() : p.textDisabled();
        r.text(r.trim(row.label(), sx - 6 - metaW - 6 - nx), nx, ty, nameColor);
        drawSwitch(r, p, row.key(), sx, ry + (ROW_H - TRACK_H) / 2, on ? 1 : 0);
        if (hover && !row.tooltip().isEmpty()) ctx.requestTooltip(this, row.label(), row.tooltip());
    }

    private void chip(Render2D r, Theme p, String text, int cx, int cw, boolean enabled, boolean active, int mouseX, int mouseY) {
        boolean hover = enabled && active && Hit.inside(mouseX, mouseY, cx, chipY, cw, CHIP_H);
        r.roundedRect(cx, chipY, cw, CHIP_H, 2, hover ? ColorUtil.lerp(p.control(), p.accent(), 0.18) : p.control());
        int color = !enabled ? p.textDisabled() : hover ? p.text() : p.textMuted();
        r.text(text, cx + CHIP_PAD, chipY + Layout.textY(CHIP_H), color);
    }

    /** The BooleanComponent switch (0 = off, 1 = on, 0.5 = mixed), animated per {@code id}. */
    private void drawSwitch(Render2D r, Theme p, String id, int tx, int ty, double target) {
        Animation a = switches.computeIfAbsent(id, k -> new Animation(Durations.MICRO, Easing.OUT_CUBIC, target));
        a.animateTo(target);
        double t = a.get(), c = Math.clamp(t, 0, 1);
        int accent = Fx.flow(ctx.themes(), p, tx, ty);
        r.pill(tx, ty, TRACK_W, TRACK_H, ColorUtil.lerp(p.control(), accent, c));
        double knobR = TRACK_H / 2.0 - 1;
        double knobX = tx + 1 + knobR + (TRACK_W - 2 - knobR * 2) * t;
        r.circle(knobX, ty + TRACK_H / 2.0, knobR, ColorUtil.lerp(p.textMuted(), 0xFFFFFFFF, c));
    }

    @Override
    protected boolean onClick(double mouseX, double mouseY, int button) {
        if (button != MouseButton.LEFT) return false;
        refresh();
        if (mouseY < y + Layout.SETTING_HEIGHT) {
            if (groups.isEmpty()) return false;
            if (Hit.inside(mouseX, mouseY, allX, chipY, allW, CHIP_H)) {
                setting.setAll(listed(), true);
                return true;
            }
            if (Hit.inside(mouseX, mouseY, noneX, chipY, noneW, CHIP_H)) {
                setting.setAll(listed(), false);
                return true;
            }
            return false;
        }
        int gy = y + Layout.SETTING_HEIGHT;
        int sx = right() - TRACK_W;
        for (Group g : groups) {
            if (mouseY < gy + GROUP_H) {
                if (mouseX >= sx - 2) {
                    boolean allOn = true;
                    for (Row row : g.rows) if (!setting.isOn(row)) allOn = false;
                    setting.setAll(g.rows, !allOn);
                } else if (!searching()) {
                    if (!expanded.remove(g.name)) expanded.add(g.name);
                }
                return true;
            }
            gy += GROUP_H;
            int rowsH = rowsHeight(g);
            if (mouseY < gy + rowsH) {
                int i = (int) ((mouseY - gy) / ROW_H);
                if (i < 0 || i >= g.rows.size()) return false;
                Row row = g.rows.get(i);
                setting.set(row, !setting.isOn(row));
                return true;
            }
            gy += rowsH;
        }
        return false;
    }

    private List<Row> listed() {
        List<Row> out = new ArrayList<>();
        for (Group g : groups) out.addAll(g.rows);
        return out;
    }
}
