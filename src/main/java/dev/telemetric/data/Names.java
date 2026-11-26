package dev.telemetric.data;

import java.util.Locale;

/** Name matching shared by every table lookup. */
public final class Names {

    private Names() {}

    /** Match key, only lower-case letters and digits ("Golden Freddy" → "goldenfreddy", "Kobold's Den" → "koboldsden"). */
    public static String key(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) b.append(c);
        }
        return b.toString();
    }

    /**
     * Match key of a place name as the HUD shows it: {@link #key} without a leading "The" or a trailing ": Part …"
     * ("The Aviary" → "aviary", "Rustborn Kingdom: Part II" → "rustbornkingdom").
     */
    public static String placeKey(String s) {
        if (s == null) return "";
        String t = s.trim();
        int part = t.toLowerCase(Locale.ROOT).indexOf(": part");
        if (part > 0) t = t.substring(0, part);
        return partKey(t);
    }

    /**
     * Like {@link #placeKey}, but keeps a trailing ": Part …" ("Rustborn Kingdom: Part II" → "rustbornkingdompartii",
     * "The Aviary" → "aviary"). Same as {@link #placeKey} for names without a part.
     */
    public static String partKey(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.regionMatches(true, 0, "the ", 0, 4)) t = t.substring(4);
        return key(t);
    }
}
