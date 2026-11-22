package dev.telemetric.module.impl.visual;

import dev.telemetric.data.TelosData;
import dev.telemetric.data.TelosData.Bag;
import dev.telemetric.game.CustomBagAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.ActionSetting;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ModeSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.notification.Toasts;

import java.util.List;

/**
 * Your own picture (PNG / JPG / animated GIF) and drop sound (OGG / WAV) for each loot bag. Pick the bag, and
 * "Set Picture…" / "Set Sound…" open a file picker. The files get copied to {@code config/telemetric/custom/bags/}.
 * The bag types come from the bags in {@code data/tables/rarities.json}.
 */
public final class CustomBagsModule extends Module {

    private final ModeSetting bag = add(new ModeSetting("Bag", "Which bag you are customising", "Royal",
            () -> TelosData.BAGS.get().labels()));
    private final NumberSetting volume = add(new NumberSetting("Sound Volume", "Loudness of custom drop sounds",
            100, 5, 100, 5).withSuffix("%"));
    private final BooleanSetting showBanner = add(new BooleanSetting("Show Banner",
            "Show the bag's name banner above your picture", true));
    private final NumberSetting size = add(new NumberSetting("Picture Size",
            "How big the drop pop is while it shows your picture", 100, 25, 300, 5).withSuffix("%"));
    private final NumberSetting thickness = add(new NumberSetting("Picture Thickness",
            "How deep (3D) your picture is. Lower = flatter", 25, 0, 100, 5).withSuffix("%"));
    private final NumberSetting animSpeed = add(new NumberSetting("Animation Speed",
            "How fast the drop pop plays while it shows your picture. Lower = it stays on screen longer (the GIF itself keeps its speed)",
            100, 5, 100, 5).withSuffix("%"));
    private final ModeSetting shiny = add(new ModeSetting("Shiny Effect",
            "Animated rainbow tint, outline and glint on your picture, like the Telos shiny pouch",
            "Shiny Bag", "Off", "Shiny Bag", "All Bags"));
    private final NumberSetting shinyStrength = add(new NumberSetting("Shiny Strength",
            "How strongly the shiny effect colours your picture", 60, 10, 100, 5).withSuffix("%")
            .visibleWhen(() -> !shiny.get().equals("Off")));
    private final NumberSetting maxLength = add(new NumberSetting("Max Sound Length",
            "Custom sounds are cut off (with a short fade) after this long", 6, 1, 30, 1).withSuffix(" s"));
    private final BooleanSetting stopOnWorldChange = add(new BooleanSetting("Stop On World Change",
            "Stop the custom sound when you change world or dimension. Off = it keeps playing", true));

    public CustomBagsModule() {
        super("Custom Bags", "Your own picture and drop sound for loot bags", Category.INVENTORY);
        add(new ActionSetting("Set Picture", "Pick a PNG, JPG or GIF (max 15 MB) for this bag",
                () -> withBag(b -> CustomBagAdapter.pickImage(b, err -> report(err, "Picture set for " + b.label())))));
        add(new ActionSetting("Clear Picture", "Back to the normal bag picture",
                () -> withBag(b -> Toasts.info(CustomBagAdapter.clear(b, true) ? "Picture removed" : "No custom picture"))));
        add(new ActionSetting("Set Sound", "Pick an OGG or WAV file (max 15 MB, cut at Max Sound Length) for this bag",
                () -> withBag(b -> CustomBagAdapter.pickSound(b, err -> report(err, "Sound set for " + b.label())))));
        add(new ActionSetting("Clear Sound", "Back to the normal drop sound",
                () -> withBag(b -> Toasts.info(CustomBagAdapter.clear(b, false) ? "Sound removed" : "No custom sound"))));
        add(new ActionSetting("Preview", "Show this bag's drop with your picture and sound", this::preview));
        syncOn(() -> CustomBagAdapter.setSettings(isEnabled()
                ? new CustomBagAdapter.Settings(volume.getFloat() / 100f, showBanner.get(), size.getFloat() / 100f,
                        maxLength.getFloat(), stopOnWorldChange.get(), shinyMode(), shinyStrength.getFloat() / 100f,
                        animSpeed.getFloat() / 100f, thickness.getFloat() / 100f) : null),
                volume, showBanner, size, thickness, animSpeed, maxLength, stopOnWorldChange, shiny, shinyStrength);
    }

    @Override
    public String getWarning() {
        return "Custom bag pictures and sounds change how loot bags look and sound on your screen.\n\n"
                        + "Using custom bag textures is a valid reason for a reimbursement to be denied.\n\n"
                        + "Enable Custom Bags anyway?";
    }

    private CustomBagAdapter.Shiny shinyMode() {
        return switch (shiny.get()) {
            case "Off" -> CustomBagAdapter.Shiny.OFF;
            case "All Bags" -> CustomBagAdapter.Shiny.ALL_BAGS;
            default -> CustomBagAdapter.Shiny.SHINY_BAG;
        };
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
