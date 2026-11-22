package dev.telemetric.chat;

import java.util.List;

/**
 * The "new version" chat block (Mahoraga-themed), centred. Only the title, the mod name and the two versions get the
 * gold-to-black gradient; everything else is grey. Pure Java — {@code game/ChatAdapter#post} shows each line.
 */
public final class UpdateNotice {

    public static final String DISCORD = "https://discord.gg/dqjD94x3Ra"; // null = "ᴅɪѕᴄᴏʀᴅ" stays plain text

    private UpdateNotice() {}

    public static List<ChatText> lines(String current, String latest, String modrinthUrl) {
        int[] g = ChatStyle.GOLD_TO_BLACK;
        int t = ChatStyle.TEXT;

        ChatText title = ChatStyle.gradient(ChatText.of(), "✦ ADAPTATION IS AVAILABLE ✦", g, ChatText.BOLD);

        ChatText wheel = ChatText.of().add("The wheel turns. ", t);
        ChatStyle.gradient(wheel, "Telemetric", g, ChatText.BOLD).add(" adapts once again.", t);

        ChatText versions = ChatText.of().add("Current version: ", t);
        ChatStyle.gradient(versions, "v" + current, g, 0).add(" → ", t).add("New version: ", t);
        ChatStyle.gradient(versions, "v" + latest, g, 0);

        ChatText adapt = ChatText.of().add("No matter what changes, it will adapt.", t);

        ChatText download = ChatText.of().add("Download the latest release on ", t)
                .add("ᴍᴏᴅʀɪɴᴛʜ", t, ChatText.UNDERLINED)
                .hover(ChatText.of().add(modrinthUrl, t)).url(modrinthUrl);
        download.add(" or ", t).add("ᴅɪѕᴄᴏʀᴅ", t, DISCORD == null ? 0 : ChatText.UNDERLINED);
        if (DISCORD != null) download.hover(ChatText.of().add(DISCORD, t)).url(DISCORD);
        download.add("!", t);

        List<ChatText> out = List.of(title, wheel, versions, adapt, download);
        for (ChatText line : out) line.center().tag(ChatStyle.TAG);
        return out;
    }
}
