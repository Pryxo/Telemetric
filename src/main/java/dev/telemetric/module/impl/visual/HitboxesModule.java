package dev.telemetric.module.impl.visual;

import dev.telemetric.game.EntityVisualsAdapter;
import dev.telemetric.game.EntityVisualsAdapter.BoxKind;
import dev.telemetric.game.WorldRenderAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.GroupToggleSetting;
import dev.telemetric.setting.impl.ModeSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.render.ColorUtil;

import java.util.EnumMap;
import java.util.Map;

/** Draws entity hitboxes (outline and/or translucent fill), with an on/off switch and colour per entity kind. */
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
        super("Hitboxes", "Shows entity hitboxes with a colour per entity kind", Category.VISUAL);
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
}
