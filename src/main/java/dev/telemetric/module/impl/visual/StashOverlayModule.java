package dev.telemetric.module.impl.visual;

import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.stash.StashSession;

/**
 * Replaces the Telos stash screen with a grid of every saved stash page plus the player's inventory. Pages are saved
 * as you see them (or all at once with Scan). Clicking a page walks the stash there using its own arrows, items can be
 * moved like in a normal container (on other pages too), and pages can be renamed.
 * Normal and seasonal pages are kept apart, with a toggle in the overlay header. The logic is in {@link StashSession},
 * the drawing in {@code ui/stash/StashOverlay}, and game access in {@code game/StashAdapter}.
 */
public final class StashOverlayModule extends Module {

    private final NumberSetting columns = add(new NumberSetting("Columns", "Pages per row", 3, 1, 6, 1));
    private final NumberSetting rows = add(new NumberSetting("Rows", "Rows of pages shown before it scrolls", 3, 1, 6, 1));
    private final NumberSetting maxScale = add(new NumberSetting("Max Scale",
            "Largest size of the overlay (it shrinks to fit the screen)", 1.0, 0.3, 1.5, 0.05));
    private final BooleanSetting syncOnOpen = add(new BooleanSetting("Sync On Open",
            "Jump to page 1 when the stash opens, so the current page is known", true));
    private final BooleanSetting autoScan = add(new BooleanSetting("Auto Scan",
            "Flip through every page once when the page count is not known yet (first open per mode)", true));
    private final NumberSetting pageDelay = add(new NumberSetting("Page Delay",
            "Minimum ms between two page turns (scan, walking to a page). Shift + click Scan benchmarks the fastest pace",
            100, StashSession.MIN_PAGE_DELAY_MS, StashSession.MAX_PAGE_DELAY_MS, 10));
    private final BooleanSetting tooltips = add(new BooleanSetting("Item Tooltips",
            "Show the item tooltip when hovering an item in a saved page", true));

    public StashOverlayModule() {
        super("Stash Overlay", "Replaces the stash with all saved pages + your inventory; right-click a page name to rename it",
                Category.HUD);
    }

    @Override
    public void onTick() {
        StashSession.get().tick(syncOnOpen.get(), autoScan.get(), pageDelay.getInt());
    }

    @Override
    public void onDisable() {
        StashSession.get().flush();
    }

    public int columns() { return columns.getInt(); }

    public int rows() { return rows.getInt(); }

    public float maxScale() { return maxScale.getFloat(); }

    public boolean tooltips() { return tooltips.get(); }
}
