package dev.telemetric.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ClickGUI layout that persists between sessions: each panel's position, collapsed state and scroll offset, which
 * module accordions and setting dropdowns are open, and the panel stacking order.
 * It's stored apart from the profiles ({@code layout.json}), so switching profiles doesn't move panels around.
 * See docs/config.md.
 */
public final class GuiLayout implements JsonSerializable {

    /** v3 brought a new panel order (Player | Visual | Dungeon | HUD | Misc), so older layouts get re-arranged once. */
    public static final int FORMAT_VERSION = 5;

    /** Mutable per-panel state. {@code x/y} are in ClickGUI units (after UI scale). */
    public static final class PanelState {
        public int x;
        public int y;
        public boolean collapsed;
        /** {@code false} until the panel has been placed once. Lets the GUI auto-arrange new panels. */
        public boolean placed;
        /** Scroll offset of the panel body (ClickGUI units). */
        public int scroll;
        /** Names of modules whose settings accordion is open. */
        public final Set<String> openModules = new LinkedHashSet<>();
        /** Module name to the names of its open expandable settings (dropdowns, colour pickers). */
        public final Map<String, Set<String>> openSettings = new LinkedHashMap<>();

        public boolean isModuleOpen(String module) {
            return openModules.contains(module);
        }

        public Set<String> openSettingsOf(String module) {
            Set<String> set = openSettings.get(module);
            return set == null ? Collections.emptySet() : set;
        }

        /** Replaces the remembered open state of one module. */
        public void setModuleState(String module, boolean open, Set<String> settings) {
            if (open) openModules.add(module);
            else openModules.remove(module);
            if (settings.isEmpty()) openSettings.remove(module);
            else openSettings.put(module, new LinkedHashSet<>(settings));
        }
    }

    /**
     * The module Settings window: position and size, the module shown (null = closed), the selected section of each
     * module and the open dropdowns / groups of each module.
     */
    public static final class WindowState {
        public int x;
        public int y;
        public int w;
        public int h;
        public boolean placed;
        public String module;
        public final Map<String, String> sections = new LinkedHashMap<>();
        public final Map<String, Set<String>> openSettings = new LinkedHashMap<>();

        public Set<String> openSettingsOf(String module) {
            Set<String> set = openSettings.get(module);
            return set == null ? Collections.emptySet() : set;
        }

        public void setOpenSettings(String module, Set<String> names) {
            if (names.isEmpty()) openSettings.remove(module);
            else openSettings.put(module, new LinkedHashSet<>(names));
        }
    }

    /** View options shared by every 3D preview of the Settings window (its top-left menu). */
    public static final class PreviewOptions {
        /** Smallest / largest World Radius; 0 = Auto (as far as the box shows). */
        public static final int MIN_RADIUS = 2, MAX_RADIUS = 10;

        /** The blocks around you. */
        public boolean world = true;
        /** World Radius in blocks, 0 = Auto. */
        public int radius = 0;
        /** The dotted floor grid. */
        public boolean grid = true;
        /** The X / Y / Z gizmo. */
        public boolean axes = true;
        /** Slowly turn the view while it isn't dragged. */
        public boolean spin = false;
        /** Zoom in when the window grows (off = a bigger window shows more around you, at the same size). */
        public boolean grow = false;
    }

    private final Map<String, PanelState> panels = new LinkedHashMap<>();
    private final WindowState window = new WindowState();
    private final PreviewOptions preview = new PreviewOptions();
    /** Settings pinned to a module's right-click menu, by module name, in pin order. */
    private final Map<String, List<String>> pins = new LinkedHashMap<>();
    private int pinsVersion;
    /** Panel ids, bottom-most first (the last one is drawn on top). */
    private final List<String> zOrder = new ArrayList<>();

    public PanelState get(String panelId) {
        return panels.computeIfAbsent(panelId, id -> new PanelState());
    }

    public WindowState window() {
        return window;
    }

    public PreviewOptions preview() {
        return preview;
    }

    /** The setting names pinned to a module's right-click menu (empty if none). */
    public List<String> pinsOf(String module) {
        List<String> list = pins.get(module);
        return list == null ? Collections.emptyList() : Collections.unmodifiableList(list);
    }

