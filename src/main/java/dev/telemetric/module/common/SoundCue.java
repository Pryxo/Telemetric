package dev.telemetric.module.common;

import dev.telemetric.game.SoundAdapter;
import dev.telemetric.setting.Setting;
import dev.telemetric.setting.SettingBundle;
import dev.telemetric.setting.impl.ActionSetting;
import dev.telemetric.setting.impl.GroupToggleSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.setting.impl.TextSetting;
import dev.telemetric.ui.notification.Toasts;

import java.util.List;

/**
 * Reusable "play a sound" settings: a group toggle with the sound id, volume, pitch and a Test button.
 * <pre>{@code private final SoundCue sound = addBundle(new SoundCue("Ready Sound", "When the cooldown ends", "entity.player.levelup"));
 * ...
 * sound.play(); // no-op while the toggle is off}</pre>
 * Config keys are {@code <name>}, {@code <name> Id}, {@code <name> Volume} and {@code <name> Pitch}.
 */
public final class SoundCue implements SettingBundle {

    private final GroupToggleSetting toggle;
    private final TextSetting id;
    private final NumberSetting volume;
    private final NumberSetting pitch;
    private final ActionSetting test;

    public SoundCue(String name, String when, String defaultSound) {
        toggle = new GroupToggleSetting(name, "Play a sound " + when.toLowerCase(java.util.Locale.ROOT)
                + " (right-click: pick the sound)", true);
        id = toggle.child(new TextSetting(name + " Id",
                "Sound id, e.g. entity.player.levelup or a resource pack's namespace:path", defaultSound, 96)
                .withLabel("Sound"));
        volume = toggle.child(new NumberSetting(name + " Volume", "Loudness", 1.0, 0.05, 1.0, 0.05)
                .withLabel("Volume"));
        pitch = toggle.child(new NumberSetting(name + " Pitch", "Higher = brighter", 1.0, 0.5, 2.0, 0.05)
                .withLabel("Pitch"));
        test = toggle.child(new ActionSetting(name + " Test", "Play the sound now", this::test)
                .withLabel("Test Sound"));
    }

    @Override
    public List<Setting<?>> settings() {
        return List.of(toggle, id, volume, pitch, test);
    }

    public boolean isEnabled() {
        return toggle.get();
    }

    /** Plays the sound if the toggle is on. */
    public void play() {
        if (toggle.get()) SoundAdapter.play(id.get(), volume.getFloat(), pitch.getFloat());
    }

    private void test() {
        if (!SoundAdapter.play(id.get(), volume.getFloat(), pitch.getFloat())) {
            Toasts.error("Unknown sound: " + id.get());
        }
    }
}
