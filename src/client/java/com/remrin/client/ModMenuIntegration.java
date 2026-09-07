package com.remrin.client;

import com.remrin.client.gui.SettingsScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class ModMenuIntegration implements ModMenuApi {
   public ConfigScreenFactory<?> getModConfigScreenFactory() {
      // 模组菜单入口给「模组设置」，而不是需要联机的主界面。
      // 原先返回 new CommandGUIScreen()，在标题界面切到假人控制页会发网络包导致崩溃
      // （IllegalStateException: Cannot send packets when not in game）。
      // 游戏内仍由快捷键打开 CommandGUIScreen，不受影响。
      return parent -> new SettingsScreen(parent);
   }
}