    public boolean isPinned(String module, String setting) {
        return pinsOf(module).contains(setting);
    }

    public void setPinned(String module, String setting, boolean pinned) {
        List<String> list = pins.computeIfAbsent(module, m -> new ArrayList<>());
        boolean changed = pinned ? !list.contains(setting) && list.add(setting) : list.remove(setting);
        if (list.isEmpty()) pins.remove(module);
        if (changed) pinsVersion++;
    }

    /** Bumped on every pin change, so the module rows rebuild their right-click menus. */
    public int pinsVersion() {
        return pinsVersion;
    }

    /** Stacking order, bottom-most first. Unknown panels aren't listed. */
    public List<String> getZOrder() {
        return Collections.unmodifiableList(zOrder);
    }

    public void setZOrder(List<String> ids) {
        zOrder.clear();
        zOrder.addAll(ids);
    }

    /** Forgets every position so the GUI auto-arranges on the next frame. Open/closed state is kept. */
    public void reset() {
        panels.values().forEach(p -> {
            p.placed = false;
            p.scroll = 0;
        });
        window.placed = false;
        zOrder.clear();
    }

    @Override
    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        JsonArray order = new JsonArray();
        zOrder.forEach(order::add);
        root.add("order", order);
        JsonObject list = new JsonObject();
        panels.forEach((id, state) -> {
            if (!state.placed) return;
            JsonObject p = new JsonObject();
            p.addProperty("x", state.x);
            p.addProperty("y", state.y);
            p.addProperty("collapsed", state.collapsed);
            p.addProperty("scroll", state.scroll);
            JsonArray open = new JsonArray();
            state.openModules.forEach(open::add);
            p.add("openModules", open);
            JsonObject settings = new JsonObject();
            state.openSettings.forEach((module, names) -> {
                JsonArray arr = new JsonArray();
                names.forEach(arr::add);
                settings.add(module, arr);
            });
            p.add("openSettings", settings);
            list.add(id, p);
        });
        root.add("panels", list);

        JsonObject w = new JsonObject();
        if (window.placed) {
            w.addProperty("x", window.x);
            w.addProperty("y", window.y);
            w.addProperty("w", window.w);
            w.addProperty("h", window.h);
        }
        if (window.module != null) w.addProperty("module", window.module);
        JsonObject sections = new JsonObject();
        window.sections.forEach(sections::addProperty);
        w.add("sections", sections);
        w.add("openSettings", stringSets(window.openSettings));
        root.add("window", w);

        JsonObject pinned = new JsonObject();
        pins.forEach((module, names) -> {
            JsonArray arr = new JsonArray();
            names.forEach(arr::add);
            pinned.add(module, arr);
        });
        root.add("pins", pinned);

