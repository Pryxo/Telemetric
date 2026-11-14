package dev.telemetric.devbuild.mixin;

import dev.telemetric.devbuild.PacketLogAdapter;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DEV MC ADAPTER (mixin) — every packet the client receives, before it is handled, goes to
 * {@link PacketLogAdapter#onReceive}. Runs on the Netty thread; the adapter only queues a line.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"))
    private void telemetricDev$onReceive(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        PacketLogAdapter.onReceive(packet);
    }
}
