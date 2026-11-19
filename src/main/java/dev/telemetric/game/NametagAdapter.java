package dev.telemetric.game;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import dev.telemetric.data.TelosData;
import dev.telemetric.hud.StatusRankColors;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.objects.AtlasSprite;
import net.minecraft.resources.Identifier;
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
 * {@link PlayerVisibilityAdapter}; title line on top: {@link #titleFor} from {@code TextDisplayTitleMixin}. Everything is off away from Telos.
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

    // ------------------------------------------------------------------ title line

    /**
     * A status title line for a name tag: {@code "#1 <icon> Miraj - 1,234 kills"}. {@code rank} ("#1", may be empty)
     * and {@code text} are coloured by {@code tier} (animated; null = white rank, grey text), the icon is drawn as is.
     *
     * @param icon null = none
     */
    public record TitleLine(String rank, TelosData.StatusIcon icon, String text, StatusRankColors.Tier tier) {}

    /** Source of a title: (owner UUID dashed, is it you) → line, or null for none. */
    public interface TitleSource {
        TitleLine titleFor(String ownerId, boolean self);
    }

    private static volatile TitleSource titleSource;

    /** Null source = no titles. */
    public static void setTitleSource(TitleSource source) {
        titleSource = source;
    }

    /** Where the title line goes in the name tag. */
    public enum TitlePosition { TOP, MIDDLE, BOTTOM }

    private static volatile TitlePosition titlePosition = TitlePosition.TOP;

    public static void setTitlePosition(TitlePosition position) {
        titlePosition = position == null ? TitlePosition.TOP : position;
    }

    public static TitlePosition titlePosition() {
        return titlePosition;
    }

    /**
     * Mixin hook: the name tag text with {@code title} put in as its own line — Top = first line, Middle = right
     * before the "HP:" line (after the first line when there is none), Bottom = last line. Styles are kept.
     */
    public static Component withTitle(Component text, Component title, TitlePosition position) {
        if (position == TitlePosition.TOP) return Component.empty().append(title).append("\n").append(text);
        if (position == TitlePosition.BOTTOM) return Component.empty().append(text).append("\n").append(title);
        java.util.List<MutableComponent> lines = new java.util.ArrayList<>();
        java.util.List<StringBuilder> plain = new java.util.ArrayList<>();
        lines.add(Component.empty());
        plain.add(new StringBuilder());
        text.visit((style, s) -> {
            String[] parts = s.split("\n", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    lines.add(Component.empty());
                    plain.add(new StringBuilder());
                }
                if (!parts[i].isEmpty()) {
                    lines.getLast().append(Component.literal(parts[i]).withStyle(style));
                    plain.getLast().append(parts[i]);
                }
            }
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        int at = Math.min(1, lines.size());
        for (int i = 0; i < plain.size(); i++) {
            if (plain.get(i).indexOf("HP:") >= 0) {
                at = i;
                break;
            }
        }
        MutableComponent out = Component.empty();
        for (int i = 0; i <= lines.size(); i++) {
            if (i == at) {
                if (i > 0) out.append("\n");
                out.append(title);
            }
            if (i < lines.size()) {
                if (i > 0 || at == 0) out.append("\n");
                out.append(lines.get(i));
            }
        }
        return out;
    }

    /** Per display: {TitleLine, Component} last built, so the same title stays the same instance. */
    private static final Map<Display.TextDisplay, Object[]> TITLE_CACHE = new WeakHashMap<>();

    /**
     * Mixin hook ({@code TextDisplayTitleMixin}, every time the display's lines are needed): the line to put on top
     * of this Telos name tag, or null. Returns the same instance while the title is unchanged — the animation runs
     * in the font hook ({@link AnimatedTextAdapter}), so the line never needs rebuilding.
     */
    public static Component titleFor(Display.TextDisplay display) {
        TitleSource source = titleSource;
        if (source == null || !ServerGate.onTelos() || nametagText(display) == null) return null;
        Player owner = owner(display);
        TitleLine line = owner == null ? null
                : source.titleFor(owner.getUUID().toString(), owner == Minecraft.getInstance().player);
        if (line == null) return null;
        Object[] cached = TITLE_CACHE.get(display);
        if (cached != null && cached[0].equals(line)) return (Component) cached[1];
        Component built = build(line);
        TITLE_CACHE.put(display, new Object[]{line, built});
        return built;
    }

    private static Component build(TitleLine line) {
        MutableComponent out = Component.empty();
        int[] chars = {0}; // animated characters so far: the gradient runs on across rank and text
        if (!line.rank().isEmpty()) {
            out.append(colored(line.rank(), line.tier(), ChatFormatting.WHITE, chars));
            out.append(" ");
        }
        Component icon = icon(line.icon(), line.tier() != null && line.tier().wave ? chars[0]++ : -1);
        if (icon != null) out.append(icon).append(" ");
        out.append(colored(line.text(), line.tier(), ChatFormatting.GRAY, chars));
        return out;
    }

    /** {@code text} in the tier's animated gradient (one marker per character), or plain {@code plain}. */
    private static Component colored(String text, StatusRankColors.Tier tier, ChatFormatting plain, int[] chars) {
        if (tier == null) return Component.literal(text).withStyle(plain);
        MutableComponent out = Component.empty();
        for (int c = 0; c < text.length(); ) {
            int cp = text.codePointAt(c);
            int n = Character.charCount(cp);
            out.append(Component.literal(text.substring(c, c + n))
                    .withStyle(Style.EMPTY.withColor(AnimatedTextAdapter.status(tier, chars[0]++))));
            c += n;
        }
        return out;
    }

    /** {@code waveIndex} ≥ 0 = the icon bobs with the title's wave as that character. */
    private static Component icon(TelosData.StatusIcon icon, int waveIndex) {
        if (icon == null) return null;
        Style style = waveIndex >= 0 ? Style.EMPTY.withColor(AnimatedTextAdapter.waveOnly(waveIndex))
                : Style.EMPTY.withColor(ChatFormatting.WHITE);
        if (icon.sprite() != null) {
            Identifier id = Identifier.tryParse(icon.sprite());
            return id == null ? null
                    : Component.object(new AtlasSprite(AtlasSprite.DEFAULT_ATLAS, id), Component.literal(""))
                            .withStyle(style);
        }
        Identifier font = icon.font() == null ? null : Identifier.tryParse(icon.font());
        if (font != null) style = style.withFont(new FontDescription.Resource(font));
        return Component.literal(icon.glyph()).withStyle(style);
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
