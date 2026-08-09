package com.remrin.server;

import com.remrin.server.config.MachineConfig;
import com.remrin.server.net.MachinePayloads;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side mod entry point for the machine switch system.
 * <p>
 * Responsibilities: load the machine config, register network payloads and handlers, push the
 * machine list to joining players, and drive the timeline scheduler on every server tick.
 */
public class MachineMod implements ModInitializer {

  public static final String MOD_ID = "command-gui-server";
  public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

  /**
   * The currently running server, kept for broadcasting machine state changes from the scheduler.
   */
  private static MinecraftServer currentServer;

  public static MinecraftServer getCurrentServer() {
    return currentServer;
  }

  @Override
  public void onInitialize() {
    MachineConfig.load();

    // Payload types must be registered on both ends before any receivers are registered.
    PayloadTypeRegistry.serverboundPlay().register(
        MachinePayloads.ActionPayload.TYPE, MachinePayloads.ActionPayload.CODEC);
    PayloadTypeRegistry.clientboundPlay().register(
        MachinePayloads.SyncPayload.TYPE, MachinePayloads.SyncPayload.CODEC);
    PayloadTypeRegistry.clientboundPlay().register(
        MachinePayloads.BlockQueryResultPayload.TYPE, MachinePayloads.BlockQueryResultPayload.CODEC);

    ServerPlayNetworking.registerGlobalReceiver(
        MachinePayloads.ActionPayload.TYPE,
        (payload, context) -> MachineManager.handleAction(context.player(), context.server(),
            payload));

    ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> {
      currentServer = server;
      MachineManager.syncTo(listener.getPlayer());
    });

    ServerPlayConnectionEvents.DISCONNECT.register((listener, server) ->
        MachineManager.onPlayerDisconnect(listener.getPlayer().getGameProfile().name()));

    CommandRegistrationCallback.EVENT.register(MachineAdminCommand::register);

    // Snapshot machine detection blocks into memory when their chunk unloads, so detection stays
    // fresh even when the chunk is unloaded (and the region files may lag behind autosaves).
    ServerChunkEvents.CHUNK_UNLOAD.register(MachineBlockCache::onChunkUnload);

    ServerTickEvents.END_SERVER_TICK.register(server -> {
      currentServer = server;
      MachineScheduler.tick(server);
      MachineModeChain.tick(server);
      MachineManager.tickStates(server);
    });

    ServerLifecycleEvents.SERVER_STARTED.register(server -> MachineBlockCache.rebuild());

    ServerLifecycleEvents.SERVER_STOPPING.register(server -> currentServer = null);

    LOGGER.info("Command-GUI Server initialized!");
  }
}
