package dev.telemetric.module.impl.social;

import dev.telemetric.chat.ChatBus;
import dev.telemetric.chat.ChatPattern;
import dev.telemetric.data.ServerMessageTable;
import dev.telemetric.data.TelosData;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.MultiChoiceSetting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Social → Server Messages: hides picked server lines from the chat. Lines come from
 * {@code tables/serverMessages.json} — a boss option hides every line listed under that boss, a misc option its one
 * line. Hidden lines are still dispatched on {@link ChatBus}, so trackers keep reading them (All Chat shows them).
 */
public class ServerMessagesModule extends Module {

    private final MultiChoiceSetting bosses = add(new MultiChoiceSetting("Boss",
            "Bosses whose messages are hidden (all lines listed for them)",
            () -> TelosData.SERVER_MESSAGES.get().bossNames()).searchable());
    private final MultiChoiceSetting misc = add(new MultiChoiceSetting("Misc",
            "Other server messages to hide",
            () -> TelosData.SERVER_MESSAGES.get().miscNames()).searchable());

    private final List<ChatBus.Subscription> hides = new ArrayList<>();

    public ServerMessagesModule() {
        super("Server Messages", "Hide picked boss / misc server messages from the chat (still tracked)", Category.SOCIAL);
        TelosData.SERVER_MESSAGES.onChange(table -> rebuild());
        rebuild();
    }

    private synchronized void rebuild() {
        hides.forEach(ChatBus.Subscription::cancel);
        hides.clear();
        ServerMessageTable table = TelosData.SERVER_MESSAGES.get();
        for (Map.Entry<String, List<Pattern>> e : table.bosses().entrySet()) {
            String boss = e.getKey();
            int i = 0;
            for (Pattern p : e.getValue()) {
                hides.add(ChatBus.hide(pattern(boss + " line " + (i++), p), m -> isEnabled() && bosses.contains(boss)));
            }
        }
        for (Map.Entry<String, Pattern> e : table.misc().entrySet()) {
            String name = e.getKey();
            hides.add(ChatBus.hide(pattern("Misc line " + name, e.getValue()), m -> isEnabled() && misc.contains(name)));
        }
    }

    private static ChatPattern pattern(String name, Pattern p) {
        return ChatPattern.builder(name, p.pattern()).anyRuns().build();
    }
}
