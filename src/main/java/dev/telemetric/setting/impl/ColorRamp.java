package dev.telemetric.setting.impl;

/**
 * A colour that may change along an element: {@code at(0)} is its start (left), {@code at(1)} its end (right). A
 * single colour is a ramp that returns the same value everywhere ({@link #isUniform()}), so drawing code can take a
 * ramp and only pay for per-glyph / per-column colours when there really is a gradient.
 * {@link ColorSetting} is one (its gradient mode), and so is {@link dev.telemetric.hud.HudColor}.
 */
@FunctionalInterface
public interface ColorRamp {

    /** The ARGB colour at {@code t} (0..1) along the element. Flowing gradients also move over time. */
    int at(float t);

    /** True when every {@code t} gives the same colour (callers can then draw it as one plain colour). */
    default boolean isUniform() {
        return false;
    }

    /** A ramp that is one colour everywhere. */
    static ColorRamp solid(int argb) {
        return new ColorRamp() {
            @Override
            public int at(float t) {
                return argb;
            }

            @Override
            public boolean isUniform() {
                return true;
            }
        };
    }
}
