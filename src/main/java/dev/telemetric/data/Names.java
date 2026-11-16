package dev.telemetric.data;

import java.util.Locale;

/** Name matching shared by every table lookup. Pure Java. */
public final class Names {

    private Names() {}

    /** Match key: lower-case letters and digits only ("Golden Freddy" → "goldenfreddy", "Kobold's Den" → "koboldsden"). */
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
     * Match key of a place name as the HUD shows it: {@link #key} without a leading "The" and a trailing
     * ": Part …" ("The Aviary" → "aviary", "Rustborn Kingdom: Part II" → "rustbornkingdom").
     */
    public static String placeKey(String s) {
        if (s == null) return "";
        String t = s.trim();
        int part = t.toLowerCase(Locale.ROOT).indexOf(": part");
        if (part > 0) t = t.substring(0, part);
        if (t.regionMatches(true, 0, "the ", 0, 4)) t = t.substring(4);
        return key(t);
    }
}
