package dev.telemetric.ui.hud;

import dev.telemetric.Telemetric;
import dev.telemetric.hud.HudModule;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.ui.render.Render2D;

import java.util.ArrayList;
import java.util.List;

/** Shared placement + drawing of HUD elements (used by the in-game overlay, the editor and the ClickGUI). */
public final class HudRenderer {

    private HudRenderer() {}

    /** Shown HUD elements in registration order. */
    public static List<HudModule> activeElements() {
        List<HudModule> list = new ArrayList<>();
        for (Module module : Telemetric.get().modules().getModules()) { // HUD elements may live in any panel
            if (module instanceof HudModule hud && hud.isShown()) list.add(hud);
        }
        return list;
    }

    /** Screen rectangle {x, y, w, h} of an element in GUI units. */
    public static int[] bounds(Render2D r, HudModule m, int sw, int sh, boolean preview) {
        int w = m.scaledWidth(r, preview), h = m.scaledHeight(r, preview);
        return new int[]{m.left(sw, w), m.top(sh, h), w, h};
    }

    /** Draws one element at its stored position and scale. */
    public static void render(Render2D r, HudModule m, int sw, int sh, boolean preview) {
        int[] b = bounds(r, m, sw, sh, preview);
        r.pushTransform(b[0], b[1], m.getScale());
        try {
            m.draw(r, preview, m.isMirrored());
        } finally {
            r.popTransform();
        }
    }
}
