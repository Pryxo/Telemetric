package dev.telemetric.module.impl.visual;

import dev.telemetric.Telemetric;
import dev.telemetric.game.BossStageAdapter;
import dev.telemetric.game.BossStageAdapter.Kind;
import dev.telemetric.game.BossStageAdapter.Stage;
import dev.telemetric.game.EntityPreviewAdapter;
import dev.telemetric.game.WorldLabelAdapter;
import dev.telemetric.input.InputUtil;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.module.impl.hud.DpsMeterHud;
import dev.telemetric.setting.Setting;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.GroupSetting;
import dev.telemetric.setting.impl.GroupToggleSetting;
import dev.telemetric.setting.impl.ModeSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.clickgui.window.PlayerPreview;
import dev.telemetric.ui.clickgui.window.SettingsPreview;
import dev.telemetric.ui.framework.GuiContext;
import dev.telemetric.ui.render.BadgeText;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A floating badge on the ground under each boss that says whether it can be damaged right now (Immune / Shielded /
 * Vulnerable). The stage is read from the boss's status text display ({@link BossStageAdapter}), and the badge is
 * drawn in the world with a badge font ({@link WorldLabelAdapter}).
 * <p>
 * The label is a stack of lines — Stage Label, Health and one line per DPS stat (DPS, Max Hit, Last Hit, Total,
 * Hits) — whose order the player drags in the ClickGUI (movable settings, like Stats Info). Each line has its own style section (font, bold,
 * underline, shadow / outline, colour, size). DPS only shows on the nearest boss. The Settings window shows a 3D
 * preview of the label with sample values; drag a line there to set the Height Offset, click the badge to try the
 * other stages.
 */
public final class BossStageTrackerModule extends Module {

    static final String REST_HP = "Rest HP", PERCENT_HP = "% HP", CURRENT_MAX = "Current / Max";
    private static final String[] STYLES = {"Pill", "Ribbon", "Text"};

    /** The look settings one label line has: a section of its own in the Settings window. */
    private final class LineStyle {
        final ModeSetting style;
        final BooleanSetting compact; // stage line only, else null
        final BooleanSetting bold, underline, shadow, outline;
        final NumberSetting size;

        LineStyle(GroupSetting g, String what, String styleKey, String compactKey, String boldKey, String underlineKey,
                  String shadowKey, String outlineKey, String sizeKey, double defaultSize) {
            style = add(g.child(new ModeSetting(styleKey, what + ": Pill / Ribbon = badge font, Text = plain Minecraft text",
                    "Pill", STYLES).withLabel("Style")));
            compact = compactKey == null ? null : add(g.child(new BooleanSetting(compactKey,
                    "Badge styles: a small blank badge in the stage colour instead of the word", false)
                    .visibleWhen(() -> !style.is("Text"))));
            bold = add(g.child(new BooleanSetting(boldKey, what + " (Text style): bold", false)
                    .visibleWhen(() -> style.is("Text")).withLabel("Bold")));
            underline = add(g.child(new BooleanSetting(underlineKey, what + " (Text style): underlined", false)
                    .visibleWhen(() -> style.is("Text")).withLabel("Underline")));
            shadow = add(g.child(new BooleanSetting(shadowKey, what + ": drop shadow under the text", true)
                    .visibleWhen(() -> !outline().get()).withLabel("Shadow")));
            outline = add(g.child(new BooleanSetting(outlineKey, what + ": black outline around the text (instead of the shadow)",
                    false).withLabel("Outline")));
            size = add(g.child(new NumberSetting(sizeKey, what + ": height in blocks", defaultSize, 0.06, 0.6, 0.02)
                    .withLabel("Size")));
        }

        private BooleanSetting outline() {
            return outline;
        }

        BadgeText.Style font() {
            return switch (style.get()) {
                case "Ribbon" -> BadgeText.Style.RIBBON;
                case "Text" -> BadgeText.Style.PLAIN;
                default -> BadgeText.Style.PILL;
            };
        }
    }

