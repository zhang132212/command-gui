package com.remrin.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.remrin.client.config.CommandConfig;
import com.remrin.client.config.PresetConfig;
import com.remrin.client.config.SettingsConfig;
import com.remrin.client.gui.ChainedCommandExecutor;
import com.remrin.client.gui.CommandGUIScreen;
import com.remrin.client.gui.TimedTaskManager;
import com.remrin.client.machine.MachineNetworkManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.KeyMapping.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.PreparableReloadListener.SharedState;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side mod entry point. Responsible for initializing configs, registering the preset reload
 * listener, binding the hotkey, and driving per-tick logic (timed tasks and the delayed command
 * queue).
 */
public class CommandGUIClient implements ClientModInitializer {

  /**
   * Key binding category for this mod (shown in the Controls settings screen)
   */
  public static final Category CMD_GUI_CATEGORY = Category.register(
      Identifier.parse("command-gui:general"));
  /**
   * Key mapping to open/close the GUI, defaults to the C key
   */
  private static KeyMapping openGuiKey;

  @Override
  public void onInitializeClient() {
    // Load persistent custom command and settings configs
    CommandConfig.load();
    SettingsConfig.load();

    // Register the machine switch network layer (server sync receiver + action sender)
    MachineNetworkManager.init();

    // Register a resource reload listener: re-read preset JSON files after each resource pack load
    if (Minecraft.getInstance().getResourceManager() instanceof ReloadableResourceManager rrm) {
      rrm.registerReloadListener(new SimpleReloadListener<Void>() {
        @Override
        protected Void prepare(SharedState state) {
          return null;
        }

        @Override
        protected void apply(Void data, SharedState state) {
          PresetConfig.load();
        }
      });
    }

    openGuiKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
        "key.command-gui.open_gui",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_C,
        CMD_GUI_CATEGORY
    ));

    ClientTickEvents.END_CLIENT_TICK.register(client -> {
      // Toggle the GUI: close it if already open, otherwise open the main screen
      while (openGuiKey.consumeClick()) {
        if (client.gui.screen() instanceof CommandGUIScreen) {
          client.gui.setScreen(null);
        } else if (client.gui.screen() == null) {
          client.gui.setScreen(new CommandGUIScreen());
        }
      }

      // Advance timed tasks (countdown spawn/kill) and the delayed command queue every tick
      TimedTaskManager.tick();
      ChainedCommandExecutor.tickDelayed();
    });
  }
}
