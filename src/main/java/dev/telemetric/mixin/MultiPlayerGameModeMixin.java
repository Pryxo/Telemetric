package dev.telemetric.mixin;

import dev.telemetric.game.StashAdapter;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MC ADAPTER (mixin) — sees every container click the client sends (player's and ours) so the stash
 * overlay can follow the page arrows ({@link StashAdapter#onSlotClick}).
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {

    @Inject(method = "handleContainerInput", at = @At("HEAD"))
    private void telemetric$onContainerInput(int containerId, int slot, int button, ContainerInput input,
                                             Player player, CallbackInfo ci) {
        StashAdapter.onSlotClick(containerId, slot);
    }
}
