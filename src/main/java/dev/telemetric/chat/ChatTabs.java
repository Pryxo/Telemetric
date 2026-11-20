package dev.telemetric.chat;

import dev.telemetric.data.TelosData;
import dev.telemetric.data.TelosData.ChatRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Chat tabs: sorts every chat line into one category ({@code tables/chat.json}) and tracks which tab is open.
 * This is display only. A line's category never triggers anything, so lines a player typed can land in any tab.
 * "All" ({@link #ALL}, always first) shows everything. Pure Java; the chat HUD asks {@link #visible} for each line.
 */
public final class ChatTabs {

    public static final String ALL = "All";
    /** Category for lines that matched no rule. */
    public static final String OTHER = "";

    private final Predicate<String> callout;
    private List<String> shown = List.of(ALL);
    private String active = ALL;
    /** "All Chat Tab" setting: when on, "All" shows the full archive (every line since the game started, hidden ones too). */
    private boolean archiveAll;
    /** Lines that arrived in a tab while another one was open. Cleared when the tab is opened. */
    private final Map<String, Integer> unread = new HashMap<>();

    /** @param callout the {@code builtin: callout} check ({@link Callouts#isCallout}) */
    public ChatTabs(Predicate<String> callout) {
        this.callout = callout;
    }

    /** Category key for a line's plain text: the first matching category in {@code chat.json}, or {@link #OTHER}. */
    public String categorize(String plain) {
        for (ChatRules.Category c : TelosData.CHAT.get().categories()) {
            if ("callout".equals(c.builtin()) && callout.test(plain)) return c.key();
            for (Pattern p : c.patterns()) if (p.matcher(plain).find()) return c.key();
        }
        return OTHER;
    }

    /** True if a line of {@code category} shows in the open tab. */
    public boolean visible(String category) {
        return shows(active, category);
    }

    /** True if a line of {@code category} belongs in {@code tab}. */
    public boolean shows(String tab, String category) {
        if (ALL.equals(tab)) return true;
        ChatRules.Tab t = TelosData.CHAT.get().tab(tab);
        return t == null || t.shows(category);
    }

    /** True while "All" is open and shows the full archive. */
    public boolean isArchive() {
        return archiveAll && ALL.equals(active);
    }

    /**
     * Counts a new line as unread in every other tab it belongs to. {@code hidden} lines (kept out of the chat) only
     * count for "All", and only when it shows the archive.
     */
    public void countNew(String category, boolean hidden) {
        for (String tab : shown) {
            if (tab.equals(active)) continue;
            if (hidden ? archiveAll && ALL.equals(tab) : shows(tab, category)) unread.merge(tab, 1, Integer::sum);
        }
    }

    /** Unread lines in {@code tab} (always 0 for the open one). */
    public int unread(String tab) {
        return unread.getOrDefault(tab, 0);
    }

    /**
     * Sets the tabs in the bar: "All" first, then the ticked ones in {@code chat.json} order. Returns true if the open
     * tab had to fall back to "All".
     */
    public boolean setShown(Set<String> ticked, boolean allChat) {
        List<String> list = new ArrayList<>();
        list.add(ALL);
        for (String name : TelosData.CHAT.get().tabNames()) {
            for (String t : ticked) if (t.equalsIgnoreCase(name)) { list.add(name); break; }
        }
        archiveAll = allChat;
        shown = List.copyOf(list);
        unread.keySet().retainAll(shown);
        if (!shown.contains(active)) {
            active = ALL;
            unread.remove(ALL);
            return true;
        }
        return false;
    }

    public List<String> shown() {
        return shown;
    }

    public String active() {
        return active;
    }

    /** Opens {@code tab}. Returns false if it's already open or isn't in the bar. */
    public boolean open(String tab) {
        if (tab == null || tab.equals(active) || !shown.contains(tab)) return false;
        active = tab;
        unread.remove(tab);
        return true;
    }

    /** The tab {@code step} places away from the open one (wraps around). */
    public String neighbour(int step) {
        int i = Math.max(0, shown.indexOf(active));
        int n = shown.size();
        return shown.get(Math.floorMod(i + step, n));
    }

    /** The server chat mode the open tab sends in ({@code guild} …), or "" if it has none (All, Callouts, …). */
    public String channelOf(String tab) {
        ChatRules.Tab t = TelosData.CHAT.get().tab(tab);
        return t == null ? "" : t.channel();
    }
}
