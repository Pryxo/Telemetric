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
 * Finds the mob behind a boss bar. The client doesn't say which entity a bar belongs to, and Telos reuses bars as HUD
 * widgets, so a bar is matched by its UUID, or by its letters matching a mob's name or a name tag over it. Bars with
 * fewer than {@link #MIN_NAME} letters are HUD widgets and are skipped.
 */
public final class BossBarAdapter {

    /** Shorter names are HUD widgets (glyphs or empty), not boss names. */
    private static final int MIN_NAME = 4;
    /** A name tag counts for a mob within TAG_RADIUS sideways and TAG_BELOW blocks below it. */
    private static final double TAG_RADIUS = 3.0, TAG_BELOW = 6.0;

    private BossBarAdapter() {}

    /** True while a bar with a boss name (not a HUD widget) is shown. */
    public static boolean hasBossBar() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return false;
        for (LerpingBossEvent e : ((BossHealthOverlayAccessor) mc.gui.getBossOverlay()).telemetric$getEvents().values()) {
            if (letters(e.getName()).length() >= MIN_NAME) return true;
        }
        return false;
    }

    /** Feet position of the nearest boss-bar mob, or null. */
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
     * Position of the boss's nameplate, or null. The plate (about 1.7 blocks above the feet) is sent even when the boss
     * mob is invisible or absent, so it marks the arena before the fight. Boss plates use glyphs U+1604A to U+1604C,
     * normal mob plates use U+15FF5 and up. Checked in Treasure Cave (plate at -31, 44.7, -31, floor y 43).
     * The lowest plate wins.
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
     * Location text of the bottom-right HUD box, or null. It is the part of the bar name drawn in the font
     * {@code mythichud:…/rotmc2-hud-biome/biome-text} (checked in Resounding Ruins, 2026-11-14).
     */
    public static String hudLocation() {
        Component part = hudLocationPart();
        return part == null ? null : part.getString().strip();
    }

    /** Colour of the HUD-box location text as 0xRRGGBB, or 0 if there is no box or colour. */
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

    /** Telos custom-font glyphs (plane 1, private use) on boss nameplates. */
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

    private static Component nameOf(Entity e) {
        if (e instanceof Display.TextDisplay td) return ((TextDisplayAccessor) td).telemetric$getText();
        return e.getCustomName();
    }

    /** The mob a name tag rides, else the nearest mob just under the tag. */
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
            // "cave" must not match "treasurecave", so the shorter name has to be most of the longer one
            if (longS.contains(shortS) && shortS.length() * 5 >= longS.length() * 3) return true;
        }
        return false;
    }

    private static volatile float barScale = 1f;
    private static Component lastName;
    private static boolean lastIsBoss;

    /** Boss Bar Scale for known bosses; 1 is vanilla. Read by {@code BossHealthOverlayMixin}. */
    public static void setBossBarScale(float scale) {
        barScale = Math.clamp(scale, 0.1f, 1f);
    }

    /**
     * Scale for the bar with this name. A known boss gets its Boss Bar Scale size (tables/Boss.json, matched by name
     * hash or boss name); everything else (HUD widget bars, the dungeon name) gets 1.
     */
    public static float scaleFor(Component name) {
        float s = barScale;
        if (s >= 1f || name == null || !ServerGate.onTelos()) return 1f;
        if (name != lastName) { // the server keeps the same name object until it renames the bar
            lastName = name;
            lastIsBoss = TelosData.BOSS_BARS.get().isBossBar(name.hashCode(), letters(name));
        }
        return lastIsBoss ? s : 1f;
    }

    /**
     * Names of the known bosses whose bars are showing, in bar order (tables/Boss.json maps bar name hash to boss).
     * Empty when none is up.
     */
    public static List<String> knownBosses() {
        List<KnownBar> bars = knownBossBars();
        List<String> out = new ArrayList<>(bars.size());
        for (KnownBar b : bars) if (!out.contains(b.boss())) out.add(b.boss());
        return out;
    }

    /** A showing boss bar: boss name and fill in percent (0..100, rounded). */
    public record KnownBar(String boss, int percent) {}

    /** The bars of known bosses that are showing, in bar order. */
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

    /** Lower-cased Latin letters only. Drops colour codes, numbers and glyphs. */
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
