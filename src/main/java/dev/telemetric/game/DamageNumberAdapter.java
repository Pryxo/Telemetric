package dev.telemetric.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.telemetric.Telemetric;
import dev.telemetric.mixin.TextDisplayAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MC ADAPTER — finds floating damage numbers around the player.
 * <p>
 * <b>Telos Realms</b> shows hits as {@code text_display} entities whose text is drawn with image glyphs
 * from the server pack font {@code telos:text} ("xikage" sets {@code default}/{@code miniboss}/{@code boss}/
 * {@code misc}): {@code start} frame, digits / {@code dot}, {@code end} frame, separated by U+F801 spacers.
 * Mob name plates use the same glyph sets but contain letters, so they are rejected.
 * <p>
 * The glyph → character map is built at runtime from the pack's {@code assets/telos/font/text.json}: each
 * bitmap provider's file name ({@code .../xikage/<set>/<name>.png}) names its character ({@code 0}–{@code 9},
 * {@code a}–{@code z}, {@code dot}, {@code dash}, {@code slash}, {@code space}; {@code start}/{@code end}/
 * {@code frame}/{@code inner} are decoration). It is re-read whenever the world changes.
 * <p>
 * Plain-text numbers ("15.9" in the default font) are only counted when {@code plainNumbers} is on —
 * Telos uses them for countdowns ("3", "2", "1"), not damage.
 * <p>
 * Entities present when scanning (re)starts are marked as seen without being reported.
 */
public final class DamageNumberAdapter {

    /**
     * One damage number.
     *
     * @param color ARGB of the first digit (0 = uncoloured — Telos glyph numbers are uncoloured)
     * @param set   glyph set the digits were drawn with ({@code default}, {@code miniboss}, {@code boss},
     *              {@code misc}) or {@code plain} for ordinary text
     */
    public record DamageNumber(int entityId, double amount, int color, String text, String set, double distance) {}

    private record Glyph(char ch, String set) {}

    /** Marker char for frame/decoration glyphs. */
    private static final char DECOR = '\u0001';
    private static final Identifier FONT_JSON = Identifier.parse("telos:font/text.json");
    private static final Pattern XIKAGE = Pattern.compile(".*/xikage/([a-z0-9_]+)/([a-z0-9_]+)\\.png$");

    private static final Set<Integer> SEEN = new HashSet<>();
    private static final Set<Integer> PRESENT = new HashSet<>();
    private static Map<Integer, Glyph> glyphs = Map.of();
    private static Object lastLevel;
    private static boolean primed;
    private static long lastGlyphLoad;

    /** "1,234,567.8" (comma thousands) or "1234.5" / "15,9" (comma decimal). Optional k/m/b suffix. */
    private static final Pattern NUMBER = Pattern.compile("^(-?)(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:[.,]\\d+)?)([kKmMbB]?)$");

    private DamageNumberAdapter() {}

    /** Forget all seen entities; the next poll re-primes (counts nothing that already exists). */
    public static void reset() {
        SEEN.clear();
        primed = false;
    }