        JsonObject view = new JsonObject();
        view.addProperty("world", preview.world);
        view.addProperty("radius", preview.radius);
        view.addProperty("grid", preview.grid);
        view.addProperty("axes", preview.axes);
        view.addProperty("spin", preview.spin);
        view.addProperty("grow", preview.grow);
        root.add("preview", view);
        return root;
    }

    @Override
    public void fromJson(JsonObject json) {
        // v1 only had x/y/collapsed. Every newer field is optional, so v1 files load unchanged.
        JsonElement order = json.get("order");
        if (order != null && order.isJsonArray()) {
            zOrder.clear();
            for (JsonElement e : order.getAsJsonArray()) {
                if (e.isJsonPrimitive()) zOrder.add(e.getAsString());
            }
        }
        readWindow(json.get("window"));
        readPins(json.get("pins"));
        readPreview(json.get("preview"));
        JsonElement list = json.get("panels");
        if (list == null || !list.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> entry : list.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject p = entry.getValue().getAsJsonObject();
            try {
                int x = p.get("x").getAsInt();
                int y = p.get("y").getAsInt();
                PanelState state = get(entry.getKey());
                state.x = x;
                state.y = y;
                state.collapsed = p.has("collapsed") && p.get("collapsed").getAsBoolean();
                state.scroll = p.has("scroll") ? Math.max(0, p.get("scroll").getAsInt()) : 0;
                state.openModules.clear();
                state.openSettings.clear();
                readStrings(p.get("openModules"), state.openModules);
                JsonElement settings = p.get("openSettings");
                if (settings != null && settings.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> s : settings.getAsJsonObject().entrySet()) {
                        Set<String> names = new LinkedHashSet<>();
                        readStrings(s.getValue(), names);
                        if (!names.isEmpty()) state.openSettings.put(s.getKey(), names);
                    }
                }
                state.placed = true;
            } catch (RuntimeException ignored) {
                // skip a malformed entry
            }
        }
        JsonElement version = json.get("version");
        int loaded = version != null && version.isJsonPrimitive() ? version.getAsInt() : 1;
        if (loaded < 5) reset(); // new panel order / renamed panels: re-arrange the positions once but keep the open state
    }

    private void readWindow(JsonElement element) {
        if (element == null || !element.isJsonObject()) return;
        JsonObject w = element.getAsJsonObject();
        try {
            if (w.has("x") && w.has("y") && w.has("w") && w.has("h")) {
                window.x = w.get("x").getAsInt();
                window.y = w.get("y").getAsInt();
                window.w = w.get("w").getAsInt();
                window.h = w.get("h").getAsInt();
                window.placed = window.w > 0 && window.h > 0;
            }
            JsonElement module = w.get("module");
            window.module = module != null && module.isJsonPrimitive() ? module.getAsString() : null;
            window.sections.clear();
            JsonElement sections = w.get("sections");
            if (sections != null && sections.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : sections.getAsJsonObject().entrySet()) {
                    if (e.getValue().isJsonPrimitive()) window.sections.put(e.getKey(), e.getValue().getAsString());
                }
            }
            window.openSettings.clear();
            readStringSets(w.get("openSettings"), window.openSettings);
        } catch (RuntimeException ignored) {
            // a malformed window entry: keep the defaults
        }
    }

    private void readPreview(JsonElement element) {
        if (element == null || !element.isJsonObject()) return;
        JsonObject v = element.getAsJsonObject();
        try {
            if (v.has("world")) preview.world = v.get("world").getAsBoolean();
            if (v.has("radius")) {
                int r = v.get("radius").getAsInt();
                preview.radius = r <= 0 ? 0 : Math.clamp(r, PreviewOptions.MIN_RADIUS, PreviewOptions.MAX_RADIUS);
            }
            if (v.has("grid")) preview.grid = v.get("grid").getAsBoolean();
            if (v.has("axes")) preview.axes = v.get("axes").getAsBoolean();
            if (v.has("spin")) preview.spin = v.get("spin").getAsBoolean();
            if (v.has("grow")) preview.grow = v.get("grow").getAsBoolean();
        } catch (RuntimeException ignored) {
            // a malformed preview entry: keep the defaults
        }
    }

    private void readPins(JsonElement element) {
        pins.clear();
        pinsVersion++;
        if (element == null || !element.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
            Set<String> names = new LinkedHashSet<>();
            readStrings(e.getValue(), names);
            if (!names.isEmpty()) pins.put(e.getKey(), new ArrayList<>(names));
        }
    }

    private static JsonObject stringSets(Map<String, Set<String>> map) {
        JsonObject out = new JsonObject();
        map.forEach((key, names) -> {
            JsonArray arr = new JsonArray();
            names.forEach(arr::add);
            out.add(key, arr);
        });
        return out;
    }

    private static void readStringSets(JsonElement element, Map<String, Set<String>> out) {
        if (element == null || !element.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
            Set<String> names = new LinkedHashSet<>();
            readStrings(e.getValue(), names);
            if (!names.isEmpty()) out.put(e.getKey(), names);
        }
    }

    private static void readStrings(JsonElement element, Set<String> out) {
        if (element == null || !element.isJsonArray()) return;
        for (JsonElement e : element.getAsJsonArray()) {
            if (e.isJsonPrimitive()) out.add(e.getAsString());
        }
    }
}
