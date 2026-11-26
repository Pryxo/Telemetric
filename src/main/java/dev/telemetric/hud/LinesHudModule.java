package dev.telemetric.hud;

import dev.telemetric.Telemetric;
import dev.telemetric.module.Category;
import dev.telemetric.module.impl.misc.HudSettingsModule;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A HUD element made of "label value" rows (optionally with a title row) in a shared panel style.
 * Subclasses only have to provide {@link #lines(boolean)}.
 */
public abstract class LinesHudModule extends HudModule {

    /**
     * One row. Colours are ARGB, or 0 for "theme default".
     *
     * @param valueRuns server-styled value (drawn instead of {@code value} when not empty), may be empty
     * @param icon      icon in front of the label, or null
     * @param tintIcon  tint the icon with the row's value colour (for white symbols)
     * @param labelRuns styled label (drawn instead of {@code label} when not empty; {@code label} stays its plain text)
     * @param ownColors the runs keep their colours even when "Use Server Color" is off (e.g. the shiny animation)
     */
    public record Line(String label, String value, int labelColor, int valueColor, List<TextRun> valueRuns, HudIcon icon,
                       boolean tintIcon, List<TextRun> labelRuns, boolean ownColors) {
        public Line(String label, String value, int labelColor, int valueColor, List<TextRun> valueRuns, HudIcon icon,
                    boolean tintIcon) {
            this(label, value, labelColor, valueColor, valueRuns, icon, tintIcon, List.of(), false);
        }

        public Line(String label, String value, int labelColor, int valueColor, List<TextRun> valueRuns, HudIcon icon) {
            this(label, value, labelColor, valueColor, valueRuns, icon, false);
        }

        /** The same row with a styled label and/or value whose run colours always apply. */
        public Line styledRuns(List<TextRun> label, List<TextRun> value) {
            StringBuilder plain = new StringBuilder();
            for (TextRun r : label) plain.append(r.text());
            return new Line(plain.toString(), this.value, labelColor, valueColor, value, icon, tintIcon, label, true);
        }

        boolean styledLabel() {
            return labelRuns != null && !labelRuns.isEmpty();
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

        /** A thin horizontal rule between two groups of rows (compared by identity). */
        public static final Line DIVIDER = new Line("", "");

        boolean divider() {
            return this == DIVIDER;
        }
    }

    /** Height of a {@link Line#DIVIDER} row (the 1 px rule sits in the middle). */
    private static final int DIVIDER_H = 5;

    protected static final int PAD_X = 4, PAD_Y = 3, LINE_GAP = 2, ICON_GAP = 3;

    /**
     * Telos Realms' Tab-list palette (General / Character Info): yellow labels and green values. This is server data,
     * not a theme colour, read off the live Tab list.
     */
    public static final int SERVER_LABEL = 0xFFDFE560, SERVER_VALUE = 0xFF55FF55;

    /** The element's own "Use Server Color" toggle, or null if it doesn't have one (theme colours only). */
    private BooleanSetting useServerColor;

    private final HudColor titleColor, labelColor, valueColor, separatorColor, dividerColor;

    protected LinesHudModule(String name, String description, double defaultX, double defaultY, boolean enabledByDefault) {
        this(name, description, Category.STATS, defaultX, defaultY, enabledByDefault);
    }

    /** A row element listed in another panel (e.g. Combat → Assassin Stacks). */
    protected LinesHudModule(String name, String description, Category category, double defaultX, double defaultY,
                             boolean enabledByDefault) {
        super(name, description, category, defaultX, defaultY, enabledByDefault);
        addValueAlign();
        addTitleSetting();
        addFrameSettings(true);
        addIconSetting();
        titleColor = paletteColor("Title", "The title row", Theme::accent);
        labelColor = hudColor("Label", "Labels (\"Kills\" in \"Kills: 12\")",
                () -> serverPalette() ? SERVER_LABEL : Telemetric.get().theme().hudIndex());
        valueColor = hudColor("Value", "Values (\"12\" in \"Kills: 12\")",
                () -> serverPalette() ? SERVER_VALUE : Telemetric.get().theme().hudValue());
        separatorColor = paletteColor("Separator", "The \": \" between label and value", Theme::textMuted);
        dividerColor = paletteColor("Divider", "Lines between row groups", t -> ColorUtil.fade(t.textMuted(), 0.5));
    }

    @Override
    protected boolean hasTitle() {
        return title() != null;
    }

    /** The title row to draw (null without one or with Show Title off). */
    private String shownTitle() {
        return showTitle() ? title() : null;
    }

    @Override
    protected boolean drawsOwnFrame() {
        return true;
    }

    /** The element whose colours this one uses (split-off parts use their owner's). */
    private LinesHudModule colorSource() {
        return styleOwner() instanceof LinesHudModule l ? l : this;
    }

    /** "Use Server Color" is on (the server palette is the default label / value colour). */
    private boolean serverPalette() {
        BooleanSetting toggle = serverColorToggle();
        return toggle != null && toggle.get();
    }

    /**
     * Adds the "Use Server Color" toggle (on by default). When on, rows without their own server colour are drawn in
     * {@link #SERVER_LABEL}/{@link #SERVER_VALUE}. When off, the Theme's HUD Index / HUD Value colours are used and
     * Tab-list row colours are ignored.
     */
    protected final BooleanSetting addUseServerColor() {
        useServerColor = addHudSetting(new BooleanSetting("Use Server Color",
                "Draw this element in the server's colours (yellow labels, green values) instead of the theme", true));
        return useServerColor;
    }

    private BooleanSetting flashOnUpdate;
    private ColorSetting flashColor;
    private NumberSetting flashTime;
    /** Per row label: last drawn value and when it last changed (ms). */
    private final Map<String, String> flashLast = new HashMap<>();
    private final Map<String, Long> flashAt = new HashMap<>();

    /**
     * Adds "Flash on Update": a row's value briefly flashes in the flash colour (white by default) when it changes,
     * then fades back, like hearts flashing when you take damage.
     */
    protected final void addUpdateFlash() {
        flashOnUpdate = addHudSetting(new BooleanSetting("Flash on Update",
                "Values flash briefly when they change", true));
        flashColor = addHudSetting(new ColorSetting("Flash Color", "Colour of the update flash", 0xFFFFFFFF)
                .visibleWhen(flashOnUpdate::get));
        flashTime = addHudSetting(new NumberSetting("Flash Time", "How long the flash takes to fade",
                Durations.MICRO, 50, 600, 10).withSuffix("ms").visibleWhen(flashOnUpdate::get));
    }

    /** 1 right after this row's value changed, falling to 0 over Flash Time (0 = no flash). */
    private double flashAmount(Line line, boolean preview) {
        return preview ? 0 : flashAmount(line.label(), line.value());
    }

    private double flashAmount(String key, String value) {
        if (flashOnUpdate == null || !flashOnUpdate.get()) return 0;
        long now = System.nanoTime() / 1_000_000L;
        String old = flashLast.put(key, value);
        if (old != null && !old.equals(value)) flashAt.put(key, now);
        Long at = flashAt.get(key);
        int dur = Durations.scaled(flashTime.get().intValue());
        if (at == null || dur <= 0) return 0;
        double t = (now - at) / (double) dur;
        return t >= 1 ? 0 : 1 - t;
    }

    /** Value runs of a mirrored (right-aligned, "Value :Label") row, the same width as the normal runs. */
    protected List<TextRun> mirroredValueRuns(Line line) {
        return line.valueRuns();
    }

    /** The "Use Server Color" toggle this element follows (null for none). Split-off parts return their owner's. */
    protected BooleanSetting serverColorToggle() {
        return useServerColor;
    }

    /** The rows to show. In preview mode, return placeholder rows when the real data is missing (never empty). */
    protected abstract List<Line> lines(boolean preview);

    /** Optional title row (null for none). */
    protected String title() {
        return null;
    }

    /** Pixels between the icon column and the label. */
    protected int iconGap() {
        return ICON_GAP;
    }

    /** The text between label and value. */
    protected String separator() {
        return ": ";
    }

    @Override
    public boolean hasData() {
        return !rows(false).isEmpty();
    }

    // One draw asks for the rows ~6 times (hasData, bounds, width/height, draw), and some lines() sort or format, so
    // they're built once per draw pass (Render2D.drawPass) and per mode.
    private List<Line> liveRows, previewRows;
    private long livePass = -1, previewPass = -1;

    /** {@link #lines(boolean)}, cached for the current draw pass. */
    protected final List<Line> rows(boolean preview) {
        long pass = Render2D.drawPass();
        if (preview) {
            if (previewRows == null || previewPass != pass) {
                previewRows = lines(true);
                previewPass = pass;
            }
            return previewRows;
        }
        if (liveRows == null || livePass != pass) {
            liveRows = lines(false);
            livePass = pass;
        }
        return liveRows;
    }

    protected static HudSettingsModule style() {
        return Telemetric.get().modules().get(HudSettingsModule.class);
    }

    private int lineHeight(Render2D r) {
        return r.fontHeight() + LINE_GAP;
    }

    private boolean icons() {
        return showIcons();
    }

    private static int iconWidth(Render2D r, HudIcon icon) {
        return switch (icon) {
            case null -> 0;
            case HudIcon.Glyph g -> r.glyphWidth(g);
            case HudIcon.Texture t -> Math.round(t.regionW() * (float) iconHeight(r) / t.regionH());
            case HudIcon.Item i -> iconHeight(r) + 1;
        };
    }

    private static int iconHeight(Render2D r) {
        return r.fontHeight();
    }

    /** The width reserved in front of every row's label (0 if no row has an icon). */
    private int iconSlot(Render2D r, List<Line> lines) {
        if (!icons()) return 0;
        int w = 0;
        for (Line line : lines) w = Math.max(w, iconWidth(r, line.icon()));
        return w == 0 ? 0 : w + iconGap();
    }

    private int valueWidth(Render2D r, Line line) {
        return line.styled() ? r.runsWidth(line.valueRuns()) : r.textWidth(line.value());
    }

    private int labelWidth(Render2D r, Line line) {
        return line.styledLabel() ? r.runsWidth(line.labelRuns()) : r.textWidth(line.label());
    }

    private int rowWidth(Render2D r, Line line) {
        if (line.label().isEmpty()) return valueWidth(r, line); // a row that's only an icon
        return labelWidth(r, line) + r.textWidth(separator()) + valueWidth(r, line);
    }

    @Override
    public int contentWidth(Render2D r, boolean preview) {
        List<Line> lines = rows(preview);
        String title = shownTitle();
        int w = title == null ? 0 : r.textWidth(title);
        int slot = iconSlot(r, lines);
        for (Line line : lines) w = Math.max(w, slot + rowWidth(r, line));
        return w + PAD_X * 2;
    }

    @Override
    public int contentHeight(Render2D r, boolean preview) {
        List<Line> lines = rows(preview);
        int dividers = 0;
        for (Line line : lines) if (line.divider()) dividers++;
        int rows = lines.size() - dividers + (shownTitle() == null ? 0 : 1);
        return Math.max(1, rows) * lineHeight(r) + dividers * DIVIDER_H - LINE_GAP + PAD_Y * 2;
    }

    @Override
    public void draw(Render2D r, boolean preview, boolean alignRight) {
        HudSettingsModule style = style();
        List<Line> lines = rows(preview);
        int w = contentWidth(r, preview), h = contentHeight(r, preview);
        boolean centered = !alignRight && isCentered();

        drawFrame(r, w, h, alignRight);
        LinesHudModule colors = colorSource();
        int sepColor = colors.separatorColor.get();
        boolean edge = valuesAtEdge();

        BooleanSetting useServerColor = serverColorToggle();
        boolean serverPalette = useServerColor != null && useServerColor.get();
        boolean serverColors = style.serverColors.get() && (useServerColor == null || serverPalette);
        int defaultLabel = colors.labelColor.get();
        int defaultValue = colors.valueColor.get();
        int slot = iconSlot(r, lines);
        int y = PAD_Y;
        String title = shownTitle();
        if (title != null) {
            int tw = r.textWidth(title);
            r.text(title, alignRight ? w - PAD_X - tw : centered ? (w - tw) / 2 : PAD_X, y, colors.titleColor.get());
            y += lineHeight(r);
        }
        for (Line line : lines) {
            if (line.divider()) {
                r.rect(PAD_X, y + 1, w - PAD_X * 2, 1, colors.dividerColor.get());
                y += DIVIDER_H;
                continue;
            }
            int lc = serverColors && line.labelColor() != 0 ? line.labelColor() : defaultLabel;
            int vc = serverColors && line.valueColor() != 0 ? line.valueColor() : defaultValue;
            int tint = line.tintIcon() ? iconTint(line, vc) : 0xFFFFFFFF;
            boolean runColors = serverColors || line.ownColors();
            double flash = flashAmount(line, preview);
            if (flash > 0) {
                vc = ColorUtil.lerp(vc, flashColor.get(), flash);
                runColors = false;
            }
            int x;
            if (alignRight && isTextMirrored()) {
                // right-aligned: the whole row is mirrored ("Value :Label <icon>"), with icons in a column at the right edge
                if (slot > 0 && line.icon() != null)
                    drawIcon(r, line.icon(), w - PAD_X - (slot - iconGap() + iconWidth(r, line.icon())) / 2, y, tint);
                x = w - PAD_X - slot;
                if (!line.label().isEmpty()) {
                    x -= labelWidth(r, line);
                    if (line.styledLabel()) r.runs(line.labelRuns(), x, y, lc, runColors);
                    else r.text(line.label(), x, y, lc);
                    String sep = new StringBuilder(separator()).reverse().toString();
                    x -= r.textWidth(sep);
                    r.text(sep, x, y, sepColor);
                }
                x -= valueWidth(r, line);
                if (edge && !line.label().isEmpty()) x = PAD_X; // Value Align Right: mirrored values at the far (left) edge
                if (line.styled()) r.runs(mirroredValueRuns(line), x, y, vc, runColors);
                else r.text(line.value(), x, y, vc);
                y += lineHeight(r);
                continue;
            } else if (centered || alignRight) {
                // centred, or right without mirroring: each whole row (its own icon plus text) is aligned as one block
                int iw = slot > 0 && line.icon() != null ? iconWidth(r, line.icon()) + iconGap() : 0;
                int rw = iw + rowWidth(r, line);
                x = alignRight ? w - PAD_X - rw : (w - rw) / 2;
                if (iw > 0) drawIcon(r, line.icon(), x, y, tint);
                x += iw;
            } else {
                // icons are centred in the shared column, so narrow ones (Server/Glory PNGs) line up with the glyphs
                if (slot > 0 && line.icon() != null)
                    drawIcon(r, line.icon(), PAD_X + (slot - iconGap() - iconWidth(r, line.icon())) / 2, y, tint);
                x = PAD_X + slot;
            }

            if (!line.label().isEmpty()) {
                if (line.styledLabel()) r.runs(line.labelRuns(), x, y, lc, runColors);
                else r.text(line.label(), x, y, lc);
                x += labelWidth(r, line);
                r.text(separator(), x, y, sepColor);
                x += r.textWidth(separator());
                // Value Align Right: left-aligned rows push their value to the right edge
                if (edge && !centered && !alignRight) x = Math.max(x, w - PAD_X - valueWidth(r, line));
            }
            if (line.styled()) r.runs(line.valueRuns(), x, y, vc, runColors);
            else r.text(line.value(), x, y, vc);
            y += lineHeight(r);
        }
    }

    /**
     * The stat colour for a tinted icon: the server's label colour (Telos colours the label and leaves the value white),
     * or else a coloured value, or else the drawn value colour. Pure white or grey counts as "no colour".
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
            case HudIcon.Item i -> r.itemModel(i.model(), x, y - 1, iconHeight(r) + 1);
        }
    }
}
