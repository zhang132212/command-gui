package com.remrin.client.rules;

import com.google.gson.Gson;
import com.remrin.rules.*;
import java.util.*;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.minecraft.client.Minecraft;

public final class CarpetRuleClient {
   private static final Gson GSON = new Gson();
   private static List<RuleData> rules = List.of();
   private static final List<RuleData> incoming = new ArrayList<>();
   public static final Map<String, RuleData.Change> pending = new LinkedHashMap<>();
   private static int request, nextPage, version;
   private static boolean busy, applying;
   private static long started, refreshAt, lastRefresh;
   private static String status = "点击刷新，从服务器查询规则";
   public static boolean canView() {
      Minecraft mc = Minecraft.getInstance();
      return mc.player != null && RuleAccess.allowed(mc.player.permissions(), mc.hasSingleplayerServer(),
         mc.getSingleplayerServer() != null && mc.getSingleplayerServer().getWorldData().isAllowCommands());
   }
   public static void init() {
      ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
      ClientPlayNetworking.registerGlobalReceiver(RulePayloads.Snapshot.TYPE, (payload, ctx) -> {
         if (payload.request() != request || !busy || !canView()) return;
         try {
            CarpetRuleService.Page page = GSON.fromJson(payload.json(), CarpetRuleService.Page.class);
            if (page.index() != nextPage++ || page.rules() == null) throw new IllegalArgumentException();
            incoming.addAll(page.rules());
            if (page.last()) {
               busy = false;
               status = page.error().isEmpty() ? "已查询 " + incoming.size() + " 项规则" : page.error();
               if (applying) {
                  if (status.startsWith("批量请求已处理")) pending.clear();
                  applying = false; refreshAt = System.currentTimeMillis() + 1200;
               } else if (page.error().isEmpty()) rules = List.copyOf(incoming);
               version++;
            }
         } catch (RuntimeException e) { busy = false; applying = false; status = "规则数据读取失败，请刷新重试"; version++; }
      });
   }
   public static List<RuleData> rules() { return rules; }
   public static int version() { return version; }
   public static boolean busy() { return busy; }
   public static String status() { return status; }
   public static void reset() {
      rules = List.of(); pending.clear(); incoming.clear(); busy = applying = false;
      refreshAt = lastRefresh = 0; status = "点击刷新，从服务器查询规则"; request++; version++;
   }
   public static void tick() {
      if (!canView()) { if (busy || !rules.isEmpty() || !pending.isEmpty()) reset(); return; }
      if (busy && System.currentTimeMillis() - started > 15000) {
         busy = false; applying = false; status = "请求超时，执行结果不确定，请刷新核对后再操作"; version++;
      }
      if (!busy && refreshAt > 0 && System.currentTimeMillis() >= refreshAt) { refreshAt = 0; refresh(); }
   }
   private static void begin(boolean apply) {
      request++; nextPage = 0; incoming.clear(); busy = true; applying = apply; started = System.currentTimeMillis(); version++;
   }
   public static void refresh() {
      if (!canView() || busy) return;
      if (System.currentTimeMillis() - lastRefresh < 1200) { refreshAt = lastRefresh + 1200; return; }
      if (!ClientPlayNetworking.canSend(RulePayloads.Query.TYPE)) {
         status = "需在服务器安装新版 Command-GUI，才能查询规则及扩展分类"; version++; return;
      }
      begin(false); lastRefresh = System.currentTimeMillis(); status = "正在查询 Carpet 及扩展规则…";
      ClientPlayNetworking.send(new RulePayloads.Query(request));
   }
   public static void stage(RuleData rule, String operation, String value) {
      if (!canView() || busy || rule.locked()) return;
      rule.command(operation, value);
      if (pending.size() >= 64 && !pending.containsKey(rule.key())) throw new IllegalArgumentException("每批最多选择 64 项规则");
      pending.put(rule.key(), new RuleData.Change(rule.key(), rule.value(), operation, value)); version++;
   }
   public static void apply() {
      if (!canView() || busy || pending.isEmpty()) return;
      if (!ClientPlayNetworking.canSend(RulePayloads.Apply.TYPE)) { status = "服务器不支持批量规则操作"; version++; return; }
      begin(true); status = "正在提交，请等待服务器结果…";
      ClientPlayNetworking.send(new RulePayloads.Apply(request, GSON.toJson(pending.values())));
   }
}
