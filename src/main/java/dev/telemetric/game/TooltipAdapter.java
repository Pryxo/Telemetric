package dev.telemetric.game;

import dev.telemetric.Telemetric;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Item tooltips. Features add lines through {@link #addEditor}: they get the tooltip's plain lines and return
 * {@link Insert}s. The adapter word-wraps each one to the tooltip's width and styles it like the part of the line it
 * belongs to. On Telos only.
 */
public final class TooltipAdapter {

    /**
     * Text to insert under line {@code after} (an index into the plain lines), styled like the part of that line whose
     * text is {@code styleOf} (gray if it isn't found).
     */
    public record Insert(int after, String text, String styleOf) {}

    private static final List<Function<List<String>, List<Insert>>> EDITORS = new CopyOnWriteArrayList<>();
    private static boolean registered;

    private TooltipAdapter() {}

    private static volatile float tooltipScale = 1f;

    public static void setTooltipScale(float s) { tooltipScale = Math.max(0.25f, Math.min(3f, s)); }

    public static float tooltipScale() { return tooltipScale; }

    public static synchronized void addEditor(Function<List<String>, List<Insert>> editor) {
        EDITORS.add(editor);
        if (registered) return;
        registered = true;
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> edit(lines));
    }

    private static void edit(List<Component> lines) {
        if (lines.isEmpty() || !ServerGate.onTelos()) return;
        List<String> plain = new ArrayList<>(lines.size());
        for (Component c : lines) plain.add(c.getString());
        List<Insert> inserts = new ArrayList<>();
        for (Function<List<String>, List<Insert>> e : EDITORS) {
            try {
                List<Insert> l = e.apply(plain);
                if (l != null) inserts.addAll(l);
            } catch (RuntimeException ex) {
                Telemetric.LOGGER.error("Tooltip editor failed", ex);
            }
        }
        if (inserts.isEmpty()) return;
        Font font = Minecraft.getInstance().font;
        int width = Math.max(120, font.width(lines.getFirst()));
        inserts.sort(Comparator.comparingInt(Insert::after).reversed()); // bottom first, so the indices stay valid
        for (Insert in : inserts) {
            if (in.after() < 0 || in.after() >= lines.size()) continue;
            Style style = styleOf(lines.get(in.after()), in.styleOf());
            List<String> wrapped = wrap(font, in.text(), style, width);
            for (int i = wrapped.size() - 1; i >= 0; i--) {
                lines.add(in.after() + 1, Component.literal(wrapped.get(i)).withStyle(style));
            }
        }
    }

    /** Style of the part of {@code line} that reads {@code text} (or else its last coloured part, or else gray). */
    private static Style styleOf(Component line, String text) {
        Style[] found = {null}, lastColoured = {null};
        line.visit((style, part) -> {
            if (style.getColor() != null) lastColoured[0] = style;
            if (text != null && part.strip().equalsIgnoreCase(text.strip())) {
                found[0] = style;
                return java.util.Optional.of(Boolean.TRUE);
            }
            return java.util.Optional.empty();
        }, Style.EMPTY);
        Style s = found[0] != null && found[0].getColor() != null ? found[0] : lastColoured[0];
        return (s != null ? s : Style.EMPTY.withColor(ChatFormatting.GRAY)).withBold(false).withUnderlined(false);
    }

    private static List<String> wrap(Font font, String text, Style style, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && font.width(Component.literal(candidate).withStyle(style)) > width) {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(candidate);
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }
}
