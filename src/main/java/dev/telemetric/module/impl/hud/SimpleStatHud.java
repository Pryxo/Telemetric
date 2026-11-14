package dev.telemetric.module.impl.hud;

import dev.telemetric.hud.LinesHudModule;

import java.util.List;

/** One-row HUD element ("FPS 144"). Subclasses return the current value or {@code null} when unknown. */
abstract class SimpleStatHud extends LinesHudModule {

    private final String label;
    private final String placeholder;

    SimpleStatHud(String name, String description, String label, String placeholder, double defaultX, double defaultY) {
        super(name, description, defaultX, defaultY, false);
        this.label = label;
        this.placeholder = placeholder;
        addUseServerColor();
    }

    /** Current value, or null when not available. */
    protected abstract String value();

    @Override
    protected String separator() {
        return " ";
    }

    @Override
    protected List<Line> lines(boolean preview) {
        String v = value();
        if (v == null) return preview ? List.of(new Line(label, placeholder)) : List.of();
        return List.of(new Line(label, v));
    }
}