    // ---- General: the label lines (top to bottom in the user's drag order) and when to track
    private final BooleanSetting stageLine = add(new BooleanSetting("Stage Label",
            "The Immune / Shielded / Vulnerable badge (drag to reorder the label lines)", true).movable());
    private final GroupToggleSetting healthLine = add(new GroupToggleSetting("Health",
            "Boss health on the label (drag to reorder; right-click for the format)", false).movable());
    private final ModeSetting healthFormat = add(healthLine.child(new ModeSetting("Health Format",
            "Rest HP: health left. % HP: health in percent. Current / Max: both numbers",
            REST_HP, REST_HP, PERCENT_HP, CURRENT_MAX).withLabel("Format").attachedTo(healthLine)));
    /** One movable label line per DPS stat; names are {@link DpsMeterHud}'s stat names. */
    private final BooleanSetting dpsLine = add(dpsStat(DpsMeterHud.DPS, "Damage per second"));
    private final BooleanSetting maxHitLine = add(dpsStat(DpsMeterHud.MAX_HIT, "Highest single hit of the fight"));
    private final BooleanSetting lastHitLine = add(dpsStat(DpsMeterHud.LAST_HIT, "Most recent hit"));
    private final BooleanSetting totalLine = add(dpsStat(DpsMeterHud.TOTAL, "Total damage of the fight"));
    private final BooleanSetting hitsLine = add(dpsStat(DpsMeterHud.HITS, "Number of hits in the fight"));
    private final List<BooleanSetting> dpsLines = List.of(dpsLine, maxHitLine, lastHitLine, totalLine, hitsLine);
    private final BooleanSetting hideDpsHud = add(new BooleanSetting("Hide DPS HUD",
            "Hide the DPS Meter HUD element while a boss is tracked here (the stats are on the label instead)", true)
            .visibleWhen(this::anyDpsLine));

    private final BooleanSetting showDamageable = add(new BooleanSetting("Show Vulnerable",
            "Also show the stage badge while the boss can be damaged", true));
    private final BooleanSetting throughWalls = add(new BooleanSetting("Through Walls",
            "Label stays visible behind blocks", true));
    private final NumberSetting range = add(new NumberSetting("Range", "Track bosses up to this far away",
            48, 8, 128, 4).withSuffix(" blocks"));

    // ---- one section per line
    private final GroupSetting stageGroup = add(new GroupSetting("Stage Label Style", "Look of the stage badge"));
    private final LineStyle stageStyle = new LineStyle(stageGroup, "Stage badge", "Label Style", "Compact", "Bold",
            "Underline", "Stage Shadow", "Stage Outline", "Label Size", 0.2);
    private final ColorSetting immuneColor = add(stageGroup.child(new ColorSetting("Immune Color",
            "Badge colour while immune", 0xFF1757A7, false)));
    private final ColorSetting shieldedColor = add(stageGroup.child(new ColorSetting("Shielded Color",
            "Badge colour while shielded", 0xFFA167E2, false)));
    private final ColorSetting damageableColor = add(stageGroup.child(new ColorSetting("Vulnerable Color",
            "Badge colour while the boss can be damaged", 0xFF5AD123, false)));

    private final GroupSetting healthGroup = add(new GroupSetting("Health Line Style", "Look of the Health line"));
    private final LineStyle healthStyle = new LineStyle(healthGroup, "Health line", "Health Style", null, "Health Bold",
            "Health Underline", "Health Shadow", "Health Outline", "Health Size", 0.16);
    private final ColorSetting healthColor = add(healthGroup.child(new ColorSetting("Health Color",
            "Colour of the Health line", 0xFFE5484D, false).withLabel("Color")));

    private final GroupSetting dpsGroup = add(new GroupSetting("DPS Line Style", "Look of the DPS lines"));
    private final LineStyle dpsStyle = new LineStyle(dpsGroup, "DPS lines", "DPS Style", null, "DPS Bold",
            "DPS Underline", "DPS Shadow", "DPS Outline", "DPS Size", 0.16);
    private final ColorSetting dpsColor = add(dpsGroup.child(new ColorSetting("DPS Color",
            "Colour of the DPS lines", 0xFFF5A524, false).withLabel("Color")));
    private final BooleanSetting dpsDamageColors = add(dpsGroup.child(new BooleanSetting("DPS Damage Colors",
            "Max / Last Hit use their damage colour (when the DPS Meter's Damage Colors is on) instead of the colour above",
            true).withLabel("Damage Colors")));

