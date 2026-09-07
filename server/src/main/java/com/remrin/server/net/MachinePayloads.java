package com.remrin.server.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

public final class MachinePayloads {
   private MachinePayloads() {
   }

   public static record ActionPayload(String json) implements CustomPacketPayload {
      public static final Type<MachinePayloads.ActionPayload> TYPE = new Type(Identifier.parse("command-gui-server:action"));
      public static final StreamCodec<RegistryFriendlyByteBuf, MachinePayloads.ActionPayload> CODEC = StreamCodec.of(
         (buf, payload) -> buf.writeUtf(payload.json(), 1048576), buf -> new MachinePayloads.ActionPayload(buf.readUtf(1048576))
      );

      public Type<? extends CustomPacketPayload> type() {
         return TYPE;
      }
   }

   public static record BlockQueryResultPayload(String json) implements CustomPacketPayload {
      public static final Type<MachinePayloads.BlockQueryResultPayload> TYPE = new Type(Identifier.parse("command-gui-server:block-query"));
      public static final StreamCodec<RegistryFriendlyByteBuf, MachinePayloads.BlockQueryResultPayload> CODEC = StreamCodec.of(
         (buf, payload) -> buf.writeUtf(payload.json(), 1048576), buf -> new MachinePayloads.BlockQueryResultPayload(buf.readUtf(1048576))
      );

      public Type<? extends CustomPacketPayload> type() {
         return TYPE;
      }
   }

   public static record FakePlayerStatesPayload(String json) implements CustomPacketPayload {
      public static final Type<MachinePayloads.FakePlayerStatesPayload> TYPE = new Type(Identifier.parse("command-gui-server:fake-states"));
      public static final StreamCodec<RegistryFriendlyByteBuf, MachinePayloads.FakePlayerStatesPayload> CODEC = StreamCodec.of(
         (buf, payload) -> buf.writeUtf(payload.json(), 1048576), buf -> new MachinePayloads.FakePlayerStatesPayload(buf.readUtf(1048576))
      );

      public Type<? extends CustomPacketPayload> type() {
         return TYPE;
      }
   }

   public static record SyncPayload(String json) implements CustomPacketPayload {
      public static final Type<MachinePayloads.SyncPayload> TYPE = new Type(Identifier.parse("command-gui-server:machines"));
      public static final StreamCodec<RegistryFriendlyByteBuf, MachinePayloads.SyncPayload> CODEC = StreamCodec.of(
         (buf, payload) -> buf.writeUtf(payload.json(), 1048576), buf -> new MachinePayloads.SyncPayload(buf.readUtf(1048576))
      );

      public Type<? extends CustomPacketPayload> type() {
         return TYPE;
      }
   }
}
