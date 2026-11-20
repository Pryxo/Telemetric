package dev.telemetric.ui.animation;

/**
 * A time-based scalar animation that doesn't depend on the frame rate.
 * <p>
 * Call {@link #animateTo(double)} whenever the target might change. It does nothing if the target is the same, and
 * re-targets smoothly from the current value if an animation is already running. Read it with {@link #get()}.
 * <pre>{@code
 * Animation hover = new Animation(Durations.HOVER, Easing.OUT_CUBIC);
 * hover.animateTo(isHovered ? 1 : 0);
 * int bg = ColorUtil.lerp(palette.surface(), palette.surfaceHover(), hover.getF());
 * }</pre>
 */
public final class Animation {

    private final int baseDuration;
    private final Easing easing;

    private double from;
    private double to;
    private long startNanos;
    private long delayNanos;
    private int duration;

    public Animation(int baseDurationMs, Easing easing) {
        this(baseDurationMs, easing, 0);
    }

    public Animation(int baseDurationMs, Easing easing, double initial) {
        this.baseDuration = baseDurationMs;
        this.easing = easing;
        this.from = initial;
        this.to = initial;
        this.startNanos = System.nanoTime();
    }

    /** Animates towards {@code target} (does nothing if it's already heading there). */
    public Animation animateTo(double target) {
        return animateTo(target, 0);
    }

    /** Animates towards {@code target} after {@code delayMs}. */
    public Animation animateTo(double target, int delayMs) {
        if (target == to) return this;
        from = get();
        to = target;
        startNanos = System.nanoTime();
        delayNanos = Durations.isEnabled() ? delayMs * 1_000_000L : 0;
        duration = Durations.scaled(baseDuration);
        return this;
    }

    /** Jumps straight to a value, cancelling any running animation. */
    public Animation snapTo(double value) {
        from = value;
        to = value;
        duration = 0;
        delayNanos = 0;
        return this;
    }

    public double get() {
        double progress = progress();
        if (progress >= 1) return to;
        return from + (to - from) * easing.apply(progress);
    }

    public float getF() {
        return (float) get();
    }

    /** The linear progress 0..1 of the current transition. */
    public double progress() {
        if (duration <= 0) return 1;
        long elapsed = System.nanoTime() - startNanos - delayNanos;
        if (elapsed <= 0) return 0;
        return Math.min(1, elapsed / (duration * 1_000_000.0));
    }

    public boolean isRunning() {
        return progress() < 1;
    }

    public double getTarget() {
        return to;
    }

    /** True when it has settled at {@code value}. */
    public boolean isAt(double value) {
        return to == value && !isRunning();
    }
}
