package dev.telemetric.ui.chat;

import dev.telemetric.Telemetric;
import dev.telemetric.game.ChatHudAdapter;
import dev.telemetric.game.ServerGate;
import dev.telemetric.input.InputUtil;
import dev.telemetric.module.impl.social.ChatModule;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
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
 * MC ADAPTER (UI) — what Social → Chat adds to the chat screen ({@code mixin/ChatScreenMixin}): the tab bar between
 * the chat lines and the input box (click a tab, Alt + ←/→ cycles, the scroll wheel over the bar pans it; a red badge counts
 * lines a tab got while another one was open), right-click on a line (a link's right-click
 * action, else copy) and typed messages while a queued send switches chat modes. Colours from the theme; the
 * active-tab underline slides ({@link Durations#STANDARD}), hover fades ({@link Durations#HOVER}).
 */
public final class ChatScreenHooks {

    private static final int PAD = 3, GAP = 2, LEFT = 2, BOTTOM_OFFSET = 32;
    private static final int GLFW_MOD_ALT = 0x0004;
    /** Unread badge: fixed status colours (like toast success / error), not theme colours. */
    private static final int PING = 0xFFEF4444, PING_TEXT = 0xFFFFFFFF;
    private static final int BADGE_GAP = 3;

    private static final Render2D R = new Render2D();
    private static final Map<String, Animation> HOVER = new HashMap<>();
    private static final Animation UNDER_X = new Animation(Durations.STANDARD, Easing.OUT_CUBIC, 0);
    private static final Animation UNDER_W = new Animation(Durations.STANDARD, Easing.OUT_CUBIC, 0);
    private static int scroll;
    private static int maxScroll;
    private static String followed;
    /** Pixels the bar pans per wheel notch. */
    private static final int WHEEL_STEP = 12;
    private static boolean underlineShown;

    private ChatScreenHooks() {}

    private static ChatModule chat() {
        return ServerGate.onTelos() ? Telemetric.get().modules().get(ChatModule.class) : null;
    }

    // ------------------------------------------------------------------ tab bar

    public static void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        ChatModule chat = chat();
        if (chat == null || !chat.showsTabBar()) return;
        Telemetric mod = Telemetric.get();
        Theme p = mod.theme().palette();
        List<String> tabs = chat.tabs().shown();
        String active = chat.tabs().active();
        int y = top(), h = barHeight(), maxW = ChatHudAdapter.chatWidth();
        keepVisible(chat, tabs, active, maxW);

        R.begin(graphics, mod.theme().textShadow());
        try {
            R.pushClip(LEFT, y - 1, maxW, h + 2);
            int x = LEFT - scroll;
            for (String tab : tabs) {
                int w = tabWidth(chat, tab);
                boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
                Animation hover = HOVER.computeIfAbsent(tab, k -> new Animation(Durations.HOVER, Easing.OUT_CUBIC, 0));
                hover.animateTo(hovered ? 1 : 0);
                boolean isActive = tab.equals(active);
                int bg = isActive ? p.surfaceActive() : ColorUtil.lerp(ColorUtil.fade(p.surface(), 0.6), p.surfaceHover(), hover.get());
                R.roundedRect(x, y, w, h, 2, bg);
                int text = isActive ? p.text() : ColorUtil.lerp(p.textMuted(), p.text(), hover.get());
                R.text(tab, x + PAD, y + PAD, text);
                int unread = chat.tabs().unread(tab);
                if (unread > 0) badge(x + PAD + R.textWidth(tab) + BADGE_GAP, y, h, unread);
                if (isActive) slideUnderline(x + PAD - (LEFT - scroll), w - PAD * 2);
                x += w + GAP;
            }
            R.rect(LEFT - scroll + (int) UNDER_X.get(), y + h - 1, (int) UNDER_W.get(), 1, p.accent());
            R.popClip();
        } finally {
            R.end();
        }
    }

    /** Red pill with the unread count ("99+" above 99). */
    private static void badge(int x, int y, int barH, int count) {
        String label = badgeLabel(count);
        int bh = barH - 4, bw = Math.max(bh, R.textWidth(label) + 4);
        R.roundedRect(x, y + 2, bw, bh, bh / 2, PING);
        R.text(label, x + (bw - R.textWidth(label)) / 2 + 1, y + 3, PING_TEXT);
    }

    private static String badgeLabel(int count) {
        return count > 99 ? "99+" : String.valueOf(count);
    }

    /** Tab width: label + padding (+ badge). Same font as {@link Render2D#textWidth}. */
    private static int tabWidth(ChatModule chat, String tab) {
        var font = Minecraft.getInstance().font;
        int w = font.width(tab) + PAD * 2;
        int unread = chat.tabs().unread(tab);
        if (unread > 0) {
            int bh = barHeight() - 4;
            w += BADGE_GAP + Math.max(bh, font.width(badgeLabel(unread)) + 4);
        }
        return w;
    }

    /** Moves the underline to the open tab ({@code barX} from the bar's start, so scrolling does not animate it). */
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

    /** Scrolls the bar so the open tab is fully visible when the tabs are wider than the chat. */
    private static void keepVisible(ChatModule chat, List<String> tabs, String active, int maxW) {
        int x = 0, total = 0;
        for (String tab : tabs) total += tabWidth(chat, tab) + GAP;
        maxScroll = Math.max(0, total - GAP - maxW);
        if (maxScroll == 0) {
            scroll = 0;
            return;
        }
        if (scroll > maxScroll) scroll = maxScroll;
        // Only follow the open tab when it changes, so the wheel can pan freely.
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

    // ------------------------------------------------------------------ input

    public static boolean mouseClicked(double mouseX, double mouseY, int button) {
        ChatModule chat = chat();
        if (chat == null || !chat.isEnabled()) return false;
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

    /** Scroll wheel over the tab bar pans it (never switches tab): up / left = left. True = consumed. */
    public static boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        ChatModule chat = chat();
        if (chat == null || !chat.showsTabBar()) return false;
        int y = top(), h = barHeight();
        if (mouseY < y || mouseY >= y + h || mouseX < LEFT || mouseX > LEFT + ChatHudAdapter.chatWidth()) return false;
        double amount = scrollY != 0 ? scrollY : -scrollX;
        if (amount == 0) return true;
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.round(amount * WHEEL_STEP)));
        return true;
    }

    public static boolean keyPressed(int key, int modifiers) {
        ChatModule chat = chat();
        if (chat == null || !chat.showsTabBar() || (modifiers & GLFW_MOD_ALT) == 0) return false;
        if (key == InputUtil.KEY_LEFT) chat.cycleTab(-1);
        else if (key == InputUtil.KEY_RIGHT) chat.cycleTab(1);
        else return false;
        return true;
    }

    /** True = the message was queued (do not send it now). */
    public static boolean onSend(String message, boolean addToHistory) {
        ChatModule chat = chat();
        if (chat == null || message == null || !chat.onTyped(message)) return false;
        Minecraft mc = Minecraft.getInstance();
        if (addToHistory && mc.gui != null) mc.gui.getChat().addRecentChat(message);
        return true;
    }
}