    private final GroupSetting positionGroup = add(new GroupSetting("Position", "Spacing and height of the label"));
    private final NumberSetting spacing = add(positionGroup.child(new NumberSetting("Line Spacing",
            "Gap between the lines, in line heights", 0.35, 0, 2, 0.05)));
    private final NumberSetting offset = add(positionGroup.child(new NumberSetting("Height Offset",
            "Blocks above the ground under the boss (negative = into the floor)", 0.05, -1.0, 3.0, 0.05)));

    private volatile List<Stage> stages = List.of();

    public BossStageTrackerModule() {
        super("Boss Stage Tracker", "Shows under each boss whether it is immune, shielded or vulnerable",
                Category.COMBAT);
        WorldLabelAdapter.addSource(this::labels);
    }

    @Override
    public void onTick() {
        stages = BossStageAdapter.scan(range.get());
    }

    @Override
    protected void onDisable() {
        stages = List.of();
    }

    private static BooleanSetting dpsStat(String stat, String what) {
        return new BooleanSetting(stat, what + " on the nearest boss's label (needs the DPS Meter on; drag to reorder)",
                false).movable();
    }

    private boolean anyDpsLine() {
        for (BooleanSetting l : dpsLines) if (l.get()) return true;
        return false;
    }

    /** True while the DPS Meter HUD element should hide: a boss is tracked and its label carries DPS stats. */
    public boolean hidesDpsHud() {
        return isEnabled() && anyDpsLine() && hideDpsHud.get() && !stages.isEmpty();
    }

    /** One label line before it's stacked: text, its line's look and colour, and the movable setting it belongs to. */
    private record Row(String text, LineStyle look, int argb, Setting<?> head) {}

    private List<WorldLabelAdapter.Label> labels(float partialTick) {
        List<Stage> current = stages;
        if (!isEnabled() || current.isEmpty()) return List.of();
        Map<String, DpsMeterHud.Row> dps = anyDpsLine()
                ? Telemetric.get().modules().get(DpsMeterHud.class).allStatRows() : Map.of();

        List<WorldLabelAdapter.Label> out = new ArrayList<>();
        for (int i = 0; i < current.size(); i++) {
            Stage s = current.get(i);
            double[] xz = BossStageAdapter.position(s.entityId(), partialTick);
            if (xz == null) continue;
            // DPS is global, so it only goes on the nearest boss
            List<Row> rows = rows(s.kind(), healthLine.get() ? healthText(s) : null, i == 0 ? dps : Map.of());
            out.addAll(stack(rows, xz[0], s.groundY() + offset.get(), xz[1], throughWalls.get()));
        }
        return out;
    }

    /** The label lines in the user's order. {@code hp} null = no Health line. */
    private List<Row> rows(Kind kind, String hp, Map<String, DpsMeterHud.Row> dps) {
        List<Row> rows = new ArrayList<>();
        for (Setting<?> line : getSettingOrder()) {
            if (line == stageLine && stageLine.get()) {
                if (kind == Kind.DAMAGEABLE && !showDamageable.get()) continue;
                boolean blank = stageStyle.compact.get() && stageStyle.font() != BadgeText.Style.PLAIN;
                rows.add(new Row(blank ? "" : name(kind), stageStyle, color(kind), stageLine));
            } else if (line == healthLine && healthLine.get()) {
                if (hp != null) rows.add(new Row(hp, healthStyle, healthColor.get(), healthLine));
            } else if (line instanceof BooleanSetting b && b.get() && dpsLines.contains(b)) {
                DpsMeterHud.Row r = dps.get(b.getName());
                if (r == null) continue;
                int argb = dpsDamageColors.get() && r.argb() != 0 ? r.argb() : dpsColor.get();
                rows.add(new Row(r.label() + " " + r.value(), dpsStyle, argb, b));
            }
        }
        return rows;
    }

    /** Stacks the rows bottom-up: the last row stands at {@code y}, each row above it one line (+ spacing) higher. */
    private List<WorldLabelAdapter.Label> stack(List<Row> rows, double x, double y, double z, boolean walls) {
        List<WorldLabelAdapter.Label> out = new ArrayList<>(rows.size());
        for (int r = rows.size() - 1; r >= 0; r--) {
            Row row = rows.get(r);
            LineStyle l = row.look();
            boolean plain = l.font() == BadgeText.Style.PLAIN;
            out.add(new WorldLabelAdapter.Label(x, y, z, row.text(), l.font(), l.size.get(), row.argb(), walls,
                    l.shadow.get(), plain && l.bold.get(), plain && l.underline.get())
                    .look(l.shadow.get(), l.outline.get()));
            y += l.size.get() * (1 + spacing.get());
        }
        return out;
    }

