package com.remrin.server;

import com.remrin.server.BlockStateFileReader.BlockStateEntry;
import com.remrin.server.config.MachineConfig.DetectionData;
import com.remrin.server.config.MachineConfig.MachineData;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Evaluates a machine's block-state detection. Reads the configured block either from server
 * memory (when its chunk is loaded — exact) or directly from the region files (when unloaded —
 * on-disk state, which may lag a few seconds behind recent changes).
 * <p>
 * Result: {@link MachineState#DISABLED} when no detection is configured; otherwise ON / OFF /
 * ABNORMAL based on the block's property value against the configured on/off value sets. Any
 * missing/mismatched block, unknown property or unlisted value counts as ABNORMAL.
 */
public final class MachineDetector {

  private MachineDetector() {
  }

  public enum MachineState {
    DISABLED, ON, OFF, ABNORMAL
  }

  /**
   * Evaluation result plus a human-readable reason. The reason is only set for ABNORMAL results
   * (and null otherwise), so callers can tell the player exactly why the switch is locked.
   */
  public record DetectionResult(MachineState state, String reason) {
  }

  /**
   * Evaluates a detection config against the given server. The in-memory block state is used when
   * the chunk is loaded; otherwise the {@link MachineBlockCache} snapshot (fresh, captured at
   * chunk unload) is consulted first, falling back to a direct region-file read.
   */
  public static DetectionResult evaluateDetection(DetectionData detection, MinecraftServer server,
      boolean useCache) {
    if (detection == null || !detection.enabled) {
      return new DetectionResult(MachineState.DISABLED, null);
    }
    ServerLevel level = server.getLevel(
        ResourceKey.create(Registries.DIMENSION, Identifier.parse(detection.dimension)));
    if (level == null) {
      return new DetectionResult(MachineState.ABNORMAL, "维度不存在");
    }
    BlockPos pos = new BlockPos(detection.x, detection.y, detection.z);

    String blockId;
    Map<String, String> current;
    if (level.hasChunkAt(pos)) {
      BlockState state = level.getBlockState(pos);
      blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
      current = new HashMap<>();
      for (Property.Value<?> value : state.getValues().toList()) {
        current.put(value.property().getName(), value.valueName());
      }
    } else if (useCache) {
      BlockStateEntry cached = MachineBlockCache.get(detection.dimension,
          detection.x, detection.y, detection.z);
      if (cached == null) {
        // No snapshot yet (e.g. right after a restart): fall back to the region files.
        cached = BlockStateFileReader.read(server, detection.dimension,
            detection.x, detection.y, detection.z);
      }
      if (cached == null) {
        return new DetectionResult(MachineState.ABNORMAL,
            "检测方块所在区块未加载，且内存快照与磁盘均无该方块数据");
      }
      blockId = cached.blockId();
      current = cached.properties();
    } else {
      BlockStateEntry entry = BlockStateFileReader.read(server, detection.dimension,
          detection.x, detection.y, detection.z);
      if (entry == null) {
        return new DetectionResult(MachineState.ABNORMAL,
            "检测方块所在区块未生成且磁盘读取失败");
      }
      blockId = entry.blockId();
      current = entry.properties();
    }

    if (!normalizeId(blockId).equals(normalizeId(detection.blockId))) {
      return new DetectionResult(MachineState.ABNORMAL,
          "检测方块不匹配：实际是 " + blockId + "，配置是 " + detection.blockId);
    }
    String value = current.get(detection.property);
    if (value == null) {
      return new DetectionResult(MachineState.ABNORMAL, "方块缺少属性 " + detection.property);
    }
    if (contains(detection.onValues, value)) {
      return new DetectionResult(MachineState.ON, null);
    }
    if (contains(detection.offValues, value)) {
      return new DetectionResult(MachineState.OFF, null);
    }
    return new DetectionResult(MachineState.ABNORMAL,
        "属性 " + detection.property + "=" + value + " 不在配置的开关值中");
  }

  /**
   * Evaluates the detection for a machine on the given server. The in-memory block state is used
   * when the chunk is loaded; otherwise the {@link MachineBlockCache} snapshot (fresh, captured at
   * chunk unload) is consulted first, falling back to a direct region-file read.
   */
  public static DetectionResult evaluateDetailed(MachineData machine, MinecraftServer server,
      boolean useCache) {
    return evaluateDetection(machine.detection, server, useCache);
  }

  /**
   * Evaluates the detection for a machine on the given server.
   */
  public static MachineState evaluate(MachineData machine, MinecraftServer server) {
    return evaluateDetailed(machine, server, true).state();
  }

  private static boolean contains(java.util.List<String> values, String value) {
    return values != null && values.contains(value);
  }

  /**
   * Normalizes a block id: adds the {@code minecraft:} prefix when missing.
   */
  public static String normalizeId(String id) {
    if (id == null || id.isEmpty()) {
      return "";
    }
    return id.contains(":") ? id : "minecraft:" + id;
  }
}
