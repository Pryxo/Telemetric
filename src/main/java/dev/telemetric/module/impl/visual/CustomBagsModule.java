package dev.telemetric.module.impl.visual;

import dev.telemetric.data.TelosData;
import dev.telemetric.data.TelosData.Bag;
import dev.telemetric.game.CustomBagAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.ActionSetting;
import dev.telemetric.setting.impl.ModeSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.notification.Toasts;

import java.util.List;

/**
 * Your own picture (PNG / JPG / animated GIF) and drop sound (OGG / WAV) for each loot bag. Pick the bag, then
 * "Set Picture…" / "Set Sound…" open a file picker; the files are copied to {@code config/telemetric/custom/bags/}.
 * Bag types come from the bags in {@code data/tables/rarities.json}.
 */
public final class CustomBagsModule extends Module {

    private final ModeSetting bag = add(new ModeSetting("Bag", "Which bag you are customising", "Royal",
            () -> TelosData.BAGS.get().labels()));
    private final NumberSetting volume = add(new NumberSetting("Sound Volume", "Loudness of custom drop sounds",
            100, 5, 100, 5).withSuffix("%"));

    public CustomBagsModule() {
        super("Custom Bags", "Your own picture and drop sound for loot bags", Category.TEXTURE_PACK);
        add(new ActionSetting("Set Picture", "Pick a PNG, JPG or GIF (max 15 MB) for this bag",
                () -> withBag(b -> CustomBagAdapter.pickImage(b, err -> report(err, "Picture set for " + b.label())))));
        add(new ActionSetting("Clear Picture", "Back to the normal bag picture",
                () -> withBag(b -> Toasts.info(CustomBagAdapter.clear(b, true) ? "Picture removed" : "No custom picture"))));
        add(new ActionSetting("Set Sound", "Pick an OGG or WAV file (max 15 MB, plays up to 6 s) for this bag",
                () -> withBag(b -> CustomBagAdapter.pickSound(b, err -> report(err, "Sound set for " + b.label())))));
        add(new ActionSetting("Clear Sound", "Back to the normal drop sound",
                () -> withBag(b -> Toasts.info(CustomBagAdapter.clear(b, false) ? "Sound removed" : "No custom sound"))));
        add(new ActionSetting("Preview", "Show this bag's drop with your picture and sound", this::preview));
        syncOn(() -> CustomBagAdapter.setSettings(isEnabled()
                ? new CustomBagAdapter.Settings(volume.getFloat() / 100f) : null), volume);
    }

    private void withBag(java.util.function.Consumer<Bag> action) {
        Bag b = TelosData.BAGS.get().byLabel(bag.get());
        if (b == null) {
            List<Bag> all = TelosData.BAGS.get().list();
            if (all.isEmpty()) {
                Toasts.error("No bag list loaded");
                return;
            }
            b = all.getFirst();
        }
        action.accept(b);
    }

    private static void report(String error, String success) {
        if (error == null) Toasts.success(success);
        else Toasts.error(error);
    }

    private void preview() {
        if (!isEnabled()) {
            Toasts.info("Turn Custom Bags on first");
            return;
        }
        withBag(b -> {
            if (CustomBagAdapter.isProcessing(b.key())) Toasts.info("Still loading the picture…");
            else if (!CustomBagAdapter.hasImage(b.key()) && !CustomBagAdapter.hasSound(b.key()))
                Toasts.info("No picture or sound set for " + b.label());
            CustomBagAdapter.preview(b);
        });
    }
}
