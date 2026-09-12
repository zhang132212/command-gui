package com.remrin.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.remrin.server.config.MachineConfig;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.Commands.CommandSelection;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.ArrayList;
import java.util.List;

public final class MachineAdminCommand {
   private MachineAdminCommand() {
   }

   public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext, CommandSelection selection) {
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("machineadmin")
                     .requires(source -> source.getPlayer() == null || Commands.LEVEL_MODERATORS.check(source.getPlayer().permissions())))
                  .then(Commands.literal("add").then(Commands.argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                              whitelistNames(context.getSource()), builder))
                        .executes(MachineAdminCommand::add))))
               .then(Commands.literal("remove").then(Commands.argument("player", StringArgumentType.word())
                     .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                           MachineConfig.getEditorWhitelist(), builder))
                     .executes(MachineAdminCommand::remove))))
            .then(Commands.literal("list").executes(MachineAdminCommand::list))
      );
   }

   /** Tab 补全：服务端 whitelist.json 里的名字，外加当前在线玩家（含假人）。 */
   private static List<String> whitelistNames(CommandSourceStack source) {
      List<String> names = new ArrayList<>();
      try {
         for (String name : source.getServer().getPlayerList().getWhiteListNames()) {
            if (name != null && !name.isBlank()) {
               names.add(name);
            }
         }
      } catch (Exception ignored) {
         // 拿不到白名单就算了，退化成只补在线玩家
      }
      try {
         for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
            String name = player.getGameProfile().name();
            if (!names.contains(name)) {
               names.add(name);
            }
         }
      } catch (Exception ignored) {
         // 忽略
      }
      return names;
   }

   private static int add(CommandContext<CommandSourceStack> context) {
      String name = StringArgumentType.getString(context, "player");
      if (MachineConfig.addEditorWhitelist(name)) {
         ((CommandSourceStack)context.getSource()).sendSuccess(() -> Component.literal("已将 " + name + " 加入机器编辑白名单"), true);
      } else {
         ((CommandSourceStack)context.getSource()).sendFailure(Component.literal(name + " 已在白名单中"));
      }

      MachineManager.broadcastSync(((CommandSourceStack)context.getSource()).getServer().getPlayerList());
      return 1;
   }

   private static int remove(CommandContext<CommandSourceStack> context) {
      String name = StringArgumentType.getString(context, "player");
      if (MachineConfig.removeEditorWhitelist(name)) {
         ((CommandSourceStack)context.getSource()).sendSuccess(() -> Component.literal("已将 " + name + " 移出机器编辑白名单"), true);
      } else {
         ((CommandSourceStack)context.getSource()).sendFailure(Component.literal(name + " 不在白名单中"));
      }

      MachineManager.broadcastSync(((CommandSourceStack)context.getSource()).getServer().getPlayerList());
      return 1;
   }

   private static int list(CommandContext<CommandSourceStack> context) {
      StringBuilder sb = new StringBuilder("机器编辑白名单：");
      if (MachineConfig.getEditorWhitelist().isEmpty()) {
         sb.append("（空）");
      } else {
         sb.append(String.join("、", MachineConfig.getEditorWhitelist()));
      }

      ((CommandSourceStack)context.getSource()).sendSuccess(() -> Component.literal(sb.toString()), false);
      return 1;
   }
}
