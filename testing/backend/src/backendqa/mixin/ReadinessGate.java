package backendqa.mixin;
import backendqa.Hooks;
import com.remrin.server.FakePlayerStateTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=FakePlayerStateTracker.class,remap=false)
public class ReadinessGate {
   @Inject(method="isReady",at=@At("HEAD"),cancellable=true)
   private static void gate(MinecraftServer server,ServerPlayer player,CallbackInfoReturnable<Boolean> ci){
      if(Hooks.blockReadiness && player!=null && player.getGameProfile().name().equals("QAB_Late"))ci.setReturnValue(false);
   }
}
