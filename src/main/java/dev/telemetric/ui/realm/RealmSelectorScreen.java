package dev.telemetric.ui.realm;

import dev.telemetric.Telemetric;
import dev.telemetric.api.HeadStatusApi;
import dev.telemetric.data.TelosData;
import dev.telemetric.data.TelosData.Realms;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.RegionSwitchAdapter;
import dev.telemetric.game.ServerInfoAdapter;
import dev.telemetric.game.TelosServer;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.framework.Hit;
import dev.telemetric.ui.framework.MouseButton;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Screen for Player → Keybinds → Realm Selector. There's one button per online realm of a region, with its
 * player count ("Astra (8)"). Region tabs sit on top with your region selected, hubs and missions get their own row, and
 * there's a "Previous" button (the realm before this one, only if it's live). Clicking runs {@code /joinq <name>}.
 * Only the servers the live HeadStatus list ({@link HeadStatusApi}) returns are shown, plus a Missions button in every
 * live region. {@code realms.json} only provides the tab order, display names and the hub split. Theme colours, an
 * open fade of {@link Durations#SCREEN}, and a button hover of {@link Durations#HOVER}.
 * <p>
 * With Region Switch on, your region's tab shows your live ping above it, and double-clicking another
 * region's tab picks that region in {@code /locations} (the server transfers you) without reloading the resource pack
 * ({@link RegionSwitchAdapter}).
 */
public class RealmSelectorScreen extends Screen {

    private static final int BTN_H = 18, GAP = 4, PAD = 10, TAB_H = 14;

    private record Button(String label, String target, int x, int y, int w, int h, boolean current) {
        boolean contains(double mx, double my) {
            return Hit.inside(mx, my, x, y, w, h);
        }
    }

    private final Render2D r = new Render2D();
    private final Animation open = new Animation(Durations.SCREEN, Easing.OUT_CUBIC, 0);
    private final Map<String, Animation> hover = new HashMap<>();
    private final String previous;
    private final TelosServer here;
    /** The region name of the server you're on ({@code realms.json} prefix match), or null if unknown. */
    private final String myRegion;
    private int region;
    private boolean regionPicked;
    private final List<Button> buttons = new ArrayList<>();
    private final List<Button> tabs = new ArrayList<>();
    /** API region id per tab (same index as {@link #tabs}), null if no live server of that region was seen. */
    private final List<String> tabIds = new ArrayList<>();
    private final boolean regionSwitch;
    private static final int PING_H = 10;

    /** API region id to tab name. */
    private static final Map<String, String> REGION_NAMES = Map.of("de", "EU", "ny", "NA", "sg", "SG");

    /** Always offered in every region, even when the API doesn't list it. */
    private static final String MISSIONS = "Missions";
    private static final String TUTORIAL = "Tutorial";

    /**
     * @param name button name; @param players online players, -1 if not in the API list;
     * @param join server for {@code /joinq} (the name, or the real {@code tutorial-N} behind the Tutorial button)
     */
    private record Entry(String name, int players, String join) {
        Entry(String name, int players) {
            this(name, players, name);
        }

        String label() {
            return players < 0 ? name : name + " (" + players + ")";
        }
    }

    private record LiveRegion(String name, List<Entry> realms, List<Entry> hubs, String[] id) {
        LiveRegion(String name) {
            this(name, new ArrayList<>(), new ArrayList<>(), new String[1]);
        }
    }

    /**
     * @param previous the realm you were on before this one (null for none)
     * @param regionSwitch show region pings and switch region on a tab double-click
     */
    public RealmSelectorScreen(String previous, boolean regionSwitch) {
        super(Component.literal("Realm Selector"));
        this.previous = previous;
        this.regionSwitch = regionSwitch;
        this.here = TelosServer.current();
        Realms.Region mine = TelosData.REALMS.get().forServerRegion(here == null ? null : here.region());
        this.myRegion = mine == null ? null : mine.name();
        open.animateTo(1);
    }

    /**
     * Opens the selector (on the client thread). This is the only place the live realm list gets requested again, and
     * only once the {@link HeadStatusApi#REALM_TTL_MS 30 s} cache has run out. Otherwise the cached list is shown.
     */
    public static void open(String previous, boolean regionSwitch) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new RealmSelectorScreen(previous, regionSwitch)));
        HeadStatusApi.refreshRealmsIfStale().thenRun(() -> mc.execute(() -> {
            if (mc.screen instanceof RealmSelectorScreen s) s.rebuild();
        }));
    }

    /**
     * The regions, with only the servers the API lists. The tab order, names and hub split come from {@code realms.json}
     * (unknown names get capitalised, and {@code hub…} / {@code missions…} count as hubs).
     */
    private static List<LiveRegion> liveRegions() {
        List<Realms.Region> known = TelosData.REALMS.get().regions();
        Map<String, LiveRegion> byName = new LinkedHashMap<>();
        for (Realms.Region k : known) {
            byName.put(k.name().toUpperCase(Locale.ROOT), new LiveRegion(k.name()));
        }
        for (HeadStatusApi.LiveRealm lr : HeadStatusApi.realms()) {
            String rn = REGION_NAMES.getOrDefault(lr.region(), lr.region().toUpperCase(Locale.ROOT));
            LiveRegion reg = byName.computeIfAbsent(rn.toUpperCase(Locale.ROOT),
                    n -> new LiveRegion(rn));
            reg.id()[0] = lr.region();
            Realms.Region k = known.stream().filter(x -> x.name().equalsIgnoreCase(reg.name())).findFirst().orElse(null);
            String hubName = k == null ? null : match(k.hubs(), lr.name());
            String realmName = k == null ? null : match(k.realms(), lr.name());
            // mission-N / tutorial-N → one Missions / Tutorial button (summed players); Tutorial only while one exists
            String group = lr.name().startsWith("mission") ? MISSIONS : lr.name().startsWith("tutorial") ? TUTORIAL : null;
            if (group != null) {
                Entry old = reg.hubs().stream().filter(e -> e.name().equals(group)).findFirst().orElse(null);
                if (old != null) reg.hubs().remove(old);
                String join = group.equals(TUTORIAL) ? (old == null ? lr.name() : old.join()) : group;
                reg.hubs().add(new Entry(group, (old == null ? 0 : old.players()) + Math.max(0, lr.players()), join));
            } else if (hubName != null) reg.hubs().add(new Entry(hubName, lr.players()));
            else if (realmName != null) reg.realms().add(new Entry(realmName, lr.players()));
            else if (lr.name().startsWith("hub") || lr.name().startsWith("missions")) {
                reg.hubs().add(new Entry(capitalise(lr.name()), lr.players()));
            } else reg.realms().add(new Entry(capitalise(lr.name()), lr.players()));
        }
        List<LiveRegion> out = new ArrayList<>();
        for (LiveRegion reg : byName.values()) {
            if (reg.realms().isEmpty() && reg.hubs().isEmpty()) continue;
            if (reg.hubs().stream().noneMatch(e -> e.name().equalsIgnoreCase(MISSIONS))) {
                reg.hubs().add(new Entry(MISSIONS, -1));
            }
            Realms.Region k = known.stream().filter(x -> x.name().equalsIgnoreCase(reg.name())).findFirst().orElse(null);
            reg.realms().sort(order(k == null ? List.of() : k.realms()));
            reg.hubs().sort(order(k == null ? List.of() : k.hubs()));
            out.add(reg);
        }
        return out;
    }

    private static String match(List<String> names, String id) {
        for (String n : names) if (n.equalsIgnoreCase(id)) return n;
        return null;
    }

    /** {@code realms.json} order first, then unknown names alphabetically. */
    private static Comparator<Entry> order(List<String> known) {
        return Comparator.<Entry>comparingInt(e -> {
            for (int i = 0; i < known.size(); i++) if (known.get(i).equalsIgnoreCase(e.name())) return i;
            return Integer.MAX_VALUE;
        }).thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER);
    }

    /** Turns {@code hub-1} into {@code Hub-1}. */
    private static String capitalise(String id) {
        StringBuilder sb = new StringBuilder(id.length());
        boolean up = true;
        for (char c : id.toCharArray()) {
            sb.append(up ? Character.toUpperCase(c) : c);
            up = c == '-' || c == '_' || c == ' ';
        }
        return sb.toString();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        layout();
    }

    /** Lays the buttons out again when a realm request comes back while the screen is open. */
    private void rebuild() {
        if (font != null) layout();
    }

    private void layout() {
        buttons.clear();
        tabs.clear();
        tabIds.clear();
        List<LiveRegion> regions = liveRegions();
        if (regions.isEmpty()) return;
        if (!regionPicked) {
            region = 0;
            for (int i = 0; i < regions.size(); i++) {
                if (regions.get(i).name().equalsIgnoreCase(myRegion)) region = i;
            }
        }
        region = Math.min(region, regions.size() - 1);
        LiveRegion g = regions.get(region);
        int bw = 90; // fixed, so buttons don't resize as counts or servers change (it only grows for very long names)
        for (Entry e : g.realms()) bw = Math.max(bw, font.width(e.label()) + 16);
        for (Entry e : g.hubs()) bw = Math.max(bw, font.width(e.label()) + 16);
        int cols = Math.max(1, Math.min(6, (width - PAD * 2 + GAP) / (bw + GAP)));
        int gridW = cols * bw + (cols - 1) * GAP;
        int rows = (g.realms().size() + cols - 1) / cols;
        // "Previous" only shows if that server is in this region's live list
        String prev = null;
        if (previous != null) {
            for (List<Entry> list : List.of(g.realms(), g.hubs())) {
                for (Entry e : list) if (e.name().equalsIgnoreCase(previous)) prev = e.name();
            }
        }
        // "Previous" isn't part of the centred block. It hangs below, so the grid never jumps when it appears.
        int totalH = TAB_H + GAP * 2 + rows * (BTN_H + GAP) + GAP + BTN_H;
        int left = (width - gridW) / 2, y = Math.max(PAD + 12 + (regionSwitch ? PING_H : 0), (height - totalH) / 2);

        // region tabs
        int tx = left;
        for (int i = 0; i < regions.size(); i++) {
            LiveRegion lr = regions.get(i);
            int total = 0;
            for (List<Entry> list : List.of(lr.realms(), lr.hubs())) {
                for (Entry e : list) total += Math.max(0, e.players());
            }
            String name = lr.name() + " (" + total + ")";
            int w = font.width(name) + 12;
            tabs.add(new Button(name, Integer.toString(i), tx, y, w, TAB_H, i == region));
            tabIds.add(lr.id()[0]);
            tx += w + GAP;
        }
        y += TAB_H + GAP * 2;

        for (int i = 0; i < g.realms().size(); i++) {
            Entry e = g.realms().get(i);
            buttons.add(new Button(e.label(), e.join(), left + (i % cols) * (bw + GAP), y + (i / cols) * (BTN_H + GAP), bw,
                    BTN_H, isHere(e.name())));
        }
        y += rows * (BTN_H + GAP) + GAP;
        // hubs row (hubs, Missions), centred
        int rowN = g.hubs().size();
        int hx = (width - (rowN * bw + Math.max(0, rowN - 1) * GAP)) / 2;
        for (List<Entry> list : List.of(g.hubs())) {
            for (Entry e : list) {
                buttons.add(new Button(e.label(), e.join(), hx, y, bw, BTN_H, isHere(e.name())));
                hx += bw + GAP;
            }
        }
        if (prev != null) {
            String label = "Previous: " + prev;
            int w = Math.max(bw, font.width(label) + 16);
            buttons.add(new Button(label, prev, (width - w) / 2, y + BTN_H + GAP, w, BTN_H, false));
        }
    }

    /** The tab of the region you're connected to (no switch to it). */
    private boolean isMyRegion(Button tab) {
        return myRegion != null && tab.label().regionMatches(true, 0, myRegion + " (", 0, myRegion.length() + 2);
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
            if (tabs.isEmpty()) {
                String msg = HeadStatusApi.realmsLoading() ? "Loading realms…"
                        : HeadStatusApi.realmsLoaded() ? "No realms online" : "Realm list unavailable";
                r.textCentered(msg, width / 2, height / 2, p.textMuted());
            } else {
                int titleY = Math.max(4, tabs.getFirst().y() - 14 - (regionSwitch ? PING_H : 0));
                r.textCentered(here == null ? "Realm Selector" : "Realm Selector — " + here.display(), width / 2, titleY, p.text());
            }
            for (int i = 0; i < tabs.size(); i++) {
                Button t = tabs.get(i);
                draw(t, mouseX, mouseY, p, t.current() ? p.accent() : p.surface(), t.current() ? p.text() : p.textMuted());
                // only your own region: every region host sits behind the same DDoS-protection edge, which answers any
                // outside ping (TCP or server-list) itself, so the only real number is your live in-game latency
                if (regionSwitch && isMyRegion(t)) {
                    int ms = ServerInfoAdapter.ping();
                    if (ms > 0) r.textCentered(ms + " ms", t.x() + t.w() / 2, t.y() - PING_H, p.textMuted());
                }
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
        // keyed without the player count, so a count change keeps the hover state
        Animation h = hover.computeIfAbsent(b.label().replaceFirst(" \\(\\d+\\)$", ""), k -> new Animation(Durations.HOVER, Easing.OUT_CUBIC, 0));
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
                int i = Integer.parseInt(t.target());
                String id = tabIds.get(i);
                if (doubleClick && regionSwitch && RegionSwitchAdapter.canSwitchTo(id) && !isMyRegion(t)) {
                    onClose();
                    RegionSwitchAdapter.switchTo(id);
                    return true;
                }
                region = i;
                regionPicked = true;
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
