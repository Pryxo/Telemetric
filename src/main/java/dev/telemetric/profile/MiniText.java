package dev.telemetric.profile;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;

/**
 * A tiny MiniMessage reader for Telos trait descriptions ({@code <!italic><#BCECEA>+20.0%<white>…</white></#BCECEA>}):
 * colour tags ({@code <#RRGGBB>}, named colours), {@code bold}/{@code italic} and their negations, closing tags. Other
 * tags are dropped. Gives JSON text runs.
 */
final class MiniText {

    private static final Set<String> NAMED = Set.of("black", "dark_blue", "dark_green", "dark_aqua", "dark_red",
            "dark_purple", "gold", "gray", "grey", "dark_gray", "dark_grey", "blue", "green", "aqua", "red",
            "light_purple", "yellow", "white");

    private MiniText() {}

    private record Style(String color, Boolean bold, Boolean italic) {}

    static JsonArray toJson(String mini) {
        JsonArray out = new JsonArray();
        if (mini == null || mini.isEmpty()) return out;
        Deque<Style> stack = new ArrayDeque<>();
        stack.push(new Style(null, null, false));
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < mini.length()) {
            char c = mini.charAt(i);
            int close = c == '<' ? mini.indexOf('>', i) : -1;
            if (close < 0) {
                text.append(c);
                i++;
                continue;
            }
            emit(out, text, stack.peek());
            String tag = mini.substring(i + 1, close).toLowerCase(Locale.ROOT).trim();
            i = close + 1;
            if (tag.startsWith("/")) {
                if (stack.size() > 1) stack.pop();
                continue;
            }
            Style top = stack.peek();
            boolean neg = tag.startsWith("!");
            String name = neg ? tag.substring(1) : tag;
            if (name.startsWith("#") && name.length() == 7) stack.push(new Style(name, top.bold(), top.italic()));
            else if (NAMED.contains(name)) stack.push(new Style(name.replace("grey", "gray"), top.bold(), top.italic()));
            else if (name.equals("bold") || name.equals("b")) stack.push(new Style(top.color(), !neg, top.italic()));
            else if (name.equals("italic") || name.equals("i") || name.equals("em")) stack.push(new Style(top.color(), top.bold(), !neg));
            else if (name.startsWith("color:")) stack.push(new Style(name.substring(6), top.bold(), top.italic()));
            else stack.push(top); // unknown tag: keep the style so its closing tag pops evenly
        }
        emit(out, text, stack.peek());
        return out;
    }

    private static void emit(JsonArray out, StringBuilder text, Style s) {
        if (text.isEmpty()) return;
        JsonObject o = new JsonObject();
        o.addProperty("text", text.toString());
        if (s.color() != null) o.addProperty("color", s.color());
        if (s.bold() != null) o.addProperty("bold", s.bold());
        if (s.italic() != null) o.addProperty("italic", s.italic());
        out.add(o);
        text.setLength(0);
    }
}
