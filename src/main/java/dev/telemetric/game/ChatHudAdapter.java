package dev.telemetric.game;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import dev.telemetric.Telemetric;
import dev.telemetric.chat.ChatLink;
import dev.telemetric.chat.ChatView;
import dev.telemetric.mixin.ChatComponentAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * MC ADAPTER — the chat HUD ({@code ChatComponent}, via {@code mixin/ChatComponentMixin} / {@code ChatScreenMixin}):
 * <ul>
 *   <li><b>links</b>: features add clickable parts to new lines ({@link #addLinker}, pure {@link ChatLink}s). A link is
 *       a {@code ClickEvent.Custom} {@code telemetric:link/<n>} handled here on the client ({@link #onClick}) — never
 *       sent to the server, no command confirmation screen, and server click events elsewhere stay untouched;</li>
 *   <li><b>view</b>: which lines show and which are censored ({@link #setView}, chat tabs). Lines stay in the chat
 *       history, so switching tabs ({@link #refresh}) re-shows them — vanilla's own {@code refreshTrimmedMessages};</li>
 *   <li><b>history</b>: lines kept (vanilla 100, {@link #setHistory});</li>
 *   <li>the line / click target under the mouse while the chat is open.</li>
 * </ul>
 * Everything is display-only and off Telos inactive.
 */
public final class ChatHudAdapter {

    public static final int VANILLA_HISTORY = 100;
    private static final String LINK_PATH = "link/";
    private static final int MAX_LINKS = 4096;
    /** Messages All Chat keeps (newest first), hidden server lines included; never cleared. */
    public static final int ARCHIVE_MAX = 20_000;
    private static final ArrayDeque<GuiMessage> ARCHIVE = new ArrayDeque<>();

    private static final List<Function<String, List<ChatLink>>> LINKERS = new CopyOnWriteArrayList<>();
    /** Link id → link; oldest dropped first (their lines left the history long ago). */
    private static final Map<Integer, ChatLink> LINKS = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, ChatLink> eldest) {
            return size() > MAX_LINKS;
        }
    };
    /** Line content → category (identity keys: a line keeps its component). */
    private static final Cache<Component, String> CATEGORIES = CacheBuilder.newBuilder().weakKeys().build();

    private static int nextLink;
    private static volatile ChatView view;
    private static volatile int history = VANILLA_HISTORY;

    private ChatHudAdapter() {}

    // ------------------------------------------------------------------ links

    /** Adds clickable parts to every new line: {@code linker} gets the plain text, returns links (or empty). */
    public static void addLinker(Function<String, List<ChatLink>> linker) {
        LINKERS.add(linker);
    }

    /** {@code mixin/ChatComponentMixin}: a new line, before it is stored. */
    public static Component decorate(Component message) {
        if (message == null || LINKERS.isEmpty() || !ServerGate.onTelos()) return message;
        String plain = message.getString();
        List<ChatLink> links = new ArrayList<>(2);
        for (Function<String, List<ChatLink>> linker : LINKERS) {
            try {
                List<ChatLink> l = linker.apply(plain);
                if (l != null) for (ChatLink link : l) if (link.end() <= plain.length()) links.add(link);
            } catch (RuntimeException e) {
                Telemetric.LOGGER.error("Chat linker failed", e);
            }
        }
        if (links.isEmpty()) return message;
        links.sort(Comparator.comparingInt(ChatLink::start));
        return withLinks(message, links);
    }

    /** Rebuilds the line so the characters of each link carry its click + hover (the rest keeps its style). */
    private static Component withLinks(Component message, List<ChatLink> links) {
        Style[] linkStyles = new Style[links.size()];
        int hoverColor = Telemetric.get().theme().palette().accent() & 0xFFFFFF;
        for (int i = 0; i < links.size(); i++) {
            ChatLink link = links.get(i);
            int id;
            synchronized (LINKS) {
                id = nextLink++;
                LINKS.put(id, link);
            }
            Identifier target = Identifier.fromNamespaceAndPath(Telemetric.MOD_ID, LINK_PATH + id);
            linkStyles[i] = Style.EMPTY.withClickEvent(new ClickEvent.Custom(target, Optional.empty()))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(link.hover())
                            .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(hoverColor)))));
        }
        MutableComponent out = Component.empty();
        int[] pos = {0};
        message.visit((style, text) -> {
            int start = pos[0], end = start + text.length();
            pos[0] = end;
            int cut = start;
            while (cut < end) {
                int li = linkAt(links, cut);
                int next = end;
                if (li >= 0) next = Math.min(end, links.get(li).end());
                else for (ChatLink l : links) if (l.start() > cut && l.start() < next) next = l.start();
                Style s = li < 0 ? style
                        : style.withClickEvent(linkStyles[li].getClickEvent()).withHoverEvent(linkStyles[li].getHoverEvent());
                out.append(Component.literal(text.substring(cut - start, next - start)).withStyle(s));
                cut = next;
            }
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    private static int linkAt(List<ChatLink> links, int index) {
        for (int i = 0; i < links.size(); i++) {
            ChatLink l = links.get(i);
            if (index >= l.start() && index < l.end()) return i;
        }
        return -1;
    }

    private static ChatLink linkFor(ClickEvent event) {
        if (!(event instanceof ClickEvent.Custom custom)) return null;
        Identifier id = custom.id();
        if (!Telemetric.MOD_ID.equals(id.getNamespace()) || !id.getPath().startsWith(LINK_PATH)) return null;
        try {
            int n = Integer.parseInt(id.getPath().substring(LINK_PATH.length()));
            synchronized (LINKS) {
                return LINKS.get(n);
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code mixin/ScreenMixin}: a clicked chat event. True = it was ours and is handled (do not send it). */
    public static boolean onClick(ClickEvent event) {
        if (event instanceof ClickEvent.Custom c && Telemetric.MOD_ID.equals(c.id().getNamespace())) {
            ChatLink link = linkFor(event);
            if (link != null && ServerGate.onTelos()) run(link.click());
            return true; // ours (maybe expired): never sent to the server
        }
        return false;
    }

    /** Right-click on a link under the mouse: runs its right-click action. True if there was one. */
    public static boolean rightClickLink(double mouseX, double mouseY) {
        Style style = styleAt(mouseX, mouseY);
        ChatLink link = style == null ? null : linkFor(style.getClickEvent());
        if (link == null || link.rightClick() == null) return false;
        run(link.rightClick());
        return true;
    }

    private static void run(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            Telemetric.LOGGER.error("Chat link action failed", e);
        }
    }

    // ------------------------------------------------------------------ view (tabs / censor)

    /** Sets how lines show (null = like vanilla); call {@link #refresh} afterwards to re-lay out the chat. */
    public static void setView(ChatView newView) {
        view = newView;
    }

    /** Re-builds the visible lines from the history (after a tab switch / censor toggle). */
    public static void refresh() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.gui != null) mc.gui.getChat().rescaleChat();
        });
    }

    private static String categoryOf(GuiMessage message, ChatView v) {
        Component content = message.content();
        String c = CATEGORIES.getIfPresent(content);
        if (c == null) {
            c = v.category(content.getString());
            CATEGORIES.put(content, c);
        }
        return c;
    }

    /**
     * {@code mixin/ChatComponentMixin}: false = keep this line out of the visible chat (it stays in the history).
     * Client lines (feature feedback, never from the server) show in every tab.
     */
    public static boolean isVisible(GuiMessage message) {
        ChatView v = view;
        if (v == null || !ServerGate.onTelos() || message.source() == GuiMessageSource.SYSTEM_CLIENT) return true;
        try {
            return v.visible(categoryOf(message, v));
        } catch (RuntimeException e) {
            Telemetric.LOGGER.error("Chat view failed", e);
            return true;
        }
    }

    /** {@code mixin/ChatComponentMixin}: the wrapped lines of a message, censored when the view says so. */
    public static List<FormattedCharSequence> split(GuiMessage message, Font font, int width,
                                                    Function<GuiMessage, List<FormattedCharSequence>> vanilla) {
        ChatView v = view;
        if (v == null || !ServerGate.onTelos()) return vanilla.apply(message);
        String plain = message.content().getString();
        int keep = v.censorAt(plain, categoryOf(message, v));
        if (keep < 0 || keep >= plain.length()) return vanilla.apply(message);
        GuiMessage censored = new GuiMessage(message.addedTime(), truncate(message.content(), keep),
                message.signature(), message.source(), message.tag());
        return censored.splitLines(font, width);
    }

    /** The first {@code keep} characters of {@code c} (styles kept) + "...". */
    private static Component truncate(Component c, int keep) {
        MutableComponent out = Component.empty();
        int[] left = {keep};
        Style[] last = {Style.EMPTY};
        c.visit((style, text) -> {
            if (left[0] <= 0) return Optional.of(Boolean.TRUE);
            String part = text.length() <= left[0] ? text : text.substring(0, left[0]);
            out.append(Component.literal(part).withStyle(style));
            left[0] -= part.length();
            last[0] = style;
            return Optional.empty();
        }, Style.EMPTY);
        out.append(Component.literal("...").withStyle(last[0]));
        return out;
    }

    // ------------------------------------------------------------------ history

    /** Chat lines kept (vanilla 100); takes effect for new lines. */
    public static void setHistory(int lines) {
        history = Math.max(VANILLA_HISTORY, lines);
    }

    /** {@code mixin/ChatComponentMixin}: replaces vanilla's 100-message history limit. */
    public static int history() {
        return history;
    }

    /** {@code mixin/ChatComponentMixin}: wrapped lines shown (the history, or the whole archive in All Chat). */
    public static int displayLimit() {
        ChatView v = view;
        return v != null && v.archive() ? ARCHIVE_MAX * 4 : history;
    }

    // ------------------------------------------------------------------ All Chat archive

    /** {@code mixin/ChatComponentMixin}: a new line entered the history (once; re-layouts do not come here). */
    public static void onStored(GuiMessage message) {
        if (message == null || !ServerGate.onTelos()) return;
        archive(message);
        ChatView v = view;
        if (v == null || message.source() == GuiMessageSource.SYSTEM_CLIENT) return;
        try {
            v.onNewLine(message.content().getString(), categoryOf(message, v), false);
        } catch (RuntimeException e) {
            Telemetric.LOGGER.error("Chat view failed", e);
        }
    }

    /** {@code game/ChatAdapter}: a server line a hide rule kept out of the chat — only All Chat shows it. */
    public static void onHidden(Component content) {
        Minecraft mc = Minecraft.getInstance();
        if (content == null || mc.gui == null || !ServerGate.onTelos()) return;
        GuiMessage message = new GuiMessage(mc.gui.getGuiTicks(), content, null, GuiMessageSource.SYSTEM_SERVER, null);
        archive(message);
        ChatView v = view;
        if (v == null) return;
        try {
            v.onNewLine(content.getString(), categoryOf(message, v), true);
            if (v.archive()) ((ChatComponentAccessor) mc.gui.getChat()).telemetric$addToDisplay(message);
        } catch (RuntimeException e) {
            Telemetric.LOGGER.error("Chat view failed", e);
        }
    }

    private static void archive(GuiMessage message) {
        ARCHIVE.addFirst(message);
        while (ARCHIVE.size() > ARCHIVE_MAX) ARCHIVE.removeLast();
    }

    /** {@code mixin/ChatComponentMixin} ({@code refreshTrimmedMessages}): true = rebuilt from the archive. */
    public static boolean refreshFromArchive(ChatComponent chat) {
        ChatView v = view;
        if (v == null || !ServerGate.onTelos() || !v.archive()) return false;
        ChatComponentAccessor a = (ChatComponentAccessor) chat;
        a.telemetric$getTrimmedMessages().clear();
        Iterator<GuiMessage> oldestFirst = ARCHIVE.descendingIterator();
        while (oldestFirst.hasNext()) a.telemetric$addToDisplay(oldestFirst.next());
        return true;
    }

    /** {@code mixin/ChatComponentMixin}: vanilla cleared the chat; All Chat re-shows the archive. */
    public static void afterClear() {
        ChatView v = view;
        if (v != null && v.archive()) refresh();
    }

    // ------------------------------------------------------------------ under the mouse (chat open)

    /** Plain text of the chat line under the mouse, or null. */
    public static String messageAt(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return null;
        ChatComponent chat = mc.gui.getChat();
        ChatComponentAccessor acc = (ChatComponentAccessor) chat;
        List<GuiMessage.Line> lines = acc.telemetric$getTrimmedMessages();
        if (lines.isEmpty() || !chat.isChatFocused()) return null;
        double scale = acc.telemetric$getScale();
        if (scale <= 0) return null;
        if (mouseX < 0 || mouseX > acc.telemetric$getWidth() + 8) return null;
        // same geometry as ChatComponent#extractRenderState: lines stack upwards from 40 px above the bottom
        int baseY = Mth.floor((mc.getWindow().getGuiScaledHeight() - 40) / scale);
        int lineHeight = (int) (9.0 * (mc.options.chatLineSpacing().get() + 1.0));
        if (lineHeight <= 0) return null;
        int line = Mth.floor((baseY - mouseY / scale) / lineHeight);
        int scroll = acc.telemetric$getScrollPos();
        int visible = Math.min(lines.size() - scroll, chat.getLinesPerPage());
        if (line < 0 || line >= visible) return null;
        int index = line + scroll;
        if (index < 0 || index >= lines.size()) return null;
        GuiMessage parent = lines.get(index).parent();
        return parent == null ? null : parent.content().getString();
    }

    private static Style styleAt(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return null;
        ActiveTextCollector.ClickableStyleFinder finder =
                new ActiveTextCollector.ClickableStyleFinder(mc.font, (int) mouseX, (int) mouseY);
        mc.gui.getChat().captureClickableText(finder, mc.getWindow().getGuiScaledHeight(), mc.gui.getGuiTicks(),
                ChatComponent.DisplayMode.FOREGROUND);
        return finder.result();
    }

    /** Chat box width in GUI pixels (for the tab bar). */
    public static int chatWidth() {
        Minecraft mc = Minecraft.getInstance();
        return ChatComponent.getWidth(mc.options.chatWidth().get());
    }
}