    /**
     * New damage numbers since the last call.
     *
     * @param range        max distance from the player (blocks)
     * @param ignoreSelf   skip numbers spawned on the local player (damage taken)
     * @param plainNumbers also count numbers written in ordinary (non-glyph) text
     * @param debug        log every new text entity (parsed or not) once, with colours / fonts
     */
    public static List<DamageNumber> poll(double range, boolean ignoreSelf, boolean plainNumbers, boolean debug) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            reset();
            lastLevel = null;
            return List.of();
        }
        if (level != lastLevel) {
            lastLevel = level;
            reset();
        }
        refreshGlyphs(level);

        List<DamageNumber> out = new ArrayList<>();
        PRESENT.clear();
        double rangeSq = range * range;
        for (Entity entity : level.entitiesForRendering()) {
            Component text = textOf(entity);
            if (text == null) continue;
            int id = entity.getId();
            PRESENT.add(id);
            if (SEEN.contains(id)) continue;
            if (text.getString().isBlank()) continue; // text not synced yet — look again next tick
            SEEN.add(id);
            if (!primed) continue;

            double distSq = entity.distanceToSqr(player);
            Parsed parsed = parse(text);
            boolean accepted = parsed != null && (plainNumbers || !parsed.set.equals("plain"));
            if (debug) {
                Telemetric.LOGGER.info("[DPS debug] {} #{} dist={} text={} -> {}",
                        entity.getClass().getSimpleName(), id, String.format(Locale.ROOT, "%.1f", Math.sqrt(distSq)),
                        ServerInfoAdapter.describe(text),
                        parsed == null ? "ignored" : parsed.raw + " (" + parsed.set + ")" + (accepted ? "" : " ignored"));
            }
            if (!accepted || distSq > rangeSq) continue;
            if (ignoreSelf && isOnPlayer(entity, player)) continue;
            out.add(new DamageNumber(id, parsed.amount, parsed.color, parsed.raw, parsed.set, Math.sqrt(distSq)));
        }
        SEEN.retainAll(PRESENT);
        primed = true;
        return out;
    }

    // ------------------------------------------------------------------ entity text

    /** Text of a text display, or the visible custom name of an armor stand / non-living marker entity. */
    private static Component textOf(Entity entity) {
        if (entity instanceof Display.TextDisplay td) return ((TextDisplayAccessor) td).telemetric$getText();
        if (entity instanceof LivingEntity && !(entity instanceof ArmorStand)) return null;
        if (!entity.isCustomNameVisible()) return null;
        return entity.getCustomName();
    }

    private static boolean isOnPlayer(Entity e, LocalPlayer p) {
        double dx = e.getX() - p.getX(), dz = e.getZ() - p.getZ();
        double dy = e.getY() - p.getY();
        return dx * dx + dz * dz < 1.2 * 1.2 && dy > -1 && dy < p.getBbHeight() + 1.5;
    }

    // ------------------------------------------------------------------ parsing

    private record Parsed(double amount, int color, String raw, String set) {}

    /** Null when the text is not a single (non-heal) number. */
    private static Parsed parse(Component component) {
        StringBuilder sb = new StringBuilder();
        int[] color = {0};
        String[] set = {null};
        boolean[] plainDigit = {false};
        component.visit((style, segment) -> {
            segment.codePoints().forEach(cp -> {
                Glyph g = glyphs.get(cp);
                if (g != null && isFont(style, FONT_JSON)) {
                    if (g.ch() == DECOR) return;
                    if (Character.isDigit(g.ch()) && set[0] == null) set[0] = g.set();
                    sb.append(g.ch());
                    return;
                }
                if (ServerInfoAdapter.isSpacer(cp)) return;
                char mapped = mapPlain(cp);
                if (mapped == 0) {
                    sb.append(' ');
                    return;
                }
                if (Character.isDigit(mapped)) {
                    plainDigit[0] = true;
                    if (color[0] == 0) {
                        int rgb = ServerInfoAdapter.rgb(style);
                        color[0] = rgb < 0 ? 0 : 0xFF000000 | rgb;
                    }
                }
                sb.append(mapped);
            });
            return Optional.empty();
        }, Style.EMPTY);

        String raw = sb.toString().trim().replaceAll("\\s+", " ");
        if (raw.isEmpty() || raw.indexOf(' ') >= 0) return null; // must be exactly one token
        Matcher m = NUMBER.matcher(raw);
        if (!m.matches()) return null;
        String num = m.group(2);
        num = num.matches("\\d{1,3}(,\\d{3})+(\\.\\d+)?") ? num.replace(",", "") : num.replace(',', '.');
        double value;
        try {
            value = Double.parseDouble(num);
        } catch (NumberFormatException e) {
            return null;
        }
        value *= switch (m.group(3).toLowerCase(Locale.ROOT)) {
            case "k" -> 1e3;
            case "m" -> 1e6;
            case "b" -> 1e9;
            default -> 1;
        };
        if (value <= 0) return null;
        String s = plainDigit[0] || set[0] == null ? "plain" : set[0];
        return new Parsed(value, color[0], raw, s);
    }

    /** Keeps digits (any script → ASCII), separators, signs and letters; any other symbol → 0 (separator). */
    private static char mapPlain(int cp) {
        if (Character.isDigit(cp)) return (char) ('0' + Character.digit(cp, 10));
        if (cp == '.' || cp == ',' || cp == '-' || cp == '+' || cp == '/') return (char) cp;
        if (Character.isLetter(cp) && cp < 0x80) return (char) cp;
        if (Character.isLetter(cp)) return 'x'; // non-ASCII letters: keep as "not a number"
        return 0;
    }

    private static boolean isFont(Style style, Identifier fontJson) {
        FontDescription f = style.getFont();
        // telos:font/text.json  <->  font id telos:text
        return f instanceof FontDescription.Resource res
                && res.id().getNamespace().equals(fontJson.getNamespace())
                && fontJson.getPath().equals("font/" + res.id().getPath() + ".json");
    }

    // ------------------------------------------------------------------ server pack font

    private static Object glyphLevel;

    /** (Re)reads the server pack font on a new level, and every 5 s while it is still empty (pack applied late). */
    private static void refreshGlyphs(ClientLevel level) {
        long now = System.currentTimeMillis();
        if (level != glyphLevel || (glyphs.isEmpty() && now - lastGlyphLoad > 5_000)) {
            glyphLevel = level;
            glyphs = loadGlyphs();
            lastGlyphLoad = now;
        }
    }

    /**
     * The letter a Telos text-font glyph draws ({@code telos:font/text.json}, every xikage set: default /
     * miniboss / boss / misc mob plates, damage numbers): lower-case {@code a}–{@code z}, a digit, or ' ';
     * 0 for anything else (brackets, frames, unknown code points).
     */
    public static char textFontLetter(int cp) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) refreshGlyphs(level);
        Glyph g = glyphs.get(cp);
        if (g == null) return 0;
        char c = g.ch();
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == ' ' ? c : 0;
    }

    private static Map<Integer, Glyph> loadGlyphs() {
        Map<Integer, Glyph> map = new HashMap<>();
        try {
            for (Resource res : Minecraft.getInstance().getResourceManager().getResourceStack(FONT_JSON)) {
                try (Reader reader = res.openAsReader()) {
                    JsonElement root = JsonParser.parseReader(reader);
                    if (!root.isJsonObject() || !root.getAsJsonObject().has("providers")) continue;
                    for (JsonElement el : root.getAsJsonObject().getAsJsonArray("providers")) {
                        if (el.isJsonObject()) readProvider(el.getAsJsonObject(), map);
                    }
                }
            }
        } catch (Exception e) {
            Telemetric.LOGGER.warn("DPS: could not read {}: {}", FONT_JSON, e.toString());
        }
        if (!map.isEmpty()) Telemetric.debug("DPS: mapped {} damage-number glyphs from {}", map.size(), FONT_JSON);
        return map;
    }

    private static void readProvider(JsonObject p, Map<Integer, Glyph> map) {
        if (!p.has("file") || !p.has("chars")) return;
        Matcher m = XIKAGE.matcher(p.get("file").getAsString());
        if (!m.matches()) return;
        String set = m.group(1);
        if (set.equals("title")) return; // title set reuses ASCII code points
        char ch = switch (m.group(2)) {
            case "dot" -> '.';
            case "dash" -> '-';
            case "slash" -> '/';
            case "space" -> ' ';
            case "start", "end", "frame", "inner" -> DECOR;
            default -> m.group(2).length() == 1 ? m.group(2).charAt(0) : 0;
        };
        if (ch == 0) return;
        JsonArray chars = p.getAsJsonArray("chars");
        StringBuilder all = new StringBuilder();
        for (JsonElement row : chars) all.append(row.getAsString());
        all.codePoints().forEach(cp -> map.put(cp, new Glyph(ch, set)));
    }
}
