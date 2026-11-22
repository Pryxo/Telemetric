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

    private final Map<String, PanelState> panels = new LinkedHashMap<>();
    /** Panel ids, bottom-most first (the last one is drawn on top). */
    private final List<String> zOrder = new ArrayList<>();

    public PanelState get(String panelId) {
        return panels.computeIfAbsent(panelId, id -> new PanelState());
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

    private static void readStrings(JsonElement element, Set<String> out) {
        if (element == null || !element.isJsonArray()) return;
        for (JsonElement e : element.getAsJsonArray()) {
            if (e.isJsonPrimitive()) out.add(e.getAsString());
        }
    }
}
