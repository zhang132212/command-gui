package qa;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screens.TitleScreen;

public final class ClientRegressionsQa implements ClientModInitializer {
   private boolean complete;
   private int ticks;

   @Override
   public void onInitializeClient() {
      ClientTickEvents.END_CLIENT_TICK.register(mc -> {
         if (complete) return;
         try {
            if (++ticks > 2400) throw new AssertionError("client regression startup timeout");
            if (mc.gui.overlay() != null || !(mc.gui.screen() instanceof TitleScreen) || ticks < 40) return;
            complete = true;
            ClientRegressionChecks.run(mc);
            mc.stop();
         } catch (Throwable failure) {
            complete = true;
            failure.printStackTrace();
            System.out.println("CLIENT_REGRESSIONS_FAILED");
            mc.stop();
         }
      });
   }
}
