package dev.telemetric.game;

import dev.telemetric.Telemetric;
import dev.telemetric.api.ModUsers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MC ADAPTER: the Telemetric badge (glyph {@code U+E000} of font {@code telemetric:mod_user}) for players online with
 * the mod ({@link ModUsers}). Chat: inserted right before the sender's name ("[Rank] ⎈ Name: …"). Tab list: drawn at
 * the right edge of the entry instead of the ping icon. Telos tab entries are fake profiles whose display name ends
 * with the player's name, so the name is read from the display text when the profile has none.
 */
public final class ModUserBadgeAdapter {

    private static final String GLYPH = "";
    private static final FontDescription FONT =
            new FontDescription.Resource(Identifier.fromNamespaceAndPath(Telemetric.MOD_ID, "mod_user"));
    private static final Style BADGE_STYLE = Style.EMPTY.withFont(FONT).withColor(TextColor.fromRgb(0xFFFFFF))
            .withBold(false).withItalic(false).withObfuscated(false).withUnderlined(false).withStrikethrough(false);
    private static final Component BADGE = Component.literal(GLYPH).withStyle(BADGE_STYLE);
    /** Badge width in GUI pixels (8 px glyph + 1 px advance). */
    private static final int BADGE_W = 9;
    /** Sender of a chat line: the first "Name: " after a space or at the start. */
    private static final Pattern SENDER = Pattern.compile("(?:^| )([A-Za-z0-9_]{3,16}): ");

    private ModUserBadgeAdapter() {}

    /** Chat line with the badge before the sender's name, or the line unchanged. */
    public static Component decorateChat(Component message) {
        if (message == null || !ModUsers.any()) return message;
        String plain = message.getString();
        if (plain.contains(GLYPH)) return message;
        Matcher m = SENDER.matcher(plain);
        if (!m.find() || !ModUsers.isUser(m.group(1))) return message;
        int at = m.start(1);
        MutableComponent out = Component.empty();
        int[] pos = {0};
        boolean[] done = {false};
        message.visit((style, text) -> {
            int start = pos[0];
            pos[0] = start + text.length();
            if (done[0] || at < start || at >= pos[0]) {
                out.append(Component.literal(text).withStyle(style));
                return Optional.empty();
            }
            if (at > start) out.append(Component.literal(text.substring(0, at - start)).withStyle(style));
            Style badge = BADGE_STYLE.withClickEvent(style.getClickEvent()).withHoverEvent(style.getHoverEvent());
            out.append(Component.literal(GLYPH).withStyle(badge));
            out.append(Component.literal(" ").withStyle(style.withObfuscated(false)));
            out.append(Component.literal(text.substring(at - start)).withStyle(style));
            done[0] = true;
            return Optional.empty();
        }, Style.EMPTY);
        return done[0] ? out : message;
    }

    /**
     * Tab-list entry hook (in place of the ping icon, same cell geometry).
     *
     * @return true if the badge was drawn (the ping icon is then skipped)
     */
    public static boolean tabBadge(GuiGraphicsExtractor g, int width, int x, int y, PlayerInfo info) {
        String profileName = info.getProfile().name();
        if (profileName != null && !profileName.isEmpty()) ModUsers.learn(info.getProfile().id().toString(), profileName);
        if (!ModUsers.any()) return false;
        if (!ModUsers.isUser(profileName) && !ModUsers.isUser(displayName(info))) return false;
        var font = Minecraft.getInstance().font;
        int bx = x + width - BADGE_W;
        // High GUI scale: vanilla clamps the column to the screen, so long names run past the cell edge — keep the
        // badge after the name instead of on top of it (name starts after the 8 px head + 1 px gap).
        Component name = info.getTabListDisplayName();
        if (name == null && profileName != null) name = Component.literal(profileName);
        if (name != null) bx = Math.max(bx, x + 9 + font.width(name) + 1);
        g.text(font, BADGE, bx, y, 0xFFFFFFFF, false);
        return true;
    }

    /** Last word of the entry's display text ("… [Rank] Name" → "Name"), or null. */
    private static String displayName(PlayerInfo info) {
        Component display = info.getTabListDisplayName();
        if (display == null) return null;
        String s = display.getString().strip();
        int space = s.lastIndexOf(' ');
        return space < 0 ? s : s.substring(space + 1);
    }
}
