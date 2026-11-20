package dev.telemetric.module.impl.combat;

import dev.telemetric.module.common.SoundCue;
import dev.telemetric.setting.Setting;
import dev.telemetric.setting.SettingBundle;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.GroupToggleSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.setting.impl.TextSetting;

import java.util.ArrayList;
import java.util.List;

/**
 * "Cooldown over" feedback shared by every cooldown feature: a <b>Ready Alert</b> group toggle (text, colour, time,
 * shown by {@link ReadyAlertsHud}) and a <b>Ready Sound</b> ({@link SoundCue}).
 * The alert text can use {@code {item}} (item name) and {@code {slot}} (Weapon / Ability / Helmet / …).
 */
final class ReadyCue implements SettingBundle {

    /** The default popup colour (also used for the HUD editor sample in {@link ReadyAlertsHud}). */
    static final int DEFAULT_COLOR = 0xFF7CFFB2;

    private final GroupToggleSetting alert = new GroupToggleSetting("Ready Alert",
            "Show a popup when the cooldown ends (right-click: text, colour, time; position: Combat → Ready Alerts)", true);
    private final TextSetting text;
    private final ColorSetting color;
    private final NumberSetting seconds;
    private final SoundCue sound;

    ReadyCue(String defaultText, String defaultSound) {
        text = alert.child(new TextSetting("Alert Text", "Popup text. {item} = item name, {slot} = Weapon / Ability / "
                + "Helmet / Chestplate / Leggings / Boots", defaultText, 48).withLabel("Text"));
        color = alert.child(new ColorSetting("Alert Color", "Colour of the popup text", DEFAULT_COLOR, false)
                .withLabel("Color"));
        seconds = alert.child(new NumberSetting("Alert Time", "How long the popup stays", 3.0, 0.5, 10.0, 0.5)
                .withSuffix("s").withLabel("Time"));
        sound = new SoundCue("Ready Sound", "When the cooldown ends", defaultSound);
    }

    @Override
    public List<Setting<?>> settings() {
        List<Setting<?>> list = new ArrayList<>(List.of(alert, text, color, seconds));
        list.addAll(sound.settings());
        return list;
    }

    /** Shows the alert and plays the sound, each only if it's switched on. */
    void fire(String itemName, String slotLabel) {
        if (alert.get()) {
            String shown = text.get().replace("{item}", itemName == null ? "" : itemName)
                    .replace("{slot}", slotLabel == null ? "" : slotLabel).trim();
            ReadyAlertsHud.get().post(shown, color.get(), Math.round(seconds.get() * 1000));
        }
        sound.play();
    }
}
