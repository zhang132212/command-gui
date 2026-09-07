package com.remrin.server;

import com.remrin.server.config.MachineConfig;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property.Value;
import net.minecraft.world.level.chunk.LevelChunk;

public final class MachineBlockCache {
   private static final Map<String, Set<BlockPos>> watchedPositions = new HashMap<>();
   private static final Map<String, Map<BlockPos, BlockStateFileReader.BlockStateEntry>> cache = new HashMap<>();

   /** PERF: 磁盘读结果缓存，独立于上面的权威快照；entry 为 null 表示"确认读不到"的负缓存。 */
   private record DiskEntry(BlockStateFileReader.BlockStateEntry entry, long time) {
   }

   private static final Map<String, Map<BlockPos, DiskEntry>> diskCache = new HashMap<>();
   private static final long DISK_TTL_MS = 30_000L;

   private static String dimOf(String dimension) {
      return dimension != null ? dimension : "minecraft:overworld";
   }

   public static void putDiskResult(String dimension, int x, int y, int z, BlockStateFileReader.BlockStateEntry entry) {
      diskCache.computeIfAbsent(dimOf(dimension), k -> new HashMap<>())
         .put(new BlockPos(x, y, z), new DiskEntry(entry, System.currentTimeMillis()));
   }

   public static boolean hasFreshDisk(String dimension, int x, int y, int z) {
      Map<BlockPos, DiskEntry> m = diskCache.get(dimOf(dimension));
      if (m == null) {
         return false;
      }

      DiskEntry e = m.get(new BlockPos(x, y, z));
      return e != null && System.currentTimeMillis() - e.time() < DISK_TTL_MS;
   }

   public static BlockStateFileReader.BlockStateEntry getDisk(String dimension, int x, int y, int z) {
      Map<BlockPos, DiskEntry> m = diskCache.get(dimOf(dimension));
      DiskEntry e = m == null ? null : m.get(new BlockPos(x, y, z));
      return e == null ? null : e.entry();
   }

   private MachineBlockCache() {
   }

   public static void rebuild() {
      watchedPositions.clear();

      for (MachineConfig.MachineData machine : MachineConfig.getMachines()) {
         watch(machine.detection);
         if (machine.modes != null) {
            for (MachineConfig.ModeData mode : machine.modes) {
               watch(mode.detection);
            }
         }
      }
   }


   public static void prime(MinecraftServer server) {
      for (Map.Entry<String, Set<BlockPos>> entry : watchedPositions.entrySet()) {
         String dimension = entry.getKey();
         ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
         if (level == null) {
            continue;
         }

         Map<BlockPos, BlockStateFileReader.BlockStateEntry> dimCache = cache.computeIfAbsent(dimension, k -> new HashMap<>());
         for (BlockPos pos : entry.getValue()) {
            if (level.hasChunkAt(pos)) {
               dimCache.put(pos, snapshot(level.getBlockState(pos)));
            }
         }
      }
   }


   public static void invalidate(MachineConfig.MachineData machine) {
      if (machine == null) {
         return;
      }

      invalidateDetection(machine.detection);
      if (machine.modes != null) {
         for (MachineConfig.ModeData mode : machine.modes) {
            invalidateDetection(mode.detection);
         }
      }
   }

   private static void invalidateDetection(MachineConfig.DetectionData detection) {
      if (detection == null || !detection.enabled) {
         return;
      }

      String dimension = detection.dimension != null ? detection.dimension : "minecraft:overworld";
      Map<BlockPos, BlockStateFileReader.BlockStateEntry> dimCache = cache.get(dimension);
      if (dimCache != null) {
         dimCache.remove(new BlockPos(detection.x, detection.y, detection.z));
      }

      Map<BlockPos, DiskEntry> dimDisk = diskCache.get(dimension);
      if (dimDisk != null) {
         dimDisk.remove(new BlockPos(detection.x, detection.y, detection.z));
      }
   }

   private static void watch(MachineConfig.DetectionData detection) {
      if (detection != null && detection.enabled) {
         String dimension = detection.dimension != null ? detection.dimension : "minecraft:overworld";
         watchedPositions.computeIfAbsent(dimension, k -> new HashSet<>()).add(new BlockPos(detection.x, detection.y, detection.z));
      }
   }

   public static void onChunkUnload(ServerLevel level, LevelChunk chunk) {
      Set<BlockPos> watched = watchedPositions.get(level.dimension().identifier().toString());
      if (watched != null && !watched.isEmpty()) {
         int chunkX = chunk.getPos().x();
         int chunkZ = chunk.getPos().z();
         Map<BlockPos, BlockStateFileReader.BlockStateEntry> dimCache = cache.computeIfAbsent(level.dimension().identifier().toString(), k -> new HashMap<>());

         for (BlockPos pos : watched) {
            if (pos.getX() >> 4 == chunkX && pos.getZ() >> 4 == chunkZ) {
               BlockStateFileReader.BlockStateEntry entry = BlockStateFileReader.read(
                  level.getServer(), level.dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ()
               );
               if (entry == null) {
                  entry = snapshot(chunk.getBlockState(pos));
               }

               if (entry != null) {
                  dimCache.put(pos, entry);
               }
            }
         }
      }
   }

   public static BlockStateFileReader.BlockStateEntry get(String dimension, int x, int y, int z) {
      Map<BlockPos, BlockStateFileReader.BlockStateEntry> dimCache = cache.get(dimension);
      if (dimCache == null) {
         return null;
      }
      return dimCache.get(new BlockPos(x, y, z));
   }

   private static BlockStateFileReader.BlockStateEntry snapshot(BlockState state) {
      Map<String, String> properties = new HashMap<>();

      for (Value<?> value : state.getValues().toList()) {
         properties.put(value.property().getName(), value.valueName());
      }

      return new BlockStateFileReader.BlockStateEntry(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), properties);
   }
}
