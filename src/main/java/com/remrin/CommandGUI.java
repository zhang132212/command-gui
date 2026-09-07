package com.remrin;

import com.remrin.server.MachineMod;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommandGUI implements ModInitializer {
   public static final String MOD_ID = "command-gui";
   public static final Logger LOGGER = LoggerFactory.getLogger("command-gui");

   public void onInitialize() {
      LOGGER.info("Command-GUI initialized!");
      if (!FabricLoader.getInstance().isModLoaded("command-gui-server")) {
         ServerLifecycleEvents.SERVER_STARTING.register(server -> MachineMod.init());
      }
   }
}
