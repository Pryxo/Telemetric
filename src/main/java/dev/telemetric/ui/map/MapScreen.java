package dev.telemetric.ui.map;

import dev.telemetric.Telemetric;
import dev.telemetric.chat.ChatText;
import dev.telemetric.data.MapMarkerTable.Category;
import dev.telemetric.data.MapMarkerTable.Source;
import dev.telemetric.data.MapTable;
import dev.telemetric.data.TelosData;
import dev.telemetric.dungeon.Point;
import dev.telemetric.game.ChatAdapter;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.game.MapTextureAdapter;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.input.InputUtil;
import dev.telemetric.map.MapLocator;
import dev.telemetric.map.MapMarkers;
import dev.telemetric.map.MapMarkers.Marker;
import dev.telemetric.module.impl.visual.MapModule;
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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * World → Telos Map. A full-screen map of one region with region tabs on top, the map in a panel with a clickable
 * legend in its top-left corner (the {@code mapMarkers.json} categories this region has; a row click shows/hides it), and the cursor's coordinates plus key hints below.
 * <ul>
 *   <li>Drag pans, the wheel zooms smoothly around the cursor ({@link Durations#HOVER}), C / middle-click centres on
 *       you and follows you, Tab switches region, the open key or Esc closes.</li>
 *   <li>Hover a marker for its state, coordinates and distance. Left-click a boss someone fights to /tp to them.</li>
 * </ul>
 * You are drawn as your head; the view follows you smoothly (partial-tick positions). The view is clamped to the region. With View → Remember View the last view per region is kept until restart.
 */
public final class MapScreen extends Screen {

    private static final int PAD = 8, GAP = 4, TAB_H = 14, FOOT_H = 9, RADIUS = 4, TIP_PAD = 6;
    private static final int LEG_PAD = 6, LEG_ROW = 16, LEG_SWATCH = 12, LEG_GAP = 4, LEG_COUNT_GAP = 8;
    private static final double MAX_SCALE = 8, ZOOM_STEP = 1.25, MARKER_BASE = 14;
    private static final int[] SCALE_STEPS = {5, 10, 25, 50, 100, 250, 500, 1000, 2500};

    /** Last view per region id: {centerX, centerZ, scale}. */
    private static final Map<String, double[]> SAVED = new HashMap<>();
    private static String lastRegion;

    private record Button(String label, String id, int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return Hit.inside(mx, my, x, y, w, h);
        }
    }

    private final MapModule module;
    private final Render2D r = new Render2D();
    private final Animation open = new Animation(Durations.SCREEN, Easing.OUT_CUBIC, 0);
    private final Animation zoom = new Animation(Durations.HOVER, Easing.OUT_CUBIC, 1);
    private final Map<String, Animation> hover = new HashMap<>();
    private final MapView view = new MapView();
    private final MapTable map = TelosData.MAP.get();
    private final MapTable.Region playerRegion = MapLocator.current();
    private MapTable.Region region;

    private double cx, cz;
    private boolean follow, dragging, viewReady;
    private boolean anchored;
    private double anchorSX, anchorSY, anchorWX, anchorWZ;
    private Marker hovered;
    private final List<Button> tabs = new ArrayList<>(), legend = new ArrayList<>();
    private final List<Category> legendCats = new ArrayList<>();
    private final List<String> legendIcons = new ArrayList<>(), legendCounts = new ArrayList<>();
    private int legX, legY, legW, legH;
    private int px, py, pw, ph;

    private MapScreen(MapModule module) {
        super(Component.literal("Telos Map"));
        this.module = module;
        MapTextureAdapter.retryFailed();
        MapRenderer.forgetIcons();
        region = playerRegion != null ? playerRegion
                : lastRegion != null && map.byId(lastRegion) != null ? map.byId(lastRegion)
                : map.regions().isEmpty() ? null : map.regions().getFirst();
        open.animateTo(1);
    }

    /** Opens the map on the client thread. */
    public static void open(MapModule module) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new MapScreen(module)));
    }

    // ---- layout -----------------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        px = PAD;
        py = PAD + TAB_H + GAP;
        pw = Math.max(40, width - PAD * 2);
        ph = Math.max(40, height - py - GAP - FOOT_H - PAD);
        layoutBar();
        if (!viewReady) {
            showRegion(region);
            viewReady = true;
        } else {
            clampView();
        }
    }

    private void layoutBar() {
        tabs.clear();
        int x = PAD;
        int avail = width - PAD * 2;
        List<MapTable.Region> regions = map.regions();
        int tabsW = 0;
        for (MapTable.Region reg : regions) tabsW += textW(reg.label()) + 12 + GAP;
        int each = regions.isEmpty() ? 0 : Math.max(24, (avail - GAP * regions.size()) / regions.size());
        boolean squeeze = tabsW > avail;
        for (MapTable.Region reg : regions) {
            int w = squeeze ? each : textW(reg.label()) + 12;
            tabs.add(new Button(squeeze ? trim(reg.label(), w - 6) : reg.label(), reg.id(), x, PAD, w, TAB_H));
            x += w + GAP;
        }
    }

    /**
     * The legend card in the panel's top-left corner: one toggle row per category the region has — its marker badge,
     * name and how many of it the region has.
     */
    private void layoutLegend(List<Marker> all) {
        legend.clear();
        legendCats.clear();
        legendIcons.clear();
        legendCounts.clear();
        for (Category c : MapMarkers.categories()) if (MapMarkers.any(all, c)) legendCats.add(c);
        int w = textW("Legend");
        for (Category c : legendCats) {
            int n = 0;
            String icon = c.icon();
            for (Marker m : all) {
                if (m.category() != c) continue;
                n++;
                if (icon == null && m.icon() != null) icon = m.icon();
            }
            String count = c.source() == Source.PLAYERS || n == 0 ? "" : Integer.toString(n);
            legendIcons.add(icon);
            legendCounts.add(count);
            int countW = count.isEmpty() ? 0 : LEG_COUNT_GAP + textW(count);
            w = Math.max(w, LEG_SWATCH + LEG_GAP + textW(c.label()) + countW);
        }
        legW = w + LEG_PAD * 2;
        legX = px + 6;
        legY = py + 6;
        int y = legY + LEG_PAD + font.lineHeight + 2;
        for (Category c : legendCats) {
            legend.add(new Button(c.label(), c.id(), legX + 2, y, legW - 4, LEG_ROW));
            y += LEG_ROW;
        }
        legH = y - legY + LEG_PAD - 2;
    }

    private boolean inLegend(double x, double y) {
        return !legend.isEmpty() && Hit.inside(x, y, legX, legY, legW, legH);
    }

    private int textW(String s) {
        return font.width(s);
    }

    private String trim(String s, int maxW) {
        if (font.width(s) <= maxW) return s;
        String e = "…";
        while (s.length() > 1 && font.width(s + e) > maxW) s = s.substring(0, s.length() - 1);
        return s + e;
    }

    // ---- view -------------------------------------------------------------------------------------------------------

    private void showRegion(MapTable.Region next) {
        if (region != null && viewReady) SAVED.put(region.id(), new double[]{cx, cz, zoom.getTarget()});
        region = next;
        anchored = false;
        follow = false;
        if (region == null) return;
        lastRegion = region.id();
        double[] saved = SAVED.get(region.id());
        double scale;
        if (module.rememberView.get() && saved != null) {
            cx = saved[0];
            cz = saved[1];
            scale = saved[2];
        } else {
            scale = module.defaultZoom.get();
            if (!centerOnPlayer()) centerOnRegion();
        }
        zoom.snapTo(Math.clamp(scale, minScale(), MAX_SCALE));
        clampView();
    }

    /** Centres on you and follows you if you're in the shown region. */
    private boolean centerOnPlayer() {
        Point me = me();
        if (me == null || playerRegion == null || region == null || !playerRegion.id().equals(region.id())) return false;
        cx = me.x();
        cz = me.z();
        follow = true;
        anchored = false;
        return true;
    }

    private void centerOnRegion() {
        double[] b = region == null ? null : region.bounds();
        if (b == null) return;
        cx = (b[0] + b[2]) / 2;
        cz = (b[1] + b[3]) / 2;
    }

    private double minScale() {
        double[] b = region == null ? null : region.bounds();
        if (b == null) return 0.05;
        return Math.min(MAX_SCALE, Math.min(pw / (b[2] - b[0]), ph / (b[3] - b[1])) * 0.9);
    }

    private void clampView() {
        double[] b = region == null ? null : region.bounds();
        if (b == null) return;
        cx = Math.clamp(cx, b[0], b[2]);
        cz = Math.clamp(cz, b[1], b[3]);
    }

    private void updateView() {
        double scale = zoom.get();
        float mx = px + pw / 2f, my = py + ph / 2f;
        if (anchored && zoom.isRunning()) {
            cx = anchorWX - (anchorSX - mx) / scale;
            cz = anchorWZ - (anchorSY - my) / scale;
        } else {
            anchored = false;
        }
        if (follow) {
            Point me = me();
            if (me != null) {
                cx = me.x();
                cz = me.z();
            }
        }
        clampView();
        view.set(cx, cz, scale, 0, mx, my);
    }

    /** Your position this frame (partial-tick interpolated, so following you is smooth). */
    private static Point me() {
        return PlayerAdapter.feet(PlayerAdapter.partialTick());
    }

    private boolean inPanel(double x, double y) {
        return Hit.inside(x, y, px, py, pw, ph);
    }

    // ---- render -----------------------------------------------------------------------------------------------------

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        Theme p = Telemetric.get().theme().palette();
        graphics.fill(0, 0, width, height, ColorUtil.fade(p.backdrop(), 0.75 * open.get()));
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
            updateView();
            drawBar(mouseX, mouseY, p);
            drawPanel(mouseX, mouseY, p);
            drawFooter(mouseX, mouseY, p);
            if (hovered != null) {
                r.nextLayer();
                drawTooltip(hovered, mouseX, mouseY, p);
            }
            r.popAlpha();
        } finally {
            r.end();
        }
    }

    private void drawBar(int mouseX, int mouseY, Theme p) {
        for (Button t : tabs) {
            boolean current = region != null && region.id().equals(t.id);
            boolean here = playerRegion != null && playerRegion.id().equals(t.id);
            button(t, mouseX, mouseY, p, current ? p.surfaceActive() : p.surface(), current ? p.text() : p.textMuted(), current);
            if (here) MapRenderer.disc(r, t.x + 5, t.y + t.h / 2.0, 1.5, p.accent()); // "you are here"
        }
    }

    private void button(Button b, int mouseX, int mouseY, Theme p, int bg, int text, boolean accentBar) {
        Animation h = hover.computeIfAbsent(b.id + b.label, k -> new Animation(Durations.HOVER, Easing.OUT_CUBIC, 0));
        h.animateTo(b.contains(mouseX, mouseY) ? 1 : 0);
        r.roundedRect(b.x, b.y, b.w, b.h, 3, ColorUtil.lerp(bg, p.surfaceHover(), h.get()));
        r.textCentered(b.label, b.x + b.w / 2, b.y + (b.h - r.fontHeight()) / 2 + 1, text);
        if (accentBar) r.rect(b.x + 4, b.y + b.h - 2, b.w - 8, 1, p.accent());
    }

    private void drawPanel(int mouseX, int mouseY, Theme p) {
        r.shadow(px, py, pw, ph, RADIUS, p.shadow(), 6);
        r.roundedRect(px - 1, py - 1, pw + 2, ph + 2, RADIUS + 1, p.border());
        r.roundedRect(px, py, pw, ph, RADIUS, p.panel());
        hovered = null;
        if (region == null || region.tiles().isEmpty()) {
            r.textCentered("No map data", px + pw / 2, py + ph / 2 - 4, p.textMuted());
            return;
        }
        r.pushClip(px, py, pw, ph);
        try {
            float[] clip = roundedRect(px, py, pw, ph, RADIUS);
            MapRenderer.TileStatus status = MapRenderer.drawTiles(r, region, view, clip, clip.length / 2,
                    Render2D.guiScale(), p.control());
            drawMarkers(mouseX, mouseY, p);
            drawSelf(p);
            drawScaleBar(p);
            drawStatus(status, p);
            drawLegend(mouseX, mouseY, p);
        } finally {
            r.popClip();
        }
    }

    /** Corners of a rounded rectangle (convex, so tiles can be clipped to it). */
    private static float[] roundedRect(float x, float y, float w, float h, float rad) {
        int seg = 4;
        float[] out = new float[4 * (seg + 1) * 2];
        float[][] centres = {{x + w - rad, y + rad}, {x + w - rad, y + h - rad}, {x + rad, y + h - rad}, {x + rad, y + rad}};
        int k = 0;
        for (int c = 0; c < 4; c++) {
            double start = -Math.PI / 2 + c * Math.PI / 2;
            for (int i = 0; i <= seg; i++) {
                double a = start + (Math.PI / 2) * i / seg;
                out[k++] = (float) (centres[c][0] + Math.cos(a) * rad);
                out[k++] = (float) (centres[c][1] + Math.sin(a) * rad);
            }
        }
        return out;
    }

    private double markerSize() {
        return MARKER_BASE * module.markerSize.get();
    }

    private void drawMarkers(int mouseX, int mouseY, Theme p) {
        List<Marker> all = MapMarkers.collect(region, c -> true);
        layoutLegend(all);
        List<Marker> markers = new ArrayList<>(all.size());
        for (Marker m : all) if (module.shows(m.category())) markers.add(m);
        double size = markerSize();
        boolean mouseIn = inPanel(mouseX, mouseY);
        if (mouseIn && !dragging && !inLegend(mouseX, mouseY)) {
            for (int i = markers.size() - 1; i >= 0; i--) {
                Marker m = markers.get(i);
                double dx = mouseX - view.sx(m.x(), m.z()), dy = mouseY - view.sy(m.x(), m.z());
                double hit = MapRenderer.hitRadius(m, size) + 1;
                if (dx * dx + dy * dy <= hit * hit) {
                    hovered = m;
                    break;
                }
            }
        }
        double cull = size * 2;
        for (Marker m : markers) {
            float sx = view.sx(m.x(), m.z()), sy = view.sy(m.x(), m.z());
            if (sx < px - cull || sx > px + pw + cull || sy < py - cull || sy > py + ph + cull) continue;
            boolean isHover = m == hovered;
            r.pushAlpha(isHover || m.active() ? 1 : m.category().fade());
            MapRenderer.marker(r, m, sx, sy, size, isHover, p);
            if ((m.category().labelsAt(view.scale) || isHover) && !m.label().isEmpty()) {
                int ly = (int) Math.round(sy + MapRenderer.hitRadius(m, size) + 2);
                r.textCentered(m.label(), Math.round(sx), ly, isHover ? p.text() : p.textMuted());
            }
            r.popAlpha();
        }
    }

    private void drawSelf(Theme p) {
        if (playerRegion == null || !playerRegion.id().equals(region.id())) return;
        Point me = me();
        if (me == null) return;
        double size = Math.max(5, markerSize() * 0.8);
        MapRenderer.head(r, selfSkin(), view.sx(me.x(), me.z()), view.sy(me.x(), me.z()), size, 1.5, p.accent(), p.textMuted());
    }

    private static String selfSkin() {
        for (DungeonAdapter.PlayerMarker pm : DungeonAdapter.players()) if (pm.self()) return pm.skin();
        return null;
    }

    private void drawLegend(int mouseX, int mouseY, Theme p) {
        if (legend.isEmpty()) return;
        r.roundedRect(legX - 1, legY - 1, legW + 2, legH + 2, RADIUS + 1, ColorUtil.withAlpha(p.border(), 220));
        r.roundedRect(legX, legY, legW, legH, RADIUS, ColorUtil.withAlpha(p.panel(), 235));
        r.text("Legend", legX + LEG_PAD, legY + LEG_PAD, p.textMuted());
        String skin = selfSkin();
        for (int i = 0; i < legend.size(); i++) {
            Button b = legend.get(i);
            Category c = legendCats.get(i);
            boolean on = module.shows(c);
            Animation h = hover.computeIfAbsent("leg" + b.id, k -> new Animation(Durations.HOVER, Easing.OUT_CUBIC, 0));
            h.animateTo(b.contains(mouseX, mouseY) ? 1 : 0);
            if (h.get() > 0.01) r.roundedRect(b.x, b.y, b.w, b.h, 3, ColorUtil.fade(p.surfaceHover(), h.get()));
            int sx = b.x + LEG_PAD - 2;
            r.pushAlpha(on ? 1 : 0.3);
            MapRenderer.swatch(r, c, legendIcons.get(i), sx + LEG_SWATCH / 2.0, b.y + b.h / 2.0, LEG_SWATCH, skin, p);
            r.popAlpha();
            int ty = b.y + (b.h - r.fontHeight()) / 2 + 1;
            r.text(b.label, sx + LEG_SWATCH + LEG_GAP, ty, on ? p.text() : p.textDisabled());
            String count = legendCounts.get(i);
            if (!count.isEmpty()) {
                r.text(count, b.x + b.w - (LEG_PAD - 2) - textW(count), ty, on ? p.textMuted() : p.textDisabled());
            }
        }
    }

    private void drawScaleBar(Theme p) {
        int len = SCALE_STEPS[0];
        for (int s : SCALE_STEPS) if (s * view.scale <= 80) len = s;
        int w = (int) Math.round(len * view.scale);
        int x = px + 8, y = py + ph - 8;
        int dark = ColorUtil.withAlpha(p.shadow(), 200);
        r.rect(x - 1, y - 1, w + 2, 3, dark);
        r.rect(x, y, w, 1, p.text());
        r.rect(x - 1, y - 4, 3, 4, dark);
        r.rect(x + w - 1, y - 4, 3, 4, dark);
        r.rect(x, y - 3, 1, 3, p.text());
        r.rect(x + w - 1, y - 3, 1, 3, p.text());
        r.text(MapRenderer.distance(len), x + 4, y - 3 - r.fontHeight(), p.text());
    }

    private void drawStatus(MapRenderer.TileStatus status, Theme p) {
        String msg = null;
        if (status.ready() == 0 && status.failed() > 0) msg = "Map download failed";
        else if (status.loading()) msg = MapRenderer.downloading(region) ? "Downloading map…" : "Loading map…";
        if (msg == null) return;
        int w = r.textWidth(msg) + 12, x = px + (pw - w) / 2, y = py + 6;
        r.roundedRect(x, y, w, 14, 3, ColorUtil.withAlpha(p.panel(), 230));
        r.textCentered(msg, x + w / 2, y + 4, status.failed() > 0 ? p.textMuted() : p.text());
    }

    private void drawFooter(int mouseX, int mouseY, Theme p) {
        int y = py + ph + GAP;
        String left;
        if (inPanel(mouseX, mouseY) && region != null && !region.tiles().isEmpty()) {
            double wx = view.wx(mouseX, mouseY), wz = view.wz(mouseX, mouseY);
            left = "X " + (int) Math.floor(wx) + "  Z " + (int) Math.floor(wz);
            Point me = me();
            if (me != null && playerRegion != null && playerRegion.id().equals(region.id())) {
                left += " · " + MapRenderer.distance(Math.hypot(wx - me.x(), wz - me.z()));
            }
        } else {
            left = playerRegion == null ? "Not in a realm" : follow ? "Following you" : "";
        }
        r.text(left, PAD, y, p.textMuted());
        String hints = "Drag move · Scroll zoom · C centre · Tab region";
        int room = width - PAD * 2 - r.textWidth(left) - 12;
        if (r.textWidth(hints) > room) hints = "C centre · Tab region";
        if (r.textWidth(hints) <= room) r.textRight(hints, width - PAD, y, p.textDisabled());
    }

    private void drawTooltip(Marker m, int mouseX, int mouseY, Theme p) {
        List<String> lines = new ArrayList<>(5);
        List<Integer> colors = new ArrayList<>(5);
        String title = m.label().isEmpty() ? "Player" : m.label();
        lines.add(title);
        colors.add(p.text());
        if (!m.state().isEmpty()) {
            lines.add(m.state());
            colors.add(m.category().live() ? m.color() : p.textMuted());
        }
        String coords = "X " + (int) Math.floor(m.x()) + "  Y " + (int) Math.round(m.y()) + "  Z " + (int) Math.floor(m.z());
        Point me = me();
        if (me != null && playerRegion != null && playerRegion.id().equals(region.id())) {
            coords += " · " + MapRenderer.distance(Math.sqrt(me.distanceSq(m.x(), m.y(), m.z())));
        }
        lines.add(coords);
        colors.add(p.textMuted());
        String hint = m.calledBy() != null && module.clickTeleport.get() ? "Click: /tp " + m.calledBy() : null;
        if (hint != null) {
            lines.add(hint);
            colors.add(p.textDisabled());
        }
        int w = 0;
        for (String s : lines) w = Math.max(w, r.textWidth(s));
        w += TIP_PAD * 2;
        int lineH = r.fontHeight() + 2;
        int h = lines.size() * lineH - 2 + TIP_PAD * 2;
        int x = Math.min(mouseX + 10, width - w - 2), y = Math.min(mouseY + 10, height - h - 2);
        x = Math.max(2, x);
        y = Math.max(2, y);
        r.shadow(x, y, w, h, RADIUS, p.shadow(), 6);
        r.roundedRect(x - 1, y - 1, w + 2, h + 2, RADIUS + 1, m.kind() == Source.PLAYERS ? p.border() : ColorUtil.lerp(p.border(), m.color(), 0.6));
        r.roundedRect(x, y, w, h, RADIUS, p.panel());
        for (int i = 0; i < lines.size(); i++) {
            r.text(lines.get(i), x + TIP_PAD, y + TIP_PAD + i * lineH, colors.get(i));
        }
    }

    // ---- input ------------------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int button = event.button();
        if (button == MouseButton.LEFT) {
            for (Button t : tabs) {
                if (t.contains(mx, my)) {
                    MapTable.Region next = map.byId(t.id);
                    if (next != null && (region == null || !next.id().equals(region.id()))) showRegion(next);
                    return true;
                }
            }
            for (int i = 0; i < legend.size(); i++) {
                if (legend.get(i).contains(mx, my)) {
                    module.toggle(legendCats.get(i));
                    return true;
                }
            }
        }
        if (!inPanel(mx, my) || region == null || inLegend(mx, my)) return super.mouseClicked(event, doubleClick);
        if (button == MouseButton.LEFT) {
            if (hovered != null && hovered.calledBy() != null && module.clickTeleport.get()) {
                teleport(hovered);
                return true;
            }
            dragging = true;
            return true;
        }
        if (button == MouseButton.MIDDLE) {
            if (!centerOnPlayer()) centerOnRegion();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void teleport(Marker m) {
        String me = PlayerAdapter.name();
        if (m.calledBy().equalsIgnoreCase(me)) {
            ChatAdapter.post(ChatText.of().add("You are the one fighting " + m.label(), ChatText.RED));
            return;
        }
        onClose();
        PlayerAdapter.sendCommand("tp " + m.calledBy());
        ChatAdapter.post(ChatText.of().add("Teleporting to ", ChatText.GRAY)
                .add(m.calledBy(), ChatText.YELLOW, ChatText.UNDERLINED).add(" at ", ChatText.GRAY)
                .add(m.label(), ChatText.YELLOW, ChatText.BOLD));
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging && event.button() == MouseButton.LEFT) {
            double scale = Math.max(1e-6, zoom.get());
            cx -= dx / scale;
            cz -= dy / scale;
            follow = false;
            anchored = false;
            zoom.snapTo(zoom.getTarget());
            clampView();
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == MouseButton.LEFT) dragging = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (region == null || scrollY == 0) return true;
        double target = Math.clamp(zoom.getTarget() * Math.pow(ZOOM_STEP, scrollY), minScale(), MAX_SCALE);
        if (!follow && inPanel(mouseX, mouseY)) {
            // keep the world point under the cursor under the cursor while the zoom animates
            anchorSX = mouseX;
            anchorSY = mouseY;
            anchorWX = view.wx(mouseX, mouseY);
            anchorWZ = view.wz(mouseX, mouseY);
            anchored = true;
        }
        zoom.animateTo(target);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == module.openKey() && key != InputUtil.KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (key == InputUtil.KEY_C) {
            if (!centerOnPlayer()) centerOnRegion();
            return true;
        }
        if (key == InputUtil.KEY_TAB && !map.regions().isEmpty()) {
            List<MapTable.Region> regions = map.regions();
            int i = region == null ? -1 : regions.indexOf(region);
            int step = InputUtil.isShiftDown() ? -1 : 1;
            showRegion(regions.get(Math.floorMod(i + step, regions.size())));
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void removed() {
        if (region != null) SAVED.put(region.id(), new double[]{cx, cz, zoom.getTarget()});
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
