package dev.telemetric.hud;

import dev.telemetric.Telemetric;
import dev.telemetric.module.impl.misc.HudSettingsModule;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.List;

/**
 * HUD element made of "label value" rows (optionally with a title row) on a shared panel style.
 * Subclasses only provide {@link #lines(boolean)}.
 */
public abstract class LinesHudModule extends HudModule {

    /**
     * One row. Colours are ARGB or 0 for "theme default".
     *
     * @param valueRuns server-styled value (drawn instead of {@code value} when non-empty), may be empty
     * @param icon      icon in front of the label, or null
     * @param tintIcon  tint the icon with the row's value colour (for white symbols)
     */
    public record Line(String label, String value, int labelColor, int valueColor, List<TextRun> valueRuns, HudIcon icon,
                       boolean tintIcon) {
        public Line(String label, String value, int labelColor, int valueColor, List<TextRun> valueRuns, HudIcon icon) {
            this(label, value, labelColor, valueColor, valueRuns, icon, false);
        }

        public Line(String label, String value, int labelColor, int valueColor) {
            this(label, value, labelColor, valueColor, List.of(), null);
        }

        public Line(String label, String value) {
            this(label, value, 0, 0);
        }

        boolean styled() {
            return valueRuns != null && !valueRuns.isEmpty();
        }
    }

    protected static final int PAD_X = 4, PAD_Y = 3, LINE_GAP = 2, ICON_GAP = 3;

    /**
     * Telos Realms' Tab-list palette (General / Character Info): yellow labels, green values. Server data, not
     * a theme colour — read off the live Tab list (2026-11-14).
     */
    public static final int SERVER_LABEL = 0xFFDFE560, SERVER_VALUE = 0xFF55FF55;

    /** Per-element "Use Server Color" toggle, or null when the element has none (theme colours only). */
    private BooleanSetting useServerColor;

    protected LinesHudModule(String name, String description, double defaultX, double defaultY, boolean enabledByDefault) {
        super(name, description, defaultX, defaultY, enabledByDefault);
    }

    /**
     * Adds the "Use Server Color" toggle (on by default): rows without their own server colour are drawn in
     * {@link #SERVER_LABEL}/{@link #SERVER_VALUE}; off = theme colours (and Tab-list row colours are ignored).
     */
    protected final BooleanSetting addUseServerColor() {
        useServerColor = add(new BooleanSetting("Use Server Color",
                "Draw this element in the server's colours (yellow labels, green values) instead of the theme", true));
        return useServerColor;
    }

    /** Rows to show. In preview mode, return placeholder rows when real data is missing (never empty). */
    protected abstract List<Line> lines(boolean preview);

    /** Optional title row (null = none). */
    protected String title() {
        return null;
    }

    /** Pixels between the icon column and the label. */
    protected int iconGap() {
        return ICON_GAP;
    }

    /** Text between label and value. */
    protected String separator() {
        return ": ";
    }

    @Override
    public boolean hasData() {
        return !lines(false).isEmpty();
    }

    protected static HudSettingsModule style() {
        return Telemetric.get().modules().get(HudSettingsModule.class);
    }

    private int lineHeight(Render2D r) {
        return r.fontHeight() + LINE_GAP;
    }

    private static boolean icons() {
        return style().statIcons.get();
    }

    private static int iconWidth(Render2D r, HudIcon icon) {
        return switch (icon) {
            case null -> 0;
            case HudIcon.Glyph g -> r.glyphWidth(g);
            case HudIcon.Texture t -> Math.round(t.regionW() * (float) iconHeight(r) / t.regionH());
        };
    }

    private static int iconHeight(Render2D r) {
        return r.fontHeight();
    }

    /** Width reserved in front of every row's label (0 when no row has an icon). */
    private int iconSlot(Render2D r, List<Line> lines) {
        if (!icons()) return 0;
        int w = 0;
        for (Line line : lines) w = Math.max(w, iconWidth(r, line.icon()));
        return w == 0 ? 0 : w + iconGap();
    }

    private int valueWidth(Render2D r, Line line) {
        return line.styled() ? r.runsWidth(line.valueRuns()) : r.textWidth(line.value());
    }