    /** The Health line in the chosen format, or null when the boss's health isn't known. */
    private String healthText(Stage s) {
        boolean numbers = s.hasHp();
        if (healthFormat.is(PERCENT_HP) || !numbers) {
            return s.percent() < 0 ? null : String.format(Locale.ROOT, "%.0f%%", s.percent());
        }
        if (healthFormat.is(CURRENT_MAX)) return compact(s.hp()) + "/" + compact(s.maxHp());
        return compact(s.hp()) + " HP";
    }

    /** "950", "12.3k", "1.25M" (no thousands commas: the badge fonts don't have them). */
    static String compact(double v) {
        if (v >= 1e9) return trim(String.format(Locale.ROOT, "%.2f", v / 1e9)) + "B";
        if (v >= 1e6) return trim(String.format(Locale.ROOT, "%.2f", v / 1e6)) + "M";
        if (v >= 1e4) return trim(String.format(Locale.ROOT, "%.1f", v / 1e3)) + "k";
        return String.format(Locale.ROOT, "%.0f", v);
    }

    private static String trim(String s) {
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    private static String name(Kind kind) {
        return switch (kind) {
            case IMMUNE -> "Immune";
            case SHIELDED -> "Shielded";
            case DAMAGEABLE -> "Vulnerable";
        };
    }

    private int color(Kind kind) {
        return switch (kind) {
            case IMMUNE -> immuneColor.get();
            case SHIELDED -> shieldedColor.get();
            case DAMAGEABLE -> damageableColor.get();
        };
    }

    // ================================================================== Settings window preview

    @Override
    public SettingsPreview createPreview() {
        // framed like the player previews (a player-tall subject), so the label keeps its real size next to the blocks
        return PlayerPreview.withoutModel(EntityPreviewAdapter.playerHeight(), new PreviewLabel());
    }

    /**
     * The label as it would stand under a boss, with sample values (no boss is drawn, only its ground spot). Drag a
     * line up / down to reorder the lines (each DPS stat on its own); Shift-drag a line, or drag the stem / ground
     * ring, to set the Height Offset (with one line shown, dragging it always does); click the stage badge to see the
     * next stage.
     */
    private final class PreviewLabel implements PlayerPreview.Layer {
        private static final double MAX_HP = 13_100_000, HP = 8_420_000;
        private Kind sample = Kind.IMMUNE;
        private final List<double[]> rects = new ArrayList<>(); // GUI {x0, y0, x1, y1} per drawn line
        private final List<Setting<?>> heads = new ArrayList<>(); // the label line (movable setting) per drawn line
        private double[] ground = {0, 0}, stemTop = {0, 0};
        private double groundR;
        private boolean held, reordering;
        private Setting<?> heldHead;
        private boolean heldStage;
        private double acc, movedPx, heldY;

        private String sampleHp() {
            if (!healthLine.get()) return null;
            if (healthFormat.is(PERCENT_HP)) return String.format(Locale.ROOT, "%.0f%%", HP / MAX_HP * 100);
            if (healthFormat.is(CURRENT_MAX)) return compact(HP) + "/" + compact(MAX_HP);
            return compact(HP) + " HP";
        }

        private Map<String, DpsMeterHud.Row> sampleDps() {
            if (!anyDpsLine()) return Map.of();
            Map<String, DpsMeterHud.Row> live = Telemetric.get().modules().get(DpsMeterHud.class).allStatRows();
            if (!live.isEmpty()) return live;
            return Map.of(
                    DpsMeterHud.DPS, new DpsMeterHud.Row(DpsMeterHud.DPS, "48.2k", 0),
                    DpsMeterHud.MAX_HIT, new DpsMeterHud.Row(DpsMeterHud.MAX_HIT, "212k", 0),
                    DpsMeterHud.LAST_HIT, new DpsMeterHud.Row(DpsMeterHud.LAST_HIT, "31.7k", 0),
                    DpsMeterHud.TOTAL, new DpsMeterHud.Row(DpsMeterHud.TOTAL, "1.24M", 0),
                    DpsMeterHud.HITS, new DpsMeterHud.Row(DpsMeterHud.HITS, "57", 0));
        }

        private int stageIndex = -1; // index of the stage badge in the last labels(), or -1

        /** The label lines bottom first (as stacked), filling {@link #heads} and {@link #stageIndex} to match. */
        private List<WorldLabelAdapter.Label> labels() {
            if (sample == Kind.DAMAGEABLE && !showDamageable.get()) sample = Kind.IMMUNE;
            List<Row> rows = rows(sample, sampleHp(), sampleDps());
            stageIndex = -1;
            heads.clear();
            for (int i = rows.size() - 1; i >= 0; i--) {
                if (rows.get(i).look() == stageStyle) stageIndex = rows.size() - 1 - i;
                heads.add(rows.get(i).head());
            }
            return stack(rows, 0, offset.get(), 0, true);
        }

        /** The label lines shown, top to bottom, each once. */
        private List<Setting<?>> shownHeads() {
            List<Setting<?>> out = new ArrayList<>();
            for (int i = heads.size() - 1; i >= 0; i--) if (!out.contains(heads.get(i))) out.add(heads.get(i));
            return out;
        }

        /** The GUI y centre of all drawn lines of {@code head} (last frame), or NaN. */
        private double centreOf(Setting<?> head) {
            double sum = 0;
            int n = 0;
            for (int i = 0; i < rects.size() && i < heads.size(); i++) {
                if (heads.get(i) != head) continue;
                sum += (rects.get(i)[1] + rects.get(i)[3]) / 2;
                n++;
            }
            return n == 0 ? Double.NaN : sum / n;
        }

        @Override
        public double topRoom() {
            double h = Math.max(0, offset.get());
            for (WorldLabelAdapter.Label l : labels()) h = Math.max(h, l.y() + l.height());
            return h + 0.1;
        }

        @Override
        public String hint() {
            String move = shownHeads().size() > 1 ? "Drag a line: reorder · Shift-drag: height" : "Drag: height";
            return stageLine.get() ? move + " · Click badge: stage" : move;
        }

        @Override
        public void render(Render2D r, GuiContext ctx, PlayerPreview.View v, int mouseX, int mouseY) {
            Theme p = ctx.palette();
            // the boss's ground spot: a small ring on the floor, and a stem up to the label
            boolean stemHot = !held && overStem(mouseX, mouseY);
            int ring = ColorUtil.fade(p.accent(), stemHot || (held && !reordering) ? 0.9 : 0.55);
            double[] prev = null;
            for (int i = 0; i <= 32; i++) {
                double a = i / 32.0 * Math.PI * 2;
                double[] pt = v.project(Math.cos(a) * 0.5, 0, Math.sin(a) * 0.5);
                if (prev != null) PlayerPreview.line(r, prev[0], prev[1], pt[0], pt[1], ring);
                prev = pt;
            }
            ground = v.project(0, 0, 0);
            groundR = Math.max(5, v.scale() * 0.5);
            r.rect((int) Math.round(ground[0]) - 1, (int) Math.round(ground[1]) - 1, 3, 3, ring);
            stemTop = v.project(0, Math.max(0, offset.get()), 0);
            if (offset.get() > 0.05) PlayerPreview.line(r, ground[0], ground[1], stemTop[0], stemTop[1], ColorUtil.fade(p.textMuted(), 0.35));

            rects.clear();
            List<WorldLabelAdapter.Label> labels = labels();
            if (labels.isEmpty()) {
                r.textCentered("All label lines are off", (int) Math.round(ground[0]), (int) Math.round(ground[1]) - 20, p.textDisabled());
                return;
            }
            int hot = held ? -1 : lineAt(mouseX, mouseY);
            Setting<?> hotHead = hot >= 0 && hot < heads.size() ? heads.get(hot) : null;
            for (int i = 0; i < labels.size(); i++) {
                WorldLabelAdapter.Label l = labels.get(i);
                double k = v.scale() * l.height() / 8.0; // GUI px per label px
                double[] a = v.project(0, l.y(), 0);
                int lw = r.worldLabelWidth(l);
                double left = a[0] - lw * k / 2, top = a[1] - 8 * k;
                rects.add(new double[] {left, top, left + lw * k, a[1]});
                Setting<?> head = i < heads.size() ? heads.get(i) : null;
                boolean lit = held ? (!reordering || head == heldHead) : head == hotHead;
                if (lit) {
                    r.rect((int) Math.floor(left) - 1, (int) Math.floor(top) - 1, (int) Math.ceil(lw * k) + 2,
                            (int) Math.ceil(8 * k) + 2, ColorUtil.fade(p.accent(), held ? 0.25 : 0.14));
                }
                r.pushTransform((float) left, (float) top, (float) k);
                try {
                    if (l.outline()) {
                        WorldLabelAdapter.Label black = new WorldLabelAdapter.Label(0, 0, 0, l.text(), l.style(),
                                l.height(), 0xFF000000, true, false, l.bold(), l.underline());
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dy = -1; dy <= 1; dy++) if (dx != 0 || dy != 0) r.worldLabel(black, dx, dy);
                        }
                    }
                    r.worldLabel(l, 0, 0);
                } finally {
                    r.popTransform();
                }
            }
            if (hot >= 0) {
                boolean reorder = shownHeads().size() > 1;
                String what = reorder ? "Drag up / down: reorder the lines. Shift-drag: Height Offset ("
                        + offset.format() + ")" : "Drag up / down: Height Offset (" + offset.format() + ")";
                ctx.requestTooltip(this, what + (hot == stageIndex ? ". Click: next stage" : ""));
            } else if (stemHot) {
                ctx.requestTooltip(this, "Drag up / down: Height Offset (" + offset.format() + ")");
            }
        }

