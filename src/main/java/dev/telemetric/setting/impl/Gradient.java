package dev.telemetric.setting.impl;

import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.render.ColorUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An immutable multi-stop colour gradient (2–{@value #MAX_STOPS} stops) with an optional flow animation. Used by
 * {@link ColorSetting}'s gradient mode.
 * <p>
 * Stops keep their insertion order (so an editor's "selected stop" index stays valid while a stop is dragged past
 * another one); sampling uses a position-sorted copy.
 * <p>
 * Text form ({@link #toCode} / {@link #parse}): CSS-like, e.g. {@code "#FF5F6D, #FFC371"} or
 * {@code "#FF5F6D 0%, #FFC371 60%, #2193B0 100%"}. Positions are only written when the stops aren't evenly spaced.
 * Parsing is lenient: any text with two or more {@code #RGB / #RRGGBB / #AARRGGBB} colours works, so a CSS
 * {@code linear-gradient(90deg, #ff5f6d 0%, #ffc371 100%)} pastes straight in.
 */
public final class Gradient {

    public static final int MIN_STOPS = 2, MAX_STOPS = 8;

    /** Flow presets: index 0 is still. */
    public static final String[] FLOW_NAMES = {"Off", "Slow", "Medium", "Fast"};
    private static final int[] FLOW_LOOPS = {0, Durations.GRADIENT_FLOW_SLOW, Durations.GRADIENT_FLOW_MEDIUM,
            Durations.GRADIENT_FLOW_FAST};

    /** One colour stop: {@code pos} 0..1 along the gradient, {@code argb} its colour. */
    public record Stop(float pos, int argb) {
        public Stop {
            pos = Float.isFinite(pos) ? Math.clamp(pos, 0f, 1f) : 0f;
        }
    }

    /** A named ready-made gradient (the picker's preset chips). */
    public record Preset(String name, Gradient gradient) {}

    public static final List<Preset> PRESETS = List.of(
            new Preset("Sunset", of(0xFFFF5F6D, 0xFFFFC371)),
            new Preset("Ocean", of(0xFF2193B0, 0xFF6DD5ED)),
            new Preset("Aurora", of(0xFF00C9FF, 0xFF92FE9D)),
            new Preset("Violet", of(0xFF8E2DE2, 0xFF4A00E0)),
            new Preset("Fire", of(0xFFF12711, 0xFFF5AF19)),
            new Preset("Rainbow", of(0xFFFF4D4D, 0xFFFFD24D, 0xFF4DFF88, 0xFF4DC3FF, 0xFFB84DFF, 0xFFFF4D4D)));

    private static final Pattern COLOR = Pattern.compile(
            "(?i)(?:#|0x)([0-9a-f]{8}|[0-9a-f]{6}|[0-9a-f]{3})(?![0-9a-f])(?:\\s*(-?\\d+(?:\\.\\d+)?)\\s*%)?");

    private final List<Stop> stops;
    private final Stop[] sorted;
    private final int flow;

    public Gradient(List<Stop> stops, int flow) {
        if (stops == null || stops.size() < MIN_STOPS) throw new IllegalArgumentException("a gradient needs 2+ stops");
        this.stops = List.copyOf(stops.size() > MAX_STOPS ? stops.subList(0, MAX_STOPS) : stops);
        this.sorted = this.stops.toArray(new Stop[0]);
        Arrays.sort(sorted, Comparator.comparingDouble(Stop::pos));
        this.flow = Math.clamp(flow, 0, FLOW_LOOPS.length - 1);
    }

    /** Evenly spaced stops, no flow. */
    public static Gradient of(int... colors) {
        List<Stop> out = new ArrayList<>();
        for (int i = 0; i < colors.length; i++) out.add(new Stop(i / (float) (colors.length - 1), colors[i]));
        return new Gradient(out, 0);
    }

    /** A sensible first gradient for a setting that was {@code base}: from it to a hue-shifted sibling. */
    public static Gradient from(int base) {
        float[] hsv = ColorUtil.toHsv(base);
        int end = hsv[1] < 0.08f
                ? ColorUtil.shade(base, ColorUtil.luminance(base) > 0.5 ? -0.45 : 0.45)
                : ColorUtil.hueShift(base, 0.12f);
        return of(base, end);
    }

    public List<Stop> stops() {
        return stops;
    }

    public int size() {
        return stops.size();
    }

    public int flow() {
        return flow;
    }

    public boolean flows() {
        return flow > 0 && Durations.loop(FLOW_LOOPS[flow]) > 0;
    }

    // ---- copies --------------------------------------------------------------------------------------------------

    public Gradient withFlow(int newFlow) {
        return new Gradient(stops, newFlow);
    }

    public Gradient withStop(int index, Stop stop) {
        List<Stop> out = new ArrayList<>(stops);
        out.set(index, stop);
        return new Gradient(out, flow);
    }

    public Gradient withColor(int index, int argb) {
        return withStop(index, new Stop(stops.get(index).pos(), argb));
    }

    public Gradient withPos(int index, float pos) {
        return withStop(index, new Stop(pos, stops.get(index).argb()));
    }

    /** Adds a stop at {@code pos} with the colour the gradient already has there; the new stop is the last index. */
    public Gradient withAdded(float pos) {
        if (stops.size() >= MAX_STOPS) return this;
        List<Stop> out = new ArrayList<>(stops);
        out.add(new Stop(pos, sample(pos)));
        return new Gradient(out, flow);
    }

    public Gradient without(int index) {
        if (stops.size() <= MIN_STOPS) return this;
        List<Stop> out = new ArrayList<>(stops);
        out.remove(index);
        return new Gradient(out, flow);
    }

    /** Mirrored: the end becomes the start. */
    public Gradient reversed() {
        List<Stop> out = new ArrayList<>();
        for (Stop s : stops) out.add(new Stop(1 - s.pos(), s.argb()));
        return new Gradient(out, flow);
    }

    /** Same colours in the same order, evenly spaced. Keeps the indices of the stops. */
    public Gradient evened() {
        Integer[] order = new Integer[stops.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(i -> stops.get(i).pos()));
        List<Stop> out = new ArrayList<>(stops);
        for (int rank = 0; rank < order.length; rank++) {
            int i = order[rank];
            out.set(i, new Stop(rank / (float) (order.length - 1), stops.get(i).argb()));
        }
        return new Gradient(out, flow);
    }

    /** Every stop fully opaque (for colours without alpha). */
    public Gradient opaque() {
        boolean any = false;
        for (Stop s : stops) any |= ColorUtil.alpha(s.argb()) != 255;
        if (!any) return this;
        List<Stop> out = new ArrayList<>();
        for (Stop s : stops) out.add(new Stop(s.pos(), s.argb() | 0xFF000000));
        return new Gradient(out, flow);
    }

    // ---- sampling ------------------------------------------------------------------------------------------------

    /** The still colour at {@code t} (0..1), linearly blended between the two nearest stops. */
    public int sample(float t) {
        if (t <= sorted[0].pos()) return sorted[0].argb();
        Stop last = sorted[sorted.length - 1];
        if (t >= last.pos()) return last.argb();
        for (int i = 1; i < sorted.length; i++) {
            Stop b = sorted[i];
            if (t <= b.pos()) {
                Stop a = sorted[i - 1];
                float span = b.pos() - a.pos();
                return span <= 1e-5f ? b.argb() : ColorUtil.lerp(a.argb(), b.argb(), (t - a.pos()) / span);
            }
        }
        return last.argb();
    }

    /**
     * The colour at {@code t} right now. While flowing, the gradient slides along and bounces back (a mirrored
     * repeat, so there's never a hard seam); a single point ({@code t = 0}) runs through every stop and back.
     */
    public int at(float t, long nowMs) {
        int loop = flow > 0 ? Durations.loop(FLOW_LOOPS[flow]) : 0;
        if (loop <= 0) return sample(t);
        double s = (t + 2.0 * (nowMs % loop) / loop) % 2.0;
        return sample((float) (s > 1 ? 2 - s : s));
    }

    // ---- text form -----------------------------------------------------------------------------------------------

    /** CSS-like text ("#FF5F6D, #FFC371"); 8-digit {@code #AARRGGBB} only for stops that aren't opaque. */
    public String toCode() {
        boolean even = true;
        for (int i = 0; i < sorted.length; i++) even &= Math.abs(sorted[i].pos() - i / (float) (sorted.length - 1)) < 0.005f;
        StringBuilder b = new StringBuilder();
        for (Stop s : sorted) {
            if (!b.isEmpty()) b.append(", ");
            int a = ColorUtil.alpha(s.argb());
            b.append(a == 255 ? String.format(Locale.ROOT, "#%06X", s.argb() & 0xFFFFFF) : String.format(Locale.ROOT, "#%08X", s.argb()));
            if (!even) b.append(' ').append(Math.round(s.pos() * 100)).append('%');
        }
        return b.toString();
    }

    /** Parses a gradient from text (see the class comment); {@code null} if it holds fewer than two colours. */
    public static Gradient parse(String raw, int flow) {
        if (raw == null) return null;
        Matcher m = COLOR.matcher(raw);
        List<Integer> colors = new ArrayList<>();
        List<Float> positions = new ArrayList<>();
        while (m.find() && colors.size() < MAX_STOPS) {
            colors.add(parseHex(m.group(1)));
            positions.add(m.group(2) == null ? null : Float.parseFloat(m.group(2)) / 100f);
        }
        if (colors.size() < MIN_STOPS) return null;
        // CSS rules: a missing first/last position is 0%/100%, missing ones between are spread evenly
        int n = colors.size();
        if (positions.get(0) == null) positions.set(0, 0f);
        if (positions.get(n - 1) == null) positions.set(n - 1, 1f);
        for (int i = 1; i < n; i++) {
            if (positions.get(i) != null) continue;
            int j = i;
            while (positions.get(j) == null) j++;
            float from = positions.get(i - 1), to = positions.get(j);
            for (int k = i; k < j; k++) positions.set(k, from + (to - from) * (k - i + 1) / (j - i + 1));
        }
        List<Stop> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Stop(positions.get(i), colors.get(i)));
        return new Gradient(out, flow);
    }

    /** {@code RGB}, {@code RRGGBB} or {@code AARRGGBB} hex digits to ARGB (opaque unless alpha is given). */
    static int parseHex(String hex) {
        if (hex.length() == 3) {
            StringBuilder b = new StringBuilder();
            for (char ch : hex.toCharArray()) b.append(ch).append(ch);
            hex = b.toString();
        }
        long v = Long.parseLong(hex, 16);
        if (hex.length() == 6) v |= 0xFF000000L;
        return (int) v;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Gradient g && g.flow == flow && g.stops.equals(stops);
    }

    @Override
    public int hashCode() {
        return stops.hashCode() * 31 + flow;
    }

    @Override
    public String toString() {
        return toCode();
    }
}
