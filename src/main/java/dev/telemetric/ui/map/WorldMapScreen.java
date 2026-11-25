package dev.telemetric.ui.map;

import com.mojang.blaze3d.platform.InputConstants;
import dev.telemetric.Telemetric;
import dev.telemetric.data.SharedMaps;
import dev.telemetric.dungeon.Point;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.map.MapProjection;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;
import dev.telemetric.map.FogField;
import dev.telemetric.map.MapCoverage;
import dev.telemetric.worldmap.MapFog;
import dev.telemetric.worldmap.MapWaypoints;
import dev.telemetric.worldmap.MapWaypoints.Waypoint;
import dev.telemetric.worldmap.WorldMapSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MC ADAPTER: the full-screen world map (own keybind, default M). Drag = pan, scroll = zoom (around the cursor),
 * Space = back to you. Shows the map tiles ({@link WorldMapSource}), players as their faces, boss / miniboss spots and
 * the ones seen alive nearby ({@link MapWaypoints}). Click a marker for its info card (name, state, distance, drops)
 * with Track (beam in the world + Minimap arrow until you arrive). North is up.
 */
public class WorldMapScreen extends Screen {

    /** Ground colour under the map, seen where the fog thins out (Diorama's surface backdrop). */
    private static final int BACKDROP = 0xFF8C99AB;
    private static final FogField.Style FOG_STYLE = FogField.Style.surface();
    private static final MapFog FOG = new MapFog();

    private static final double MIN_SCALE = 0.25, MAX_SCALE = 8, ZOOM_STEP = 1.25, CLICK_SLOP = 3;
    private static final int HEAD = 10, MARKER = 7, CARD_W = 170, PAD = 6;
    private static double lastScale = 1.5;

    private final Render2D r = new Render2D();
    private final Animation open = new Animation(Durations.SCREEN, Easing.OUT_CUBIC, 0);
    private double scale = lastScale;
    private double cu, cv;
    private boolean follow = true;
    private boolean dragging;
    private double pressX, pressY, lastX, lastY;
    private boolean moved;
    private Waypoint selected;
    private final List<Placed> placed = new ArrayList<>();
    private int[] trackButton, closeButton, card;

    private record Placed(Waypoint w, int x, int y) {}

    public WorldMapScreen() {
        super(Component.literal("World Map"));
        open.animateTo(1);
    }

