package dev.telemetric.game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/** Plays UI sounds by id: vanilla {@code "entity.player.levelup"} or a resource pack's {@code "ns:path"}. */
public final class SoundAdapter {

    private SoundAdapter() {}

    /**
     * Plays a sound on the UI channel (not positional).
     *
     * @return false if the id is malformed or no loaded sound has it (then nothing plays)
     */
    public static boolean play(String id, float volume, float pitch) {
        Identifier sound = id == null ? null : Identifier.tryParse(id.trim());
        if (sound == null) return false;
        SoundManager manager = Minecraft.getInstance().getSoundManager();
        if (manager.getSoundEvent(sound) == null) return false;
        manager.play(SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(sound), pitch, volume));
        return true;
    }
}
