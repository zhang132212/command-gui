package com.remrin.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.storage.LevelResource;

public final class BlockStateFileReader {
   private static final int SECTOR_SIZE = 4096;
   private static final int REGION_WIDTH = 32;

   private BlockStateFileReader() {
   }

   public static BlockStateFileReader.BlockStateEntry read(MinecraftServer server, String dimension, int x, int y, int z) {
      try {
         Path regionDir = getRegionDir(server, dimension);
         if (regionDir != null && Files.exists(regionDir)) {
            int chunkX = x >> 4;
            int chunkZ = z >> 4;
            // 用 MC 自己的 RegionFileStorage（正确性优先）。曾尝试改用本文件的 readChunk() 以
            // 规避 close() 触发的 fsync，但 readChunk 的区域内索引写反了（见该方法注释），
            // 会读到转置坐标的另一个区块 -> 未加载区域误报异常。已回滚。
            // 读盘频次现已由 tickStates 门闩 + MachineBlockCache 磁盘缓存降到极低，fsync 不再是瓶颈。
            RegionStorageInfo info = new RegionStorageInfo(
               "minecraft", ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)), "chunk"
            );
            try (RegionFileStorage storage = new RegionFileStorage(info, regionDir, false)) {
               CompoundTag chunkNbt = storage.read(new ChunkPos(chunkX, chunkZ));
               if (chunkNbt == null) {
                  return null;
               }
               return readBlockState(chunkNbt, x, y, z);
            }
         } else {
            return null;
         }
      } catch (Exception var12) {
         MachineMod.LOGGER.debug("Failed to read block state at ({},{},{}) from disk: {}", new Object[]{x, y, z, var12.getMessage()});
         return null;
      }
   }

   /**
    * 未使用。注意：下面的 entryIndex 把 x/z 写反了，Anvil 标准为 (chunkX & 31) + (chunkZ & 31) * 32，
    * 直接启用会读到转置坐标处的另一个区块。若要复活此方法以避开 RegionFileStorage.close() 的 fsync，
    * 必须先修正该索引。
    */
   private static CompoundTag readChunk(Path regionFile, int chunkX, int chunkZ) throws IOException {
      byte[] header = new byte[8192];

      CompoundTag var13;
      try (RandomAccessFile raf = new RandomAccessFile(regionFile.toFile(), "r")) {
         if (raf.length() < (long)header.length) {
            return null;
         }

         raf.readFully(header);
         int entryIndex = (chunkZ & 31) + (chunkX & 31) * 32;
         int locationOffset = entryIndex * 4;
         int offset = (header[locationOffset] & 255) << 16 | (header[locationOffset + 1] & 255) << 8 | header[locationOffset + 2] & 255;
         int sectorCount = header[locationOffset + 3] & 255;
         if (offset == 0 || sectorCount == 0) {
            return null;
         }

         raf.seek((long)offset * 4096L);
         int length = raf.readInt();
         if (length <= 0 || length > 4096 * sectorCount) {
            return null;
         }

         int compressionType = raf.readByte();
         byte[] compressed = new byte[length - 1];
         raf.readFully(compressed);
         byte[] decompressed = decompress(compressed, compressionType);
         if (decompressed == null) {
            return null;
         }

         var13 = NbtIo.read(new DataInputStream(new ByteArrayInputStream(decompressed)), NbtAccounter.unlimitedHeap());
      }

      return var13;
   }

   private static BlockStateFileReader.BlockStateEntry readBlockState(CompoundTag chunkNbt, int x, int y, int z) {
      byte sectionY = (byte)(y >> 4);
      ListTag sections = chunkNbt.getListOrEmpty("sections");

      for (int i = 0; i < sections.size(); i++) {
         CompoundTag section = sections.getCompoundOrEmpty(i);
         if (section.getByteOr("Y", (byte)0) == sectionY) {
            CompoundTag blockStates = section.getCompoundOrEmpty("block_states");
            if (blockStates.isEmpty()) {
               return null;
            }

            ListTag palette = blockStates.getListOrEmpty("palette");
            if (palette.isEmpty()) {
               return null;
            }

            CompoundTag entry;
            if (palette.size() == 1) {
               entry = palette.getCompoundOrEmpty(0);
            } else {
               long[] data = (long[])blockStates.getLongArray("data").orElse(null);
               if (data == null) {
                  return null;
               }

               int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
               int index = (y & 15) * 256 + (z & 15) * 16 + (x & 15);
               int entriesPerLong = 64 / bits;
               int longIndex = index / entriesPerLong;
               int bitOffset = (index % entriesPerLong) * bits;
               if (longIndex >= data.length) {
                  return null;
               }

               long mask = (1L << bits) - 1L;
               int paletteIndex = (int)((data[longIndex] >>> bitOffset) & mask);
               if (paletteIndex < 0 || paletteIndex >= palette.size()) {
                  return null;
               }

               entry = palette.getCompoundOrEmpty(paletteIndex);
            }

            if (entry.isEmpty()) {
               return null;
            }

            String blockId = entry.getStringOr("Name", "");
            if (blockId.isEmpty()) {
               return null;
            }

            Map<String, String> properties = new HashMap<>();
            CompoundTag props = entry.getCompoundOrEmpty("Properties");
            if (!props.isEmpty()) {
               for (String key : props.keySet()) {
                  properties.put(key, props.getStringOr(key, ""));
               }
            }

            return new BlockStateFileReader.BlockStateEntry(blockId, properties);
         }
      }

      return null;
   }

   private static byte[] decompress(byte[] data, int compressionType) throws IOException {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      switch (compressionType) {
         case 1:
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
               gz.transferTo(out);
               break;
            }
         case 2:
            try (InflaterInputStream inf = new InflaterInputStream(new ByteArrayInputStream(data))) {
               inf.transferTo(out);
               break;
            }
         case 3:
            out.write(data);
            break;
         default:
            return null;
      }

      return out.toByteArray();
   }

   private static Path getRegionDir(MinecraftServer server, String dimension) throws IOException {
      Path worldDir = server.getWorldPath(LevelResource.ROOT);

      return switch (dimension) {
         case "minecraft:the_nether" -> regionOr(worldDir, "DIM-1", "dimensions/minecraft/the_nether");
         case "minecraft:the_end" -> regionOr(worldDir, "DIM1", "dimensions/minecraft/the_end");
         default -> regionOr(worldDir, "", "dimensions/minecraft/overworld");
      };
   }

   private static Path regionOr(Path worldDir, String legacySub, String modernSub) {
      if (!legacySub.isEmpty()) {
         Path legacy = worldDir.resolve(legacySub).resolve("region");
         if (Files.exists(legacy)) {
            return legacy;
         }
      } else {
         Path legacy = worldDir.resolve("region");
         if (Files.exists(legacy)) {
            return legacy;
         }
      }

      Path modern = worldDir.resolve(modernSub).resolve("region");
      if (Files.exists(modern)) {
         return modern;
      }
      return null;
   }

   public static record BlockStateEntry(String blockId, Map<String, String> properties) {
   }
}
