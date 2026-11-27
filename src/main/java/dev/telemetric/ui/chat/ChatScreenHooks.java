package dev.telemetric.ui.chat;

import dev.telemetric.Telemetric;
import dev.telemetric.game.ChatHudAdapter;
import dev.telemetric.game.ServerGate;
import dev.telemetric.input.InputUtil;
import dev.telemetric.module.impl.social.ChatModule;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.framework.Hit;
import dev.telemetric.ui.framework.MouseButton;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What Social → Chat adds to the chat screen ({@code mixin/ChatScreenMixin}). That's the tab bar
 * between the chat lines and the input box (click a tab, Alt + ←/→ cycles, the scroll wheel over the bar pans it, and a
 * red badge counts the lines a tab got while another one was open), right-click on a line (a link's right-click action,
 * otherwise copy), typed messages while a queued send is switching chat modes, and the emoji icon + {@link EmojiPicker}
 * at the right end of the input box. Colours come from the theme. The
 * active-tab underline slides ({@link Durations#STANDARD}) and hover fades ({@link Durations#HOVER}).
 */
public final class ChatScreenHooks {

    private static final int PAD = 3, GAP = 2, LEFT = 2, BOTTOM_OFFSET = 32;
    private static final int GLFW_MOD_ALT = 0x0004;
    /** The unread badge uses fixed status colours (like toast success / error), not theme colours. */
    private static final int PING = 0xFFEF4444, PING_TEXT = 0xFFFFFFFF;
    /** Badge height, and how far it pokes out above and to the right of its tab. */
    private static final int BADGE_H = 9, BADGE_RISE = 3, BADGE_OUT = 3;

    private static final Render2D R = new Render2D();
    private static final Map<String, Animation> HOVER = new HashMap<>();
    private static final Animation UNDER_X = new Animation(Durations.STANDARD, Easing.OUT_CUBIC, 0);
    private static final Animation UNDER_W = new Animation(Durations.STANDARD, Easing.OUT_CUBIC, 0);
    private static int scroll;
    private static int maxScroll;
    private static String followed;
    /** How many pixels the bar pans per wheel notch. */
    private static final int WHEEL_STEP = 12;
    private static boolean underlineShown;

    private ChatScreenHooks() {}

    private static ChatModule chat() {
        return ServerGate.onTelos() ? Telemetric.get().modules().get(ChatModule.class) : null;
    }

    public static void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        ChatModule chat = chat();
        if (chat == null) return;
        renderTabBar(graphics, mouseX, mouseY, chat);
        var window = Minecraft.getInstance().getWindow();
        EmojiPicker.render(graphics, mouseX, mouseY, chat, window.getGuiScaledWidth(), window.getGuiScaledHeight());
    }

    private static void renderTabBar(GuiGraphicsExtractor graphics, int mouseX, int mouseY, ChatModule chat) {
        if (!chat.showsTabBar()) return;
        Telemetric mod = Telemetric.get();
        Theme p = mod.theme().palette();
        List<String> tabs = chat.tabs().shown();
        String active = chat.tabs().active();
        int y = top(), h = barHeight(), maxW = ChatHudAdapter.chatWidth();
        keepVisible(chat, tabs, active, maxW);

        R.begin(graphics, mod.theme().textShadow());
        try {
            R.pushClip(LEFT, y - BADGE_RISE - 1, maxW + BADGE_OUT, h + BADGE_RISE + 2);
            int x = LEFT - scroll;
            for (String tab : tabs) {
                int w = tabWidth(chat, tab);
                boolean hovered = Hit.inside(mouseX, mouseY, x, y, w, h);
                Animation hover = HOVER.computeIfAbsent(tab, k -> new Animation(Durations.HOVER, Easing.OUT_CUBIC, 0));
                hover.animateTo(hovered ? 1 : 0);
                boolean isActive = tab.equals(active);
                int bg = isActive ? p.surfaceActive() : ColorUtil.lerp(ColorUtil.fade(p.surface(), 0.6), p.surfaceHover(), hover.get());
                R.roundedRect(x, y, w, h, 2, bg);
                int text = isActive ? p.text() : ColorUtil.lerp(p.textMuted(), p.text(), hover.get());
                R.text(tab, x + PAD, y + PAD, text);
                if (isActive) slideUnderline(x + PAD - (LEFT - scroll), w - PAD * 2);
                x += w + GAP;
            }
            R.rect(LEFT - scroll + (int) UNDER_X.get(), y + h - 1, (int) UNDER_W.get(), 1, p.accent());
            // Badges go in a second pass so a neighbouring tab never covers them.
            x = LEFT - scroll;
            for (String tab : tabs) {
                int w = tabWidth(chat, tab);
                int unread = chat.tabs().unread(tab);
                if (unread > 0) badge(x + w, y, unread);
                x += w + GAP;
            }
            R.popClip();
        } finally {
            R.end();
        }
    }

    /**
     * A red dot or pill with the unread count ("99+" above 99), overlapping the tab's top-right corner so the tab keeps
     * its size. The label is centred on its ink (font width minus the trailing 1 px spacing), and the pill width has the
     * same parity as the ink, so the margins come out equal on both sides.
     */
    private static void badge(int tabRight, int tabTop, int count) {
        String label = badgeLabel(count);
        int ink = R.textWidth(label) - 1;
        int bw = Math.max(BADGE_H, ink + 4);
        if ((bw - ink) % 2 != 0) bw++;
        int bx = tabRight - bw + BADGE_OUT, by = tabTop - BADGE_RISE;
        R.roundedRect(bx, by, bw, BADGE_H, BADGE_H / 2, PING);
        R.text(label, bx + (bw - ink) / 2, by + 1, PING_TEXT); // the digit ink is 7 px tall in a 9 px pill
    }

    private static String badgeLabel(int count) {
        return count > 99 ? "99+" : String.valueOf(count);
    }

    /** Tab width: the label plus padding (the badge overlaps the corner). Same font as {@link Render2D#textWidth}. */
    private static int tabWidth(ChatModule chat, String tab) {
        var font = Minecraft.getInstance().font;
        int w = font.width(tab) + PAD * 2;
        return w;
    }

    /** Moves the underline to the open tab ({@code barX} counts from the bar's start, so scrolling doesn't animate it). */
    private static void slideUnderline(int barX, int width) {
        if (!underlineShown) {
            UNDER_X.snapTo(barX);
            UNDER_W.snapTo(width);
            underlineShown = true;
        } else {
            UNDER_X.animateTo(barX);
            UNDER_W.animateTo(width);
        }
    }

    /** When the tabs are wider than the chat, scrolls the bar so the open tab is fully visible. */
    private static void keepVisible(ChatModule chat, List<String> tabs, String active, int maxW) {
        int x = 0, total = 0;
        for (String tab : tabs) total += tabWidth(chat, tab) + GAP;
        maxScroll = Math.max(0, total - GAP - maxW);
        if (maxScroll == 0) {
            scroll = 0;
            return;
        }
        if (scroll > maxScroll) scroll = maxScroll;
        // only follow the open tab when it changes, so the wheel can pan freely
        if (active.equals(followed)) return;
        followed = active;
        for (String tab : tabs) {
            int w = tabWidth(chat, tab);
            if (tab.equals(active)) {
                if (x < scroll) scroll = x;
                else if (x + w > scroll + maxW) scroll = x + w - maxW;
                return;
            }
            x += w + GAP;
        }
    }

    private static String tabAt(ChatModule chat, double mouseX, double mouseY) {
        int y = top(), h = barHeight();
        if (mouseY < y || mouseY >= y + h || mouseX < LEFT || mouseX > LEFT + ChatHudAdapter.chatWidth()) return null;
        int x = LEFT - scroll;
        for (String tab : chat.tabs().shown()) {
            int w = tabWidth(chat, tab);
            if (mouseX >= x && mouseX < x + w) return tab;
            x += w + GAP;
        }
        return null;
    }

    private static int top() {
        return Minecraft.getInstance().getWindow().getGuiScaledHeight() - BOTTOM_OFFSET;
    }

    private static int barHeight() {
        return Minecraft.getInstance().font.lineHeight + PAD * 2 - 1;
    }

    public static boolean mouseClicked(double mouseX, double mouseY, int button) {
        ChatModule chat = chat();
        if (chat == null || !chat.isEnabled()) return false;
        boolean pickerWasOpen = EmojiPicker.isOpen();
        if (EmojiPicker.mouseClicked(chat, mouseX, mouseY, button)) return true;
        if (pickerWasOpen && button == MouseButton.RIGHT) return false; // the click only closed the picker
        if (button == MouseButton.LEFT && chat.showsTabBar()) {
            String tab = tabAt(chat, mouseX, mouseY);
            if (tab != null) {
                chat.openTab(tab);
                return true;
            }
        }
        if (button == MouseButton.RIGHT) {
            return ChatHudAdapter.rightClickLink(mouseX, mouseY) || chat.copyLine(ChatHudAdapter.messageAt(mouseX, mouseY));
        }
        return false;
    }

    /** The scroll wheel over the tab bar pans it (it never switches tab): up / left goes left. Returns true if consumed. */
    public static boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        ChatModule chat = chat();
        if (chat == null) return false;
        if (EmojiPicker.mouseScrolled(mouseX, mouseY, scrollY)) return true;
        if (!chat.showsTabBar()) return false;
        int y = top(), h = barHeight();
        if (mouseY < y || mouseY >= y + h || mouseX < LEFT || mouseX > LEFT + ChatHudAdapter.chatWidth()) return false;
        double amount = scrollY != 0 ? scrollY : -scrollX;
        if (amount == 0) return true;
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.round(amount * WHEEL_STEP)));
        return true;
    }

    public static boolean keyPressed(int key, int modifiers) {
        ChatModule chat = chat();
        if (chat == null) return false;
        if (EmojiPicker.isOpen()) return EmojiPicker.keyPressed(chat, key, modifiers);
        if (!chat.showsTabBar() || (modifiers & GLFW_MOD_ALT) == 0) return false;
        if (key == InputUtil.KEY_LEFT) chat.cycleTab(-1);
        else if (key == InputUtil.KEY_RIGHT) chat.cycleTab(1);
        else return false;
        return true;
    }

    /** A character typed into the chat input. True if the Emoji Picker's search box took it. */
    public static boolean charTyped(int codepoint) {
        return chat() != null && EmojiPicker.charTyped(codepoint);
    }

    /** The chat screen closed. */
    public static void onClosed() {
        ChatModule chat = chat();
        EmojiPicker.onChatClosed(chat);
    }

    /** Returns true if the message was queued (so don't send it now). */
    public static boolean onSend(String message, boolean addToHistory) {
        ChatModule chat = chat();
        if (chat == null || message == null || !chat.onTyped(message)) return false;
        Minecraft mc = Minecraft.getInstance();
        if (addToHistory && mc.gui != null) mc.gui.getChat().addRecentChat(message);
        return true;
    }
}
