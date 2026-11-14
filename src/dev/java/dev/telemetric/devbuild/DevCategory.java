package dev.telemetric.devbuild;

import dev.telemetric.module.Category;

/** Dev-build-only categories. */
public final class DevCategory {

    /** Developer tools — right of Misc. Its panel always uses {@link DevPalette#RED_BLACK}. */
    public static final Category DEV = Category.register("Dev", 1010);

    private DevCategory() {
    }
}
