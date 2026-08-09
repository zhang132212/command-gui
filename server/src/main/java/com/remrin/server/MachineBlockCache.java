package com.remrin.server;

import com.remrin.server.BlockStateFileReader.BlockStateEntry;
import com.remrin.server.config.MachineConfig;
import com.remrin.server.config.MachineConfig.DetectionData;
import com.remrin.server.config.MachineConfig.MachineData;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Lightweight in-memory snapshot cache of machine detection blocks.
 * <p>
 * Watches the (small, fixed) set of positions that machines use as detection blocks. When a chunk
 * containing such a position unloads, the block's state is snapshotted into memory — it is fresh
 * (the chunk holds the authoritative state right up to unload) and does not require the chunk to
 * stay loaded or the region files to have flushed (autosave can lag minutes behind).
 * <p>
 * Memory usage is bounded by the number of detection positions (one per machine, typically a
 * handful): entries are overwritten on every unload and never accumulate. The watched set is
 * rebuilt whenever machines are added, edited or deleted.
 */
public final class MachineBlockCache {

  /** dimension -> watched detection positions. */
  private static final Map<String, Set<BlockPos>> watchedPositions = new HashMap<>();
  /** dimension -> (position -> last known block state snapshot). */
  private static final Map<String, Map<BlockPos, BlockStateEntry>> cache = new HashMap<>();

  private MachineBlockCache() {
  }

  /**
   * Rebuilds the watched position set from the current machine configs (machine switches and
   * mode detections). Call after machines are added, edited or deleted (and once at server start).
   */
  public static void rebuild() {
    watchedPositions.clear();
    for (MachineData machine : MachineConfig.getMachines()) {
      watch(machine.detection);
      if (machine.modes != null) {
        for (com.remrin.server.config.MachineConfig.ModeData mode : machine.modes) {
          watch(mode.detection);
        }
      }
    }
  }

  private static void watch(DetectionData detection) {
    if (detection == null || !detection.enabled) {
      return;
    }
    String dimension = detection.dimension != null
        ? detection.dimension
        : "minecraft:overworld";
    watchedPositions.computeIfAbsent(dimension, k -> new HashSet<>())
        .add(new BlockPos(detection.x, detection.y, detection.z));
  }

  /**
   * Snapshots the state of any watched detection blocks inside the unloaded chunk. Called from the
   * {@code CHUNK_UNLOAD} event; the chunk's block states are still authoritative at this point.
   */
  public static void onChunkUnload(ServerLevel level, LevelChunk chunk) {
    Set<BlockPos> watched = watchedPositions.get(level.dimension().identifier().toString());
    if (watched == null || watched.isEmpty()) {
      return;
    }
    int chunkX = chunk.getPos().x();
    int chunkZ = chunk.getPos().z();
    Map<BlockPos, BlockStateEntry> dimCache = cache.computeIfAbsent(
        level.dimension().identifier().toString(), k -> new HashMap<>());
    for (BlockPos pos : watched) {
      if ((pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ) {
        dimCache.put(pos, snapshot(chunk.getBlockState(pos)));
      }
    }
  }

  /**
   * Returns the last snapshotted state of a detection block, or {@code null} when the chunk has
   * not unloaded since the cache was built (e.g. right after a server restart).
   */
  public static BlockStateEntry get(String dimension, int x, int y, int z) {
    Map<BlockPos, BlockStateEntry> dimCache = cache.get(dimension);
    if (dimCache == null) {
      return null;
    }
    return dimCache.get(new BlockPos(x, y, z));
  }

  private static BlockStateEntry snapshot(BlockState state) {
    Map<String, String> properties = new HashMap<>();
    for (Property.Value<?> value : state.getValues().toList()) {
      properties.put(value.property().getName(), value.valueName());
    }
    return new BlockStateEntry(
        BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), properties);
  }
}
