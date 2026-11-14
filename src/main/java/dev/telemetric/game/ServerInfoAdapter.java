package dev.telemetric.game;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import dev.telemetric.Telemetric;
import dev.telemetric.hud.HudIcon;
import dev.telemetric.hud.TextRun;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * MC ADAPTER — client/server facts for HUD elements: FPS, own ping and the stats Telos Realms
 * publishes in the Tab list.
 * <p>
 * Telos fills the Tab list with fake entries whose display names read {@code "➜ Label: value (extra)"}.
 * Parsing rules ({@link #parseEntry}):
 * <ul>
 *   <li>invisible spacing characters (BMP private use, Yi block) are dropped,</li>
 *   <li>{@link ChatFormatting#DARK_GRAY} segments are dropped (the {@code (42.1%)} extras),
 *       plus any trailing {@code (...)} as a fallback,</li>
 *   <li>the first pack glyph before the colon (custom font or supplementary-plane char) becomes the icon;
 *       other glyphs and leading symbols (arrows, dots) before the label are stripped,</li>
 *   <li>the value keeps every styled piece (colour + font) as {@link TextRun}s, so multi-coloured values
 *       render exactly like the Tab list,</li>
 *   <li>entries without {@code ':'} (section titles, real players) are ignored.</li>
 * </ul>
 * The snapshot is rebuilt at most every {@link #REFRESH_MS} ms. Each label's raw segments are logged once.
 */
public final class ServerInfoAdapter {

    /**
     * One parsed Tab-list stat. Colours are ARGB, or {@link #NO_COLOR} if the server sent none.
     *
     * @param value     plain value text (glyphs removed) — for logic and fallbacks
     * @param valueRuns the value as the server styled it (colours, fonts, inline glyphs)
     * @param icon      glyph shown in front of the label in the Tab list, or null
     */
    public record TabStat(String label, String value, int labelColor, int valueColor,
                          List<TextRun> valueRuns, HudIcon.Glyph icon) {}

    public static final int NO_COLOR = 0;
    private static final long REFRESH_MS = 250;
    private static final int DARK_GRAY = 0x555555;
    private static final String UNSUPPORTED_FONT = "";
    private static final Set<String> LOGGED_LABELS = new HashSet<>();

    private static Map<String, TabStat> cache = Collections.emptyMap();
    private static long lastRefresh;

    private ServerInfoAdapter() {}

    public static int fps() {
        return Minecraft.getInstance().getFps();
    }

    /** Own latency in ms as reported by the server, or -1 outside multiplayer / when unknown. */
    public static int ping() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener conn = mc.getConnection();
        if (conn == null || mc.player == null) return -1;
        PlayerInfo info = conn.getPlayerInfo(mc.player.getUUID());
        return info == null ? -1 : info.getLatency();
    }

    public static boolean isConnected() {
        return Minecraft.getInstance().getConnection() != null;
    }

    /** Parsed Tab-list stats keyed by lower-case label, in Tab order. Never null. */
    public static Map<String, TabStat> tabStats() {
        long now = System.currentTimeMillis();
        if (now - lastRefresh >= REFRESH_MS) {
            lastRefresh = now;
            cache = readTabList();
        }
        return cache;
    }

    /** Case-insensitive lookup of one stat. */
    public static Optional<TabStat> tabStat(String label) {
        return Optional.ofNullable(tabStats().get(label.toLowerCase(Locale.ROOT)));
    }

    private static Map<String, TabStat> readTabList() {
        ClientPacketListener conn = Minecraft.getInstance().getConnection();
        if (conn == null) return Collections.emptyMap();
        Map<String, TabStat> out = new LinkedHashMap<>();
        try {
            for (PlayerInfo info : conn.getListedOnlinePlayers()) {
                Component name = info.getTabListDisplayName();
                if (name == null) continue;
                TabStat stat = parseEntry(name);
                if (stat != null) out.putIfAbsent(stat.label().toLowerCase(Locale.ROOT), stat);
            }
        } catch (RuntimeException e) {
            // concurrent packet handling or odd components — keep the last good snapshot
            return cache;
        }
        return Collections.unmodifiableMap(out);
    }

    /** One code point with the style it was sent in. {@code font} null = default font. */
    private record Cell(int cp, int color, String font) {
        boolean whitespace() {
            return Character.isWhitespace(cp);
        }

        /** Resource-pack "shortcut" glyph: anything in a custom font, or a supplementary-plane char. */
        boolean glyph() {
            return font != null || Character.isSupplementaryCodePoint(cp);
        }
    }

    private static TabStat parseEntry(Component component) {
        List<Cell> cells = new ArrayList<>();
        component.visit((style, segment) -> {
            int rgb = rgb(style);
            if (rgb == DARK_GRAY) return Optional.empty();
            String font = fontId(style);
            if (UNSUPPORTED_FONT.equals(font)) return Optional.empty();
            int argb = rgb < 0 ? NO_COLOR : 0xFF000000 | rgb;
            segment.codePoints().forEach(cp -> {
                if (!isSpacer(cp)) cells.add(new Cell(cp, argb, font));
            });
            return Optional.empty();
        }, Style.EMPTY);

        int colon = -1;
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            if (c.cp() == ':' && c.font() == null) { colon = i; break; }
        }
        if (colon <= 0) return null;

        // ---- label (+ the icon glyph in front of it)
        HudIcon.Glyph icon = null;
        StringBuilder label = new StringBuilder();
        int labelColor = NO_COLOR;
        boolean started = false;
        for (int i = 0; i < colon; i++) {
            Cell c = cells.get(i);
            if (c.glyph()) {
                if (icon == null) icon = new HudIcon.Glyph(Character.toString(c.cp()), c.font());
                continue;
            }
            if (!started && !Character.isLetterOrDigit(c.cp())) continue; // leading arrows / dots
            started = true;
            label.appendCodePoint(c.cp());
            if (labelColor == NO_COLOR && !c.whitespace()) labelColor = c.color();
        }
        String labelText = label.toString().replaceAll("\\s+", " ").trim();

        // ---- value: every styled cell after the colon (whitespace collapsed, trailing "(...)" dropped)
        List<Cell> value = new ArrayList<>();
        for (int i = colon + 1; i < cells.size(); i++) {
            Cell c = cells.get(i);
            if (c.whitespace() && (value.isEmpty() || value.getLast().whitespace())) continue;
            value.add(c.whitespace() && c.cp() != ' ' ? new Cell(' ', c.color(), c.font()) : c);
        }
        trimTrailingWhitespace(value);
        stripTrailingParens(value);
        if (labelText.isEmpty() || value.isEmpty()) return null;

        StringBuilder plain = new StringBuilder();
        int valueColor = NO_COLOR;
        for (Cell c : value) {
            if (c.glyph()) continue;
            plain.appendCodePoint(c.cp());
            if (valueColor == NO_COLOR && !c.whitespace()) valueColor = c.color();
        }

        TabStat stat = new TabStat(labelText, plain.toString().trim(), labelColor, valueColor, toRuns(value), icon);
        if (LOGGED_LABELS.add(stat.label().toLowerCase(Locale.ROOT))) {
            Telemetric.LOGGER.info("Tab stat '{}': {}", stat.label(), describe(component));
        }
        return stat;
    }

    private static List<TextRun> toRuns(List<Cell> cells) {
        List<TextRun> runs = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        Cell first = null;
        for (Cell c : cells) {
            if (first != null && (first.color() != c.color() || !Objects.equals(first.font(), c.font()))) {
                runs.add(new TextRun(text.toString(), first.color(), first.font()));
                text.setLength(0);
                first = null;
            }
            if (first == null) first = c;
            text.appendCodePoint(c.cp());
        }
        if (first != null) runs.add(new TextRun(text.toString(), first.color(), first.font()));
        return List.copyOf(runs);
    }

    private static void trimTrailingWhitespace(List<Cell> cells) {
        while (!cells.isEmpty() && cells.getLast().whitespace()) cells.removeLast();
    }

    /** Drops a trailing {@code (...)} group (the extras), unless it is the whole value. */
    private static void stripTrailingParens(List<Cell> cells) {
        if (cells.isEmpty() || cells.getLast().cp() != ')') return;
        for (int i = cells.size() - 2; i > 0; i--) {
            int cp = cells.get(i).cp();
            if (cp == ')') return;
            if (cp == '(') {
                cells.subList(i, cells.size()).clear();
                trimTrailingWhitespace(cells);
                return;
            }
        }
    }

    static int rgb(Style style) {
        TextColor color = style.getColor();
        return color == null ? -1 : color.getValue() & 0xFFFFFF;
    }

    /** null = default font; {@link #UNSUPPORTED_FONT} for non-resource fonts (sprites) we can't redraw. */
    private static String fontId(Style style) {
        FontDescription font = style.getFont();
        if (font == null || FontDescription.DEFAULT.equals(font)) return null;
        if (font instanceof FontDescription.Resource res) return res.id().toString();
        return UNSUPPORTED_FONT;
    }

    /** Invisible (negative-)spacing characters resource packs use for layout (BMP private use, Yi block). */
    static boolean isSpacer(int cp) {
        return (cp >= 0xE000 && cp <= 0xF8FF) || (cp >= 0xA400 && cp <= 0xA4CF) || cp == 0x200B;
    }

    /** Debug dump of an entry's segments (text, colour, font) — logged once per label to help mapping. */
    static String describe(Component component) {
        StringBuilder sb = new StringBuilder();
        component.visit((style, segment) -> {
            sb.append('[');
            segment.codePoints().forEach(cp -> {
                if (cp >= 0x20 && cp < 0x7F) sb.appendCodePoint(cp);
                else sb.append("\\u").append(Integer.toHexString(cp).toUpperCase(Locale.ROOT));
            });
            int rgb = rgb(style);
            sb.append(" | ").append(rgb < 0 ? "none" : String.format("#%06X", rgb));
            String font = fontId(style);
            if (font != null) sb.append(" | ").append(font);
            sb.append(']');
            return Optional.empty();
        }, Style.EMPTY);
        return sb.toString();
    }
}
