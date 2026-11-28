package dev.telemetric.game;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.telemetric.Telemetric;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds the item stacks the profile screen draws: a paper stack with the Telos item model, name, lore and tooltip
 * style, so the vanilla tooltip renders it exactly like the item in game (stat glyphs and translate keys come from the
 * server resource pack, so they only look right while it is loaded — i.e. on Telos).
 */
public final class ProfileItemAdapter {

    /** Telos's lore divider glyphs: a run of {@link #BAR_FILL} with one {@link #BAR_MID} after the first half. */
    private static final String BAR_FILL = "𖉄", BAR_MID = "𖉃";

    private ProfileItemAdapter() {}

    /**
     * One lore line.
     *
     * @param json a JSON text component, null for a divider bar
     * @param wrap ability text: word-wrapped to the tooltip width
     */
    public record Line(String json, boolean wrap) {
        public static final Line BAR = new Line(null, false);
    }

    /**
     * Builds the stack laid out like Telos does in game: the tooltip width is the widest fixed line (or the name),
     * ability text is word-wrapped to it, divider bars are sized to it and the name is centred over it with spaces.
     *
     * @param model        item model id ({@code telos:material/…})
     * @param nameJson     the name as a JSON text component
     * @param lore         the lore lines
     * @param barColor     divider colour ({@code #RRGGBB} / named), or null
     * @param tooltipStyle tooltip sprite set ({@code telos:untiered/royal}), or null for the vanilla look
     */
    public static GameItem build(String model, String nameJson, List<Line> lore, String barColor, String tooltipStyle) {
        ItemStack stack = new ItemStack(Items.PAPER);
        Identifier id = model == null ? null : Identifier.tryParse(model);
        if (id != null) stack.set(DataComponents.ITEM_MODEL, id);
        Font font = Minecraft.getInstance().font;
        Component name = parse(nameJson);

        // parse, then measure the fixed lines
        List<Component> parsed = new ArrayList<>(lore.size());
        int width = name == null ? 0 : font.width(name);
        for (Line l : lore) {
            Component c = l.json() == null ? null : parse(l.json());
            parsed.add(c);
            if (c != null && !l.wrap()) width = Math.max(width, font.width(c));
        }
        if (width <= 0) width = 150;

        Style barStyle = Style.EMPTY.withItalic(false);
        if (barColor != null) {
            TextColor c = TextColor.parseColor(barColor).result().orElse(null);
            if (c != null) barStyle = barStyle.withColor(c);
        }
        Component bar = Component.literal(bar(font, width)).withStyle(barStyle);

        List<Component> lines = new ArrayList<>(lore.size());
        for (int i = 0; i < lore.size(); i++) {
            Line l = lore.get(i);
            Component c = parsed.get(i);
            if (l.json() == null) {
                // never two dividers in a row (a dropped line can leave one), never one first
                if (!lines.isEmpty() && lines.getLast() != bar) lines.add(bar);
            } else if (c != null) {
                if (l.wrap()) for (Component w : wrap(font, c, width)) lines.add(plain(w));
                else lines.add(plain(c));
            }
        }
        while (!lines.isEmpty() && lines.getLast() == bar) lines.removeLast();
        if (!lines.isEmpty()) stack.set(DataComponents.LORE, new ItemLore(lines));
        if (name != null) stack.set(DataComponents.CUSTOM_NAME, plain(centred(font, name, width)));
        Identifier style = tooltipStyle == null ? null : Identifier.tryParse(tooltipStyle);
        if (style != null) stack.set(DataComponents.TOOLTIP_STYLE, style);
        return new GameItem(stack);
    }

    /**
     * Vanilla draws lore italic dark purple (and custom names italic) unless a line says otherwise; server lines don't
     * always. Wrapping each line in a non-italic white root gives the in-game look; the runs keep their own styles.
     */
    private static Component plain(Component c) {
        return Component.empty().withStyle(Style.EMPTY.withItalic(false).withColor(TextColor.fromRgb(0xFFFFFF))).append(c);
    }

    /** A divider about {@code width} px wide: fill glyphs with the middle glyph after the first half (2 + 1 + 3, 19 + 1 + 19). */
    private static String bar(Font font, int width) {
        int gw = Math.max(1, font.width(BAR_FILL));
        int n = Math.max(3, Math.round(width / (float) gw));
        int left = (n - 1) / 2, right = n - 1 - left;
        return BAR_FILL.repeat(left) + BAR_MID + BAR_FILL.repeat(right);
    }

    /** The name padded with spaces on both sides (one more on the right if odd) so it sits centred over {@code width}. */
    private static Component centred(Font font, Component name, int width) {
        int space = Math.max(1, font.width(" "));
        int free = Math.max(0, width - font.width(name)) / space;
        int left = free / 2, right = free - left;
        if (free == 0) return name;
        return Component.empty().withStyle(Style.EMPTY.withItalic(false))
                .append(Component.literal(" ".repeat(left))).append(name).append(Component.literal(" ".repeat(right)));
    }

    /** Word-wraps a styled line to {@code width} px, keeping each run's style. */
    private static List<Component> wrap(Font font, Component line, int width) {
        if (font.width(line) <= width) return List.of(line);
        List<Component> out = new ArrayList<>();
        for (FormattedText part : font.getSplitter().splitLines(line, width, Style.EMPTY)) {
            MutableComponent c = Component.empty().withStyle(Style.EMPTY.withItalic(false));
            part.visit((style, text) -> {
                c.append(Component.literal(text).withStyle(style));
                return Optional.empty();
            }, Style.EMPTY);
            out.add(c);
        }
        return out.isEmpty() ? List.of(line) : out;
    }

    private static Component parse(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).result().orElse(null);
        } catch (RuntimeException e) {
            Telemetric.LOGGER.debug("Bad profile lore line {}", json, e);
            return null;
        }
    }
}
