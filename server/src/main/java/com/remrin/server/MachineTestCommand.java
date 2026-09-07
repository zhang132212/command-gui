package com.remrin.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.remrin.server.config.MachineConfig;
import com.remrin.server.net.MachinePayloads;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.Commands.CommandSelection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 测试驱动命令（仅测试服使用，勿部署生产）。
 *
 * <p>目的：不经 GUI，直接以【指定在线玩家】的身份向 {@link MachineManager#handleAction}
 * 注入与真实客户端 GUI 完全相同的 action，从而可编写脚本验证后端多人语义
 * （编辑锁互斥 / 删除放行 / 开关机拒绝 / 权限等），等效于"无头浏览器驱动 GUI"。
 *
 * <p>用法（要求执行者是 OP 或控制台）：
 * <pre>
 * /cgtest lock list                     查看当前所有编辑锁
 * /cgtest edit &lt;player&gt; &lt;machine&gt; open   以 player 身份打开机器编辑(加锁)
 * /cgtest edit &lt;player&gt; &lt;machine&gt; close  以 player 身份关闭编辑(解锁)
 * /cgtest delete &lt;player&gt; &lt;machine&gt;       以 player 身份删除机器
 * /cgtest toggle &lt;player&gt; &lt;machine&gt;       以 player 身份开关机
 * /cgtest setmodes &lt;player&gt; &lt;machine&gt; &lt;modeId...&gt;  以 player 身份切模式
 * /cgtest machine list                  列出服务器已加载机器
 * /cgtest action &lt;player&gt; &lt;json&gt;         以 player 身份注入任意 action JSON（底层通道）
 * </pre>
 */
public final class MachineTestCommand {
   private MachineTestCommand() {
   }

   public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext ctx, CommandSelection selection) {
      LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("cgtest")
         .requires(src -> src.getPlayer() == null || net.minecraft.commands.Commands.LEVEL_MODERATORS.check(src.getPlayer().permissions()));
      root.then(Commands.literal("lock").then(Commands.literal("list").executes(MachineTestCommand::lockList)));
      root.then(
         Commands.literal("edit")
            .then(
               Commands.argument("player", StringArgumentType.word())
                  .then(
                     Commands.argument("machine", StringArgumentType.word())
                        .then(
                           Commands.literal("open").executes(c -> edit(c, true))
                        )
                        .then(Commands.literal("close").executes(c -> edit(c, false)))
                  )
            )
      );
      root.then(
         Commands.literal("delete")
            .then(
               Commands.argument("player", StringArgumentType.word())
                  .then(Commands.argument("machine", StringArgumentType.word()).executes(MachineTestCommand::delete))
            )
      );
      root.then(
         Commands.literal("toggle")
            .then(
               Commands.argument("player", StringArgumentType.word())
                  .then(Commands.argument("machine", StringArgumentType.word()).executes(MachineTestCommand::toggle))
            )
      );
      root.then(
         Commands.literal("setmodes")
            .then(
               Commands.argument("player", StringArgumentType.word())
                  .then(
                     Commands.argument("machine", StringArgumentType.word())
                        .then(Commands.argument("modeIds", StringArgumentType.greedyString()).executes(MachineTestCommand::setModes))
                  )
            )
      );
      root.then(Commands.literal("machine").then(Commands.literal("list").executes(MachineTestCommand::machineList)));
      root.then(
         Commands.literal("action")
            .then(
               Commands.argument("player", StringArgumentType.word())
                  .then(Commands.argument("json", StringArgumentType.greedyString()).executes(MachineTestCommand::rawAction))
            )
      );
      dispatcher.register(root);
   }

   // ---------- handlers ----------

   private static int lockList(CommandContext<CommandSourceStack> ctx) {
      String dump = MachineManager.debugLocksDump();
      ctx.getSource().sendSuccess(() -> Component.literal(dump), false);
      return 1;
   }

   private static int machineList(CommandContext<CommandSourceStack> ctx) {
      StringBuilder sb = new StringBuilder("机器(");
      sb.append(MachineConfig.getMachines().size()).append("):");
      for (MachineConfig.MachineData m : MachineConfig.getMachines()) {
         sb.append("\n  ").append(m.id).append(" = ").append(m.name).append(" [").append(String.join(",", m.bots)).append("]");
      }
      String out = sb.toString();
      ctx.getSource().sendSuccess(() -> Component.literal(out), false);
      return 1;
   }

   private static int edit(CommandContext<CommandSourceStack> ctx, boolean open) {
      return inject(ctx, buildAction("editSession", "machineId", ctx.getArgument("machine", String.class))
         .flatJson(b -> {
            b.addProperty("open", open);
            return b;
         }).build());
   }

   private static int delete(CommandContext<CommandSourceStack> ctx) {
      return inject(ctx, buildAction("delete", "machineId", ctx.getArgument("machine", String.class)).build());
   }

   private static int toggle(CommandContext<CommandSourceStack> ctx) {
      return inject(ctx, buildAction("toggle", "machineId", ctx.getArgument("machine", String.class)).build());
   }

   private static int setModes(CommandContext<CommandSourceStack> ctx) {
      String modeIdsRaw = ctx.getArgument("modeIds", String.class);
      JsonObject action = new JsonObject();
      action.addProperty("type", "setModes");
      action.addProperty("machineId", ctx.getArgument("machine", String.class));
      JsonArray arr = new JsonArray();
      for (String s : modeIdsRaw.trim().split("\\s+")) {
         if (!s.isEmpty()) {
            arr.add(s);
         }
      }
      action.add("modeIds", arr);
      return inject(ctx, action);
   }

   private static int rawAction(CommandContext<CommandSourceStack> ctx) {
      String json = ctx.getArgument("json", String.class);
      JsonObject action;
      try {
         action = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
      } catch (Exception e) {
         ctx.getSource().sendFailure(Component.literal("非法 JSON: " + e.getMessage()));
         return 0;
      }
      return inject(ctx, action);
   }

   // ---------- helpers ----------

   private static JsonObjBuilder buildAction(String type, String key, String value) {
      JsonObject o = new JsonObject();
      o.addProperty("type", type);
      if (key != null) {
         o.addProperty(key, value);
      }
      return new JsonObjBuilder(o);
   }

   private static int inject(CommandContext<CommandSourceStack> ctx, JsonObject action) {
      String playerName = ctx.getArgument("player", String.class);
      MinecraftServer server = ctx.getSource().getServer();
      ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
      if (player == null) {
         ctx.getSource().sendFailure(Component.literal("玩家不在线: " + playerName));
         return 0;
      }
      ctx.getSource().sendSuccess(() -> Component.literal("注入 action → " + playerName + ": " + action), false);
      MachineManager.handleAction(player, server, new MachinePayloads.ActionPayload(action.toString()));
      return 1;
   }

   private static final class JsonObjBuilder {
      private final JsonObject obj;

      JsonObjBuilder(JsonObject obj) {
         this.obj = obj;
      }

      JsonObjBuilder flatJson(java.util.function.Function<JsonObject, JsonObject> f) {
         f.apply(this.obj);
         return this;
      }

      JsonObject build() {
         return this.obj;
      }
   }
}
