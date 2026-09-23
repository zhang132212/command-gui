package qa.system.mixin;

import java.util.Map;
import com.remrin.server.MachineScheduler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qa.system.QaTrace;

@Mixin(value=MachineScheduler.class, remap=false)
public class ExecutionTrace {
    @Inject(method="recordExec", at=@At("HEAD"))
    private static void executed(String key, String command, CallbackInfo ci) {
        QaTrace.log("scheduler.execute", Map.of("runtime", key, "command", command));
    }
}
