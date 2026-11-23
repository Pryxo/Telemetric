package dev.telemetric.game;

import dev.telemetric.data.BossTable;
import dev.telemetric.data.TelosData;
import dev.telemetric.mixin.TextDisplayAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * MC ADAPTER: the boss damage stage on Telos Realms. A boss carries a stack of {@code text_display} passengers. One
 * of them shows the status glyph U+15FF8, tinted by stage (blue = immune, purple = shielded, anything else =
 * damageable), and a sibling passenger shows U+1604C or a nameplate that spells a Boss.json boss (e.g. Unrest). {@link #scan} finds those status displays once per tick,
 * {@link #position} gives their frame-interpolated X/Z, and the badge Y is the floor under the player.
 */
public final class BossStageAdapter {

    public enum Kind { IMMUNE, SHIELDED, DAMAGEABLE }

    /** One boss status display: entity id, stage, raw glyph colour (RGB, -1 for none), and the floor Y under the player. */
    public record Stage(int entityId, Kind kind, int rgb, double groundY) {}

    private static final String STATUS_GLYPH = new String(Character.toChars(0x15FF8));
    private static final String PAIR_GLYPH = new String(Character.toChars(0x1604C));

    /** Boss table the cached name keys were built from, and those keys ({@link DungeonAdapter#plateLetters} form). */
    private static BossTable keysFrom;
    private static Set<String> bossKeys = Set.of();
    private static final int MAX_DROP = 48;

    /** Floor Y under the player, last measured while on the ground (NaN until it's been measured). */
    private static double cachedFloorY = Double.NaN;

    private BossStageAdapter() {}

    /** Status displays within {@code range} blocks of the player. Empty when not in a world. */
    public static List<Stage> scan(double range) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        List<Stage> out = new ArrayList<>();
        if (level == null || player == null) return out;
        double r2 = range * range;
        // Badges sit on the floor the player stands on, because the boss's own column can be void or a tall prop.
        // It's cached and only re-measured while standing on the ground, so jumps and falls don't move the badges.
        if (player.onGround() || Double.isNaN(cachedFloorY)) {
            cachedFloorY = groundY(level, player.getX(), player.getY() + 0.5, player.getZ());
        }
        double floorY = cachedFloorY;
        Set<String> keys = bossKeys();
        for (Entity e : level.entitiesForRendering()) {
            if (!(e instanceof Display.TextDisplay td) || e.isRemoved()) continue;
            if (e.distanceToSqr(player) > r2) continue;
            Component text = ((TextDisplayAccessor) td).telemetric$getText();
            if (text == null || !text.getString().contains(STATUS_GLYPH) || !hasPair(td, keys)) continue;
            int rgb = statusColor(text);
            out.add(new Stage(e.getId(), kindOf(rgb), rgb, floorY));
        }
        return out;
    }

    /** Interpolated {x, z} of the entity, or null if it's gone. */
    public static double[] position(int entityId, float partialTick) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return null;
        Entity e = level.getEntity(entityId);
        if (e == null || e.isRemoved()) return null;
        return new double[]{Mth.lerp(partialTick, e.xOld, e.getX()), Mth.lerp(partialTick, e.zOld, e.getZ())};
    }

    /**
     * True when the status display belongs to a boss: a sibling passenger shows the boss-font glyph U+1604C, or a
     * sibling nameplate spells a boss name from {@code Boss.json}. Some bosses (Unrest) use the normal-mob font, whose
     * stack (name, U+15FF7, U+15FF8, title) is the same on every mob, so only the name tells them apart.
     */
    private static boolean hasPair(Display.TextDisplay status, Set<String> keys) {
        Entity root = status.getRootVehicle();
        if (root == status) return false;
        for (Entity p : root.getPassengers()) {
            if (p == status || !(p instanceof Display.TextDisplay td)) continue;
            Component t = ((TextDisplayAccessor) td).telemetric$getText();
            if (t == null) continue;
            String s = t.getString();
            if (s.contains(PAIR_GLYPH)) return true;
            if (!keys.isEmpty() && keys.contains(DungeonAdapter.plateLetters(s))) return true;
        }
        return false;
    }

    /** Boss names from {@code Boss.json} as nameplate letters, rebuilt when the table is reloaded. */
    private static Set<String> bossKeys() {
        BossTable table = TelosData.BOSSES.get();
        if (table != keysFrom) {
            Set<String> keys = new HashSet<>();
            for (BossTable.Boss b : table.all()) {
                String k = DungeonAdapter.normalizeName(b.name());
                if (!k.isEmpty()) keys.add(k);
            }
            bossKeys = keys;
            keysFrom = table;
        }
        return bossKeys;
    }

    /** Colour (RGB) of the text run that holds the status glyph, or -1 if there's none. */
    private static int statusColor(Component text) {
        Optional<Integer> found = text.visit((style, s) -> {
            if (!s.contains(STATUS_GLYPH)) return Optional.empty();
            TextColor c = style.getColor();
            return Optional.of(c == null ? -1 : c.getValue() & 0xFFFFFF);
        }, net.minecraft.network.chat.Style.EMPTY);
        return found.orElse(-1);
    }

    /** Stage from the glyph colour: roughly (23, 87, 167) is immune, roughly (161, 103, 226) is shielded. */
    static Kind kindOf(int rgb) {
        if (rgb < 0) return Kind.DAMAGEABLE;
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        if (near(r, 23) && near(g, 87) && near(b, 167)) return Kind.IMMUNE;
        if (near(r, 161) && near(g, 103) && near(b, 226)) return Kind.SHIELDED;
        return Kind.DAMAGEABLE;
    }

    private static boolean near(int v, int target) {
        return Math.abs(v - target) <= 10;
    }

    /** Top of the first solid block at or below y (searching {@value #MAX_DROP} blocks), or y itself. */
    private static double groundY(ClientLevel level, double x, double y, double z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int bx = Mth.floor(x), bz = Mth.floor(z);
        int top = Mth.floor(y), bottom = Math.max(level.getMinY(), top - MAX_DROP);
        for (int by = top; by >= bottom; by--) {
            pos.set(bx, by, bz);
            if (!level.getBlockState(pos).isAir()) return by + 1;
        }
        return y;
    }
}
