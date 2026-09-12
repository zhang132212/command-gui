package com.remrin.rules;

import com.google.gson.Gson;
import com.remrin.CommandGUI;
import java.util.*;
import net.fabricmc.fabric.api.networking.v1.*;

/** Catalogue and bounded batch adapter. All changes execute Carpet's native commands as the player. */
public final class CarpetRuleService {
   public record Page(int index, boolean last, String error, List<RuleData> rules) {}
   private static final Gson GSON = new Gson();
   private static final Map<UUID, Long> lastQuery = new HashMap<>();
   private static final Map<UUID, Integer> lastBatch = new HashMap<>();
   private static boolean allowed(net.minecraft.server.MinecraftServer server, net.minecraft.server.level.ServerPlayer player) {
      return RuleAccess.allowed(player.permissions(), !server.isDedicatedServer(), server.getWorldData().isAllowCommands());
   }
   public static void init() {
      PayloadTypeRegistry.serverboundPlay().register(RulePayloads.Query.TYPE, RulePayloads.Query.CODEC);
      PayloadTypeRegistry.clientboundPlay().register(RulePayloads.Snapshot.TYPE, RulePayloads.Snapshot.CODEC);
      PayloadTypeRegistry.serverboundPlay().register(RulePayloads.Apply.TYPE, RulePayloads.Apply.CODEC);
      ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> {
         lastQuery.remove(listener.getPlayer().getUUID()); lastBatch.remove(listener.getPlayer().getUUID());
      });
      ServerPlayNetworking.registerGlobalReceiver(RulePayloads.Apply.TYPE, (payload, ctx) -> {
         if (!ServerPlayNetworking.canSend(ctx.player(), RulePayloads.Snapshot.TYPE)) return;
         String message;
         try {
            if (!allowed(ctx.server(), ctx.player())) throw new IllegalArgumentException("权限不足，本次未执行");
            Integer previous = lastBatch.get(ctx.player().getUUID());
            if (previous != null && payload.request() <= previous) throw new IllegalArgumentException("重复批次已忽略");
            lastBatch.put(ctx.player().getUUID(), payload.request());
            RuleData.Change[] changes = GSON.fromJson(payload.json(), RuleData.Change[].class);
            if (changes == null || changes.length == 0 || changes.length > 64) throw new IllegalArgumentException("每批请选择 1–64 项规则");
            Map<String, RuleData> catalogue = new HashMap<>();
            for (RuleData rule : CarpetRuleCatalog.read()) catalogue.put(rule.key(), rule);
            List<String> commands = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (RuleData.Change change : changes) {
               RuleData rule = change == null ? null : catalogue.get(change.key());
               if (rule == null || rule.locked() || !seen.add(rule.key()) || !Objects.equals(rule.value(), change.expected()))
                  throw new IllegalArgumentException("规则状态已变化、被锁定或重复，本次未执行，请刷新后重选");
               commands.add(rule.command(change.operation(), change.target()));
            }
            for (String command : commands) {
               if (!allowed(ctx.server(), ctx.player())) break;
               ctx.server().getCommands().performPrefixedCommand(ctx.player().createCommandSourceStack(), command);
            }
            message = "批量请求已处理；各规则的验证和保存结果见聊天消息";
         } catch (ReflectiveOperationException | RuntimeException e) {
            message = e instanceof IllegalArgumentException ? e.getMessage() : "无法处理规则批次，请刷新后重试";
            CommandGUI.LOGGER.debug("Carpet rule batch rejected", e);
         }
         ServerPlayNetworking.send(ctx.player(), new RulePayloads.Snapshot(payload.request(), GSON.toJson(new Page(0, true, message, List.of()))));
      });
      ServerPlayNetworking.registerGlobalReceiver(RulePayloads.Query.TYPE, (query, ctx) -> {
         if (!ServerPlayNetworking.canSend(ctx.player(), RulePayloads.Snapshot.TYPE)) return;
         var server = ctx.server();
         boolean allowed = allowed(server, ctx.player());
         if (!allowed) {
            ServerPlayNetworking.send(ctx.player(), new RulePayloads.Snapshot(query.request(), GSON.toJson(new Page(0, true, "需要开启作弊或拥有 OP 权限", List.of()))));
            return;
         }
         long now = System.currentTimeMillis();
         Long last = lastQuery.put(ctx.player().getUUID(), now);
         if (last != null && now - last < 1000) {
            ServerPlayNetworking.send(ctx.player(), new RulePayloads.Snapshot(query.request(), GSON.toJson(new Page(0, true, "查询过于频繁，请稍后刷新", List.of()))));
            return;
         }
         try {
            List<RuleData> rules = CarpetRuleCatalog.read();
            for (int offset = 0, page = 0; offset < Math.max(1, rules.size()); offset += 16, page++) {
               int end = Math.min(offset + 16, rules.size());
               ServerPlayNetworking.send(ctx.player(), new RulePayloads.Snapshot(query.request(),
                  GSON.toJson(new Page(page, end == rules.size(), rules.isEmpty() ? "未发现 Carpet 规则" : "", rules.subList(offset, end)))));
            }
         } catch (ClassNotFoundException e) {
            ServerPlayNetworking.send(ctx.player(), new RulePayloads.Snapshot(query.request(), GSON.toJson(new Page(0, true, "服务端未安装 Carpet", List.of()))));
         } catch (ReflectiveOperationException | RuntimeException e) {
            CommandGUI.LOGGER.warn("Could not read Carpet rule catalogue", e);
            ServerPlayNetworking.send(ctx.player(), new RulePayloads.Snapshot(query.request(), GSON.toJson(new Page(0, true, "无法读取此版本的 Carpet 规则，请查看服务端日志", List.of()))));
         }
      });
   }
}
