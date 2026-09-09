package com.remrin.server;

import com.remrin.server.config.MachineConfig;
import com.remrin.server.net.MachinePayloads;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStarted;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStopping;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.EndTick;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.Disconnect;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.Join;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MachineMod implements ModInitializer {
   private static boolean initialized = false;
   public static final String MOD_ID = "command-gui-server";
   public static final Logger LOGGER = LoggerFactory.getLogger("command-gui-server");
   private static MinecraftServer currentServer;
   private static String lastFakeStatesJson = "";
   private static final Set<UUID> fakeStateSubscribers = new HashSet<>();
   private static final Set<UUID> machineStateSubscribers = new HashSet<>();

   public static MinecraftServer getCurrentServer() {
      return currentServer;
   }

   public static void subscribeFakeStates(ServerPlayer player, MinecraftServer server) {
      fakeStateSubscribers.add(player.getUUID());
      String snapshot = FakePlayerStateTracker.buildJson(server);
      lastFakeStatesJson = snapshot;
      ServerPlayNetworking.send(player, new MachinePayloads.FakePlayerStatesPayload(snapshot));
   }

   public static void unsubscribeFakeStates(ServerPlayer player) {
      fakeStateSubscribers.remove(player.getUUID());
   }

   /** PERF: 机器状态订阅者（打开机器面板的玩家），用于给 tickStates 的周期检测加门闩。 */
   public static void subscribeMachineStates(ServerPlayer player) {
      machineStateSubscribers.add(player.getUUID());
   }

   public static void unsubscribeMachineStates(ServerPlayer player) {
      machineStateSubscribers.remove(player.getUUID());
   }

   public static boolean hasMachineStateSubscribers() {
      return !machineStateSubscribers.isEmpty();
   }

   private static void registerPayload(Runnable registration) {
      try {
         registration.run();
      } catch (IllegalArgumentException e) {
      }
   }

   public void onInitialize() {
      init();
   }

   public static void init() {
      if (initialized) {
         return;
      }

      initialized = true;
      MachineConfig.load();
      registerPayload(() -> PayloadTypeRegistry.serverboundPlay().register(MachinePayloads.ActionPayload.TYPE, MachinePayloads.ActionPayload.CODEC));
      registerPayload(() -> PayloadTypeRegistry.clientboundPlay().register(MachinePayloads.SyncPayload.TYPE, MachinePayloads.SyncPayload.CODEC));
      registerPayload(() -> PayloadTypeRegistry.clientboundPlay().register(MachinePayloads.BlockQueryResultPayload.TYPE, MachinePayloads.BlockQueryResultPayload.CODEC));
      registerPayload(() -> PayloadTypeRegistry.clientboundPlay().register(MachinePayloads.FakePlayerStatesPayload.TYPE, MachinePayloads.FakePlayerStatesPayload.CODEC));
      ServerPlayNetworking.registerGlobalReceiver(
         MachinePayloads.ActionPayload.TYPE, (payload, context) -> MachineManager.handleAction(context.player(), context.server(), payload)
      );
      ServerPlayConnectionEvents.JOIN.register((Join)(listener, sender, server) -> {
         currentServer = server;
         MachineManager.syncTo(listener.getPlayer());
      });
      ServerPlayConnectionEvents.DISCONNECT.register((Disconnect)(listener, server) -> {
         unsubscribeFakeStates(listener.getPlayer());
         unsubscribeMachineStates(listener.getPlayer());
         MachineManager.onPlayerDisconnect(listener.getPlayer().getGameProfile().name());
      });
      CommandRegistrationCallback.EVENT.register(MachineAdminCommand::register);
      CommandRegistrationCallback.EVENT.register(MachineTestCommand::register);
      ServerChunkEvents.CHUNK_UNLOAD.register(MachineBlockCache::onChunkUnload);
      ServerTickEvents.END_SERVER_TICK.register((EndTick)server -> {
         currentServer = server;
         MachineScheduler.tick(server);
         MachineModeChain.tick(server);
         MachineManager.tickStates(server);
         if (!fakeStateSubscribers.isEmpty()) {
            String fakeStates = FakePlayerStateTracker.buildJson(server);
            if (!fakeStates.equals(lastFakeStatesJson)) {
               lastFakeStatesJson = fakeStates;
               MachinePayloads.FakePlayerStatesPayload payload = new MachinePayloads.FakePlayerStatesPayload(fakeStates);

               for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                  if (fakeStateSubscribers.contains(player.getUUID())) {
                     ServerPlayNetworking.send(player, payload);
                  }
               }
            }
         }
      });
      ServerLifecycleEvents.SERVER_STARTED.register((ServerStarted)server -> {
         MachineBlockCache.rebuild();
         MachineBlockCache.prime(server);
      });
      ServerLifecycleEvents.SERVER_STOPPING.register((ServerStopping)server -> {
         currentServer = null;
         lastFakeStatesJson = "";
         fakeStateSubscribers.clear();
         machineStateSubscribers.clear();
      });
      LOGGER.info("Command-GUI Server initialized!");
   }
}