        private int lineAt(double mx, double my) {
            for (int i = 0; i < rects.size(); i++) {
                double[] b = rects.get(i);
                if (mx >= b[0] - 2 && mx < b[2] + 2 && my >= b[1] - 1 && my < b[3] + 1) return i;
            }
            return -1;
        }

        /** Over the ground ring or the stem up to the label. */
        private boolean overStem(double mx, double my) {
            double dx = mx - ground[0], dy = my - ground[1];
            if (Math.abs(dx) <= groundR && Math.abs(dy) <= Math.max(4, groundR * 0.4)) return true;
            double lo = Math.min(ground[1], stemTop[1]), hi = Math.max(ground[1], stemTop[1]);
            return Math.abs(mx - (ground[0] + stemTop[0]) / 2) <= 3 && my >= lo && my <= hi;
        }

        @Override
        public boolean grab(PlayerPreview.View v, double mouseX, double mouseY) {
            int i = lineAt(mouseX, mouseY);
            boolean stem = i < 0 && overStem(mouseX, mouseY);
            if (i < 0 && !stem) return false;
            held = true;
            heldStage = i >= 0 && i == stageIndex;
            heldHead = i >= 0 && i < heads.size() ? heads.get(i) : null;
            reordering = heldHead != null && !InputUtil.isShiftDown() && shownHeads().size() > 1;
            acc = offset.get();
            heldY = mouseY;
            movedPx = 0;
            return true;
        }

