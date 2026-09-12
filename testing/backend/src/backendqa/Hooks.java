package backendqa;

import java.util.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class Hooks {
   public static boolean blockReadiness;
   public static final Map<String,ServerPlayNetworking.PlayPayloadHandler<?>> receivers = new HashMap<>();
   public static final Map<String,List<CustomPacketPayload>> packets = new HashMap<>();
   public static final Map<String,List<String>> messages = new HashMap<>();
   public static boolean testPlayer(ServerPlayer player) {
      return Boolean.getBoolean("commandgui.backendTest") && player.getGameProfile().name().startsWith("QAB_");
   }
   public static void clear() { packets.clear(); messages.clear(); }
   public static String messages(ServerPlayer p) { return String.join("\n",messages.getOrDefault(p.getGameProfile().name(),List.of())); }
   public static <T> List<T> packets(ServerPlayer p,Class<T> type) {
      return packets.getOrDefault(p.getGameProfile().name(),List.of()).stream().filter(type::isInstance).map(type::cast).toList();
   }
}
