package dev.telemetric.module.impl.social;

import dev.telemetric.chat.Callouts;
import dev.telemetric.chat.ChatBus;
import dev.telemetric.chat.ChatChannels;
import dev.telemetric.chat.ChatPattern;
import dev.telemetric.chat.ChatTabs;
import dev.telemetric.chat.ChatView;
import dev.telemetric.data.TelosData;
import dev.telemetric.game.ChatHudAdapter;
import dev.telemetric.game.ClientCommandAdapter;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.input.InputUtil;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.GroupSetting;
import dev.telemetric.setting.impl.GroupToggleSetting;
import dev.telemetric.setting.impl.KeybindSetting;
import dev.telemetric.setting.impl.MultiChoiceSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.setting.impl.TextSetting;
import dev.telemetric.ui.notification.Toasts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Chat tabs sorted by {@code chat.json}, censored guild and group lines, right-click copy, a longer
 * history and chat-mode keys. The tab bar lives in {@code ui/chat/ChatScreenHooks}, and lines are filtered through
 * {@link ChatView} on {@code game/ChatHudAdapter}, so switching tabs never loses lines. For the server chat mode and
 * {@code /ac /gc /grc}, see {@link #channels()}.
 */
public class ChatModule extends Module implements ChatView {

    /** {@code Set your chat mode to guild.}, the server's answer to {@code /chat <mode>}. */
    static final ChatPattern MODE_SET = ChatPattern.builder("Chat mode set", "Set your chat mode to (?<mode>[A-Za-z]+)\\.")
            .anyRuns().build();

    private final GroupToggleSetting tabsGroup = add(new GroupToggleSetting("Chat Tabs",
            "Tab bar above the chat box (Alt + ←/→ switches). Guild / Group / Chat tabs also switch your chat mode", true));
    private final MultiChoiceSetting shownTabs = add(tabsGroup.child(new MultiChoiceSetting("Shown Tabs",
            "Tabs in the bar ('All' is always there)", () -> TelosData.CHAT.get().tabNames(),
            TelosData.CHAT.get().defaultShown())));
    private final BooleanSetting allChatTab = add(tabsGroup.child(new BooleanSetting("All Chat Tab",
            "'All' tab shows every message since the game started (hidden server messages too) — never trimmed or cleared", true)));

    private final BooleanSetting copy = add(new BooleanSetting("Right-Click Copy",
            "Right-click a chat line to copy its text", true));
    private final NumberSetting history = add(new NumberSetting("History",
            "Chat lines kept (vanilla 100) — tabs show older lines of quiet channels", 1000, 100, 5000, 100));

    private final GroupSetting censorGroup = add(new GroupSetting("Censor", "Show only who wrote in guild / group chat"));
    private final BooleanSetting censorGuild = add(censorGroup.child(new BooleanSetting("Censor Guild",
            "Guild lines show as '[Guild] name: ...'", false)));
    private final BooleanSetting censorGroupChat = add(censorGroup.child(new BooleanSetting("Censor Group",
            "Group lines show as '[Group] name: ...'", false)));
    private final KeybindSetting censorGuildKey = add(censorGroup.child(new KeybindSetting("Censor Guild Key",
            "Toggles Censor Guild", KeybindSetting.NONE).onPress(() -> flip(censorGuild))));
    private final KeybindSetting censorGroupKey = add(censorGroup.child(new KeybindSetting("Censor Group Key",
            "Toggles Censor Group", KeybindSetting.NONE).onPress(() -> flip(censorGroupChat))));

    private final GroupSetting modeKeys = add(new GroupSetting("Chat Mode Keys", "Switch your server chat mode"));
    private final KeybindSetting guildKey = add(modeKeys.child(new KeybindSetting("Toggle Guild Chat",
            "Guild chat on / back to default", KeybindSetting.NONE).onPress(() -> toggleMode("guild"))));
    private final KeybindSetting groupKey = add(modeKeys.child(new KeybindSetting("Toggle Group Chat",
            "Group chat on / back to default", KeybindSetting.NONE).onPress(() -> toggleMode("group"))));

    private final BooleanSetting emojiButton = add(new BooleanSetting("Emoji Button",
            "Emoji icon at the right end of the chat box: opens a picker with every Telos emoji (search, favorites)", true));
    /** Hidden: favorite emoji codes, space-separated, in the order they were added. */
    private final TextSetting emojiFavorites = add(new TextSetting("Emoji Favorites", "", "", 4000)
            .filter(c -> c > 32 && c != 127).visibleWhen(() -> false));
    /** Hidden: collapsed Emoji Picker sections, '|'-separated. */
    private final TextSetting emojiCollapsed = add(new TextSetting("Emoji Collapsed", "", "", 1000)
            .visibleWhen(() -> false));
    /** Hidden: Emoji Button offset from its default spot (right-drag), "dx,dy" in GUI pixels. */
    private final TextSetting emojiButtonOffset = add(new TextSetting("Emoji Button Offset", "", "", 32)
            .visibleWhen(() -> false));

    private final ChatTabs tabs = new ChatTabs(Callouts::isCallout);
    private final ChatChannels channels = new ChatChannels(new ChatChannels.Server() {
        @Override public void command(String command) { PlayerAdapter.sendCommand(command); }
        @Override public void chat(String message) { PlayerAdapter.sendChat(message); }
    });

    public ChatModule() {
        super("Chat", "Chat tabs, censoring, copy and chat-mode keys", Category.SOCIAL);
        ChatBus.hide(MODE_SET, m -> isEnabled() && channels.expectsConfirmation(System.currentTimeMillis()));
        ChatBus.on(MODE_SET, m -> onModeSet(m.group("mode")));
        // /ac /gc /grc <message> send in all / guild / group chat without leaving your chat mode
        for (String[] c : new String[][]{{"ac", ChatChannels.DEFAULT}, {"gc", "guild"}, {"grc", "group"}}) {
            ClientCommandAdapter.greedy(c[0], text -> {
                if (!isEnabled() || text.isBlank()) return false;
                channels.enqueue(c[1], text);
                return true;
            });
        }
        syncOn(this::sync, tabsGroup, shownTabs, allChatTab, history, censorGuild, censorGroupChat);
    }


    private void sync() {
        boolean on = isEnabled();
        ChatHudAdapter.setHistory(on ? history.getInt() : ChatHudAdapter.VANILLA_HISTORY);
        String channelBefore = tabs.channelOf(tabs.active());
        boolean bar = on && tabsGroup.get();
        if (tabs.setShown(bar ? shownTabs.get() : Set.of(), bar && allChatTab.get()) && !channelBefore.isEmpty()) {
            channels.switchTo(ChatChannels.DEFAULT, now()); // the open channel tab went away
        }
        ChatHudAdapter.setView(on ? this : null);
        ChatHudAdapter.refresh();
    }

    @Override
    public void onTick() {
        channels.tick(now());
    }

    public ChatTabs tabs() {
        return tabs;
    }

    public ChatChannels channels() {
        return channels;
    }

    /** True if the tab bar is shown (module on, Chat Tabs on, more tabs than just "All"). */
    public boolean showsTabBar() {
        return isEnabled() && tabsGroup.get() && tabs.shown().size() > 1;
    }


    public boolean showsEmojiButton() {
        return isEnabled() && emojiButton.get();
    }

    public List<String> emojiFavorites() {
        String v = emojiFavorites.get().trim();
        return v.isEmpty() ? List.of() : List.of(v.split(" +"));
    }

    public void setEmojiFavorites(List<String> codes) {
        emojiFavorites.set(String.join(" ", new LinkedHashSet<>(codes)));
    }

    public Set<String> emojiCollapsed() {
        String v = emojiCollapsed.get();
        return v.isEmpty() ? Set.of() : new LinkedHashSet<>(Arrays.asList(v.split(java.util.regex.Pattern.quote("|"))));
    }

    public void setEmojiCollapsed(Set<String> sections) {
        emojiCollapsed.set(String.join("|", new ArrayList<>(sections)));
    }

    /** Emoji Button offset {dx, dy} from its default spot; {0, 0} if never moved. */
    public int[] emojiButtonOffset() {
        String[] p = emojiButtonOffset.get().split(",");
        if (p.length != 2) return new int[] {0, 0};
        try {
            return new int[] {Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())};
        } catch (NumberFormatException e) {
            return new int[] {0, 0};
        }
    }

    public void setEmojiButtonOffset(int dx, int dy) {
        emojiButtonOffset.set(dx == 0 && dy == 0 ? "" : dx + "," + dy);
    }


    /** Opens a tab (click or Alt+arrow). Switches the chat mode for Guild / Group / Chat, and back to default when leaving them. */
    public void openTab(String tab) {
        String before = tabs.active();
        if (!tabs.open(tab)) return;
        String channel = tabs.channelOf(tab);
        if (!channel.isEmpty()) channels.switchTo(channel, now());
        else if (!tabs.channelOf(before).isEmpty()) channels.switchTo(ChatChannels.DEFAULT, now());
        ChatHudAdapter.refresh();
    }

    public void cycleTab(int step) {
        openTab(tabs.neighbour(step));
    }

    private void onModeSet(String mode) {
        boolean own = channels.confirmed(mode, now());
        // you switched by hand (/chat …) while a channel tab was open, so the tab doesn't match anymore: back to All
        String tabChannel = tabs.channelOf(tabs.active());
        if (!own && isEnabled() && !tabChannel.isEmpty() && !tabChannel.equalsIgnoreCase(mode.toLowerCase(Locale.ROOT))) {
            tabs.open(ChatTabs.ALL);
            ChatHudAdapter.refresh();
        }
    }

    private void toggleMode(String channel) {
        if (!tabs.channelOf(tabs.active()).isEmpty()) {
            Toasts.error("Leave the " + tabs.active() + " tab to switch chat mode (or use /ac /gc /grc)");
            return;
        }
        channels.toggle(channel, now());
    }

    /** {@code ui/chat/ChatScreenHooks}: a message typed in the chat box. Returns true if it was handled (queued). */
    public boolean onTyped(String message) {
        if (!isEnabled() || message.startsWith("/")) return false;
        long now = now();
        if (channels.isBusy()) { // a queued send is switching modes, so send this in the mode you typed it in
            channels.enqueue(channels.typingMode(), message);
            return true;
        }
        channels.sentByUser(now);
        return false;
    }


    @Override
    public String category(String plain) {
        return tabs.categorize(plain);
    }

    @Override
    public boolean visible(String category) {
        return tabs.visible(category);
    }

    @Override
    public int censorAt(String plain, String category) {
        if (tabs.isArchive()) return -1; // All Chat shows everything as it came in
        boolean censor = ("guild".equals(category) && censorGuild.get()) || ("group".equals(category) && censorGroupChat.get());
        if (!censor) return -1;
        Callouts.Line line = Callouts.line(plain);
        return line == null ? -1 : line.contentStart();
    }

    @Override
    public boolean archive() {
        return tabs.isArchive();
    }

    @Override
    public void onNewLine(String plain, String category, boolean hidden) {
        if (showsTabBar() && plain.codePoints().anyMatch(Character::isLetterOrDigit)) tabs.countNew(category, hidden); // spacer lines don't count
    }


    /** Right-click on a chat line copies its text (pack glyphs removed). Returns true if something was copied. */
    public boolean copyLine(String plain) {
        if (!isEnabled() || !copy.get() || plain == null) return false;
        String text = withoutGlyphs(plain).strip();
        if (text.isEmpty()) return false;
        InputUtil.setClipboard(text);
        Toasts.success("Copied chat line");
        return true;
    }

    private static void flip(BooleanSetting s) {
        s.set(!s.get());
        Toasts.info(s.getName() + (s.get() ? " on" : " off"));
    }

    /** Drops pack-font glyphs: private-use characters and the plane-1 symbols below the emoji blocks (Telos icons). */
    static String withoutGlyphs(String s) {
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().filter(cp -> Character.getType(cp) != Character.PRIVATE_USE && (cp < 0x10000 || cp >= 0x1F000))
                .forEach(b::appendCodePoint);
        return b.toString().replaceAll(" {2,}", " ");
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
