package com.remrin.client;

import com.mojang.blaze3d.platform.InputConstants.Type;
import com.remrin.client.config.CommandConfig;
import com.remrin.client.config.PresetConfig;
import com.remrin.client.config.SettingsConfig;
import com.remrin.client.gui.ChainedCommandExecutor;
import com.remrin.client.gui.GuiTuning;
import com.remrin.client.gui.CommandGUIScreen;
import com.remrin.client.gui.CommandHelper;
import com.remrin.client.gui.CommandShortcut;
import com.remrin.client.gui.TimedTaskManager;
import com.remrin.client.machine.MachineNetworkManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.EndTick;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping.Category;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.PreparableReloadListener.SharedState;

public class CommandGUIClient implements ClientModInitializer {
   public static final Category CMD_GUI_CATEGORY = Category.register(Identifier.parse("command-gui:general"));
   private static KeyMapping openGuiKey;
   private static final Map<String, Boolean> shortcutPressedState = new HashMap<>();

   public void onInitializeClient() {
      CommandConfig.load();
      SettingsConfig.load();
      GuiTuning.load();
      MachineNetworkManager.init();
      if (Minecraft.getInstance().getResourceManager() instanceof ReloadableResourceManager rrm) {
         rrm.registerReloadListener(new SimpleReloadListener<Void>() {
            protected Void prepare(SharedState state) {
               return null;
            }

            protected void apply(Void data, SharedState state) {
               PresetConfig.load();
            }
         });
      }

      openGuiKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.command-gui.open_gui", Type.KEYSYM, 67, CMD_GUI_CATEGORY));
      ClientTickEvents.END_CLIENT_TICK.register((EndTick)client -> {
         while (openGuiKey.consumeClick()) {
            if (client.gui.screen() instanceof CommandGUIScreen) {
               client.gui.setScreen(null);
            } else if (client.gui.screen() == null) {
               client.gui.setScreen(new CommandGUIScreen());
            }
         }

         tickCommandShortcuts(client);
         TimedTaskManager.tick();
         ChainedCommandExecutor.tickDelayed();
      });
   }

   private static void tickCommandShortcuts(Minecraft client) {
      if (client == null || client.player == null || client.gui.screen() != null || client.getWindow() == null) {
         return;
      }

      long window = client.getWindow().handle();
      if (window == 0L) {
         return;
      }

      for (CommandConfig.Category category : CommandConfig.getCategories()) {
         for (Entry<String, CommandConfig.CommandEntry> entry : category.commands.entrySet()) {
            String shortcut = entry.getValue().shortcut;
            if (shortcut == null || shortcut.isBlank()) {
               shortcutPressedState.remove(shortcut);
               continue;
            }

            boolean pressed = CommandShortcut.isPressed(shortcut, window);
            boolean previous = shortcutPressedState.getOrDefault(shortcut, false);
            shortcutPressedState.put(shortcut, pressed);
            if (pressed && !previous) {
               executeShortcutCommand(entry.getValue());
            }
         }
      }

      for (Map<String, Object> custom : SettingsConfig.getCustomFakePlayerCommandList()) {
         String shortcut = custom.get("shortcut") instanceof String text ? text : "";
         if (shortcut == null || shortcut.isBlank()) {
            shortcutPressedState.remove(shortcut);
            continue;
         }

         boolean pressed = CommandShortcut.isPressed(shortcut, window);
         boolean previous = shortcutPressedState.getOrDefault(shortcut, false);
         shortcutPressedState.put(shortcut, pressed);
         if (pressed && !previous) {
            executeCustomShortcutCommand(custom);
         }
      }
   }

   private static void executeShortcutCommand(CommandConfig.CommandEntry entry) {
      List<String> commands = entry.getCommands();
      if (commands.isEmpty()) {
         return;
      }

      if (commands.size() == 1) {
         ChainedCommandExecutor.execute(null, commands.get(0));
      } else {
         ChainedCommandExecutor.executeMulti(null, commands, Math.max(1, entry.commandDelay));
      }
   }

   private static void executeCustomShortcutCommand(Map<String, Object> entry) {
      Object rawCommands = entry.get("commands");
      if (!(rawCommands instanceof List<?> rawList)) {
         return;
      }

      List<String> commands = new ArrayList<>();
      for (Object item : rawList) {
         if (item instanceof String command && !command.isBlank()) {
            commands.add(command.startsWith("/") ? command : "/" + command);
         }
      }

      if (commands.isEmpty()) {
         return;
      }

      Object delayObj = entry.get("commandDelay");
      int delay = delayObj instanceof Number number ? number.intValue() : 1;
      ChainedCommandExecutor.executeMulti(null, commands, Math.max(1, delay));
   }
}
