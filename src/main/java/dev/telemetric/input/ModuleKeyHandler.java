package dev.telemetric.input;

import dev.telemetric.module.Module;
import dev.telemetric.module.ModuleManager;

import java.util.HashSet;
import java.util.Set;

/**
 * Toggles modules on the rising edge of their bound key. Polled once per client tick and only while
 * no screen is open, so typing in chat never toggles modules.
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
            if (!module.isToggleable() || !module.getBind().isBound()) continue;
            int key = module.getBind().get();
            boolean down = heldNow.contains(key) || InputUtil.isKeyDown(key);
            if (!down) continue;
            heldNow.add(key);
            if (!heldLastTick.contains(key)) module.toggle();
        }
        heldLastTick.clear();
        heldLastTick.addAll(heldNow);
    }
}
