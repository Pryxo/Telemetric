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

    /** This ramp with {@code f} applied to every colour (fade, flash blend …); stays uniform if this one is. */
    default ColorRamp map(java.util.function.IntUnaryOperator f) {
        ColorRamp self = this;
        return new ColorRamp() {
            @Override
            public int at(float t) {
                return f.applyAsInt(self.at(t));
            }

            @Override
            public boolean isUniform() {
                return self.isUniform();
            }
        };
    }

    /** Only the part {@code from .. to} of this ramp, stretched to 0..1 (a filling bar keeps the full bar's gradient). */
    default ColorRamp part(float from, float to) {
        ColorRamp self = this;
        return new ColorRamp() {
            @Override
            public int at(float t) {
                return self.at(from + (to - from) * t);
            }

            @Override
            public boolean isUniform() {
                return self.isUniform();
            }
        };
    }

    /** Per position, {@code a} blended toward {@code b} by {@code f} (0 = a, 1 = b). */
    static ColorRamp lerp(ColorRamp a, ColorRamp b, double f) {
        return new ColorRamp() {
            @Override
            public int at(float t) {
                return dev.telemetric.ui.render.ColorUtil.lerp(a.at(t), b.at(t), f);
            }

            @Override
            public boolean isUniform() {
                return a.isUniform() && b.isUniform();
            }
        };
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