    /** Opens the map (from the keybind), if there's a world map here. */
    public static void openMap() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null && WorldMapSource.get().available()) mc.setScreen(new WorldMapScreen());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // the map covers the whole screen
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        Telemetric mod = Telemetric.get();
        mod.theme().update();
        Theme p = mod.theme().palette();
        Point me = DungeonAdapter.playerPos();
        if (dragging) {
            double dx = mouseX - lastX, dy = mouseY - lastY;
            if (Math.abs(mouseX - pressX) + Math.abs(mouseY - pressY) > CLICK_SLOP) moved = true;
            if (moved && (dx != 0 || dy != 0)) {
                follow = false;
                cu -= dx / scale;
                cv -= dy / scale;
            }
            lastX = mouseX;
            lastY = mouseY;
        }
        if (follow && me != null) {
            cu = me.x();
            cv = MapProjection.v(me.y(), me.z());
        }
        int w = this.width, h = this.height;
        double ox = w / 2.0 - cu * scale, oy = h / 2.0 - cv * scale;

        r.begin(graphics, mod.theme().textShadow());
        try {
            r.pushAlpha(open.get());
            r.rect(0, 0, w, h, BACKDROP);
            WorldMapSource.get().draw(r, ox, oy, scale, 0, 0, w, h);
            FOG.draw(r, 0, 0, w, h, ox, oy, scale, WorldMapSource.get().known(), FOG_STYLE, MapCoverage.version());
            r.nextLayer();
            drawWaypoints(r, p, ox, oy, w, h);
            drawPlayers(r, p, ox, oy);
            r.nextLayer();
            Waypoint hover = hit(mouseX, mouseY);
            if (hover != null && hover != selected) drawTooltip(r, p, hover, mouseX, mouseY, me);
            if (selected != null) drawCard(r, p, me, w);
            else card = trackButton = closeButton = null;
            drawFooter(r, p, w, h, me, mouseX, mouseY, ox, oy);
            r.popAlpha();
        } finally {
            r.end();
        }
    }

    // ------------------------------------------------------------------ markers

    private void drawWaypoints(Render2D r, Theme p, double ox, double oy, int w, int h) {
        placed.clear();
        Waypoint tracked = MapWaypoints.tracked();
        List<Waypoint> all = new ArrayList<>(MapWaypoints.spots());
        all.addAll(MapWaypoints.live());
        for (Waypoint wp : all) {
            int x = (int) Math.round(ox + wp.x() * scale), y = (int) Math.round(oy + MapProjection.v(wp.y(), wp.z()) * scale);
            if (x < -MARKER || y < -MARKER || x > w + MARKER || y > h + MARKER) continue;
            int color = wp.kind() == MapWaypoints.Kind.BOSS ? p.accent() : p.accentAlt();
            double rad = wp.live() ? MARKER / 2.0 + 1 : MARKER / 2.0;
            if (wp.sameAs(tracked) || wp.sameAs(selected)) r.circle(x, y, rad + 3, ColorUtil.withAlpha(p.text(), 200));
            r.circle(x, y, rad + 1, ColorUtil.withAlpha(p.shadow(), 200));
            r.circle(x, y, rad, color);
            if (!wp.live()) r.circle(x, y, rad / 2.5, ColorUtil.withAlpha(p.shadow(), 160));
            placed.add(new Placed(wp, x, y));
        }
    }

    private void drawPlayers(Render2D r, Theme p, double ox, double oy) {
        DungeonAdapter.PlayerMarker self = null;
        for (DungeonAdapter.PlayerMarker m : DungeonAdapter.players()) {
            if (m.self()) self = m;
            else head(r, m, ox, oy, p.shadow());
        }
        if (self != null) {
            int[] at = head(r, self, ox, oy, p.text());
            double yaw = Math.toRadians(self.yaw());
            double dx = -Math.sin(yaw), dy = Math.cos(yaw) * MapProjection.SIN;
            double ang = Math.atan2(dy, dx), len = HEAD / 2.0 + 5;
            r.icon("chevron-right", at[0] + Math.cos(ang) * len, at[1] + Math.sin(ang) * len, 9, p.text(), ang);
        }
    }

    private int[] head(Render2D r, DungeonAdapter.PlayerMarker m, double ox, double oy, int border) {
        int cx = (int) Math.round(ox + m.x() * scale), cy = (int) Math.round(oy + MapProjection.v(m.y(), m.z()) * scale);
        int x = cx - HEAD / 2, y = cy - HEAD / 2;
        r.rect(x - 1, y - 1, HEAD + 2, HEAD + 2, border);
        r.texture(m.skin(), x, y, HEAD, HEAD, 8, 8, 8, 8, 64, 64, 0xFFFFFFFF);
        r.texture(m.skin(), x, y, HEAD, HEAD, 40, 8, 8, 8, 64, 64, 0xFFFFFFFF);
        return new int[]{cx, cy};
    }

    private Waypoint hit(double mx, double my) {
        Placed best = null;
        double bestD = (MARKER + 3) * (MARKER + 3);
        for (Placed pl : placed) {
            double d = (pl.x() - mx) * (pl.x() - mx) + (pl.y() - my) * (pl.y() - my);
            if (d <= bestD) {
                bestD = d;
                best = pl;
            }
        }
        return best == null ? null : best.w();
    }

    // ------------------------------------------------------------------ card, tooltip, footer

    private static String distance(Waypoint wp, Point me) {
        if (me == null) return "";
        return Math.round(Math.sqrt(me.distanceSq(wp.x(), wp.y(), wp.z()))) + "m";
    }

    private void drawTooltip(Render2D r, Theme p, Waypoint wp, int mx, int my, Point me) {
        String s = wp.name() + "  " + distance(wp, me);
        int tw = r.textWidth(s) + 2 * PAD, th = r.fontHeight() + PAD;
        int x = Math.min(mx + 10, this.width - tw - 2), y = Math.max(2, my - th - 4);
        r.roundedRect(x, y, tw, th, 3, ColorUtil.withAlpha(p.panel(), 235));
        r.text(s, x + PAD, y + PAD / 2 + 1, p.text());
    }

    private void drawCard(Render2D r, Theme p, Point me, int w) {
        Waypoint wp = selected;
        int fh = r.fontHeight();
        List<String> lines = new ArrayList<>();
        String state = MapWaypoints.state(wp);
        lines.add((wp.kind() == MapWaypoints.Kind.BOSS ? "Boss" : "Miniboss") + (me != null ? " · " + distance(wp, me) : ""));
        if (state != null) lines.add(state);
        lines.add(Math.round(wp.x()) + ", " + Math.round(wp.y()) + ", " + Math.round(wp.z()));
        List<String> drops = wp.drops();
        int shown = Math.min(drops.size(), 8);
        int height = PAD + fh + 4 + lines.size() * (fh + 2) + (shown > 0 ? 4 + (shown + 1) * (fh + 2) : 0) + 4 + fh + 6 + PAD;
        int x = w - CARD_W - 8, y = 8;
        card = new int[]{x, y, CARD_W, height};
        r.shadow(x, y, CARD_W, height, 5, ColorUtil.withAlpha(p.shadow(), 120), 6);
        r.roundedRect(x, y, CARD_W, height, 5, ColorUtil.withAlpha(p.panel(), 245));
        int ty = y + PAD;
        r.text(wp.name(), x + PAD, ty, wp.kind() == MapWaypoints.Kind.BOSS ? p.accent() : p.accentAlt());
        ty += fh + 4;
        for (String l : lines) {
            r.text(l, x + PAD, ty, p.textMuted());
            ty += fh + 2;
        }
        if (shown > 0) {
            ty += 4;
            r.text("Drops", x + PAD, ty, p.text());
            ty += fh + 2;
            for (int i = 0; i < shown; i++) {
                String d = i == shown - 1 && drops.size() > shown ? "+" + (drops.size() - shown + 1) + " more" : drops.get(i);
                r.text(trim(r, d, CARD_W - 2 * PAD - 6), x + PAD + 6, ty, p.textMuted());
                ty += fh + 2;
            }
        }
        ty += 4;
        boolean isTracked = wp.sameAs(MapWaypoints.tracked());
        int bw = (CARD_W - 3 * PAD) / 2, bh = fh + 6;
        trackButton = new int[]{x + PAD, ty, bw, bh};
        closeButton = new int[]{x + 2 * PAD + bw, ty, bw, bh};
        r.roundedRect(trackButton[0], ty, bw, bh, 3, isTracked ? p.surfaceActive() : p.accent());
        r.textCentered(isTracked ? "Untrack" : "Track", trackButton[0] + bw / 2, ty + 3, isTracked ? p.text() : p.panel());
        r.roundedRect(closeButton[0], ty, bw, bh, 3, p.surface());
        r.textCentered("Close", closeButton[0] + bw / 2, ty + 3, p.text());
    }

    private static String trim(Render2D r, String s, int max) {
        if (r.textWidth(s) <= max) return s;
        while (s.length() > 1 && r.textWidth(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private void drawFooter(Render2D r, Theme p, int w, int h, Point me, int mx, int my, double ox, double oy) {
        SharedMaps.Area area = WorldMapSource.get().area();
        String src = area != null ? "Map: " + area.key() + " v" + area.version() : "Live map (loaded chunks)";
        double u = (mx - ox) / scale, v = (my - oy) / scale;
        double y = me != null ? me.y() : 64;
        String pos = "x " + Math.round(u) + "  z " + Math.round(MapProjection.zAt(v, y));
        String help = "Drag: move · Scroll: zoom · Space: you · Click a marker: info";
        int fh = r.fontHeight(), bh = fh + 2 * 3;
        r.rect(0, h - bh, w, bh, ColorUtil.withAlpha(p.panel(), 200));
        r.text(help, PAD, h - bh + 3, p.textMuted());
        r.textRight(src + "   " + pos, w - PAD, h - bh + 3, p.textMuted());
    }

    // ------------------------------------------------------------------ input

    private static boolean inside(int[] b, double x, double y) {
        return b != null && x >= b[0] && y >= b[1] && x < b[0] + b[2] && y < b[1] + b[3];
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        if (event.button() != 0) return false;
        if (inside(trackButton, mx, my)) {
            MapWaypoints.track(selected.sameAs(MapWaypoints.tracked()) ? null : selected);
            return true;
        }
        if (inside(closeButton, mx, my)) {
            selected = null;
            return true;
        }
        if (inside(card, mx, my)) return true;
        dragging = true;
        moved = false;
        pressX = lastX = mx;
        pressY = lastY = my;
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (!dragging) return false;
        dragging = false;
        if (!moved) selected = hit(event.x(), event.y());
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) return false;
        double before = scale;
        scale = Math.clamp(scale * Math.pow(ZOOM_STEP, Math.signum(scrollY)), MIN_SCALE, MAX_SCALE);
        lastScale = scale;
        // keep the map point under the cursor where it is
        double dx = mouseX - this.width / 2.0, dy = mouseY - this.height / 2.0;
        double u = cu + dx / before, v = cv + dy / before;
        if (!follow) {
            cu = u - dx / scale;
            cv = v - dy / scale;
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_SPACE) {
            follow = true;
            return true;
        }
        if (dev.telemetric.input.Keybinds.isWorldMapKey(event)) { // the map key closes it again
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }
}
