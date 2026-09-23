package qa.system.mixin;

import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qa.system.QaTrace;

@Mixin(ServerPlayer.class)
public class ServerMessages {
    @Inject(method="sendSystemMessage(Lnet/minecraft/network/chat/Component;)V", at=@At("HEAD"))
    private void message(Component message, CallbackInfo ci) {
        QaTrace.log("server.message", Map.of("player", ((ServerPlayer)(Object)this).getGameProfile().name(), "text", message.getString()));
    }
}
