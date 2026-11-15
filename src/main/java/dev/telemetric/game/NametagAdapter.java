package dev.telemetric.game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * MC ADAPTER — Telos player name tags. Telos hides the vanilla name tag (every player's team has
 * {@code nameTagVisibility: NEVER}) and instead puts a text display on the player as a passenger, e.g.
 * {@code "<rank glyph> [Member] Name\nHP: 20"}. This class finds those displays, works out whose they are and asks the
 * Nametags module's rule whether to draw them. Hook: {@link #hidden} from {@code EntityRenderDispatcherMixin} via
 * {@link PlayerVisibilityAdapter}. Everything is off away from Telos.
 */
public final class NametagAdapter {

    private NametagAdapter() {}

    /** A Telos player name tag: its owner's username, whether it is yours, and its plain text (lines split by \n). */
    public record PlayerNametag(String owner, boolean self, String text) {}

    private static volatile Predicate<PlayerNametag> hideRule;

    /** Null = draw every name tag. */
    public static void setHideRule(Predicate<PlayerNametag> rule) {
        hideRule = rule;
    }

    /** Render hook: true when this text display is a player name tag the rule hides. */
    public static boolean hidden(Display.TextDisplay display) {
        Predicate<PlayerNametag> rule = hideRule;
        if (rule == null || !ServerGate.onTelos()) return false;
        String text = nametagText(display);
        if (text == null) return false;
        Player owner = owner(display);
        if (owner == null) return false;
        return rule.test(new PlayerNametag(owner.getScoreboardName(), owner == Minecraft.getInstance().player, text));
    }

    // ------------------------------------------------------------------ detection

    /** Per display: {component, plain text or null when it is not a name tag}; refreshed when the text changes. */
    private static final Map<Display.TextDisplay, Object[]> TAG_CACHE = new WeakHashMap<>();

    /** Telos draws player names as text displays with the health in them ("… HP: …"). */
    public static boolean isNametag(Display.TextDisplay display) {
        return nametagText(display) != null;
    }

    /** Plain text of a Telos name tag, or null when the display is not one. Checked once per text change. */
    private static String nametagText(Display.TextDisplay display) {
        Component text = display.getText();
        if (text == null) return null;
        Object[] cached = TAG_CACHE.get(display);
        if (cached != null && cached[0] == text) return (String) cached[1];
        String plain = text.getString();
        String tag = plain.contains("HP:") ? plain : null;
        TAG_CACHE.put(display, new Object[]{text, tag});
        return tag;
    }

    /** The player a name tag belongs to: the one it rides, else the nearest player under it. */
    public static Player owner(Display.TextDisplay tag) {
        if (tag.getRootVehicle() instanceof Player p) return p;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return null;
        Player best = null;
        double bestD = 1.5 * 1.5;
        for (Player p : level.players()) {
            double dx = p.getX() - tag.getX(), dz = p.getZ() - tag.getZ();
            double d = dx * dx + dz * dz;
            if (d < bestD && tag.getY() >= p.getY()) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }
}
