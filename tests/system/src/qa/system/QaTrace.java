package qa.system;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Full mod payloads, UTC + monotonic timestamps, and independent sequence numbers per process. */
public final class QaTrace {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final long START = System.nanoTime();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Map<String, Long> COUNTS = new ConcurrentHashMap<>();
    private static BufferedWriter writer;
    private static volatile String failure = "";
    private QaTrace() {}

    public static Path output() { return Path.of(System.getProperty("system.outputRoot")); }
    public static synchronized void log(String event, Object data) {
        try {
            if (writer == null) {
                Files.createDirectories(output());
                writer = Files.newBufferedWriter(output().resolve("events.jsonl"), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("seq", SEQUENCE.incrementAndGet()); row.put("utc", Instant.now().toString());
            row.put("elapsedNanos", System.nanoTime() - START); row.put("role", System.getProperty("system.role", "server"));
            row.put("thread", Thread.currentThread().getName()); row.put("event", event); row.put("data", data);
            writer.write(GSON.toJson(row)); writer.newLine(); writer.flush();
        } catch (Exception error) {
            failure = error.toString();
            System.err.println("SYSTEM_TRACE_FAILURE " + error);
        }
    }
    public static void packet(String direction, Connection connection, Packet<?> packet) {
        CustomPacketPayload payload = packet instanceof ClientboundCustomPayloadPacket p ? p.payload()
            : packet instanceof ServerboundCustomPayloadPacket p ? p.payload() : null;
        if (payload == null || !payload.type().id().getNamespace().startsWith("command-gui")) return;
        String id = payload.type().id().toString();
        COUNTS.merge(direction + ":" + id, 1L, Long::sum);
        if (!connection.isMemoryConnection()) COUNTS.merge("tcp:" + direction, 1L, Long::sum);
        var data = new LinkedHashMap<String, Object>();
        data.put("direction", direction); data.put("type", id); data.put("peer", String.valueOf(connection.getRemoteAddress()));
        data.put("memoryConnection", connection.isMemoryConnection()); data.put("payload", GSON.toJsonTree(payload));
        log("packet", data);
    }
    public static long count(String key) { return COUNTS.getOrDefault(key, 0L); }
    public static Map<String, Long> counts() { return new TreeMap<>(COUNTS); }
    public static String failure() { return failure; }

    public static void writeJson(Path path, Object value) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.createDirectories(path.getParent());
        Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(value), StandardCharsets.UTF_8);
        for (int attempt = 0; ; attempt++) {
            try {
                try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
                return;
            } catch (FileSystemException error) {
                if (attempt >= 5) throw error;
                try { Thread.sleep(20L << attempt); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
            }
        }
    }
}
