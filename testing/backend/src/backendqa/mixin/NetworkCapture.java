package backendqa.mixin;

import backendqa.Hooks;
import java.util.ArrayList;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** Only replaces the transport for QAB_ fixtures; real registered business handlers execute unchanged. */
@Mixin(value=ServerPlayNetworking.class,remap=false)
public class NetworkCapture {
   @Inject(method="registerGlobalReceiver",at=@At("HEAD"))
   private static void register(CustomPacketPayload.Type<?> type,ServerPlayNetworking.PlayPayloadHandler<?> handler,CallbackInfoReturnable<Boolean> ci) {
      if(Boolean.getBoolean("commandgui.backendTest")) Hooks.receivers.put(type.id().toString(),handler);
   }
   @Inject(method="canSend(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$Type;)Z",at=@At("HEAD"),cancellable=true)
   private static void canSend(ServerPlayer player,CustomPacketPayload.Type<?> type,CallbackInfoReturnable<Boolean> ci) {
      if(Hooks.testPlayer(player)) ci.setReturnValue(true);
   }
   @Inject(method="send",at=@At("HEAD"),cancellable=true)
   private static void send(ServerPlayer player,CustomPacketPayload payload,CallbackInfo ci) {
      if(Hooks.testPlayer(player)) { Hooks.packets.computeIfAbsent(player.getGameProfile().name(),k->new ArrayList<>()).add(payload);ci.cancel(); }
   }
}
