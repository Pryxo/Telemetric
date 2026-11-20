package dev.telemetric.ui.render;

import java.util.Locale;

/**
 * Badge fonts for floating world labels (pure logic, no MC). The fonts {@code telemetric:pill} and
 * {@code telemetric:ribbon} ({@code assets/telemetric/font/*.json}) are bitmap fonts where each badge glyph carries its
 * own slice of background: a white body (which becomes the tint colour) with the character cut out in grey.
 * A label is encoded as {@code [left cap][glyph]…[right cap]} with U+200C between every glyph. That character has an
 * advance of −1 in the font and cancels the 1 px gap Minecraft puts after each bitmap glyph, so the slices join into
 * one solid badge. Draw the result as normal text in that font (see {@code game/WorldLabelAdapter}).
 * <p>
 * Atlas (128×256, 8×8 cells): rows 4–7 hold the badge glyphs, mapped to U+E030… in this order:
 * {@code ABCDEFGHIJKLMNOP}, {@code QRSTUVWXYZ?[]/%&}, {@code 0123456789!()<=>}, then {@code +-.} at U+E080, and the
 * left cap / filler / right cap at U+E060–E062.
 */
public final class BadgeText {

    /** The font of a world label. {@link #PLAIN} is Minecraft's normal font, with no badge. */
    public enum Style {
        PILL("pill"),
        RIBBON("ribbon"),
        PLAIN(null);

        /** The font id path in the {@code telemetric} namespace, or null for the vanilla font. */
        public final String font;

        Style(String font) {
            this.font = font;
        }
    }

    private static final char JOIN = '‌';
    private static final char LEFT_CAP = '', FILLER = '', RIGHT_CAP = '';
    private static final String[] ROWS = {"ABCDEFGHIJKLMNOP", "QRSTUVWXYZ?[]/%&", "0123456789!()<=>"};

    private BadgeText() {}

    /**
     * The badge glyph string for {@code text} (case-insensitive). Characters the font doesn't have (spaces too) become
     * filler. Empty text gives a small blank badge.
     */
    public static String encode(String text) {
        String s = text == null ? "" : text.toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(s.length() * 2 + 4).append(LEFT_CAP);
        if (s.isEmpty()) sb.append(JOIN).append(FILLER);
        for (int i = 0; i < s.length(); i++) sb.append(JOIN).append(glyph(s.charAt(i)));
        return sb.append(JOIN).append(RIGHT_CAP).toString();
    }

    private static char glyph(char c) {
        for (int r = 0; r < ROWS.length; r++) {
            int i = ROWS[r].indexOf(c);
            if (i >= 0) return (char) (0xE030 + r * 16 + i);
        }
        return switch (c) {
            case '+' -> '';
            case '-' -> '';
            case '.' -> '';
            default -> FILLER;
        };
    }
}
