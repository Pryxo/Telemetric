package dev.telemetric.ui.realm;

import dev.telemetric.Telemetric;
import dev.telemetric.data.TelosData;
import dev.telemetric.data.TelosData.Realms;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.TelosServer;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.framework.MouseButton;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MC ADAPTER (UI) — Player → Keybinds → Realm Selector: one button per realm of a region (tabs on top, your region
 * first), hubs / missions in their own row and "Previous" (the realm before this one). A click runs
 * {@code /joinq <name>}. Realm lists: {@code manual/realms.json}. Theme colours, open fade {@link Durations#SCREEN},
 * button hover {@link Durations#HOVER}.
 */
public class RealmSelectorScreen extends Screen {

    private static final int BTN_H = 18, GAP = 4, PAD = 10, TAB_H = 14;

    private record Button(String label, String target, int x, int y, int w, int h, boolean current) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final Render2D r = new Render2D();
    private final Animation open = new Animation(Durations.SCREEN, Easing.OUT_CUBIC, 0);
    private final Map<String, Animation> hover = new HashMap<>();
    private final String previous;
    private final TelosServer here;
    private int region;
    private final List<Button> buttons = new ArrayList<>();
    private final List<Button> tabs = new ArrayList<>();

    /** @param previous realm you were on before this one (null = none) */
    public RealmSelectorScreen(String previous) {
        super(Component.literal("Realm Selector"));
        this.previous = previous;
        this.here = TelosServer.current();
        Realms realms = TelosData.REALMS.get();
        Realms.Region mine = realms.forServerRegion(here == null ? null : here.region());
        region = Math.max(0, realms.regions().indexOf(mine));
        open.animateTo(1);
    }

    /** Opens the selector (on the client thread). */
    public static void open(String previous) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new RealmSelectorScreen(previous)));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        layout();
    }

    private void layout() {
        buttons.clear();
        tabs.clear();
        List<Realms.Region> regions = TelosData.REALMS.get().regions();
        if (regions.isEmpty()) return;
        region = Math.min(region, regions.size() - 1);
        Realms.Region g = regions.get(region);
        int bw = 70;
        for (String s : g.realms()) bw = Math.max(bw, font.width(s) + 16);
        for (String s : g.hubs()) bw = Math.max(bw, font.width(s) + 16);
        int cols = Math.max(1, Math.min(6, (width - PAD * 2 + GAP) / (bw + GAP)));
        int gridW = cols * bw + (cols - 1) * GAP;
        int rows = (g.realms().size() + cols - 1) / cols;
        int totalH = TAB_H + GAP * 2 + rows * (BTN_H + GAP) + GAP + BTN_H + (previous != null ? GAP + BTN_H : 0);
        int left = (width - gridW) / 2, y = Math.max(PAD + 12, (height - totalH) / 2);

        // region tabs
        int tx = left;
        for (int i = 0; i < regions.size(); i++) {
            String name = regions.get(i).name();
            int w = font.width(name) + 12;
            tabs.add(new Button(name, Integer.toString(i), tx, y, w, TAB_H, i == region));
            tx += w + GAP;
        }
        y += TAB_H + GAP * 2;

        for (int i = 0; i < g.realms().size(); i++) {
            String realm = g.realms().get(i);
            buttons.add(new Button(realm, realm, left + (i % cols) * (bw + GAP), y + (i / cols) * (BTN_H + GAP), bw, BTN_H,
                    isHere(realm)));
        }
        y += rows * (BTN_H + GAP) + GAP;
        int hubW = g.hubs().size() * bw + Math.max(0, g.hubs().size() - 1) * GAP;
        for (int i = 0; i < g.hubs().size(); i++) {
            String hub = g.hubs().get(i);
            buttons.add(new Button(hub, hub, (width - hubW) / 2 + i * (bw + GAP), y, bw, BTN_H, isHere(hub)));
        }
        if (previous != null) {
            String label = "Previous: " + previous;
            int w = Math.max(bw, font.width(label) + 16);
            buttons.add(new Button(label, previous, (width - w) / 2, y + BTN_H + GAP, w, BTN_H, false));
        }
    }

    private boolean isHere(String name) {
        return here != null && here.realm().equalsIgnoreCase(name);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        Theme p = Telemetric.get().theme().palette();
        graphics.fill(0, 0, width, height, ColorUtil.fade(p.backdrop(), 0.6 * open.get()));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        Telemetric mod = Telemetric.get();
        mod.theme().update();
        Theme p = mod.theme().palette();
        r.begin(graphics, mod.theme().textShadow());
        try {
            r.pushAlpha(open.get());
            if (buttons.isEmpty()) {
                r.textCentered("No realm list (manual/realms.json)", width / 2, height / 2, p.textMuted());
            } else {
                int titleY = Math.max(4, tabs.getFirst().y() - 14);
                r.textCentered(here == null ? "Realm Selector" : "Realm Selector — " + here.display(), width / 2, titleY, p.text());
            }
            for (Button t : tabs) {
                draw(t, mouseX, mouseY, p, t.current() ? p.accent() : p.surface(), t.current() ? p.text() : p.textMuted());
            }
            for (Button b : buttons) {
                draw(b, mouseX, mouseY, p, b.current() ? p.surfaceActive() : p.surface(), b.current() ? p.textDisabled() : p.text());
                if (b.current()) r.rect(b.x() + 4, b.y() + b.h() - 2, b.w() - 8, 1, p.accent());
            }
            r.popAlpha();
        } finally {
            r.end();
        }
    }

    private void draw(Button b, int mouseX, int mouseY, Theme p, int bg, int text) {
        Animation h = hover.computeIfAbsent(b.label(), k -> new Animation(Durations.HOVER, Easing.OUT_CUBIC, 0));
        h.animateTo(!b.current() && b.contains(mouseX, mouseY) ? 1 : 0);
        r.roundedRect(b.x(), b.y(), b.w(), b.h(), 3, ColorUtil.lerp(bg, p.surfaceHover(), h.get()));
        r.outline(b.x(), b.y(), b.w(), b.h(), ColorUtil.lerp(p.border(), p.accent(), h.get()));
        r.textCentered(b.label(), b.x() + b.w() / 2, b.y() + (b.h() - r.fontHeight()) / 2 + 1, text);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != MouseButton.LEFT) return super.mouseClicked(event, doubleClick);
        for (Button t : tabs) {
            if (t.contains(event.x(), event.y())) {
                region = Integer.parseInt(t.target());
                layout();
                return true;
            }
        }
        for (Button b : buttons) {
            if (b.contains(event.x(), event.y()) && !b.current()) {
                onClose();
                PlayerAdapter.sendCommand("joinq " + b.target());
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }
}
