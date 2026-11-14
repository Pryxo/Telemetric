package dev.telemetric.module.impl.dungeon;

import dev.telemetric.game.ResourceAdapter;
import dev.telemetric.ui.render.Render2D;

/**
 * Icon drawn in the middle of a special room on the Dungeon Map. Uses the PNG from the Telos server
 * resource pack while it is loaded; otherwise a bundled copy ({@code assets/telemetric/textures/hud/map/}).
 * Only the opaque box {@code (u, v, w, h)} is drawn, fitted (aspect kept) into a square of one shared size,
 * so all icons look the same size.
 */
enum RoomIcon {
    SPAWN("telos:textures/icon/misc/boss.png", "spawn.png", 32, 32, 5, 4, 23, 26),
    BOSS_DROP("telos:textures/icon/misc/dead.png", "boss_drop.png", 32, 32, 5, 3, 23, 26),
    TREASURE("telos:textures/material/pouch/gilded.png", "treasure.png", 233, 228, 0, 0, 233, 228);

    private final String pack, fallback;
    private final int texW, texH, u, v, w, h;

    RoomIcon(String pack, String file, int texW, int texH, int u, int v, int w, int h) {
        this.pack = pack;
        this.fallback = "textures/hud/map/" + file;
        this.texW = texW;
        this.texH = texH;
        this.u = u;
        this.v = v;
        this.w = w;
        this.h = h;
    }

    static RoomIcon of(DungeonMapHud.Kind kind) {
        return switch (kind) {
            case SPAWN -> SPAWN;
            case BOSS -> BOSS_DROP;
            case TREASURE -> TREASURE;
            case ROOM, GOAL -> null;
        };
    }

    /** Draws the icon centred on {@code (cx, cz)}, its longer side {@code size} px. */
    void draw(Render2D r, int cx, int cz, int size) {
        int dw = w >= h ? size : Math.max(1, Math.round(size * w / (float) h));
        int dh = h >= w ? size : Math.max(1, Math.round(size * h / (float) w));
        String path = ResourceAdapter.exists(pack) ? pack : fallback;
        r.texture(path, cx - dw / 2, cz - dh / 2, dw, dh, u, v, w, h, texW, texH, 0xFFFFFFFF);
    }
}