    private int rowWidth(Render2D r, Line line) {
        if (line.label().isEmpty()) return valueWidth(r, line); // icon-only row
        return r.textWidth(line.label()) + r.textWidth(separator()) + valueWidth(r, line);
    }

    @Override
    public int contentWidth(Render2D r, boolean preview) {
        List<Line> lines = lines(preview);
        int w = title() == null ? 0 : r.textWidth(title());
        int slot = iconSlot(r, lines);
        for (Line line : lines) w = Math.max(w, slot + rowWidth(r, line));
        return w + PAD_X * 2;
    }

    @Override
    public int contentHeight(Render2D r, boolean preview) {
        int rows = lines(preview).size() + (title() == null ? 0 : 1);
        return Math.max(1, rows) * lineHeight(r) - LINE_GAP + PAD_Y * 2;
    }

    @Override
    public void draw(Render2D r, boolean preview, boolean alignRight) {
        Theme p = Telemetric.get().theme().palette();
        HudSettingsModule style = style();
        List<Line> lines = lines(preview);
        int w = contentWidth(r, preview), h = contentHeight(r, preview);

        if (style.background.get()) {
            int bg = ColorUtil.fade(p.panel(), style.backgroundOpacity.get() / 100.0);
            r.roundedRect(0, 0, w, h, 3, bg);
            if (style.accentBar.get()) {
                r.rect(alignRight ? w - 1 : 0, 2, 1, h - 4, p.accent());
            }
        }

        boolean serverPalette = useServerColor != null && useServerColor.get();
        boolean serverColors = style.serverColors.get() && (useServerColor == null || serverPalette);
        int defaultLabel = serverPalette ? SERVER_LABEL : p.accent();
        int defaultValue = serverPalette ? SERVER_VALUE : p.text();
        int slot = iconSlot(r, lines);
        int y = PAD_Y;
        if (title() != null) {
            int tw = r.textWidth(title());
            r.text(title(), alignRight ? w - PAD_X - tw : PAD_X, y, p.accent());
            y += lineHeight(r);
        }
        for (Line line : lines) {
            int lc = serverColors && line.labelColor() != 0 ? line.labelColor() : defaultLabel;
            int vc = serverColors && line.valueColor() != 0 ? line.valueColor() : defaultValue;
            int tint = line.tintIcon() ? iconTint(line, vc) : 0xFFFFFFFF;
            int x;
            if (alignRight) {
                // mirrored: icons in a column at the right edge, text right-aligned against it
                if (slot > 0) drawIcon(r, line.icon(), w - PAD_X - iconWidth(r, line.icon()), y, tint);
                x = w - PAD_X - slot - rowWidth(r, line);
            } else {
                if (slot > 0) drawIcon(r, line.icon(), PAD_X, y, tint);
                x = PAD_X + slot;
            }

            if (!line.label().isEmpty()) {
                r.text(line.label(), x, y, lc);
                x += r.textWidth(line.label());
                r.text(separator(), x, y, p.textMuted());
                x += r.textWidth(separator());
            }
            if (line.styled()) r.runs(line.valueRuns(), x, y, vc, serverColors);
            else r.text(line.value(), x, y, vc);
            y += lineHeight(r);
        }
    }

    /**
     * Stat colour for a tinted icon: the server's label colour (Telos colours the label, the value is
     * white), else a coloured value, else the drawn value colour. Pure white/grey counts as "no colour".
     */
    private static int iconTint(Line line, int drawnValueColor) {
        if (isColored(line.labelColor())) return line.labelColor();
        if (isColored(line.valueColor())) return line.valueColor();
        for (TextRun run : line.valueRuns()) if (isColored(run.color())) return run.color();
        if (line.labelColor() != 0) return line.labelColor();
        return drawnValueColor;
    }

    private static boolean isColored(int argb) {
        if (argb == 0) return false;
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 24;
    }

    private static void drawIcon(Render2D r, HudIcon icon, int x, int y, int tint) {
        switch (icon) {
            case null -> {}
            case HudIcon.Glyph g -> r.glyph(g, x, y, tint);
            case HudIcon.Texture t -> r.texture(t.path(), x, y - 1, iconWidth(r, t), iconHeight(r),
                    t.u(), t.v(), t.regionW(), t.regionH(), t.width(), t.height(), tint);
        }
    }
}
