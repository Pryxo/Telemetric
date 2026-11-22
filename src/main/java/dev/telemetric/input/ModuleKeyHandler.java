package dev.telemetric.input;

import dev.telemetric.module.Module;
import dev.telemetric.module.ModuleManager;
import dev.telemetric.setting.Setting;
import dev.telemetric.setting.impl.KeybindSetting;

import java.util.HashSet;
import java.util.Set;

/**
 * Fires keys on their rising edge. It's polled once per client tick, and only while no screen is open, so typing in
 * chat never triggers anything.
 * <ul>
 *   <li>A module's {@code Bind} toggles the module.</li>
 *   <li>An <b>action key</b> ({@link KeybindSetting#onPress}) runs its action while its module is enabled
 *       (e.g. Hide Armor → Toggle Hiding).</li>
 * </ul>
 */
public final class ModuleKeyHandler {

    private final ModuleManager modules;
    private final Set<Integer> heldLastTick = new HashSet<>();

    public ModuleKeyHandler(ModuleManager modules) {
        this.modules = modules;
    }

    public void tick() {
        if (InputUtil.isScreenOpen()) {
            heldLastTick.clear();
            return;
        }
        Set<Integer> heldNow = new HashSet<>();
        for (Module module : modules.getModules()) {
            if (module.isToggleable() && module.getBind().isBound()) {
                if (pressed(module.getBind().get(), heldNow)) module.toggleByUser();
            }
            if (!module.isEnabled()) continue;
            for (Setting<?> s : module.getSettings()) {
                if (s instanceof KeybindSetting key && key.getPressAction() != null && key.isBound()
                        && pressed(key.get(), heldNow)) {
                    key.getPressAction().run();
                }
            }
        }
        heldLastTick.clear();
        heldLastTick.addAll(heldNow);
    }

    /** True on the tick {@code key} goes down. A key shared by several binds fires all of them. */
    private boolean pressed(int key, Set<Integer> heldNow) {
        boolean down = heldNow.contains(key) || InputUtil.isKeyDown(key);
        if (!down) return false;
        heldNow.add(key);
        return !heldLastTick.contains(key);
    }
}
