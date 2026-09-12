package com.remrin.rules;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class RulePayloads {
   private RulePayloads() {}
   public record Apply(int request, String json) implements CustomPacketPayload {
      public static final Type<Apply> TYPE = new Type<>(Identifier.parse("command-gui:rules-apply"));
      public static final StreamCodec<RegistryFriendlyByteBuf, Apply> CODEC = StreamCodec.of(
         (b,p) -> { b.writeVarInt(p.request); b.writeUtf(p.json, 32767); }, b -> new Apply(b.readVarInt(), b.readUtf(32767)));
      public Type<? extends CustomPacketPayload> type() { return TYPE; }
   }
   public record Query(int request) implements CustomPacketPayload {
      public static final Type<Query> TYPE = new Type<>(Identifier.parse("command-gui:rules-query"));
      public static final StreamCodec<RegistryFriendlyByteBuf, Query> CODEC = StreamCodec.of(
         (b,p) -> b.writeVarInt(p.request), b -> new Query(b.readVarInt()));
      public Type<? extends CustomPacketPayload> type() { return TYPE; }
   }
   public record Snapshot(int request, String json) implements CustomPacketPayload {
      public static final Type<Snapshot> TYPE = new Type<>(Identifier.parse("command-gui:rules-snapshot"));
      public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.of(
         (b,p) -> { b.writeVarInt(p.request); b.writeUtf(p.json, 200000); }, b -> new Snapshot(b.readVarInt(), b.readUtf(200000)));
      public Type<? extends CustomPacketPayload> type() { return TYPE; }
   }
}
