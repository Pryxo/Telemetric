package dev.telemetric.mixin;

import dev.telemetric.game.CombatAdapter;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.game.ServerInfoAdapter;
import dev.telemetric.game.StashAdapter;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundCooldownPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands server particle packets to {@link DungeonAdapter#onParticles} (Telos jump pads only show
 * up as a yellow dust fountain) and {@link CombatAdapter#onParticles} (Assassin stacks), and item cooldown packets to
 * {@link CombatAdapter#onCooldownPacket}, and world-time packets to {@link ServerInfoAdapter#onTimePacket} (TPS estimate). Injected right after {@code ensureRunningOnSameThread}, so on the network
 * thread that call throws (and re-queues the packet), so this only ever runs on the client thread.
 * <p>
 * Container content and slot packets go to {@link StashAdapter#onContainerPacket} at {@code RETURN}, after the menu
 * was updated (the network-thread pass throws before it gets there).
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Inject(method = "handleParticleEvent", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
            shift = At.Shift.AFTER))
    private void telemetric$onParticles(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        DungeonAdapter.onParticles(packet.getParticle(), packet.getX(), packet.getY(), packet.getZ());
        CombatAdapter.onParticles(packet.getParticle(), packet.getX(), packet.getY(), packet.getZ());
    }

    @Inject(method = "handleItemCooldown", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
            shift = At.Shift.AFTER))
    private void telemetric$onItemCooldown(ClientboundCooldownPacket packet, CallbackInfo ci) {
        CombatAdapter.onCooldownPacket(packet.cooldownGroup(), packet.duration());
    }

    @Inject(method = "handleSetTime", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
            shift = At.Shift.AFTER))
    private void telemetric$onSetTime(ClientboundSetTimePacket packet, CallbackInfo ci) {
        ServerInfoAdapter.onTimePacket();
    }

    @Inject(method = "handleContainerContent", at = @At("RETURN"))
    private void telemetric$onContainerContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        StashAdapter.onContainerPacket(packet.containerId());
    }

    @Inject(method = "handleContainerSetSlot", at = @At("RETURN"))
    private void telemetric$onContainerSetSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        StashAdapter.onContainerPacket(packet.getContainerId());
    }
}
