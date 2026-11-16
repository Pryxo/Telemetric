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
 * the chat lines and the input box (click a tab, Alt + ←/→ cycles), right-click on a line (a link's right-click
 * action, else copy) and typed messages while a queued send switches chat modes. Colours from the theme; the
 * active-tab underline slides ({@link Durations#STANDARD}), hover fades ({@link Durations#HOVER}).
 */
public final class ChatScreenHooks {

    private static final int PAD = 3, GAP = 2, LEFT = 2, BOTTOM_OFFSET = 32;
    private static final int GLFW_MOD_ALT = 0x0004;

    private static final Render2D R = new Render2D();
    private static final Map<String, Animation> HOVER = new HashMap<>();
    private static final Animation UNDER_X = new Animation(Durations.STANDARD, Easing.OUT_CUBIC, 0);
    private static final Animation UNDER_W = new Animation(Durations.STANDARD, Easing.OUT_CUBIC, 0);
    private static int scroll;
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
        keepVisible(tabs, active, maxW);

        R.begin(graphics, mod.theme().textShadow());
        try {
            R.pushClip(LEFT, y - 1, maxW, h + 2);
            int x = LEFT - scroll;
            for (String tab : tabs) {
                int w = R.textWidth(tab) + PAD * 2;
                boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
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
            R.popClip();
        } finally {
            R.end();
        }
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
    private static void keepVisible(List<String> tabs, String active, int maxW) {
        Minecraft mc = Minecraft.getInstance();
        int x = 0, total = 0;
        for (String tab : tabs) total += mc.font.width(tab) + PAD * 2 + GAP;
        if (total <= maxW) {
            scroll = 0;
            return;
        }
        for (String tab : tabs) {
            int w = mc.font.width(tab) + PAD * 2;
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
        Minecraft mc = Minecraft.getInstance();
        int x = LEFT - scroll;
        for (String tab : chat.tabs().shown()) {
            int w = mc.font.width(tab) + PAD * 2;
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
