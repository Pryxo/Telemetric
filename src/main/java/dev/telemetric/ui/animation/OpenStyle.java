package dev.telemetric.ui.animation;

import dev.telemetric.setting.impl.EnumSetting;

/** How the ClickGUI panels appear when it opens (Interface → Open Style). Saved by enum name, never rename. */
public enum OpenStyle implements EnumSetting.Nameable {
    SLIDE_UP("Slide Up"),
    DROP_IN("Drop In"),
    SLIDE_RIGHT("Slide Right"),
    ZOOM("Zoom"),
    FADE("Fade");

    private final String displayName;

    OpenStyle(String displayName) {
        this.displayName = displayName;
    }

    /** Drop In overshoots and settles; the rest ease out. */
    public Easing easing() {
        return this == DROP_IN ? Easing.OUT_BACK : Easing.OUT_CUBIC;
    }

    @Override
    public String displayName() {
        return displayName;
    }
}
