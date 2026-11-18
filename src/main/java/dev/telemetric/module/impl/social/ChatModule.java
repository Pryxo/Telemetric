package dev.telemetric.module.impl.social;

import dev.telemetric.chat.Callouts;
import dev.telemetric.chat.ChatBus;
import dev.telemetric.chat.ChatChannels;
import dev.telemetric.chat.ChatPattern;
import dev.telemetric.chat.ChatTabs;
import dev.telemetric.chat.ChatView;
import dev.telemetric.data.BossTable;
import dev.telemetric.data.Names;
import dev.telemetric.data.TelosData;
import dev.telemetric.game.ChatHudAdapter;
import dev.telemetric.game.ClientCommandAdapter;
import dev.telemetric.game.NotificationSoundAdapter;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.TelosLocations;
import dev.telemetric.input.InputUtil;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.ActionSetting;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.GroupSetting;
import dev.telemetric.setting.impl.GroupToggleSetting;
import dev.telemetric.setting.impl.KeybindSetting;
import dev.telemetric.setting.impl.MultiChoiceSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.notification.Toasts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * Social → Chat: chat tabs sorted by {@code chat.json}, hidden
 * utility lines, censored guild / group lines, right-click copy, longer history and chat-mode keys. The tab bar is
 * {@code ui/chat/ChatScreenHooks}; lines are filtered through {@link ChatView} on {@code game/ChatHudAdapter}, so a
 * tab switch never loses lines. Server chat mode + {@code /ac /gc /grc}: {@link #channels()}.
 */
public class ChatModule extends Module implements ChatView {

    /** {@code Set your chat mode to guild.} — the server's answer to {@code /chat <mode>}. */
    static final ChatPattern MODE_SET = ChatPattern.builder("Chat mode set", "Set your chat mode to (?<mode>[A-Za-z]+)\\.")
            .anyRuns().build();

    private final GroupToggleSetting tabsGroup = add(new GroupToggleSetting("Chat Tabs",
            "Tab bar above the chat box (Alt + ←/→ switches). Guild / Group / Chat tabs also switch your chat mode", true));
    private final MultiChoiceSetting shownTabs = add(tabsGroup.child(new MultiChoiceSetting("Shown Tabs",
            "Tabs in the bar ('All' is always there)", () -> TelosData.CHAT.get().tabNames(),
            TelosData.CHAT.get().defaultShown())));
    private final BooleanSetting allChatTab = add(tabsGroup.child(new BooleanSetting("All Chat Tab",
            "'All' tab shows every message since the game started (hidden utility lines too) — never trimmed or cleared", true)));

    private final BooleanSetting hideUtility = add(new BooleanSetting("Hide Utility Messages",
            "Hides auction updates, auto-sell earnings and fame gains", true));
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

    private final GroupToggleSetting notifyGroup = add(new GroupToggleSetting("Notifications",
            "Plays your sound when someone calls out a boss or portal you picked", false));
    private final MultiChoiceSetting notifyTargets = add(notifyGroup.child(new MultiChoiceSetting("Bosses & Portals",
            "Call-outs ('Teleport for …') that play the sound", ChatModule::notifyOptions)));
    private final ActionSetting uploadSound = add(notifyGroup.child(new ActionSetting("Upload Sound",
            "Pick an OGG or WAV file (max 10 s played)", () -> NotificationSoundAdapter.pick(err -> {
                if (err != null) Toasts.error(err);
                else {
                    Toasts.success("Notification sound set");
                    playNotification();
                }
            }))));
    private final ActionSetting testSound = add(notifyGroup.child(new ActionSetting("Test Sound",
            "Plays the uploaded sound", () -> {
                if (!playNotification()) Toasts.error("No sound uploaded yet");
            })));
    private final NumberSetting notifyVolume = add(notifyGroup.child(new NumberSetting("Volume",
            "Notification sound volume (%)", 100, 0, 100, 5)));

    /** Same target called again within this time plays no second sound. */
    private static final long NOTIFY_COOLDOWN_MS = 10_000L;
    private final Map<String, Long> lastNotified = new HashMap<>();

    private final ChatTabs tabs = new ChatTabs(Callouts::isCallout);
    private final ChatChannels channels = new ChatChannels(new ChatChannels.Server() {
        @Override public void command(String command) { PlayerAdapter.sendCommand(command); }
        @Override public void chat(String message) { PlayerAdapter.sendChat(message); }
    });

    public ChatModule() {
        super("Chat", "Chat tabs, hidden utility lines, censoring, copy and chat-mode keys", Category.SOCIAL);
        ChatBus.hide(MODE_SET, m -> isEnabled() && channels.expectsConfirmation(System.currentTimeMillis()));
        ChatBus.on(MODE_SET, m -> onModeSet(m.group("mode")));
        // /ac /gc /grc <message>: send in all / guild / group chat without leaving your chat mode
        for (String[] c : new String[][]{{"ac", ChatChannels.DEFAULT}, {"gc", "guild"}, {"grc", "group"}}) {
            ClientCommandAdapter.greedy(c[0], text -> {
                if (!isEnabled() || text.isBlank()) return false;
                channels.enqueue(c[1], text);
                return true;
            });
        }
        TelosData.CHAT.onChange(rules -> rebuildUtility());
        rebuildUtility();
        syncOn(this::sync, tabsGroup, shownTabs, allChatTab, history, censorGuild, censorGroupChat);
    }

    // ------------------------------------------------------------------ state

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

    /** Tab bar shown (module on, Chat Tabs on, more than "All"). */
    public boolean showsTabBar() {
        return isEnabled() && tabsGroup.get() && tabs.shown().size() > 1;
    }

    // ------------------------------------------------------------------ tabs

    /** Opens a tab (click / Alt+arrow): switches the chat mode for Guild / Group / Chat, back to default from them. */
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
        // switched by hand (/chat …) while a channel tab is open: the tab no longer matches, back to All
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

    /** {@code ui/chat/ChatScreenHooks}: a message typed in the chat box. True = handled (queued). */
    public boolean onTyped(String message) {
        if (!isEnabled() || message.startsWith("/")) return false;
        long now = now();
        if (channels.isBusy()) { // a queued send is switching modes: send this in the mode you typed it in
            channels.enqueue(channels.typingMode(), message);
            return true;
        }
        channels.sentByUser(now);
        return false;
    }

    // ------------------------------------------------------------------ ChatView

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
        if (tabs.isArchive()) return -1; // All Chat shows everything as it came
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
        if (showsTabBar() && plain.codePoints().anyMatch(Character::isLetterOrDigit)) tabs.countNew(category, hidden); // no spacer lines
        if (!hidden && notifyGroup.get()) notify(plain);
    }

    // ------------------------------------------------------------------ notifications

    /** Realm bosses, then dungeon portals (Telos tables). */
    private static List<String> notifyOptions() {
        Set<String> out = new LinkedHashSet<>();
        List<String> bosses = new ArrayList<>();
        for (BossTable.Boss b : TelosData.BOSSES.get().all()) if (!b.isDungeonBoss() && !b.name().isBlank()) bosses.add(b.name());
        bosses.sort(String.CASE_INSENSITIVE_ORDER);
        out.addAll(bosses);
        List<String> portals = new ArrayList<>(TelosLocations.dungeons());
        portals.sort(String.CASE_INSENSITIVE_ORDER);
        out.addAll(portals);
        return List.copyOf(out);
    }

    /**
     * A {@code Teleport for <boss | portal>} call-out of a picked target plays the sound (display only: nothing is
     * sent). Own call-outs and repeats within 10 s are skipped.
     */
    private void notify(String plain) {
        if (notifyTargets.get().isEmpty()) return;
        Callouts.Call call = Callouts.parseTeleport(plain);
        if (call == null || call.line().player().equalsIgnoreCase(String.valueOf(PlayerAdapter.name()))) return;
        for (String part : call.target().split(" & ")) {
            String key = Names.placeKey(part);
            for (String picked : notifyTargets.get()) {
                if (key.isEmpty() || !key.equals(Names.placeKey(picked))) continue;
                long now = now();
                Long last = lastNotified.get(key);
                if (last != null && now - last < NOTIFY_COOLDOWN_MS) return;
                lastNotified.put(key, now);
                playNotification();
                return;
            }
        }
    }

    private boolean playNotification() {
        return NotificationSoundAdapter.play(notifyVolume.getFloat() / 100f);
    }

    // ------------------------------------------------------------------ copy / utility

    /** Right-click on a chat line: copies its text (pack glyphs removed). True if something was copied. */
    public boolean copyLine(String plain) {
        if (!isEnabled() || !copy.get() || plain == null) return false;
        String text = withoutGlyphs(plain).strip();
        if (text.isEmpty()) return false;
        InputUtil.setClipboard(text);
        Toasts.success("Copied chat line");
        return true;
    }

    private final List<ChatBus.Subscription> utilityHides = new ArrayList<>();

    private void rebuildUtility() {
        utilityHides.forEach(ChatBus.Subscription::cancel);
        utilityHides.clear();
        int i = 0;
        for (String regex : TelosData.CHAT.get().utility()) {
            ChatPattern p = ChatPattern.builder("Utility line " + (i++), regex).anyRuns().build();
            utilityHides.add(ChatBus.hide(p, m -> isEnabled() && hideUtility.get()));
        }
    }

    private static void flip(BooleanSetting s) {
        s.set(!s.get());
        Toasts.info(s.getName() + (s.get() ? " on" : " off"));
    }

    /** Drops pack-font glyphs: private-use characters and plane-1 symbols below the emoji blocks (Telos icons). */
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
