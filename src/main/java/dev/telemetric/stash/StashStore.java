package dev.telemetric.stash;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.telemetric.Telemetric;
import dev.telemetric.config.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Saved stash pages, kept in {@code config/telemetric/saved/stash.json} per player UUID and per {@link StashMode}.
 * <pre>{@code
 * { "version": 1, "players": { "<uuid>": { "normal": { "pageCount": 9,
 *     "pages": { "1": { "savedAt": 1731550000000, "slots": [ <item json or null> x45 ] } } } } } }
 * }</pre>
 * The item JSON is the vanilla {@code ItemStack} codec output (see {@code StashAdapter}).
 */
public final class StashStore {

    private static final int VERSION = 1;

    private final Path file;
    private final Map<String, EnumMap<StashMode, StashBook>> players = new HashMap<>();
    private boolean loaded;
    private boolean dirty;

    public StashStore(Path file) {
        this.file = file;
    }

    public StashBook book(String player, StashMode mode) {
        ensureLoaded();
        return players.computeIfAbsent(player, k -> new EnumMap<>(StashMode.class))
                .computeIfAbsent(mode, k -> new StashBook());
    }

    public void markDirty() { dirty = true; }

    public void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonObject playersJson = new JsonObject();
        players.forEach((player, books) -> {
            JsonObject p = new JsonObject();
            books.forEach((mode, book) -> p.add(mode.key(), bookToJson(book)));
            playersJson.add(player, p);
        });
        root.add("players", playersJson);
        try {
            AtomicFiles.writeString(file, root.toString());
        } catch (IOException e) {
            Telemetric.LOGGER.error("Could not save {}", file, e);
        }
    }

    // ------------------------------------------------------------------ json

    private static JsonObject bookToJson(StashBook book) {
        JsonObject b = new JsonObject();
        b.addProperty("pageCount", book.pageCount());
        if (book.isKnownEmpty()) b.addProperty("empty", true);
        JsonObject pages = new JsonObject();
        book.pages().forEach((index, page) -> {
            JsonObject pj = new JsonObject();
            pj.addProperty("savedAt", page.savedAt());
            JsonArray slots = new JsonArray();
            for (int i = 0; i < StashPage.SLOTS; i++) {
                String s = page.slot(i);
                slots.add(s == null ? JsonNull.INSTANCE : JsonParser.parseString(s));
            }
            pj.add("slots", slots);
            pages.add(Integer.toString(index), pj);
        });
        b.add("pages", pages);
        if (!book.names().isEmpty()) {
            JsonObject names = new JsonObject();
            book.names().forEach((index, name) -> names.addProperty(Integer.toString(index), name));
            b.add("names", names);
        }
        return b;
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        if (!Files.isRegularFile(file)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject playersJson = root.getAsJsonObject("players");
            if (playersJson == null) return;
            for (Map.Entry<String, JsonElement> pe : playersJson.entrySet()) {
                EnumMap<StashMode, StashBook> books = new EnumMap<>(StashMode.class);
                for (Map.Entry<String, JsonElement> be : pe.getValue().getAsJsonObject().entrySet()) {
                    StashMode mode = StashMode.byKey(be.getKey());
                    if (mode != null) books.put(mode, bookFromJson(be.getValue().getAsJsonObject()));
                }
                players.put(pe.getKey(), books);
            }
        } catch (RuntimeException | IOException e) {
            Telemetric.LOGGER.error("Could not read {} — starting with an empty stash cache", file, e);
            AtomicFiles.backupCorrupt(file);
        }
    }

    private static StashBook bookFromJson(JsonObject b) {
        StashBook book = new StashBook();
        JsonObject pages = b.getAsJsonObject("pages");
        if (pages != null) {
            for (Map.Entry<String, JsonElement> e : pages.entrySet()) {
                int index;
                try {
                    index = Integer.parseInt(e.getKey());
                } catch (NumberFormatException ex) {
                    continue;
                }
                JsonObject pj = e.getValue().getAsJsonObject();
                JsonArray arr = pj.getAsJsonArray("slots");
                if (arr == null) continue;
                String[] slots = new String[StashPage.SLOTS];
                for (int i = 0; i < Math.min(arr.size(), StashPage.SLOTS); i++) {
                    JsonElement el = arr.get(i);
                    slots[i] = el == null || el.isJsonNull() ? null : el.toString();
                }
                long savedAt = pj.has("savedAt") ? pj.get("savedAt").getAsLong() : 0L;
                book.put(index, new StashPage(slots, savedAt));
            }
        }
        if (b.has("pageCount")) book.setPageCount(b.get("pageCount").getAsInt());
        if (b.has("empty") && b.get("empty").getAsBoolean() && book.pageCount() == 0) book.markEmpty();
        JsonObject names = b.getAsJsonObject("names");
        if (names != null) {
            for (Map.Entry<String, JsonElement> e : names.entrySet()) {
                try {
                    book.setName(Integer.parseInt(e.getKey()), e.getValue().getAsString());
                } catch (RuntimeException ignored) {
                    // skip a broken entry
                }
            }
        }
        return book;
    }
}
