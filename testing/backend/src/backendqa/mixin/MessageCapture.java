package backendqa.mixin;

import backendqa.Hooks;
import java.util.ArrayList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public class MessageCapture {
   @Inject(method="sendSystemMessage(Lnet/minecraft/network/chat/Component;)V",at=@At("HEAD"))
   private void message(Component message,CallbackInfo ci) {
      ServerPlayer p=(ServerPlayer)(Object)this;
      if(Hooks.testPlayer(p)) Hooks.messages.computeIfAbsent(p.getGameProfile().name(),k->new ArrayList<>()).add(message.getString());
   }
}
