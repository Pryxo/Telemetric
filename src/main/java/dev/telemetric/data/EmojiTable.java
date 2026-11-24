package dev.telemetric.data;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code tables/emojis.json}: the Telos chat emojis (from the /wiki Emoji page), in their wiki categories. {@code code}
 * is what you type in chat (the server turns it into the emoji), {@code glyph} is the resource-pack character it
 * shows as. Used by the chat Emoji Picker. Pure Java.
 */
public record EmojiTable(List<Category> categories) {

    static final EmojiTable EMPTY = new EmojiTable(List.of());

    public record Emoji(String code, String glyph) {}

    public record Category(String name, List<Emoji> emojis) {}

    static EmojiTable parse(JsonObject root) {
        List<Category> categories = new ArrayList<>();
        for (JsonObject c : JsonFields.objects(root, "categories")) {
            List<Emoji> emojis = new ArrayList<>();
            for (JsonObject e : JsonFields.objects(c, "emojis")) {
                String code = JsonFields.str(e, "code");
                String glyph = JsonFields.str(e, "glyph");
                if (!code.isEmpty() && !glyph.isEmpty()) emojis.add(new Emoji(code, glyph));
            }
            if (!emojis.isEmpty()) categories.add(new Category(JsonFields.str(c, "name"), List.copyOf(emojis)));
        }
        return new EmojiTable(List.copyOf(categories));
    }

    /** The emoji typed as {@code code}, or null. */
    public Emoji byCode(String code) {
        for (Category c : categories) for (Emoji e : c.emojis()) if (e.code().equals(code)) return e;
        return null;
    }
}
