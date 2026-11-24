package dev.telemetric.data;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code tables/emojis.json}: the Telos chat emojis (from the /wiki Emoji page), in their wiki categories. {@code code}
 * is what you type in chat (the server turns it into the emoji), {@code glyph} is the resource-pack character it
 * shows as. Used by the chat Emoji Picker. Pure Java.
 *
 * <p>{@code tables/modEmojis.json} uses the same format for client-side mod emojis (Melinoe): the server leaves their
 * codes as text and the client turns them into the glyph ({@code ChatHudAdapter#modEmojis}). A category may name an
 * {@code icon} texture (path in {@code assets/telemetric/}) for the picker rail.
 */
public record EmojiTable(List<Category> categories) {

    static final EmojiTable EMPTY = new EmojiTable(List.of());

    public record Emoji(String code, String glyph) {}

    /** {@code icon}: texture path for the picker rail, or empty (then the first emoji is the icon). */
    public record Category(String name, String icon, List<Emoji> emojis) {}

    static EmojiTable parse(JsonObject root) {
        List<Category> categories = new ArrayList<>();
        for (JsonObject c : JsonFields.objects(root, "categories")) {
            List<Emoji> emojis = new ArrayList<>();
            for (JsonObject e : JsonFields.objects(c, "emojis")) {
                String code = JsonFields.str(e, "code");
                String glyph = JsonFields.str(e, "glyph");
                if (!code.isEmpty() && !glyph.isEmpty()) emojis.add(new Emoji(code, glyph));
            }
            if (!emojis.isEmpty()) categories.add(new Category(JsonFields.str(c, "name"), JsonFields.str(c, "icon"), List.copyOf(emojis)));
        }
        return new EmojiTable(List.copyOf(categories));
    }

    /** The emoji typed as {@code code}, or null. */
    public Emoji byCode(String code) {
        for (Category c : categories) for (Emoji e : c.emojis()) if (e.code().equals(code)) return e;
        return null;
    }

    /** This table's categories followed by {@code other}'s. */
    public EmojiTable plus(EmojiTable other) {
        if (other.categories.isEmpty()) return this;
        if (categories.isEmpty()) return other;
        List<Category> all = new ArrayList<>(categories);
        all.addAll(other.categories);
        return new EmojiTable(List.copyOf(all));
    }
}
