package dev.telemetric.module.impl.combat;

import dev.telemetric.module.common.SoundCue;
import dev.telemetric.setting.Setting;
import dev.telemetric.setting.SettingBundle;
import dev.telemetric.setting.impl.ActionSetting;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.GroupToggleSetting;
import dev.telemetric.setting.impl.ModeSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.setting.impl.TextSetting;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * One tab of the Ready Alerts Settings window: a top-level group toggle (the tab, its switch in the tab header) with
 * the popup's text, colour and time, a Test button and a Ready Sound. Used for the fixed slot tabs (Main Hand, Off
 * Hand, Helmet, …) and for the custom item tabs of {@link ReadyAlertsHud}.
 * <p>
 * Config keys all start with {@code prefix} ("Main Hand Alert", "Main Hand Text", …). The text can use {@code {item}}
 * (item name) and {@code {slot}} (Main Hand / Off Hand / Helmet / …).
 */
final class ReadyAlertTab implements SettingBundle {

    /** The default popup colour (also used for the HUD editor sample in {@link ReadyAlertsHud}). */
    static final int DEFAULT_COLOR = 0xFF7CFFB2;
    /** Position options: the main Ready Alerts element, or "Separate N" = {@link ReadyAlertGroupHud} number N. */
    static final String MAIN = "Main", SEPARATE = "Separate ";
    static final int SEPARATE_COUNT = 3;

    private final GroupToggleSetting tab;
    private final TextSetting text;
    private final ColorSetting color;
    private final NumberSetting seconds;
    private final ModeSetting position;
    private final ActionSetting test;
    private final SoundCue sound;
    private final List<Setting<?>> extra = new ArrayList<>();

    /**
     * @param prefix     config key prefix, unique per tab
     * @param tab        the tab's group toggle (its label is the tab name)
     * @param testItem   item name used by the Test button
     */
    ReadyAlertTab(String prefix, GroupToggleSetting tab, String defaultText, String defaultSound,
                  Supplier<String> testItem, Supplier<String> testSlot) {
        this.tab = tab;
        text = tab.child(new TextSetting(prefix + " Text", "Popup text. {item} = item name, {slot} = Main Hand / "
                + "Off Hand / Helmet / Chestplate / Leggings / Boots", defaultText, 48).withLabel("Text"));
        color = tab.child(new ColorSetting(prefix + " Color", "Colour of the popup text", DEFAULT_COLOR, false)
                .withLabel("Color"));
        seconds = tab.child(new NumberSetting(prefix + " Time", "How long the popup stays", 3.0, 0.5, 10.0, 0.5)
                .withSuffix("s").withLabel("Time"));
        String[] options = new String[SEPARATE_COUNT + 1];
        options[0] = MAIN;
        for (int i = 1; i <= SEPARATE_COUNT; i++) options[i] = SEPARATE + (i + 1);
        position = tab.child(new ModeSetting(prefix + " Position", "Where this popup shows: in the main Ready Alerts "
                + "element, or in a separate one (Ready Alerts 2, 3, …) you can move on its own in the HUD editor",
                MAIN, options).withLabel("Position"));
        test = tab.child(new ActionSetting(prefix + " Test", "Show this popup and play its sound now",
                () -> show(testItem.get(), testSlot.get())).withLabel("Test Alert"));
        sound = new SoundCue(prefix + " Sound", "When the cooldown ends", defaultSound);
        tab.child(sound.settings().getFirst()); // the sound's own group toggle sits inside the tab
    }

    /** Adds a tab-specific setting (already a child of the tab) after the standard ones. */
    <S extends Setting<?>> S extra(S setting) {
        extra.add(setting);
        return setting;
    }

    GroupToggleSetting tab() {
        return tab;
    }

    boolean isOn() {
        return tab.get();
    }

    String position() {
        return position.get();
    }

    @Override
    public List<Setting<?>> settings() {
        List<Setting<?>> list = new ArrayList<>(List.of(tab, text, color, seconds, position, test));
        list.addAll(sound.settings());
        list.addAll(extra);
        return list;
    }

    /** Sets every value of this tab back to its default (used when a custom tab is removed). */
    void reset() {
        for (Setting<?> s : settings()) if (s.isPersistent()) s.reset();
    }

    /** Shows the popup and plays the sound if this tab is switched on. */
    void fire(String itemName, String slotLabel) {
        if (tab.get()) show(itemName, slotLabel);
    }

    private void show(String itemName, String slotLabel) {
        String shown = text.get().replace("{item}", itemName == null ? "" : itemName)
                .replace("{slot}", slotLabel == null ? "" : slotLabel).trim();
        ReadyAlertsHud.get().display(position.get()).post(shown, color, Math.round(seconds.get() * 1000));
        sound.play();
    }
}
