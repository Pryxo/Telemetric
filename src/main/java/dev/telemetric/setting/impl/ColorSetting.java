package dev.telemetric.setting.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.telemetric.setting.Setting;

import java.util.Locale;

/**
 * An ARGB colour that can also be a {@link Gradient} (the picker's Solid / Gradient switch).
 * <p>
 * {@link #get()} always returns one colour, so every feature works with gradients without changes: a still gradient
 * gives its start colour, a flowing one runs through its stops over time. Features that draw something with a length
 * (text, bars, backgrounds) read {@link #at(float)} / pass the setting as a {@link ColorRamp} to get the real gradient.
 * <p>
 * Saved as {@code "#AARRGGBB"} while it never was a gradient (the old format, still read), otherwise as
 * {@code {"color": "#AARRGGBB", "mode": "gradient"|"solid", "gradient": "#.., #..", "flow": "Off"}}. The solid colour and
 * the gradient are both kept, so switching modes never loses either.
 */
public class ColorSetting extends Setting<Integer> implements ColorRamp {

    private final boolean allowAlpha;
    private boolean gradientsAllowed = true;
    private boolean gradientMode;
    /** The gradient (also kept while the solid colour is shown); {@code null} until a gradient is first used. */
    private Gradient gradient;

    /** One time per rendered frame, so every read of a flowing gradient in a frame gives the same colour. */
    private static long frameMs = System.currentTimeMillis();

    /** Called once per frame by the renderer. */
    public static void tickFrame() {
        frameMs = System.currentTimeMillis();
    }

    public ColorSetting(String name, String description, int defaultArgb) {
        this(name, description, defaultArgb, true);
    }

    public ColorSetting(String name, String description, int defaultArgb, boolean allowAlpha) {
        super(name, description, defaultArgb);
        this.allowAlpha = allowAlpha;
    }

    /** Hides the Gradient mode (for colours that must stay one value, e.g. the theme palette). */
    public ColorSetting solidOnly() {
        gradientsAllowed = false;
        return this;
    }

    public boolean allowsAlpha() {
        return allowAlpha;
    }

    public boolean allowsGradient() {
        return gradientsAllowed;
    }

    @Override
    protected Integer sanitize(Integer candidate) {
        if (candidate == null) return getDefault();
        return allowAlpha ? candidate : (candidate | 0xFF000000);
    }

    // ---- reading -------------------------------------------------------------------------------------------------

    /**
     * The colour to use where only one colour fits: the solid colour, or the gradient's start (flowing: its current
     * colour). Use {@link #at(float)} where the colour can change along the element.
     */
    @Override
    public Integer get() {
        return isGradient() ? gradient.at(0, frameMs) : value;
    }

    /** The solid colour, also while a gradient is shown (the picker edits it in Solid mode). */
    public int solid() {
        return value;
    }

    @Override
    public int at(float t) {
        return isGradient() ? gradient.at(t, frameMs) : value;
    }

    @Override
    public boolean isUniform() {
        return !isGradient();
    }

    public boolean isGradient() {
        return gradientMode && gradientsAllowed;
    }

    /** The gradient (made from the solid colour the first time it's needed). */
    public Gradient gradient() {
        if (gradient == null) gradient = clean(Gradient.from(value));
        return gradient;
    }

    // ---- writing -------------------------------------------------------------------------------------------------

    /** Sets the solid colour and shows it (leaves Gradient mode). */
    public void setSolid(int argb) {
        boolean wasGradient = gradientMode;
        gradientMode = false;
        int before = value;
        set(argb);
        if (wasGradient && before == value) notifyChanged();
    }

    /** Sets the gradient and shows it (enters Gradient mode). Ignored for {@link #solidOnly()} colours. */
    public void setGradient(Gradient g) {
        if (g == null || !gradientsAllowed) return;
        Gradient c = clean(g);
        if (gradientMode && c.equals(gradient)) return;
        gradient = c;
        gradientMode = true;
        notifyChanged();
    }

