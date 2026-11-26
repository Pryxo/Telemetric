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
 * Telos player name tags. Telos hides the vanilla name tag (every player's team has {@code nameTagVisibility: NEVER})
 * and puts a text display on the player as a passenger instead, e.g. {@code "<rank glyph> [Member] Name\nHP: 20"}.
 * This class finds those displays, works out who they belong to, and asks the Status Title rule whether to draw them.
 * Hooks: {@link #hidden} (from {@code EntityRenderDispatcherMixin} via {@link PlayerVisibilityAdapter}), and the extra
 * lines (status title, Player Currency) through {@link #extrasFor} and {@link #decorate} from
 * {@code TextDisplayTitleMixin}. Off Telos it does nothing.
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

    /** One fragment count of a currency line: {@code "93"} and its Telos glyph (drawn in {@link #FRAGMENT_FONT}). */
    public record FragmentCount(String count, String glyph) {}

    /**
     * A currency line for a name tag: {@code "❖1,632,163.3 | 0 <icon> 93 <icon> …"}.
     *
     * @param fame   null for no fame part
     * @param colors RGB colours of the parts
     */
    public record CurrencyLine(String fame, java.util.List<FragmentCount> fragments, CurrencyColors colors) {}

    /** RGB colours of a currency line: the ❖ fame icon, fame number, " | " separator, fragment numbers and icons. */
    public record CurrencyColors(int fameIcon, int fame, int separator, int fragmentCount, int fragmentIcon) {}

    /**
     * Where currency lines come from: (owner username, is it you, their mode from the tag's ruleset glyph
     * {@code normal} / {@code seasonal} / {@code hardcore} / {@code group} or "") gives a line, or null for none.
     */
    public interface CurrencySource {
        CurrencyLine currencyFor(String ownerName, boolean self, String mode);
    }

    /** Font of the fragment glyphs in Telos' /balance reply. */
    private static final Identifier FRAGMENT_FONT = Identifier.parse("telos:material");

    private static volatile CurrencySource currencySource;
    private static volatile TitlePosition currencyPosition = TitlePosition.MIDDLE;
    private static volatile boolean hideHp;
    /** Status Title's own Hide HP (same effect as Player Currency's; either one removes the line). */
    private static volatile boolean titleHideHp;

    /** Nametag → Hide Own Name: drop the server's own lines (rank, name …) from your own tag, keep HP + extras. */
    private static volatile boolean hideOwnName;

    public static void setHideOwnName(boolean hide) {
        hideOwnName = hide;
    }

    /** Status Title → Hide HP: whether the "HP:" line is removed from every Telos name tag. */
    public static void setTitleHideHp(boolean hide) {
        titleHideHp = hide;
    }

    /** A null source means no currency lines. */
    public static void setCurrencySource(CurrencySource source) {
        currencySource = source;
    }

    /** Where the currency line goes, and whether the "HP:" line is removed from every Telos name tag. */
    public static void setCurrencyLayout(TitlePosition position, boolean hideHpLine) {
        currencyPosition = position == null ? TitlePosition.MIDDLE : position;
        hideHp = hideHpLine;
    }

    /** Per display, the {CurrencyLine, Component} built last time, so the same line stays the same instance. */
    private static final Map<Display.TextDisplay, Object[]> CURRENCY_CACHE = new WeakHashMap<>();

    private static Component currencyFor(Display.TextDisplay display, Player owner) {
        CurrencySource source = currencySource;
        if (source == null || owner == null) return null;
        CurrencyLine line = source.currencyFor(owner.getScoreboardName(), owner == Minecraft.getInstance().player,
                TelosCharacter.modeFromGlyph(nametagText(display)));
        if (line == null) return null;
        Object[] cached = CURRENCY_CACHE.get(display);
        if (cached != null && cached[0].equals(line)) return (Component) cached[1];
        Component built = build(line);
        CURRENCY_CACHE.put(display, new Object[]{line, built});
        return built;
    }

    /** Colours come from {@link CurrencyLine#colors()} (defaults match Telos' /balance reply). */
    private static Component build(CurrencyLine line) {
        CurrencyColors c = line.colors();
        MutableComponent out = Component.empty();
        if (line.fame() != null) {
            out.append(Component.literal("❖").withColor(c.fameIcon() & 0xFFFFFF));
            out.append(Component.literal(line.fame()).withColor(c.fame() & 0xFFFFFF));
            if (!line.fragments().isEmpty()) out.append(Component.literal(" | ").withColor(c.separator() & 0xFFFFFF));
        }
        boolean first = true;
        for (FragmentCount f : line.fragments()) {
            if (!first) out.append(" ");
            first = false;
            out.append(Component.literal(f.count() + " ").withColor(c.fragmentCount() & 0xFFFFFF));
            out.append(Component.literal(f.glyph()).withStyle(Style.EMPTY.withColor(c.fragmentIcon() & 0xFFFFFF)
                    .withFont(new FontDescription.Resource(FRAGMENT_FONT))));
        }
        return out;
    }

    /**
     * What gets added to one name tag: the status title and the currency line (either may be null) with their
     * positions. {@link #extrasFor} returns the same instance as long as nothing changes, so the mixin can compare by
     * identity and only re-split the lines when needed.
     */
    public record Extras(Component title, TitlePosition titlePosition, Component currency,
                         TitlePosition currencyPosition, boolean hideHp, boolean hideName) {
        public Extras(Component title, TitlePosition titlePosition, Component currency,
                      TitlePosition currencyPosition, boolean hideHp) {
            this(title, titlePosition, currency, currencyPosition, hideHp, false);
        }

        boolean same(Extras o) {
            return o != null && title == o.title && currency == o.currency && titlePosition == o.titlePosition
                    && currencyPosition == o.currencyPosition && hideHp == o.hideHp && hideName == o.hideName;
        }
    }

    private static final Map<Display.TextDisplay, Extras> EXTRAS_CACHE = new WeakHashMap<>();

    /**
     * Mixin hook ({@code TextDisplayTitleMixin}, every time the display's lines are needed): the lines to add to this
     * Telos name tag, or null for none.
     */
    public static Extras extrasFor(Display.TextDisplay display) {
        if (!ServerGate.onTelos() || nametagText(display) == null) return null;
        if (titleSource == null && currencySource == null) return null;
        Player owner = owner(display);
        Component title = titleFor(display, owner);
        Component currency = currencyFor(display, owner);
        boolean noHp = hideHp || titleHideHp;
        boolean noName = hideOwnName && owner != null && owner == Minecraft.getInstance().player;
        if (title == null && currency == null && !noHp && !noName) {
            EXTRAS_CACHE.remove(display);
            return null;
        }
        Extras next = new Extras(title, titlePosition, currency, currencyPosition, noHp, noName);
        Extras prev = EXTRAS_CACHE.get(display);
        if (next.same(prev)) return prev;
        EXTRAS_CACHE.put(display, next);
        return next;
    }

    /**
     * Mixin hook: the name tag text with the extra lines. Top puts a line first, Middle right before the "HP:" line
     * (after the first line if there's none), and Bottom last; with both on the same spot the title comes first. With
     * Hide HP the "HP:" line is left out (Middle lines go where it was). Styles are kept.
     */
    public static Component decorate(Component text, Extras extras) {
        java.util.List<PreviewLine> rows = rows(text, extras);
        MutableComponent out = Component.empty();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) out.append("\n");
            out.append(rows.get(i).text());
        }
        return out;
    }

    /** What a name tag line is: the server's own text, the status title or the currency line. */
    public enum LineKind { TAG, TITLE, CURRENCY }

    /** One line of a name tag preview ({@link #previewLines}). */
    public record PreviewLine(Component text, LineKind kind) {}

    /** The name tag split into its lines, with the extra lines put in (see {@link #decorate}). */
    private static java.util.List<PreviewLine> rows(Component text, Extras extras) {
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
        int hp = -1;
        for (int i = 0; i < plain.size(); i++) {
            if (plain.get(i).indexOf("HP:") >= 0) {
                hp = i;
                break;
            }
        }
        int at = hp >= 0 ? hp : Math.min(1, lines.size());
        Component currency = extras.currency();
        boolean dropHp = extras.hideHp() && hp >= 0;
        java.util.List<PreviewLine> top = new java.util.ArrayList<>(), middle = new java.util.ArrayList<>(),
                bottom = new java.util.ArrayList<>();
        if (extras.title() != null) {
            place(new PreviewLine(extras.title(), LineKind.TITLE), extras.titlePosition(), top, middle, bottom);
        }
        if (currency != null) {
            place(new PreviewLine(currency, LineKind.CURRENCY), extras.currencyPosition(), top, middle, bottom);
        }

        java.util.List<PreviewLine> rows = new java.util.ArrayList<>(top);
        for (int i = 0; i <= lines.size(); i++) {
            if (i == at) rows.addAll(middle);
            if (i < lines.size() && !(dropHp && i == hp) && !(extras.hideName() && i != hp)) rows.add(new PreviewLine(lines.get(i), LineKind.TAG));
        }
        rows.addAll(bottom);
        return rows;
    }

    /**
     * Your name tag as it looks with these extra lines (either may be null), for the Settings window preview: your
     * real Telos name tag if one is loaded, else your name and a sample "HP:" line.
     */
    public static java.util.List<PreviewLine> previewLines(TitleLine title, TitlePosition titlePosition,
                                                           CurrencyLine currency, TitlePosition currencyPosition,
                                                           boolean hideHpLine) {
        return previewLines(title, titlePosition, currency, currencyPosition, hideHpLine, false);
    }

    /** {@link #previewLines} with Hide Own Name applied (only the HP line of the server's text is kept). */
    public static java.util.List<PreviewLine> previewLines(TitleLine title, TitlePosition titlePosition,
                                                           CurrencyLine currency, TitlePosition currencyPosition,
                                                           boolean hideHpLine, boolean hideName) {
        Component base = ownTagText();
        if (base == null) {
            var player = Minecraft.getInstance().player;
            String name = player == null ? "Player" : player.getScoreboardName();
            base = Component.literal(name).withStyle(ChatFormatting.WHITE).append("\n")
                    .append(Component.literal("HP: ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("20/20").withStyle(ChatFormatting.RED));
        }
        Extras extras = new Extras(title == null ? null : titleComponent(title),
                titlePosition == null ? TitlePosition.TOP : titlePosition,
                currency == null ? null : build(currency),
                currencyPosition == null ? TitlePosition.MIDDLE : currencyPosition, hideHpLine, hideName);
        return rows(base, extras);
    }

    /** The text of your own Telos name tag, or null if none is loaded. */
    private static Component ownTagText() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return null;
        for (net.minecraft.world.entity.Entity e : level.entitiesForRendering()) {
            if (e instanceof Display.TextDisplay d && isNametag(d) && owner(d) == mc.player) return d.getText();
        }
        return null;
    }

    private static <L> void place(L line, TitlePosition position, java.util.List<L> top,
                                  java.util.List<L> middle, java.util.List<L> bottom) {
        if (line == null) return;
        switch (position) {
            case TOP -> top.add(line);
            case MIDDLE -> middle.add(line);
            case BOTTOM -> bottom.add(line);
        }
    }

    /** Per display, the {TitleLine, Component} built last time, so the same title stays the same instance. */
    private static final Map<Display.TextDisplay, Object[]> TITLE_CACHE = new WeakHashMap<>();

    /**
     * Line width for a text display carrying {@code extras}. It's at least each added line's own width, so a status
     * title or currency line never gets wrapped (cut) in the middle, e.g. a long {@code "Name"} like "True Ophanim".
     */
    public static int lineWidthFor(Extras extras, int width) {
        if (extras == null) return width;
        var font = Minecraft.getInstance().font;
        if (extras.title() != null) width = Math.max(width, font.width(extras.title()) + 1);
        if (extras.currency() != null) width = Math.max(width, font.width(extras.currency()) + 1);
        return width;
    }

    /**
     * The status title for this Telos name tag, or null. It returns the same instance as long as the title doesn't
     * change. The animation runs in the font hook ({@link AnimatedTextAdapter}), so the line never has to be rebuilt.
     */
    private static Component titleFor(Display.TextDisplay display, Player owner) {
        TitleSource source = titleSource;
        if (source == null) return null;
        TitleLine line = owner == null ? null
                : source.titleFor(owner.getUUID().toString(), owner == Minecraft.getInstance().player);
        if (line == null) return null;
        Object[] cached = TITLE_CACHE.get(display);
        if (cached != null && cached[0].equals(line)) return (Component) cached[1];
        Component built = build(line);
        TITLE_CACHE.put(display, new Object[]{line, built});
        return built;
    }

    /** Built previews by line (Status Title → Show Status Colors HUD). Same instance as long as the line doesn't change. */
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
