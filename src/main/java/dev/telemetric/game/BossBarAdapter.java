package dev.telemetric.game;

import dev.telemetric.data.TelosData;
import dev.telemetric.dungeon.Point;
import dev.telemetric.mixin.BossHealthOverlayAccessor;
import dev.telemetric.mixin.TextDisplayAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MC ADAPTER — finds the mob that shows a boss bar.
 * <p>
 * The client never learns which entity a boss bar belongs to (bars have their own ids), and Telos also uses
 * boss bars as HUD widgets (empty or glyph-only names, the dungeon name). So a bar is tied to a mob by:
 * <ol>
 *   <li>the bar id equal to an entity's UUID, or</li>
 *   <li>the bar's name (letters only) matching a mob's custom name, or a name tag (armor stand / text
 *       display) riding or floating over a mob.</li>
 * </ol>
 * Bars whose name has fewer than {@link #MIN_NAME} letters are HUD widgets and are skipped.
 */
public final class BossBarAdapter {

    /** A bar name needs this many letters to be a boss name (HUD widget bars are glyphs or empty). */
    private static final int MIN_NAME = 4;
    /** A floating name tag belongs to the mob at most this far beside / below it. */
    private static final double TAG_RADIUS = 3.0, TAG_BELOW = 6.0;

    private BossBarAdapter() {}

    /** Feet position of the boss-bar mob closest to the player, or null if none is loaded. */
    public static Point bossMobPos() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer self = mc.player;
        if (level == null || self == null || mc.gui == null) return null;
        Map<UUID, LerpingBossEvent> events = ((BossHealthOverlayAccessor) mc.gui.getBossOverlay()).telemetric$getEvents();
        if (events.isEmpty()) return null;

        List<String> names = new ArrayList<>();
        for (LerpingBossEvent e : events.values()) {
            String n = letters(e.getName());
            if (n.length() >= MIN_NAME) names.add(n);
        }

        List<Entity> all = new ArrayList<>();
        for (Entity e : level.entitiesForRendering()) if (!e.isRemoved() && e != self) all.add(e);

        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : all) {
            Entity mob = null;
            if (events.containsKey(e.getUUID()) && isMob(e)) {
                mob = e;
            } else if (!names.isEmpty()) {
                String tag = letters(nameOf(e));
                if (tag.length() >= MIN_NAME && matches(tag, names)) mob = isMob(e) ? e : mobUnder(e, all);
            }
            if (mob == null) continue;
            double d = mob.distanceToSqr(self);
            if (d < bestD) {
                bestD = d;
                best = mob;
            }
        }
        return best == null ? null : new Point(best.getX(), best.getY(), best.getZ());
    }

    /**
     * Position of the boss's floating nameplate, or null. Telos sends the nameplate (a stack of text displays,
     * ~1.7 blocks over the boss's feet) even while the boss mob itself is invisible / not sent, so it marks
     * the boss arena before the fight. Boss plates use their own custom-font glyphs ({@link #BOSS_PLATE_MIN}–
     * {@link #BOSS_PLATE_MAX}: brackets U+1604A/U+1604B, U+1604C); normal mob plates use U+15FF5… brackets.
     * Verified in Treasure Cave (plate at -31, 44.7, -31, arena floor y 43). Lowest such plate wins.
     */
    public static Point bossMarkerPos() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return null;
        Entity best = null;
        for (Entity e : level.entitiesForRendering()) {
            if (e.isRemoved() || !(e instanceof Display.TextDisplay td)) continue;
            if (best != null && e.getY() >= best.getY()) continue;
            Component text = ((TextDisplayAccessor) td).telemetric$getText();
            if (text != null && hasBossGlyph(text.getString())) best = e;
        }
        return best == null ? null : new Point(best.getX(), best.getY(), best.getZ());
    }

    /**
     * The location shown in Telos's bottom-right HUD box ("Resounding Ruins", "Treasure Cave", …), or null.
     * That box is a boss-bar HUD widget; the location is the bar-name part drawn in the
     * {@code mythichud:…/rotmc2-hud-biome/biome-text} font (verified in Resounding Ruins, 2026-11-14).
     */
    public static String hudLocation() {
        Component part = hudLocationPart();
        return part == null ? null : part.getString().strip();
    }

    /** Colour (0xRRGGBB) the server gives the HUD-box location text, or 0 when none / no box. */
    public static int hudLocationColor() {
        Component part = hudLocationPart();
        if (part == null || part.getStyle().getColor() == null) return 0;
        return part.getStyle().getColor().getValue() & 0xFFFFFF;
    }

    private static Component hudLocationPart() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return null;
        Map<UUID, LerpingBossEvent> events = ((BossHealthOverlayAccessor) mc.gui.getBossOverlay()).telemetric$getEvents();
        for (LerpingBossEvent e : events.values()) {
            Component name = e.getName();
            if (name == null) continue;
            for (Component part : name.toFlatList()) {
                if (!(part.getStyle().getFont() instanceof FontDescription.Resource r)) continue;
                String path = r.id().getPath();
                if (!path.contains("hud-biome") && !path.endsWith("biome-text")) continue;
                if (!part.getString().strip().isEmpty()) return part;
            }
        }
        return null;
    }

    /** Boss nameplate glyph range (Telos custom font, private-use plane-1 code points). */
    private static final int BOSS_PLATE_MIN = 0x1604A, BOSS_PLATE_MAX = 0x1604C;

    private static boolean hasBossGlyph(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (cp >= BOSS_PLATE_MIN && cp <= BOSS_PLATE_MAX) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean isMob(Entity e) {
        return e instanceof LivingEntity && !(e instanceof ArmorStand) && !(e instanceof Player);
    }

    /** Custom name, or a text display's text. */
    private static Component nameOf(Entity e) {
        if (e instanceof Display.TextDisplay td) return ((TextDisplayAccessor) td).telemetric$getText();
        return e.getCustomName();
    }

    /** The mob a name tag rides, or the nearest mob just under it. */
    private static Entity mobUnder(Entity tag, List<Entity> all) {
        Entity v = tag.getVehicle();
        while (v != null) {
            if (isMob(v)) return v;
            v = v.getVehicle();
        }
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : all) {
            if (!isMob(e)) continue;
            double dy = tag.getY() - e.getY();
            if (dy < -1 || dy > TAG_BELOW) continue;
            double dx = e.getX() - tag.getX(), dz = e.getZ() - tag.getZ();
            double d = dx * dx + dz * dz;
            if (d <= TAG_RADIUS * TAG_RADIUS && d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private static boolean matches(String tag, List<String> names) {
        for (String n : names) {
            String shortS = tag.length() <= n.length() ? tag : n, longS = shortS == tag ? n : tag;
            // the shorter one must be most of the longer ("cave" is no match for "treasurecave")
            if (longS.contains(shortS) && shortS.length() * 5 >= longS.length() * 3) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ boss bar scale

    private static volatile float barScale = 1f;
    private static Component lastName;
    private static boolean lastIsBoss;

    /** Boss Bar Scale: size of a known boss's bar (1 = vanilla). Read by {@code BossHealthOverlayMixin}. */
    public static void setBossBarScale(float scale) {
        barScale = Math.clamp(scale, 0.1f, 1f);
    }

    /**
     * Scale for the bar with this name this frame: the Boss Bar Scale size for a known boss
     * ({@code Extractor/Boss.json}: bar name hash or boss name), 1 for everything else (HUD widget bars, the dungeon name).
     */
    public static float scaleFor(Component name) {
        float s = barScale;
        if (s >= 1f || name == null || !ServerGate.onTelos()) return 1f;
        if (name != lastName) { // a bar keeps its name object until the server renames it
            lastName = name;
            lastIsBoss = TelosData.BOSS_BARS.get().isBossBar(name.hashCode(), letters(name));
        }
        return lastIsBoss ? s : 1f;
    }

    /**
     * Names of the bosses whose bars are showing right now ({@code Extractor/Boss.json}: bar name hash → boss),
     * in bar order; empty when no known boss bar is up.
     */
    public static List<String> knownBosses() {
        List<KnownBar> bars = knownBossBars();
        List<String> out = new ArrayList<>(bars.size());
        for (KnownBar b : bars) if (!out.contains(b.boss())) out.add(b.boss());
        return out;
    }

    /** A showing boss bar of a known boss: its name and the bar's fill in % (0..100, rounded). */
    public record KnownBar(String boss, int percent) {}

    /** The bars of known bosses showing right now ({@code Extractor/Boss.json}), in bar order. */
    public static List<KnownBar> knownBossBars() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null || !ServerGate.onTelos()) return List.of();
        Map<UUID, LerpingBossEvent> events = ((BossHealthOverlayAccessor) mc.gui.getBossOverlay()).telemetric$getEvents();
        if (events.isEmpty()) return List.of();
        TelosData.BossBars bars = TelosData.BOSS_BARS.get();
        List<KnownBar> out = new ArrayList<>(2);
        for (LerpingBossEvent e : events.values()) {
            String n = bars.nameFor(e.getName().hashCode());
            if (n != null) out.add(new KnownBar(n, Math.round(Math.clamp(e.getProgress(), 0f, 1f) * 100f)));
        }
        return out;
    }

    /** Lower-case Latin letters only: drops colour codes, HP numbers, levels and custom-font glyphs. */
    private static String letters(Component c) {
        if (c == null) return "";
        String s = c.getString();
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 0x250 && Character.isLetter(ch)) b.append(Character.toLowerCase(ch));
        }
        return b.toString();
    }
}
