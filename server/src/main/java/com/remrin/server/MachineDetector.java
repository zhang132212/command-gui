package com.remrin.server;

import com.remrin.server.config.MachineConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property.Value;

public final class MachineDetector {
   private MachineDetector() {
   }

   public static MachineDetector.DetectionResult evaluateDetection(MachineConfig.DetectionData detection, MinecraftServer server, boolean useCache) {
      if (detection != null && detection.enabled) {
         ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(detection.dimension)));
         if (level == null) {
            return new MachineDetector.DetectionResult(MachineDetector.MachineState.ABNORMAL, "维度不存在");
         } else {
            BlockPos pos = new BlockPos(detection.x, detection.y, detection.z);
            String blockId;
            Map<String, String> current;
            if (level.hasChunkAt(pos)) {
               BlockState state = level.getBlockState(pos);
               blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
               current = new HashMap<>();

               for (Value<?> value : state.getValues().toList()) {
                  current.put(value.property().getName(), value.valueName());
               }
            } else if (useCache) {
               BlockStateFileReader.BlockStateEntry cached = MachineBlockCache.get(detection.dimension, detection.x, detection.y, detection.z);
               if (cached == null) {
                  // PERF: 回填磁盘读结果（含负缓存），避免同一坐标每秒重复读盘
                  if (MachineBlockCache.hasFreshDisk(detection.dimension, detection.x, detection.y, detection.z)) {
                     cached = MachineBlockCache.getDisk(detection.dimension, detection.x, detection.y, detection.z);
                  } else {
                     cached = BlockStateFileReader.read(server, detection.dimension, detection.x, detection.y, detection.z);
                     MachineBlockCache.putDiskResult(detection.dimension, detection.x, detection.y, detection.z, cached);
                  }
               }

               if (cached == null) {
                  return new MachineDetector.DetectionResult(MachineDetector.MachineState.ABNORMAL, "检测方块所在区块未加载，且内存快照与磁盘均无该方块数据");
               }

               blockId = cached.blockId();
               current = cached.properties();
            } else {
               BlockStateFileReader.BlockStateEntry entry = BlockStateFileReader.read(server, detection.dimension, detection.x, detection.y, detection.z);
               if (entry == null) {
                  return new MachineDetector.DetectionResult(MachineDetector.MachineState.ABNORMAL, "检测方块所在区块未生成且磁盘读取失败");
               }

               blockId = entry.blockId();
               current = entry.properties();
            }

            if (!normalizeId(blockId).equals(normalizeId(detection.blockId))) {
               return new MachineDetector.DetectionResult(MachineDetector.MachineState.ABNORMAL, "检测方块不匹配：实际是 " + blockId + "，配置是 " + detection.blockId);
            } else {
               boolean hasOn = false;
               boolean hasOff = false;
               String abnormalReason = null;

               for (Entry<String, String> entry : current.entrySet()) {
                  String key = entry.getKey() + "=" + entry.getValue();
                  if (contains(detection.onValues, key)) {
                     hasOn = true;
                  } else if (contains(detection.offValues, key)) {
                     hasOff = true;
                  } else if (!contains(detection.ignoreValues, key)) {
                     abnormalReason = "属性 " + entry.getKey() + "=" + entry.getValue() + " 未配置（异常）";
                     break;
                  }
               }

               if (abnormalReason != null) {
                  return new MachineDetector.DetectionResult(MachineDetector.MachineState.ABNORMAL, abnormalReason);
               } else if (hasOn && hasOff) {
                  return new MachineDetector.DetectionResult(MachineDetector.MachineState.ABNORMAL, "当前状态同时命中开机与关机配置");
               } else if (hasOn) {
                  return new MachineDetector.DetectionResult(MachineDetector.MachineState.ON, null);
               } else {
                  return hasOff
                     ? new MachineDetector.DetectionResult(MachineDetector.MachineState.OFF, null)
                     : new MachineDetector.DetectionResult(MachineDetector.MachineState.ABNORMAL, "当前状态全部为无关或未配置，无法判断开关");
               }
            }
         }
      } else {
         return new MachineDetector.DetectionResult(MachineDetector.MachineState.DISABLED, null);
      }
   }

   public static MachineDetector.DetectionResult evaluateDetailed(MachineConfig.MachineData machine, MinecraftServer server, boolean useCache) {
      return evaluateDetection(machine.detection, server, useCache);
   }

   public static MachineDetector.MachineState evaluate(MachineConfig.MachineData machine, MinecraftServer server) {
      return evaluateDetailed(machine, server, true).state();
   }

   private static boolean contains(List<String> values, String value) {
      return values != null && values.contains(value);
   }

   public static String normalizeId(String id) {
      if (id != null && !id.isEmpty()) {
         return id.contains(":") ? id : "minecraft:" + id;
      } else {
         return "";
      }
   }

   public static record DetectionResult(MachineDetector.MachineState state, String reason) {
   }

   public static enum MachineState {
      DISABLED,
      ON,
      OFF,
      ABNORMAL;
   }
}
