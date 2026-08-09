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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Reads a block state directly from the dimension's region files ({@code .mca}) on disk, without
 * requiring the chunk to be loaded by the server. Used by the machine detection feature to find a
 * block at arbitrary coordinates (same technique as the eun_search container scanner).
 * <p>
 * Note: the on-disk state may lag behind in-memory changes (region files are written periodically),
 * so this should only be used when the chunk is not loaded.
 */
public final class BlockStateFileReader {

  private static final int SECTOR_SIZE = 4096;
  private static final int REGION_WIDTH = 32;

  private BlockStateFileReader() {
  }

  /**
   * The identity of a block read from disk: its id and the current values of its state properties.
   */
  public record BlockStateEntry(String blockId, Map<String, String> properties) {
  }

  /**
   * Reads the block at the given coordinates from the region files. Returns {@code null} when the
   * dimension, region file, chunk, section or palette cannot be resolved (e.g. the position was
   * never generated).
   */
  public static BlockStateEntry read(MinecraftServer server, String dimension, int x, int y, int z) {
    try {
      Path regionDir = getRegionDir(server, dimension);
      if (regionDir == null || !Files.exists(regionDir)) {
        return null;
      }
      int regionX = x >> 9;
      int regionZ = z >> 9;
      Path regionFile = regionDir.resolve("r." + regionX + "." + regionZ + ".mca");
      if (!Files.exists(regionFile)) {
        return null;
      }
      int chunkX = x >> 4;
      int chunkZ = z >> 4;
      CompoundTag chunkNbt = readChunk(regionFile, chunkX, chunkZ);
      if (chunkNbt == null) {
        return null;
      }
      return readBlockState(chunkNbt, x, y, z);
    } catch (Exception e) {
      MachineMod.LOGGER.debug("Failed to read block state at ({},{},{}) from disk: {}",
          x, y, z, e.getMessage());
      return null;
    }
  }

  /**
   * Reads and decompresses a chunk's NBT from a region file. Returns {@code null} on any failure.
   */
  private static CompoundTag readChunk(Path regionFile, int chunkX, int chunkZ) throws IOException {
    byte[] header = new byte[SECTOR_SIZE * 2];
    try (RandomAccessFile raf = new RandomAccessFile(regionFile.toFile(), "r")) {
      if (raf.length() < header.length) {
        return null;
      }
      raf.readFully(header);

      int entryIndex = (chunkZ & (REGION_WIDTH - 1)) + (chunkX & (REGION_WIDTH - 1)) * REGION_WIDTH;
      int locationOffset = entryIndex * 4;
      int offset = ((header[locationOffset] & 0xFF) << 16)
          | ((header[locationOffset + 1] & 0xFF) << 8)
          | (header[locationOffset + 2] & 0xFF);
      int sectorCount = header[locationOffset + 3] & 0xFF;
      if (offset == 0 || sectorCount == 0) {
        return null;
      }

      raf.seek((long) offset * SECTOR_SIZE);
      int length = raf.readInt();
      if (length <= 0 || length > SECTOR_SIZE * sectorCount) {
        return null;
      }
      int compressionType = raf.readByte();
      byte[] compressed = new byte[length - 1];
      raf.readFully(compressed);

      byte[] decompressed = decompress(compressed, compressionType);
      if (decompressed == null) {
        return null;
      }
      return NbtIo.read(new DataInputStream(new ByteArrayInputStream(decompressed)),
          NbtAccounter.unlimitedHeap());
    }
  }

  /**
   * Extracts the block state entry for a position from a parsed chunk: finds the section, decodes
   * the palette index from the packed long array and returns the palette entry's name/properties.
   * A missing section or an empty block_states compound means air.
   */
  private static BlockStateEntry readBlockState(CompoundTag chunkNbt, int x, int y, int z) {
    byte sectionY = (byte) (y >> 4);
    ListTag sections = chunkNbt.getListOrEmpty("sections");
    for (int i = 0; i < sections.size(); i++) {
      CompoundTag section = sections.getCompoundOrEmpty(i);
      if (section.getByteOr("Y", (byte) 0) != sectionY) {
        continue;
      }
      CompoundTag blockStates = section.getCompoundOrEmpty("block_states");
      if (blockStates.isEmpty()) {
        return null; // empty section = air
      }
      ListTag palette = blockStates.getListOrEmpty("palette");
      if (palette.isEmpty()) {
        return null;
      }
      CompoundTag entry;
      if (palette.size() == 1) {
        entry = palette.getCompoundOrEmpty(0);
      } else {
        long[] data = blockStates.getLongArray("data").orElse(null);
        if (data == null) {
          return null;
        }
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        int index = (y & 15) * 256 + (z & 15) * 16 + (x & 15);
        int bitIndex = index * bits;
        int longIndex = bitIndex >> 6;
        int bitOffset = bitIndex & 63;
        if (longIndex >= data.length) {
          return null;
        }
        long window;
        if (bitOffset + bits <= 64) {
          window = data[longIndex] >> bitOffset;
        } else {
          if (longIndex + 1 >= data.length) {
            return null;
          }
          window = (data[longIndex] >>> bitOffset)
              | (data[longIndex + 1] << (64 - bitOffset));
        }
        long mask = (1L << bits) - 1;
        int paletteIndex = (int) (window & mask);
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
      return new BlockStateEntry(blockId, properties);
    }
    return null; // section not found = air
  }

  /**
   * Decompresses chunk data: 1 = gzip, 2 = zlib, 3 = none. Other types return {@code null}.
   */
  private static byte[] decompress(byte[] data, int compressionType) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    switch (compressionType) {
      case 1 -> {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
          gz.transferTo(out);
        }
      }
      case 2 -> {
        try (InflaterInputStream inf = new InflaterInputStream(new ByteArrayInputStream(data))) {
          inf.transferTo(out);
        }
      }
      case 3 -> out.write(data);
      default -> {
        return null;
      }
    }
    return out.toByteArray();
  }

  /**
   * Resolves the region directory for a dimension, supporting both legacy (DIM-1 / DIM1) and
   * modern (dimensions/&lt;namespace&gt;/&lt;path&gt;) world folder layouts.
   */
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
    return Files.exists(modern) ? modern : null;
  }
}
