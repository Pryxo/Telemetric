package dev.telemetric.module.impl.hud;

import dev.telemetric.game.ServerInfoAdapter;
import dev.telemetric.game.ServerInfoAdapter.TabStat;
import dev.telemetric.hud.LinesHudModule;
import dev.telemetric.setting.Setting;
import dev.telemetric.setting.impl.BooleanSetting;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * FPS, Ping and TPS, with one toggle (drag-to-reorder) per stat. With "Combine" on it's one HUD element with all the
 * stats. With it off, every stat is its own HUD element ({@link Part}, hidden from the ClickGUI) that you move
 * separately. Stats without a value (e.g. TPS on servers that don't publish it) are skipped in-game.
 */
public final class PerformanceHud extends LinesHudModule {

    private final BooleanSetting combine;
    private final BooleanSetting useServerColor;
    private final BooleanSetting fps, ping, tps;
    private final List<Part> parts = new ArrayList<>();

    public PerformanceHud() {
        super("Performance HUD", "Shows FPS, Ping and TPS", 1.0, 0.06, false);
        combine = add(new BooleanSetting("Combine",
                "One HUD element for all stats (off: each stat is its own element, moved separately)", true));
        useServerColor = addUseServerColor();
        fps = stat("FPS", "Show your frames per second");
        ping = stat("Ping", "Show your latency to the server");
        tps = stat("TPS", "Show the server's ticks per second (from the Tab list)");
        parts.add(new Part(this, fps, "144", () -> String.valueOf(ServerInfoAdapter.fps()), 0.06));
        parts.add(new Part(this, ping, "20ms", PerformanceHud::pingValue, 0.10));
        parts.add(new Part(this, tps, "20", PerformanceHud::tpsValue, 0.14));
    }

    /** The separate per-stat elements. Register them after this module. */
    public List<Part> parts() {
        return parts;
    }

    private BooleanSetting stat(String label, String description) {
        return add(new BooleanSetting(label, description, true).movable());
    }

    @Override
    public boolean isShown() {
        return isEnabled() && combine.get();
    }

    @Override
    protected String separator() {
        return " ";
    }

    @Override
    protected List<Line> lines(boolean preview) {
        List<Line> out = new ArrayList<>();
        // rows follow the user's drag order in the ClickGUI
        for (Setting<?> s : getSettingOrder()) {
            if (s == fps) add(out, fps, preview, "144", () -> String.valueOf(ServerInfoAdapter.fps()));
            else if (s == ping) add(out, ping, preview, "20ms", PerformanceHud::pingValue);
            else if (s == tps) add(out, tps, preview, "20", PerformanceHud::tpsValue);
        }
        if (out.isEmpty() && preview) out.add(new Line("FPS", "144"));
        return out;
    }

    private static void add(List<Line> out, BooleanSetting toggle, boolean preview, String placeholder, Supplier<String> value) {
        if (!toggle.get()) return;
        String v = value.get();
        if (v != null) out.add(new Line(toggle.getName(), v));
        else if (preview) out.add(new Line(toggle.getName(), placeholder));
    }

    private static String tpsValue() {
        return ServerInfoAdapter.tabStat("TPS").map(TabStat::value).orElse(null);
    }

    /** Latency to the server, with the Tab-list "Ping" as a fallback. */
    private static String pingValue() {
        int ping = ServerInfoAdapter.ping();
        if (ping > 0) return ping + "ms";
        return ServerInfoAdapter.tabStat("Ping")
                .map(s -> s.value().endsWith("ms") ? s.value() : s.value() + "ms")
                .orElse(ping == 0 ? "0ms" : null);
    }

    /**
     * One stat as its own HUD element (Combine off). It's not listed in the ClickGUI, and its module entry only stores
     * the position, scale and mirror. Shown while the Performance HUD is on, Combine is off and the stat's toggle is on.
     */
    public static final class Part extends LinesHudModule {

        private final PerformanceHud owner;
        private final BooleanSetting stat;
        private final String placeholder;
        private final Supplier<String> value;

        private Part(PerformanceHud owner, BooleanSetting stat, String placeholder, Supplier<String> value, double defaultY) {
            super("Performance HUD " + stat.getName(), "Performance HUD: " + stat.getName() + " on its own",
                    1.0, defaultY, true);
            this.owner = owner;
            this.stat = stat;
            this.placeholder = placeholder;
            this.value = value;
        }

        @Override
        public boolean isVisibleInGui() {
            return false;
        }

        @Override
        public boolean isShown() {
            return owner.isEnabled() && !owner.combine.get() && stat.get();
        }

        /** HUD editor "Del" turns the stat off in the Performance HUD. */
        @Override
        public void hide() {
            stat.set(false);
        }

        @Override
        protected BooleanSetting serverColorToggle() {
            return owner.useServerColor;
        }

        @Override
        protected String separator() {
            return " ";
        }

        @Override
        protected List<Line> lines(boolean preview) {
            List<Line> out = new ArrayList<>(1);
            PerformanceHud.add(out, stat, preview, placeholder, value);
            return out;
        }
    }
}
