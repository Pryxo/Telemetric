package dev.telemetric.ui.theme;

import dev.telemetric.setting.impl.EnumSetting;

/** The full-screen dim behind the ClickGUI (Theme → Backdrop). Saved by enum name, never rename. */
public enum BackdropStyle implements EnumSetting.Nameable {
    /** The palette's backdrop colour, slightly darker at the bottom. */
    PLAIN("Plain"),
    /** Like Plain, but tinted with the accent colours. */
    TINTED("Accent Tint"),
    /** Accent tint plus darker screen edges. */
    VIGNETTE("Vignette");

    private final String displayName;

    BackdropStyle(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String displayName() {
        return displayName;
    }
}
