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
 * MC ADAPTER: Telos player name tags. Telos hides the vanilla name tag (every player's team has
 * {@code nameTagVisibility: NEVER}) and puts a text display on the player as a passenger instead, e.g.
 * {@code "<rank glyph> [Member] Name\nHP: 20"}. This class finds those displays, works out who they belong to, and asks
 * the Nametags module's rule whether to draw them. Hooks: {@link #hidden} from {@code EntityRenderDispatcherMixin} via
 * {@link PlayerVisibilityAdapter}, and the title line on top through {@link #titleFor} from {@code TextDisplayTitleMixin}.
 * All of it is off away from Telos.
 */
public final class NametagAdapter {

    private NametagAdapter() {}

    /** A Telos player name tag: its owner's username, whether it's yours, and its plain text (lines split by \n). */
    public record PlayerNametag(String owner, boolean self, String text) {}

    private static volatile Predicate<PlayerNametag> hideRule;

    /** Null means draw every name tag. */
    public static void setHideRule(Predicate<PlayerNametag> rule) {
        hideRule = rule;
    }

    /** Render hook: true if this text display is a player name tag that the rule hides. */
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
     * and {@code text} are coloured by {@code tier} (animated; null gives a white rank and grey text), and the icon is
     * drawn as it is.
     *
     * @param icon null for none
     */
    public record TitleLine(String rank, TelosData.StatusIcon icon, String text, StatusRankColors.Tier tier) {}

    /** Where titles come from: (owner UUID dashed, is it you) gives a line, or null for none. */
    public interface TitleSource {
        TitleLine titleFor(String ownerId, boolean self);
    }

    private static volatile TitleSource titleSource;

    /** A null source means no titles. */
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
     * Mixin hook: the name tag text with {@code title} added as its own line. Top puts it first, Middle right before the
     * "HP:" line (after the first line if there's none), and Bottom last. Styles are kept.
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

    /** Per display, the {TitleLine, Component} built last time, so the same title stays the same instance. */
    private static final Map<Display.TextDisplay, Object[]> TITLE_CACHE = new WeakHashMap<>();

    /**
     * Line width for a text display carrying {@code title}. It's at least the title's own width, so the status title
     * never gets wrapped (cut) in the middle, e.g. a long {@code "Name"} like "True Ophanim".
     */
    public static int lineWidthFor(Component title, int width) {
        if (title == null) return width;
        return Math.max(width, Minecraft.getInstance().font.width(title) + 1);
    }

    /**
     * Mixin hook ({@code TextDisplayTitleMixin}, every time the display's lines are needed): the line to put on top of
     * this Telos name tag, or null. It returns the same instance as long as the title doesn't change. The animation
     * runs in the font hook ({@link AnimatedTextAdapter}), so the line never has to be rebuilt.
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

    /** Built previews by line (Nametags → Show Status Colors HUD). Same instance as long as the line doesn't change. */
    private static final Map<TitleLine, Component> PREVIEW_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** The status title exactly as it goes into a name tag, for drawing on the HUD ({@code Render2D#statusTitle}). */
    public static Component titleComponent(TitleLine line) {
        if (PREVIEW_CACHE.size() > 64) PREVIEW_CACHE.clear();
        return PREVIEW_CACHE.computeIfAbsent(line, NametagAdapter::build);
    }

    private static Component build(TitleLine line) {
        MutableComponent out = Component.empty();
        int[] chars = {0}; // animated characters so far, so the gradient carries on across rank and text
        if (!line.rank().isEmpty()) {
            out.append(colored(line.rank(), line.tier(), ChatFormatting.WHITE, chars));
            out.append(" ");
        }
        Component icon = icon(line.icon(), line.tier() != null && line.tier().wave ? chars[0]++ : -1);
        if (icon != null) out.append(icon).append(" ");
        out.append(colored(line.text(), line.tier(), ChatFormatting.GRAY, chars));
        return out;
    }

    /** {@code text} in the tier's animated gradient (one marker per character), or in the plain colour {@code plain}. */
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

    /** If {@code waveIndex} is 0 or more, the icon bobs with the title's wave as that character. */
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

    /** Per display, {component, plain text or null if it isn't a name tag}. Refreshed when the text changes. */
    private static final Map<Display.TextDisplay, Object[]> TAG_CACHE = new WeakHashMap<>();

    /** Telos draws player names as text displays with the health in them ("… HP: …"). */
    public static boolean isNametag(Display.TextDisplay display) {
        return nametagText(display) != null;
    }

    /** Plain text of a Telos name tag, or null if the display isn't one. Checked once per text change. */
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

    /** The player a name tag belongs to: the one it rides, or else the nearest player under it. */
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
