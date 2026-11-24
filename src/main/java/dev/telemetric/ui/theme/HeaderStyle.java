package dev.telemetric.ui.theme;

import dev.telemetric.setting.impl.EnumSetting;

/** How panel and Settings window headers are painted (Theme → Header Style). Saved by enum name, never rename. */
public enum HeaderStyle implements EnumSetting.Nameable {
    /** Dark header with a thin accent line under it (Header Gradient). */
    LINE("Line"),
    /** The whole header filled with the accent gradient, text in a contrasting colour. */
    FILLED("Accent Fill");

    private final String displayName;

    HeaderStyle(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String displayName() {
        return displayName;
    }
}
