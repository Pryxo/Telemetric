package dev.telemetric.mixin;

import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

/**
 * MC ADAPTER (mixin) — exposes {@code BossHealthOverlay#events} (the boss bars on screen, by bar id).
 * Only used through {@link dev.telemetric.game.BossBarAdapter}.
 */
@Mixin(BossHealthOverlay.class)
public interface BossHealthOverlayAccessor {

    @Accessor("events")
    Map<UUID, LerpingBossEvent> telemetric$getEvents();
}
