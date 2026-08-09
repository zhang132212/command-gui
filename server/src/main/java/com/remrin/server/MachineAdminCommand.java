package com.remrin.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.remrin.server.config.MachineConfig;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /machineadmin add|remove|list <player>} — manages the editor whitelist: players in the
 * whitelist may create / edit / delete machine switches even without operator permission.
 * Managing the whitelist itself requires operator level 2.
 */
public final class MachineAdminCommand {

  private MachineAdminCommand() {
  }

  public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
      CommandBuildContext buildContext, Commands.CommandSelection selection) {
    dispatcher.register(Commands.literal("machineadmin")
        .requires(source -> source.getPlayer() == null
            || Commands.LEVEL_MODERATORS.check(source.getPlayer().permissions()))
        .then(Commands.literal("add")
            .then(Commands.argument("player", StringArgumentType.word())
                .executes(MachineAdminCommand::add)))
        .then(Commands.literal("remove")
            .then(Commands.argument("player", StringArgumentType.word())
                .executes(MachineAdminCommand::remove)))
        .then(Commands.literal("list")
            .executes(MachineAdminCommand::list)));
  }

  private static int add(CommandContext<CommandSourceStack> context) {
    String name = StringArgumentType.getString(context, "player");
    if (MachineConfig.addEditorWhitelist(name)) {
      context.getSource().sendSuccess(() ->
          Component.literal("已将 " + name + " 加入机器编辑白名单"), true);
    } else {
      context.getSource().sendFailure(Component.literal(name + " 已在白名单中"));
    }
    MachineManager.broadcastSync(context.getSource().getServer().getPlayerList());
    return 1;
  }

  private static int remove(CommandContext<CommandSourceStack> context) {
    String name = StringArgumentType.getString(context, "player");
    if (MachineConfig.removeEditorWhitelist(name)) {
      context.getSource().sendSuccess(() ->
          Component.literal("已将 " + name + " 移出机器编辑白名单"), true);
    } else {
      context.getSource().sendFailure(Component.literal(name + " 不在白名单中"));
    }
    MachineManager.broadcastSync(context.getSource().getServer().getPlayerList());
    return 1;
  }

  private static int list(CommandContext<CommandSourceStack> context) {
    StringBuilder sb = new StringBuilder("机器编辑白名单：");
    if (MachineConfig.getEditorWhitelist().isEmpty()) {
      sb.append("（空）");
    } else {
      sb.append(String.join("、", MachineConfig.getEditorWhitelist()));
    }
    context.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
    return 1;
  }
}
