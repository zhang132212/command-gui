package qa.system.mixin;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qa.system.QaTrace;

/** Observation only: never cancels, changes, delays, or fabricates packets. */
@Mixin(Connection.class)
public class PacketTrace {
    @Inject(method="send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at=@At("HEAD"))
    private void sent(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        QaTrace.packet("send", (Connection)(Object)this, packet);
    }
    @Inject(method="channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at=@At("HEAD"))
    private void received(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        QaTrace.packet("receive", (Connection)(Object)this, packet);
    }
}
