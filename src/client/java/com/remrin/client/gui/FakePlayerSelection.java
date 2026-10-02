package com.remrin.client.gui;

import com.remrin.client.machine.MachineNetworkManager;
import com.remrin.server.net.MachinePayloads;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

/** Snapshot lifecycle shared by player selectors, independent of the main fake-player tab. */
final class FakePlayerSelection {
   private boolean snapshotRequested;
   private int lastVersion = Integer.MIN_VALUE;

   boolean poll() {
      Minecraft client = Minecraft.getInstance();
      if (!this.snapshotRequested && client != null && client.getConnection() != null
         && ClientPlayNetworking.canSend(MachinePayloads.ActionPayload.TYPE)) {
         this.snapshotRequested = true;
         MachineNetworkManager.sendRequestFakeStates();
      }
      int version = MachineNetworkManager.getFakeStatesVersion();
      if (version == this.lastVersion) {
         return false;
      }
      this.lastVersion = version;
      return true;
   }

   void close() {
      if (this.snapshotRequested) {
         MachineNetworkManager.sendUnsubscribeFakeStates();
         this.snapshotRequested = false;
      }
      this.lastVersion = Integer.MIN_VALUE;
   }

   static List<String> botNames(List<String> initialNames) {
      if (MachineNetworkManager.isFakePlayerStatesSupported()) {
         return new ArrayList<>(MachineNetworkManager.getServerFakePlayers());
      }
      LinkedHashSet<String> names = new LinkedHashSet<>(initialNames);
      Minecraft client = Minecraft.getInstance();
      if (client != null && client.getConnection() != null) {
         for (PlayerInfo player : client.getConnection().getOnlinePlayers()) {
            if (CommandHelper.isFakePlayer(player)) {
               names.add(player.getProfile().name());
            }
         }
      }
      return new ArrayList<>(names);
   }
}
