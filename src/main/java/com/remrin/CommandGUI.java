package com.remrin;

import com.remrin.server.MachineMod;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommandGUI implements ModInitializer {
   public static final String MOD_ID = "command-gui";
   public static final Logger LOGGER = LoggerFactory.getLogger("command-gui");

   public void onInitialize() {
      LOGGER.info("Command-GUI initialized!");
      com.remrin.rules.CarpetRuleService.init();
      if (FabricLoader.getInstance().isModLoaded("command-gui-server")) {
         // 迁移守卫：旧版把服务端拆成独立 jar（command-gui-server）。两个 jar 现在都带同一套
         // com.remrin.server.* 类，同时安装会出现重复类与版本错配，所以跳过内嵌初始化，
         // 避免命令/事件重复注册，并提示移除旧 jar。
         LOGGER.warn("[Command-GUI] 检测到旧版独立服务端 mod command-gui-server：已跳过内置服务端初始化，请从 mods/ 移除该 jar。");
      } else {
         // 统一 jar 的服务端分支：专用服务端、以及客户端的集成服务端（单人/局域网主机）都走这里；必须在模组初始化阶段完成，
         // 不能推迟到 ServerLifecycleEvents.SERVER_STARTING。
         // MachineMod.init() 里的 CommandRegistrationCallback 只在 Commands 构造时生效，
         // 而 Commands 属于世界数据包资源（WorldStem -> ReloadableServerResources），
         // 已在本阶段之后、ServerLifecycleEvents.SERVER_STARTING 之前建好；
         // 一旦把 init() 推迟到 SERVER_STARTING，/machineadmin 与 /cgtest 会静默丢失（需 /reload 重建 Commands 才会出现）。
         // 请勿改回延时注册。
         MachineMod.init();
      }
   }
}
