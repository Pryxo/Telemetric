package dev.telemetric.module.impl.visual;

import dev.telemetric.game.EntityPreviewAdapter;
import dev.telemetric.game.EntityVisualsAdapter.BoxKind;
import dev.telemetric.game.EntityVisualsAdapter;
import dev.telemetric.game.WorldPreviewAdapter;
import dev.telemetric.game.WorldRenderAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.GroupToggleSetting;
import dev.telemetric.setting.impl.ModeSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.clickgui.window.PlayerPreview;
import dev.telemetric.ui.clickgui.window.SettingsPreview;
import dev.telemetric.ui.framework.GuiContext;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Draws entity hitboxes (outline and/or translucent fill), with an on/off switch and a colour per entity kind. */
public final class HitboxesModule extends Module {

    private final ModeSetting style = add(new ModeSetting("Style", "How a hitbox is drawn",
            "Outline + Fill", "Outline", "Fill", "Outline + Fill"));
    private final NumberSetting lineWidth = add(new NumberSetting("Line Width", "Outline thickness",
            2, 1, 6, 0.5).withSuffix("px").visibleWhen(() -> !style.get().equals("Fill")));
    private final NumberSetting fillOpacity = add(new NumberSetting("Fill Opacity", "How solid the fill is",
            20, 5, 80, 5).withSuffix("%").visibleWhen(() -> !style.get().equals("Outline")));
    private final BooleanSetting throughWalls = add(new BooleanSetting("Through Walls",
            "Hitboxes stay visible behind blocks", false));
    private final NumberSetting range = add(new NumberSetting("Range", "Only entities this close",
            48, 8, 128, 4).withSuffix(" blocks"));

    private final Map<BoxKind, Kind> kinds = new EnumMap<>(BoxKind.class);

    private record Kind(GroupToggleSetting shown, ColorSetting color) {}

    public HitboxesModule() {
        super("Hitboxes", "Shows entity hitboxes with a colour per entity kind", Category.WORLD);
        kind(BoxKind.SELF, "Yourself", "your own hitbox (third person)", true, 0xFF8A5CF6);
        kind(BoxKind.PLAYER, "Players", "other players' hitboxes", true, 0xFF3B82F6);
        kind(BoxKind.MOB, "Mobs", "mob hitboxes", true, 0xFFE5484D);
        kind(BoxKind.ARMOR_STAND, "Armor Stands", "armor stand hitboxes (Telos uses many as markers)", false, 0xFFA1A1AA);
        kind(BoxKind.ITEM, "Items", "dropped item hitboxes", true, 0xFF2DD4BF);
        kind(BoxKind.OTHER, "Other", "every other entity's hitbox (props, projectiles, NPC boxes, ...)", false, 0xFFF5A524);
        WorldRenderAdapter.addUnderDrawer(this::draw);
    }

    private void kind(BoxKind kind, String name, String what, boolean on, int color) {
        GroupToggleSetting shown = add(new GroupToggleSetting(name, "Show " + what, on));
        ColorSetting c = new ColorSetting(name + " Color", "Colour of " + what, color, false);
        c.withLabel("Color");
        add(shown.child(c));
        kinds.put(kind, new Kind(shown, c));
    }

    private void draw(WorldRenderAdapter.LineSink sink) {
        if (!isEnabled()) return;
        boolean outline = !style.get().equals("Fill"), fill = !style.get().equals("Outline");
        float width = lineWidth.getFloat();
        int fillAlpha = (int) Math.round(fillOpacity.get() * 2.55);
        boolean walls = throughWalls.get();
        EntityVisualsAdapter.forEachBox(range.get(), (kind, x0, y0, z0, x1, y1, z1) -> {
            Kind k = kinds.get(kind);
            if (k == null || !k.shown().get()) return;
            int argb = k.color().get();
            if (fill) sink.boxFill(x0, y0, z0, x1, y1, z1, ColorUtil.withAlpha(argb, fillAlpha), walls);
            if (outline) sink.boxOutline(x0, y0, z0, x1, y1, z1, argb, width, walls);
        });
    }

    // ------------------------------------------------------------------ Settings window preview

    @Override
    public SettingsPreview createPreview() {
        return new PlayerPreview(new PreviewBox());
    }

    /**
     * The hitboxes in the preview, drawn like in the world (same style, colours, widths, Through Walls) as real 3D
     * lines and fills in the preview scene: your own box ("Yourself") around the preview player, plus the boxes of
     * the nearby entities when Interface > 3D View > Show Entities is on. Falls back to a flat overlay of your own box
     * if the scene can't be drawn.
     */
    private final class PreviewBox implements PlayerPreview.Layer {
        @Override
        public void sceneShapes(WorldRenderAdapter.LineSink sink, double radius, boolean entities) {
            double[] feet = WorldPreviewAdapter.feet();
            if (feet == null) return;
            boolean outline = !style.get().equals("Fill"), fill = !style.get().equals("Outline");
            float width = lineWidth.getFloat();
            int fillAlpha = (int) Math.round(fillOpacity.get() * 2.55);
            boolean walls = throughWalls.get();
            EntityVisualsAdapter.BoxConsumer box = (kind, x0, y0, z0, x1, y1, z1) -> {
                Kind k = kinds.get(kind);
                if (k == null || !k.shown().get()) return;
                int argb = k.color().get();
                if (fill) sink.boxFill(x0, y0, z0, x1, y1, z1, ColorUtil.withAlpha(argb, fillAlpha), walls);
                if (outline) sink.boxOutline(x0, y0, z0, x1, y1, z1, argb, width, walls);
            };
            double hw = EntityPreviewAdapter.playerWidth() / 2, h = EntityPreviewAdapter.playerHeight();
            box.box(BoxKind.SELF, feet[0] - hw, feet[1], feet[2] - hw, feet[0] + hw, feet[1] + h, feet[2] + hw);
            if (entities) EntityVisualsAdapter.forEachBox(radius, false, box);
        }

        @Override
        public void render(Render2D r, GuiContext ctx, PlayerPreview.View v, int mouseX, int mouseY) {
            if (v.scene()) return; // drawn in 3D by sceneShapes
            Kind k = kinds.get(BoxKind.SELF);
            if (k == null || !k.shown().get()) return;
            double hw = EntityPreviewAdapter.playerWidth() / 2, h = v.playerHeight();
            List<double[]> corners = new ArrayList<>(8);
            for (int i = 0; i < 8; i++) {
                corners.add(v.project((i & 1) == 0 ? -hw : hw, (i & 2) == 0 ? 0 : h, (i & 4) == 0 ? -hw : hw));
            }
            int argb = k.color().get();
            if (!style.get().equals("Outline")) {
                PlayerPreview.fillHull(r, corners, ColorUtil.withAlpha(argb, (int) Math.round(fillOpacity.get() * 2.55)));
            }
            if (!style.get().equals("Fill")) {
                int t = lineWidth.get() >= 3 ? 2 : 1;
                for (int i = 0; i < 8; i++) {
                    for (int bit = 1; bit <= 4; bit <<= 1) {
                        if ((i & bit) != 0) continue;
                        double[] a = corners.get(i), b = corners.get(i | bit);
                        PlayerPreview.line(r, a[0], a[1], b[0], b[1], argb, t);
                    }
                }
            }
        }
    }
}
