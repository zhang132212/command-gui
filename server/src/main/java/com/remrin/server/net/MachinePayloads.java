package com.remrin.server.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Custom payloads for the machine switch system. Both directions carry a single UTF-8 JSON string
 * so the client mod and this server mod can be compiled independently and stay tolerant to schema
 * changes (unknown fields are simply ignored by Gson on either side).
 */
public final class MachinePayloads {

  /**
   * Server-to-client: full machine list plus runtime states and the receiving player's edit
   * permission.
   */
  public record SyncPayload(String json) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SyncPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("command-gui-server:machines"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncPayload> CODEC =
        StreamCodec.of(
            (buf, payload) -> buf.writeUtf(payload.json(), 1048576),
            buf -> new SyncPayload(buf.readUtf(1048576)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
      return TYPE;
    }
  }

  /**
   * Client-to-server: a machine action (toggle / set modes / add / edit / delete / block query)
   * encoded as JSON.
   */
  public record ActionPayload(String json) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ActionPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("command-gui-server:action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> CODEC =
        StreamCodec.of(
            (buf, payload) -> buf.writeUtf(payload.json(), 1048576),
            buf -> new ActionPayload(buf.readUtf(1048576)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
      return TYPE;
    }
  }

  /**
   * Server-to-client: result of a block query for the detection screen. Carries the found block
   * id, all possible values of its state properties (for mapping configuration) and the current
   * property values.
   */
  public record BlockQueryResultPayload(String json) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BlockQueryResultPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.parse("command-gui-server:block-query"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BlockQueryResultPayload> CODEC =
        StreamCodec.of(
            (buf, payload) -> buf.writeUtf(payload.json(), 1048576),
            buf -> new BlockQueryResultPayload(buf.readUtf(1048576)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
      return TYPE;
    }
  }

  private MachinePayloads() {
  }
}
