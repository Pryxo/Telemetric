package dev.telemetric.ui.clickgui.window;

import dev.telemetric.Telemetric;
import dev.telemetric.game.ScenePreviewAdapter;
import dev.telemetric.game.WorldRenderAdapter;
import dev.telemetric.module.impl.misc.InterfaceModule;
import dev.telemetric.config.GuiLayout;
import dev.telemetric.game.EntityPreviewAdapter;
import dev.telemetric.game.WorldPreviewAdapter;
import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;
import dev.telemetric.ui.framework.GuiContext;
import dev.telemetric.ui.framework.MouseButton;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Icons;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * A 3D preview for the Settings window, by default of your own player: left-drag to orbit, right-drag (or middle-drag)
 * to move the view, scroll to zoom toward the mouse, right-click to reset. The camera eases to where you put it
 * (time-based, {@link Durations#MICRO}). {@link Layer}s draw 2D overlays at projected world points (a name tag, the
 * health bar, a hitbox, a world label) and may be dragged to move what they show. {@link #withoutModel} gives the same
 * view with no player, for overlays that stand on their own (e.g. the Boss Stage Tracker's label).
 * <p>
 * The <b>view menu</b> (top left, shared by every preview, saved in {@link GuiLayout.PreviewOptions}): World (the
 * blocks around you, {@link WorldPreviewAdapter}), World Radius (Auto = as far as the box shows), Floor Grid, Axes,
 * Auto Rotate and Grow With Window. Without Grow With Window the view keeps at most {@link #MAX_PX_PER_BLOCK}, so a
 * bigger window shows more around you instead of a bigger player.
 */
public final class PlayerPreview implements SettingsPreview {

    /** An overlay drawn over the model. Coordinates in {@link View} are blocks, feet at the origin, world axes. */
    public interface Layer {
        void render(Render2D r, GuiContext ctx, View view, int mouseX, int mouseY);

        /** A left press: return true to start dragging this layer instead of orbiting. */
        default boolean grab(View view, double mouseX, double mouseY) {
            return false;
        }

        /** Mouse moved while grabbed (GUI pixels since the last call). */
        default void drag(View view, double dx, double dy) {}

        default void release(View view) {}

        /** The wheel turned over the preview: return true to use it (e.g. resize what's under the mouse) instead of zooming. */
        default boolean scroll(View view, double mouseX, double mouseY, double amount) {
            return false;
        }

        /** Extra blocks of room the view keeps above the head for this layer. */
        default double topRoom() {
            return 0;
        }

        /** A short hint for the bottom line (e.g. "Drag the bar to move it"), or null. */
        default String hint() {
            return null;
        }

        /**
         * 3D shapes for the preview scene, in <b>world</b> coordinates (e.g. hitbox lines). They are depth-tested
         * against the blocks and models, unlike {@link #render}'s 2D overlay. {@code radius} = blocks around you that
         * are shown; {@code entities} = other entities are drawn too.
         */
        default void sceneShapes(WorldRenderAdapter.LineSink sink, double radius, boolean entities) {}
    }

    /** The projection of the current frame. */
    public static final class View {
        private double cx, cy, scale, lift, panX, panY, sinYaw, cosYaw, sinPitch, cosPitch, height, yaw, pitch;
        private int bx, by, bw, bh;
        private boolean scene;

        /** True while the preview draws one depth-tested 3D scene: layers then draw 3D bits via {@code sceneShapes}. */
        public boolean scene() {
            return scene;
        }

        /** GUI {x, y} of a point given in blocks relative to your feet (world axes). */
        public double[] project(double wx, double wy, double wz) {
            double x1 = wx * cosYaw + wz * sinYaw;
            double z1 = -wx * sinYaw + wz * cosYaw;
            double y2 = wy * cosPitch - z1 * sinPitch;
            return new double[] {cx + scale * (panX - x1), cy + scale * (lift + panY - y2)};
        }

        /** How far a point is from the camera along the view direction (bigger = farther; only the order matters). */
        public double depth(double wx, double wy, double wz) {
            double z1 = -wx * sinYaw + wz * cosYaw;
            return wy * sinPitch + z1 * cosPitch;
        }

        /** The world direction the camera looks along {x, y, z} (unit). */
        public double[] forward() {
            return new double[] {-sinYaw * cosPitch, sinPitch, cosYaw * cosPitch};
        }

        /** GUI pixels per block. */
        public double scale() {
            return scale;
        }

        /** Your hitbox height in blocks (the subject height of a preview without a player). */
        public double playerHeight() {
            return height;
        }

        /** The world direction {x, z} (unit) that moves right on screen. */
        public double[] screenRight() {
            return new double[] {-cosYaw, -sinYaw};
        }

        /** Blocks of world height per GUI pixel moved up on screen. */
        public double blocksPerPixelUp() {
            return 1.0 / (scale * Math.max(0.2, cosPitch));
        }

        /**
         * The view direction as Minecraft camera angles (degrees): yaw 0 = looking south, pitch + = looking down.
         * Settings that follow the camera (e.g. the health bar arc) use these.
         */
        public double cameraYaw() {
            return Math.toDegrees(yaw);
        }

        public double cameraPitch() {
            return -Math.toDegrees(pitch);
        }

        public int x() { return bx; }
        public int y() { return by; }
        public int w() { return bw; }
        public int h() { return bh; }
    }

    private static final double DEFAULT_YAW = Math.toRadians(-28), DEFAULT_PITCH = Math.toRadians(-14);
    private static final double PITCH_LIMIT = Math.toRadians(80);
    private static final double MIN_ZOOM = 0.4, MAX_ZOOM = 5.0;
    /** The most GUI pixels per block at zoom 1 while Grow With Window is off. */
    private static final double MAX_PX_PER_BLOCK = 64;
    /** A right press that moves less than this (GUI px) is a click: reset the view. */
    private static final double CLICK_SLOP = 3;
    /** Alpha of the blocks above your feet (they'd otherwise hide the player, who is always drawn on top). */
    private static final double GHOST_ALPHA = 0.4;

    // view menu geometry (GUI px)
    private static final int BTN = 12, MENU_W = 112, ROW = 12;
    private enum Item { WORLD, RADIUS, GRID, AXES, SPIN, GROW }

    private final List<Layer> layers;
    private final boolean model;
    private final double fixedHeight;
    private final View view = new View();
    /** Where the camera is now (eases toward the targets below, which input changes). */
    private double yaw = DEFAULT_YAW, pitch = DEFAULT_PITCH, zoom = 1, panX, panY, span = -1;
    private double toYaw = DEFAULT_YAW, toPitch = DEFAULT_PITCH, toZoom = 1, toPanX, toPanY;
    private long lastNanos;
    private double baseScale = 1;
    private Layer grabbed;
    private boolean orbiting, panning;
    private double lastX, lastY, pressX, pressY, moved;

    private boolean menuOpen;
    private final Animation menu = new Animation(Durations.DROPDOWN, Easing.OUT_CUBIC, 0);
    private final Animation menuHover = new Animation(Durations.HOVER, Easing.OUT_CUBIC, 0);
    private int boxX, boxY;

    public PlayerPreview(Layer... layers) {
        this(true, 1.8, layers);
    }

    private PlayerPreview(boolean model, double fixedHeight, Layer... layers) {
        this.model = model;
        this.fixedHeight = fixedHeight;
        this.layers = List.of(layers);
    }

    /** The same view with no player model; {@code height} (blocks) is the room it keeps for the subject. */
    public static PlayerPreview withoutModel(double height, Layer... layers) {
        return new PlayerPreview(false, height, layers);
    }

    private static GuiLayout.PreviewOptions options() {
        return Telemetric.get().config().getLayout().preview();
    }

    @Override
    public int minWidth() {
        return 110;
    }

    @Override
    public void render(Render2D r, GuiContext ctx, int x, int y, int w, int h, int mouseX, int mouseY, float delta) {
        Theme p = ctx.palette();
        GuiLayout.PreviewOptions opt = options();
        int radius = Math.min(ctx.radius(), 6);
        r.roundedRect(x, y, w, h, radius, ColorUtil.lerp(p.settingsBg(), p.backdrop(), 0.35));
        r.gradientV(x, y + h / 2, w, h - h / 2, ColorUtil.withAlpha(p.accent(), 0), ColorUtil.fade(p.accent(), 0.07));

        int hintH = 11;
        int boxH = h - hintH;
        this.boxX = x;
        this.boxY = y;

        // follow the drag
        if (orbiting || panning || grabbed != null) {
            double dx = mouseX - lastX, dy = mouseY - lastY;
            if (Math.abs(mouseX) < 1e8 && Math.abs(mouseY) < 1e8) {
                moved = Math.max(moved, Math.hypot(mouseX - pressX, mouseY - pressY));
                if (orbiting) {
                    toYaw += dx * 0.012;
                    toPitch = Math.clamp(toPitch - dy * 0.01, -PITCH_LIMIT, PITCH_LIMIT);
                } else if (panning) {
                    double s = Math.max(1e-6, baseScale * toZoom);
                    toPanX += dx / s;
                    toPanY += dy / s;
                    clampPan(w, boxH);
                } else if (dx != 0 || dy != 0) {
                    grabbed.drag(view, dx, dy);
                }
                lastX = mouseX;
                lastY = mouseY;
            }
        }

        // ---- ease the camera toward its targets (time-based)
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 1000 : Math.clamp((now - lastNanos) / 1e6, 0, 100);
        lastNanos = now;
        int spinMs = Durations.loop(Durations.PREVIEW_SPIN_LOOP);
        if (opt.spin && spinMs > 0 && !orbiting && grabbed == null) toYaw += dt / spinMs * Math.PI * 2;
        int ms = Durations.scaled(Durations.MICRO);
        double a = ms <= 0 ? 1 : 1 - Math.exp(-dt / (ms / 3.0));
        yaw += (toYaw - yaw) * a;
        pitch += (toPitch - pitch) * a;
        zoom += (toZoom - zoom) * a;
        panX += (toPanX - panX) * a;
        panY += (toPanY - panY) * a;

        // ---- projection
        double modelH = model ? EntityPreviewAdapter.playerHeight() : fixedHeight;
        double top = 0.35;
        for (Layer l : layers) top = Math.max(top, l.topRoom());
        double wantSpan = modelH + top;
        span = span < 0 ? wantSpan : span + (wantSpan - span) * a; // lines coming and going don't jump the view
        view.bx = x;
        view.by = y;
        view.bw = w;
        view.bh = boxH;
        view.height = modelH;
        baseScale = Math.min(boxH * 0.8 / span, w * 0.9 / 1.6);
        if (!opt.grow) baseScale = Math.min(baseScale, MAX_PX_PER_BLOCK);
        view.scale = baseScale * zoom;
        view.cx = x + w / 2.0;
        view.cy = y + boxH / 2.0;
        view.lift = span / 2.0 - 0.1;
        view.panX = panX;
        view.panY = panY;
        view.sinYaw = Math.sin(yaw);
        view.cosYaw = Math.cos(yaw);
        view.yaw = yaw;
        view.pitch = pitch;
        view.sinPitch = Math.sin(pitch);
        view.cosPitch = Math.cos(pitch);

        InterfaceModule ui = Telemetric.get().modules().get(InterfaceModule.class);
        int maxEntities = ui.view3dEntities.get() ? ui.view3dMaxEntities.get().intValue() : 0;
        int worldR = worldRadius(opt, w, boxH);
        view.scene = Render2D.sceneAvailable();
        if (view.scene) {
            // one depth-tested picture: blocks, your model, nearby entities and the layers' 3D shapes
            if (opt.grid) drawFloor(r, p);
            r.nextLayer();
            boolean others = maxEntities > 0;
            WorldRenderAdapter.Drawer shapes = sink -> {
                for (Layer l : layers) l.sceneShapes(sink, worldR, others);
            };
            boolean drawn = r.previewScene(x, y, w, boxH, (float) view.scale, (float) panX, (float) (view.lift + panY),
                    (float) yaw, (float) pitch, new ScenePreviewAdapter.Request(model, opt.world ? worldR : 0,
                            worldR, maxEntities, shapes));
            r.nextLayer(); // overlays above the scene's picture
            if (model && !drawn) {
                r.textCentered("Join a world to see", x + w / 2, y + boxH / 2 - 9, p.textDisabled());
                r.textCentered("your player here", x + w / 2, y + boxH / 2 + 1, p.textDisabled());
            }
        } else {
            if (opt.world) {
                r.nextLayer(); // above the background
                drawWorld(r, worldR);
                r.nextLayer();
            }
            if (opt.grid) drawFloor(r, p);
            if (model) {
                boolean drawn = r.playerModel(x, y, w, boxH, (float) view.scale, (float) panX,
                        (float) (view.lift + panY), (float) yaw, (float) pitch);
                r.nextLayer(); // overlays above the model's picture
                if (!drawn) {
                    r.textCentered("Join a world to see", x + w / 2, y + boxH / 2 - 9, p.textDisabled());
                    r.textCentered("your player here", x + w / 2, y + boxH / 2 + 1, p.textDisabled());
                }
            }
        }
        boolean overMenu = overToolbar(mouseX, mouseY) || overMenu(mouseX, mouseY);
        int lmx = overMenu ? Integer.MIN_VALUE / 2 : mouseX, lmy = overMenu ? Integer.MIN_VALUE / 2 : mouseY;
        for (Layer l : layers) l.render(r, ctx, view, lmx, lmy);

        if (opt.axes) drawGizmo(r, p, x + 14, y + boxH - 12);

        String hint = null;
        for (Layer l : layers) if (hint == null) hint = l.hint();
        String line = (hint != null ? hint + " · " : "") + "Drag: rotate · Right-drag: move · Scroll: zoom";
        if (opt.world) r.rect(x, y + h - hintH, w, hintH, ColorUtil.fade(p.settingsBg(), 0.8));
        r.textCentered(r.trim(line, w - 6), x + w / 2, y + h - hintH + 1, ColorUtil.fade(p.textDisabled(), 0.9));

        r.nextLayer();
        renderMenu(r, ctx, p, opt, mouseX, mouseY);

        boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        if (over && !overMenu && grabbed == null && !orbiting && !panning) ctx.requestTooltip(this, "Right-click to reset the view");
    }

    // ================================================================== world

    /** The radius to show: the fixed one, or (Auto) enough blocks to reach the box's sides at the target zoom. */
    private int worldRadius(GuiLayout.PreviewOptions opt, int w, int h) {
        if (opt.radius > 0) return opt.radius;
        double s = Math.max(1e-6, baseScale * toZoom);
        double half = Math.max(w, h) * 0.5 / s + Math.max(Math.abs(toPanX), Math.abs(toPanY));
        return Math.clamp((int) Math.ceil(half) + 1, GuiLayout.PreviewOptions.MIN_RADIUS, GuiLayout.PreviewOptions.MAX_RADIUS);
    }

    private void drawWorld(Render2D r, int radius) {
        WorldPreviewAdapter.Snapshot snap = WorldPreviewAdapter.around(radius);
        double[] feet = WorldPreviewAdapter.feet();
        List<WorldPreviewAdapter.Face> faces = snap.faces();
        if (feet == null || faces.isEmpty()) return;
        double ox = snap.ox() - feet[0], oy = snap.oy() - feet[1], oz = snap.oz() - feet[2];
        double[] f = view.forward();

        // visible faces (pointing at the camera), far to near
        int n = 0;
        int[] idx = new int[faces.size()];
        double[] depth = new double[faces.size()];
        for (int i = 0; i < faces.size(); i++) {
            WorldPreviewAdapter.Face face = faces.get(i);
            if (face.nx() * f[0] + face.ny() * f[1] + face.nz() * f[2] > -1e-3) continue;
            float[] q = face.pos();
            double cx = (q[0] + q[3] + q[6] + q[9]) / 4 + ox;
            double cy = (q[1] + q[4] + q[7] + q[10]) / 4 + oy;
            double cz = (q[2] + q[5] + q[8] + q[11]) / 4 + oz;
            depth[i] = view.depth(cx, cy, cz);
            idx[n++] = i;
        }
        if (n == 0) return;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = idx[i];
        java.util.Arrays.sort(order, (i1, i2) -> Double.compare(depth[i2], depth[i1])); // stable: overlays stay on top

        float[] xy = new float[n * 8];
        float[] uv = new float[n * 8];
        int[] argb = new int[n];
        for (int k = 0; k < n; k++) {
            WorldPreviewAdapter.Face face = faces.get(order[k]);
            float[] q = face.pos();
            double[][] pt = new double[4][];
            for (int c = 0; c < 4; c++) pt[c] = view.project(q[c * 3] + ox, q[c * 3 + 1] + oy, q[c * 3 + 2] + oz);
            // GUI quads wind one way (signed area < 0 with y down): flip the others
            double area = 0;
            for (int c = 0; c < 4; c++) {
                double[] p0 = pt[c], p1 = pt[(c + 1) % 4];
                area += p0[0] * p1[1] - p1[0] * p0[1];
            }
            float[] fu = face.uv();
            for (int c = 0; c < 4; c++) {
                int src = area > 0 ? 3 - c : c;
                xy[k * 8 + c * 2] = (float) pt[src][0];
                xy[k * 8 + c * 2 + 1] = (float) pt[src][1];
                uv[k * 8 + c * 2] = fu[src * 2];
                uv[k * 8 + c * 2 + 1] = fu[src * 2 + 1];
            }
            argb[k] = face.ghost() ? ColorUtil.fade(face.argb(), GHOST_ALPHA) : face.argb();
        }
        r.blockFaces(xy, uv, argb, n);
    }

    // ================================================================== view menu (top left)

    private int btnX() { return boxX + 4; }
    private int btnY() { return boxY + 4; }
    private int menuX() { return boxX + 4; }
    private int menuY() { return boxY + 4 + BTN + 2; }
    private int menuH() { return Item.values().length * ROW + 4; }

    private boolean overToolbar(double mx, double my) {
        return mx >= btnX() && mx < btnX() + BTN * 2 + 2 && my >= btnY() && my < btnY() + BTN;
    }

    private boolean overMenu(double mx, double my) {
        return menuOpen && mx >= menuX() && mx < menuX() + MENU_W && my >= menuY() && my < menuY() + menuH();
    }

    private Item itemAt(double mx, double my) {
        if (!overMenu(mx, my) || menu.get() < 0.9) return null;
        int i = (int) ((my - menuY() - 2) / ROW);
        return i >= 0 && i < Item.values().length ? Item.values()[i] : null;
    }

    private static String label(Item item, GuiLayout.PreviewOptions o) {
        return switch (item) {
            case WORLD -> "World";
            case RADIUS -> "World Radius: " + (o.radius <= 0 ? "Auto" : o.radius);
            case GRID -> "Floor Grid";
            case AXES -> "Axes";
            case SPIN -> "Auto Rotate";
            case GROW -> "Grow With Window";
        };
    }

    private static String tip(Item item) {
        return switch (item) {
            case WORLD -> "Show the blocks around you (blocks above your feet are see-through)";
            case RADIUS -> "How far the world reaches. Left-click / scroll up: more, right-click / scroll down: less. "
                    + "Auto = as far as the box shows";
            case GRID -> "The dotted grid under your feet";
            case AXES -> "The X / Y / Z arrows in the corner";
            case SPIN -> "Slowly turn the view while you don't drag it";
            case GROW -> "On: a bigger window zooms in. Off: a bigger window shows more around you at the same size";
        };
    }

    private static Boolean value(Item item, GuiLayout.PreviewOptions o) {
        return switch (item) {
            case WORLD -> o.world;
            case GRID -> o.grid;
            case AXES -> o.axes;
            case SPIN -> o.spin;
            case GROW -> o.grow;
            case RADIUS -> null;
        };
    }

    private static void toggle(Item item, GuiLayout.PreviewOptions o) {
        switch (item) {
            case WORLD -> o.world = !o.world;
            case GRID -> o.grid = !o.grid;
            case AXES -> o.axes = !o.axes;
            case SPIN -> o.spin = !o.spin;
            case GROW -> o.grow = !o.grow;
            case RADIUS -> stepRadius(o, 1);
        }
    }

    /** Auto → 2 → … → 10 → Auto (and back). */
    private static void stepRadius(GuiLayout.PreviewOptions o, int dir) {
        int min = GuiLayout.PreviewOptions.MIN_RADIUS, max = GuiLayout.PreviewOptions.MAX_RADIUS;
        int r = o.radius <= 0 ? (dir > 0 ? min : max) : o.radius + dir;
        o.radius = r < min || r > max ? 0 : r;
    }

    private void renderMenu(Render2D r, GuiContext ctx, Theme p, GuiLayout.PreviewOptions opt, int mouseX, int mouseY) {
        boolean hotBtn = mouseX >= btnX() && mouseX < btnX() + BTN && mouseY >= btnY() && mouseY < btnY() + BTN;
        boolean hotReset = mouseX >= btnX() + BTN + 2 && mouseX < btnX() + BTN * 2 + 2 && mouseY >= btnY() && mouseY < btnY() + BTN;
        menuHover.animateTo(hotBtn || menuOpen ? 1 : 0);
        int bg = ColorUtil.fade(p.panel(), 0.85);
        r.roundedRect(btnX(), btnY(), BTN, BTN, 3, ColorUtil.lerp(bg, p.surfaceHover(), menuHover.get()));
        Icons.gear(r, btnX() + BTN / 2 - 1, btnY() + BTN / 2 - 1, menu.get() * 0.5,
                menuOpen ? p.accent() : ColorUtil.lerp(p.textMuted(), p.text(), menuHover.get()));
        r.roundedRect(btnX() + BTN + 2, btnY(), BTN, BTN, 3, hotReset ? p.surfaceHover() : bg);
        Icons.resetView(r, btnX() + BTN + 2 + BTN / 2 - 1, btnY() + BTN / 2 - 1, hotReset ? p.text() : p.textMuted());
        if (hotBtn) ctx.requestTooltip(this, "View options (all previews)");
        if (hotReset) ctx.requestTooltip(this, "Reset the view");

        menu.animateTo(menuOpen ? 1 : 0);
        double t = menu.get();
        if (t <= 0.01) return;
        int mh = (int) Math.round(menuH() * t);
        r.pushAlpha(t);
        try {
            if (ctx.themes().shadows()) r.shadow(menuX(), menuY(), MENU_W, mh, 4, p.shadow(), 4);
            r.roundedRect(menuX(), menuY(), MENU_W, mh, 4, p.panel());
            r.outline(menuX(), menuY(), MENU_W, mh, ColorUtil.fade(p.border(), 0.6));
            r.pushClip(menuX(), menuY(), MENU_W, mh);
            try {
                Item hot = itemAt(mouseX, mouseY);
                for (int i = 0; i < Item.values().length; i++) {
                    Item item = Item.values()[i];
                    int ry = menuY() + 2 + i * ROW;
                    if (item == hot) r.rect(menuX() + 2, ry, MENU_W - 4, ROW, p.surfaceHover());
                    Boolean on = value(item, opt);
                    boolean enabled = item != Item.RADIUS || opt.world;
                    int color = !enabled ? p.textDisabled() : item == hot ? p.text() : p.textMuted();
                    r.text(label(item, opt), menuX() + 6, ry + 2, color);
                    if (on != null) {
                        int sx = menuX() + MENU_W - 18, sy = ry + ROW / 2 - 3;
                        r.pill(sx, sy, 12, 6, on ? p.accent() : p.control());
                        r.circle(on ? sx + 9 : sx + 3, sy + 3.0, 2, on ? 0xFFFFFFFF : p.textMuted());
                    }
                    if (item == hot) ctx.requestTooltip(this, label(item, opt), tip(item));
                }
            } finally {
                r.popClip();
            }
        } finally {
            r.popAlpha();
        }
    }

    /** A press on the view menu or its buttons. Returns true if it was one. */
    private boolean menuClicked(double mx, double my, int button) {
        GuiLayout.PreviewOptions opt = options();
        if (overToolbar(mx, my)) {
            if (button != MouseButton.LEFT) return true;
            if (mx < btnX() + BTN) menuOpen = !menuOpen;
            else resetView();
            return true;
        }
        if (overMenu(mx, my)) {
            Item item = itemAt(mx, my);
            if (item == Item.RADIUS && opt.world) stepRadius(opt, button == MouseButton.RIGHT ? -1 : 1);
            else if (item != null && item != Item.RADIUS && button == MouseButton.LEFT) toggle(item, opt);
            return true;
        }
        if (menuOpen) menuOpen = false; // a press elsewhere closes it (and still acts)
        return false;
    }

    // ================================================================== drawing helpers

    /** Keeps the feet inside the box, so the subject can't be lost off-screen. */
    private void clampPan(int w, int h) {
        double s = Math.max(1e-6, baseScale * toZoom);
        double mx = Math.max(0.5, w * 0.5 / s), my = Math.max(0.5, h * 0.5 / s);
        toPanX = Math.clamp(toPanX, -mx, mx);
        toPanY = Math.clamp(toPanY, -my, my);
    }

    private void resetView() {
        toYaw = DEFAULT_YAW + Math.round((yaw - DEFAULT_YAW) / (2 * Math.PI)) * 2 * Math.PI; // the short way round
        toPitch = DEFAULT_PITCH;
        toZoom = 1;
        toPanX = 0;
        toPanY = 0;
    }

    private void drawFloor(Render2D r, Theme p) {
        int base = ColorUtil.lerp(p.textMuted(), p.accent(), 0.3);
        for (int i = -4; i <= 4; i++) {
            for (int j = -4; j <= 4; j++) {
                double d = Math.sqrt(i * i + j * j) / 4.0;
                if (d > 1.05) continue;
                double[] pt = view.project(i * 0.4, 0, j * 0.4);
                r.rect((int) Math.round(pt[0]), (int) Math.round(pt[1]), 1, 1, ColorUtil.fade(base, 0.55 * (1 - d * 0.8)));
            }
        }
    }

    /** X (east, red) and Z (south, blue) arrows from a fixed screen spot, turned like the world. */
    private void drawGizmo(Render2D r, Theme p, int ox, int oy) {
        double[] o = view.project(0, 0, 0);
        axis(r, ox, oy, o, view.project(1, 0, 0), 0xFFEF4444, "X");
        axis(r, ox, oy, o, view.project(0, 0, 1), 0xFF60A5FA, "Z");
        axis(r, ox, oy, o, view.project(0, 1, 0), 0xFF4ADE80, "Y");
        r.rect(ox, oy, 1, 1, p.text());
    }

    private void axis(Render2D r, int ox, int oy, double[] o, double[] end, int color, String label) {
        double dx = end[0] - o[0], dy = end[1] - o[1];
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-6) return;
        double ux = dx / len * 9, uy = dy / len * 9;
        double vis = Math.min(1, len / view.scale * 1.5);
        line(r, ox, oy, ox + ux * vis, oy + uy * vis, color);
        if (vis > 0.35) r.textCentered(label, (int) Math.round(ox + ux * 1.45), (int) Math.round(oy + uy * 1.45 - 4), ColorUtil.fade(color, 0.9));
    }

    /** Fills the convex hull of the points (GUI {x, y} pairs) with horizontal 1 px spans. */
    public static void fillHull(Render2D r, List<double[]> points, int color) {
        List<double[]> hull = hull(points);
        if (hull.size() < 3) return;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] pt : hull) {
            minY = Math.min(minY, pt[1]);
            maxY = Math.max(maxY, pt[1]);
        }
        for (int y = (int) Math.ceil(minY); y < maxY; y++) {
            double sy = y + 0.5, lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (int i = 0; i < hull.size(); i++) {
                double[] a = hull.get(i), b = hull.get((i + 1) % hull.size());
                if ((a[1] <= sy && b[1] > sy) || (b[1] <= sy && a[1] > sy)) {
                    double xi = a[0] + (sy - a[1]) / (b[1] - a[1]) * (b[0] - a[0]);
                    lo = Math.min(lo, xi);
                    hi = Math.max(hi, xi);
                }
            }
            if (hi > lo) r.rect((int) Math.round(lo), y, (int) Math.round(hi) - (int) Math.round(lo), 1, color);
        }
    }

    /** Monotone-chain convex hull. */
    private static List<double[]> hull(List<double[]> in) {
        List<double[]> pts = new ArrayList<>(in);
        pts.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        if (pts.size() < 3) return pts;
        double[][] h = new double[pts.size() * 2][];
        int n = 0;
        for (double[] pt : pts) {
            while (n >= 2 && cross(h[n - 2], h[n - 1], pt) <= 0) n--;
            h[n++] = pt;
        }
        for (int i = pts.size() - 2, lower = n + 1; i >= 0; i--) {
            double[] pt = pts.get(i);
            while (n >= lower && cross(h[n - 2], h[n - 1], pt) <= 0) n--;
            h[n++] = pt;
        }
        return java.util.Arrays.asList(java.util.Arrays.copyOf(h, Math.max(0, n - 1)));
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    /** A 1 px line from (x0, y0) to (x1, y1). */
    public static void line(Render2D r, double x0, double y0, double x1, double y1, int color) {
        line(r, x0, y0, x1, y1, color, 1);
    }

    /** A line {@code thickness} px thick. */
    public static void line(Render2D r, double x0, double y0, double x1, double y1, int color, int thickness) {
        double dx = x1 - x0, dy = y1 - y0;
        int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)));
        if (steps <= 0) {
            r.rect((int) Math.round(x0), (int) Math.round(y0), thickness, thickness, color);
            return;
        }
        int off = (thickness - 1) / 2;
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            r.rect((int) Math.round(x0 + dx * t) - off, (int) Math.round(y0 + dy * t) - off, thickness, thickness, color);
        }
    }

    // ================================================================== input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (menuClicked(mouseX, mouseY, button)) return true;
        pressX = lastX = mouseX;
        pressY = lastY = mouseY;
        moved = 0;
        if (button == MouseButton.RIGHT || button == MouseButton.MIDDLE) {
            panning = true;
            return true;
        }
        if (button != MouseButton.LEFT) return false;
        for (int i = layers.size() - 1; i >= 0; i--) {
            if (layers.get(i).grab(view, mouseX, mouseY)) {
                grabbed = layers.get(i);
                return true;
            }
        }
        orbiting = true;
        return true;
    }

    @Override
    public void mouseReleased(double mouseX, double mouseY, int button) {
        if (panning && button == MouseButton.RIGHT && moved < CLICK_SLOP) resetView();
        if (grabbed != null) grabbed.release(view);
        grabbed = null;
        orbiting = false;
        panning = false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (overMenu(mouseX, mouseY)) {
            GuiLayout.PreviewOptions opt = options();
            if (itemAt(mouseX, mouseY) == Item.RADIUS && opt.world) stepRadius(opt, amount > 0 ? 1 : -1);
            return true;
        }
        for (int i = layers.size() - 1; i >= 0; i--) if (layers.get(i).scroll(view, mouseX, mouseY, amount)) return true;
        double from = toZoom, to = Math.clamp(toZoom * Math.pow(1.15, amount), MIN_ZOOM, MAX_ZOOM);
        if (to == from) return true;
        // keep the point under the mouse where it is
        double ux = mouseX - view.cx, uy = mouseY - view.cy;
        double s0 = baseScale * from, s1 = baseScale * to;
        toPanX += ux / s1 - ux / s0;
        toPanY += uy / s1 - uy / s0;
        toZoom = to;
        clampPan(view.bw, view.bh);
        return true;
    }

    @Override
    public void onClose() {
        if (grabbed != null) grabbed.release(view);
        grabbed = null;
        orbiting = false;
        panning = false;
        menuOpen = false;
    }
}