    /** Switches between the solid colour and the gradient (both are kept). */
    public void setGradientMode(boolean on) {
        on &= gradientsAllowed;
        if (on == gradientMode) return;
        if (on) gradient();
        gradientMode = on;
        notifyChanged();
    }

    @Override
    public void reset() {
        boolean changed = gradientMode || gradient != null;
        gradientMode = false;
        gradient = null;
        int before = value;
        super.reset();
        if (changed && before == value) notifyChanged();
    }

    private Gradient clean(Gradient g) {
        return allowAlpha ? g : g.opaque();
    }

    // ---- text form (copy / paste) ---------------------------------------------------------------------------------

    public String toHex() {
        return String.format(Locale.ROOT, "#%08X", value);
    }

    /** The solid colour as the picker shows it: {@code #AARRGGBB} with alpha, else {@code #RRGGBB}. */
    public String solidCode() {
        return allowAlpha ? toHex() : String.format(Locale.ROOT, "#%06X", value & 0xFFFFFF);
    }

    /** What "Copy" puts on the clipboard: the gradient's code in Gradient mode, else the solid colour. */
    public String toCode() {
        return isGradient() ? gradient.toCode() : solidCode();
    }

    /**
     * Applies pasted text: two or more colours become a gradient (keeping the current flow), one colour becomes the
     * solid colour. Returns false (and changes nothing) if no colour was found.
     */
    public boolean applyCode(String raw) {
        if (raw == null) return false;
        if (gradientsAllowed) {
            Gradient g = Gradient.parse(raw, gradient == null ? 0 : gradient.flow());
            if (g != null) {
                setGradient(g);
                return true;
            }
        }
        Integer single = parseSingle(raw);
        if (single == null) return false;
        setSolid(single);
        return true;
    }

    /** One colour from text: {@code #RGB}, {@code RRGGBB}, {@code #AARRGGBB}, {@code 0x…} (first one found). */
    public static Integer parseSingle(String raw) {
        String t = raw.trim();
        if (t.startsWith("0x") || t.startsWith("0X")) t = t.substring(2);
        if (t.startsWith("#")) t = t.substring(1);
        StringBuilder b = new StringBuilder();
        for (char ch : t.toCharArray()) {
            if (Character.digit(ch, 16) >= 0) b.append(ch);
            else if (!b.isEmpty()) break;
        }
        int n = b.length();
        if (n != 3 && n != 6 && n != 8) return null;
        return Gradient.parseHex(b.toString());
    }

    // ---- JSON ----------------------------------------------------------------------------------------------------

    @Override
    public JsonElement toJson() {
        if (gradient == null) return new JsonPrimitive(toHex());
        JsonObject o = new JsonObject();
        o.addProperty("color", toHex());
        o.addProperty("mode", gradientMode ? "gradient" : "solid");
        o.addProperty("gradient", gradient.toCode());
        o.addProperty("flow", Gradient.FLOW_NAMES[gradient.flow()]);
        return o;
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element == null) return;
        if (element.isJsonPrimitive()) {
            Integer c = parseSingle(element.getAsString());
            if (c != null) setSolid(c);
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject o = element.getAsJsonObject();
        Integer c = o.has("color") && o.get("color").isJsonPrimitive() ? parseSingle(o.get("color").getAsString()) : null;
        if (c != null) set(c);
        int flow = 0;
        if (o.has("flow") && o.get("flow").isJsonPrimitive()) {
            String name = o.get("flow").getAsString();
            for (int i = 0; i < Gradient.FLOW_NAMES.length; i++) if (Gradient.FLOW_NAMES[i].equalsIgnoreCase(name)) flow = i;
        }
        Gradient g = o.has("gradient") && o.get("gradient").isJsonPrimitive()
                ? Gradient.parse(o.get("gradient").getAsString(), flow) : null;
        boolean wantGradient = o.has("mode") && o.get("mode").isJsonPrimitive()
                && "gradient".equalsIgnoreCase(o.get("mode").getAsString());
        if (g != null) {
            gradient = clean(g);
            gradientMode = wantGradient && gradientsAllowed;
            notifyChanged();
        } else {
            setGradientMode(false);
        }
    }
}
