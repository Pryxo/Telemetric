package dev.telemetric.ui.animation;

/**
 * Easing curves mapping linear progress {@code t ∈ [0,1]} to eased progress.
 * <ul>
 *   <li>{@link #OUT_CUBIC} — default for things entering / reacting to the user.</li>
 *   <li>{@link #IN_OUT_CUBIC} — state changes of things already on screen (accordions).</li>
 *   <li>{@link #IN_CUBIC} — things leaving the screen.</li>
 *   <li>{@link #OUT_BACK} — small playful overshoot (switch knobs). Use sparingly.</li>
 * </ul>
 */
public enum Easing {
    LINEAR {
        @Override public double apply(double t) { return t; }
    },
    OUT_QUAD {
        @Override public double apply(double t) { return 1 - (1 - t) * (1 - t); }
    },
    IN_CUBIC {
        @Override public double apply(double t) { return t * t * t; }
    },
    OUT_CUBIC {
        @Override public double apply(double t) { double u = 1 - t; return 1 - u * u * u; }
    },
    IN_OUT_CUBIC {
        @Override public double apply(double t) {
            return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
        }
    },
    OUT_QUART {
        @Override public double apply(double t) { double u = 1 - t; return 1 - u * u * u * u; }
    },
    OUT_EXPO {
        @Override public double apply(double t) { return t >= 1 ? 1 : 1 - Math.pow(2, -10 * t); }
    },
    OUT_BACK {
        @Override public double apply(double t) {
            double c1 = 1.70158, c3 = c1 + 1, u = t - 1;
            return 1 + c3 * u * u * u + c1 * u * u;
        }
    };

    public abstract double apply(double t);
}