        @Override
        public void drag(PlayerPreview.View v, double dx, double dy) {
            movedPx += Math.abs(dx) + Math.abs(dy);
            if (!reordering) {
                acc = Math.clamp(acc - dy * v.blocksPerPixelUp(), offset.getMin(), offset.getMax());
                offset.set(acc);
                return;
            }
            heldY += dy;
            // swap with the neighbour line once the mouse passes its middle
            List<Setting<?>> shown = shownHeads();
            int j = shown.indexOf(heldHead);
            if (j < 0) return;
            List<Setting<?>> order = getSettingOrder();
            if (j > 0 && heldY < centreOf(shown.get(j - 1))) {
                moveSetting(heldHead, order.indexOf(shown.get(j - 1)));
            } else if (j < shown.size() - 1 && heldY > centreOf(shown.get(j + 1))) {
                moveSetting(heldHead, order.indexOf(shown.get(j + 1)));
            }
        }

        @Override
        public void release(PlayerPreview.View v) {
            if (held && heldStage && movedPx < 3) {
                sample = switch (sample) {
                    case IMMUNE -> Kind.SHIELDED;
                    case SHIELDED -> showDamageable.get() ? Kind.DAMAGEABLE : Kind.IMMUNE;
                    case DAMAGEABLE -> Kind.IMMUNE;
                };
            }
            held = false;
            reordering = false;
            heldHead = null;
        }
    }
}
