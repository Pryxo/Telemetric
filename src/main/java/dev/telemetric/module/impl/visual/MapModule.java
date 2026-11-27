package dev.telemetric.module.impl.visual;

import dev.telemetric.data.MapMarkerTable;
import dev.telemetric.data.TelosData;
import dev.telemetric.game.MapTextureAdapter;
import dev.telemetric.input.InputUtil;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.MultiChoiceSetting;
import dev.telemetric.setting.impl.GroupSetting;
import dev.telemetric.setting.impl.KeybindSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.map.MapRenderer;
import dev.telemetric.ui.map.MapScreen;

/**
 * World → Telos Map: a full-screen map of the realm (open realm, Shadowlands, Radiant Isles, Voided Abyss) with the
 * bosses' live state, minibosses, other players and your own head ({@code ui/map/MapScreen}). What it marks (the
 * legend's categories, icons, fixed points) comes from {@code tables/mapMarkers.json}, images and regions from
 * {@code tables/map.json}.
 */
public final class MapModule extends Module {

    private final KeybindSetting openKey = add(new KeybindSetting("Open Map",
            "Opens the Telos Map (press again or Esc to close)", InputUtil.KEY_M).onPress(this::open));

    private final GroupSetting markerGroup = add(new GroupSetting("Markers", "What the map shows (also its legend)"));
    private final MultiChoiceSetting hidden = add(markerGroup.child(new MultiChoiceSetting("Hidden",
            "Marker types the map leaves out (also: click them in the map's legend)",
            () -> TelosData.MAP_MARKERS.get().labels(), hiddenByDefault(MapMarkerTable.Category::map))));
    public final NumberSetting markerSize = add(markerGroup.child(new NumberSetting("Marker Size",
            "Size of the icons on the map", 1.0, 0.6, 2.0, 0.1).withSuffix("x")));

    private final GroupSetting viewGroup = add(new GroupSetting("View", "How the map opens"));
    public final BooleanSetting rememberView = add(viewGroup.child(new BooleanSetting("Remember View",
            "Reopen where you left it (off: always centred on you)", false)));
    public final NumberSetting defaultZoom = add(viewGroup.child(new NumberSetting("Default Zoom",
            "Zoom when the map opens (GUI pixels per block)", 1.0, 0.25, 4.0, 0.05).withSuffix("x")));
    public final BooleanSetting clickTeleport = add(viewGroup.child(new BooleanSetting("Click to Teleport",
            "Left-click a boss someone is fighting: /tp to that player", true)));

    private int ticks;

    public MapModule() {
        super("Telos Map", "Full-screen realm map: bosses, minibosses, players (M)", Category.WORLD);
    }

    @Override
    protected boolean isEnabledByDefault() {
        return true;
    }

    /** True if the map draws markers of {@code c}. */
    public boolean shows(MapMarkerTable.Category c) {
        return !hidden.contains(c.label());
    }

    /** Shows or hides {@code c} (legend click). */
    public void toggle(MapMarkerTable.Category c) {
        hidden.toggle(c.label());
    }

    /** Labels of the {@code mapMarkers.json} categories whose {@code flag} is off (hidden by default). */
    static java.util.Set<String> hiddenByDefault(java.util.function.Predicate<MapMarkerTable.Category> flag) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (MapMarkerTable.Category c : TelosData.MAP_MARKERS.get().categories()) if (!flag.test(c)) out.add(c.label());
        return out;
    }

    public int openKey() {
        return openKey.get();
    }

    private void open() {
        MapScreen.open(this);
    }

    @Override
    public void onTick() {
        if (++ticks % 200 == 0) {
            MapTextureAdapter.releaseIdle();
            MapRenderer.forgetIcons();
        }
    }
}
