package qa.system;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Coordination-only packets. Product actions and responses still use the unmodified real transport. */
public final class QaProtocol {
    private QaProtocol() {}
    public record Request(int id, String json) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(Identifier.parse("command-gui-qa:request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of(
            (buf, payload) -> { buf.writeVarInt(payload.id); buf.writeUtf(payload.json, 1_000_000); },
            buf -> new Request(buf.readVarInt(), buf.readUtf(1_000_000)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Response(int id, String json) implements CustomPacketPayload {
        public static final Type<Response> TYPE = new Type<>(Identifier.parse("command-gui-qa:response"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Response> CODEC = StreamCodec.of(
            (buf, payload) -> { buf.writeVarInt(payload.id); buf.writeUtf(payload.json, 1_000_000); },
            buf -> new Response(buf.readVarInt(), buf.readUtf(1_000_000)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
