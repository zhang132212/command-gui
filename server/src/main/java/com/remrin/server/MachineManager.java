package com.remrin.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.remrin.server.config.MachineConfig;
import com.remrin.server.net.MachinePayloads;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.Property.Value;

public final class MachineManager {
   private static final Gson GSON = new GsonBuilder().create();
   private static final Map<String, MachineManager.EditLock> editLocks = new HashMap<>();
   private static String lastStateSignature = "";

   private MachineManager() {
   }

   public static void handleAction(ServerPlayer player, MinecraftServer server, MachinePayloads.ActionPayload payload) {
      try {
         JsonObject action = JsonParser.parseString(payload.json()).getAsJsonObject();
         String type = action.has("type") ? action.get("type").getAsString() : "";
         switch (type) {
            case "toggle":
               toggleMachine(player, server, getString(action, "machineId"));
               break;
            case "setModes":
               setModes(player, server, getString(action, "machineId"), parseModeIds(action));
               break;
            case "editSession":
               editSession(player, server, getString(action, "machineId"), action.has("open") && action.get("open").getAsBoolean());
               break;
            case "add":
               addMachine(player, server, action.getAsJsonObject("machine"));
               break;
            case "edit":
               editMachine(player, server, action.getAsJsonObject("machine"), action.has("baseRevision") ? action.get("baseRevision").getAsInt() : -1);
               break;
            case "delete":
               deleteMachine(player, server, getString(action, "machineId"));
               break;
            case "queryBlock":
               queryBlock(player, server, action);
               break;
            case "refreshDetection":
               refreshDetection(player, server, getString(action, "machineId"));
               break;
            case "clearCategory":
               clearCategory(player, server, getString(action, "categoryId"));
               break;
            case "requestSync":
               syncTo(player);
               MachineMod.subscribeMachineStates(player);
               break;
            case "machineStatesUnsubscribe":
               MachineMod.unsubscribeMachineStates(player);
               break;
            case "fakeStatesRequest":
               MachineMod.subscribeFakeStates(player, server);
               break;
            case "fakeStatesUnsubscribe":
               MachineMod.unsubscribeFakeStates(player);
               break;
            default:
               MachineMod.LOGGER.warn("Unknown machine action '{}' from {}", type, player.getGameProfile().name());
         }
      } catch (Exception var7) {
         MachineMod.LOGGER.warn("Malformed machine action from {}: {}", player.getGameProfile().name(), var7.getMessage());
      }
   }

   private static void toggleMachine(ServerPlayer player, MinecraftServer server, String machineId) {
      MachineConfig.MachineData machine = MachineConfig.getMachine(machineId);
      if (machine == null) {
         sendMessage(player, "机器不存在: " + machineId);
      } else if (!canToggle(player, machine)) {
         sendMessage(player, "你没有权限操作机器「" + machine.name + "」");
      } else {
         String editingBlock = editingLockedByOtherMessage(machine, player.getGameProfile().name());
         if (editingBlock != null) {
            sendMessage(player, editingBlock);
            return;
         }
         if (MachineScheduler.isRunning(machine.id)) {
            sendMessage(player, "机器「" + machine.name + "」正在开机/关机中，请稍候");
            return;
         }
         if (machine.detection != null && machine.detection.enabled) {
            int tick = server.getTickCount();
            int lockRemaining = MachineScheduler.switchLockRemaining(machine.id, tick);
            if (lockRemaining > 0) {
               sendMessage(player, "机器「" + machine.name + "」开机/关机之后有（剩余 " + lockRemaining + " tick）冷却时间，请稍后重试");
               MachineMod.LOGGER
                  .info("Machine '{}' toggle rejected by {}: switch interval ({} left)", new Object[]{machine.id, player.getGameProfile().name(), lockRemaining});
            } else {
               MachineDetector.DetectionResult detection = MachineDetector.evaluateDetailed(machine, server, true);
               MachineDetector.MachineState detected = detection.state();
               if (detected == MachineDetector.MachineState.ABNORMAL) {
                  sendMessage(player, "机器「" + machine.name + "」处于异常状态，无法开关：" + (detection.reason() != null ? detection.reason() : "检测方块异常"));
               } else {
                  if (detected == MachineDetector.MachineState.ON) {
                     MachineScheduler.start(machine, machine.offTimeline, player.getGameProfile().name(), true);
                  } else if (detected == MachineDetector.MachineState.OFF) {
                     bootMachine(player, machine);
                  } else if (MachineScheduler.isRunning(machine.id)) {
                     MachineScheduler.start(machine, machine.offTimeline, player.getGameProfile().name(), true);
                  } else {
                     bootMachine(player, machine);
                  }

                  MachineScheduler.recordSwitchTick(machine.id, tick);
                  broadcastSync(server.getPlayerList());
               }
            }
         } else {
            sendMessage(player, "机器「" + machine.name + "」未配置开关机检测，无法开关");
         }
      }
   }

   private static void refreshDetection(ServerPlayer player, MinecraftServer server, String machineId) {
      MachineConfig.MachineData machine = MachineConfig.getMachine(machineId);
      if (machine == null) {
         sendMessage(player, "机器不存在: " + machineId);
      } else if (!canToggle(player, machine)) {
         sendMessage(player, "你没有权限操作机器「" + machine.name + "」");
      } else if (machine.detection != null && machine.detection.enabled) {
         MachineDetector.DetectionResult result = MachineDetector.evaluateDetailed(machine, server, true);

         String message = switch (result.state()) {
            case ON -> "§a机器「" + machine.name + "」检测状态：开机";
            case OFF -> "机器「" + machine.name + "」检测状态：关机";
            case ABNORMAL -> "§c机器「" + machine.name + "」检测异常：" + (result.reason() != null ? result.reason() : "检测方块异常");
            default -> "机器「" + machine.name + "」检测未启用";
         };
         sendMessage(player, message);
         broadcastSync(server.getPlayerList());
      } else {
         sendMessage(player, "机器「" + machine.name + "」未配置检测方块");
      }
   }

   private static void clearCategory(ServerPlayer player, MinecraftServer server, String categoryId) {
      if (!isFullEditor(player)) {
         sendMessage(player, "你没有权限删除机器分类");
      } else if (categoryId != null && !categoryId.isEmpty()) {
         int cleared = 0;

         for (MachineConfig.MachineData machine : MachineConfig.getMachines()) {
            if (machine.category != null && machine.category.equals(categoryId)) {
               machine.category = "";
               cleared++;
            }
         }

         if (cleared > 0) {
            MachineConfig.save();
            MachineBlockCache.rebuild();
            sendMessage(player, "已清空分类「" + categoryId + "」下的 " + cleared + " 台机器");
         } else {
            sendMessage(player, "分类「" + categoryId + "」下没有机器");
         }

         broadcastSync(server.getPlayerList());
      } else {
         sendMessage(player, "默认分类不能删除");
      }
   }

   private static void bootMachine(ServerPlayer player, MachineConfig.MachineData machine) {
      MachineConfig.Timeline onTimeline = machine.onTimeline;
      MachineConfig.Step firstStep = firstStepEntry(onTimeline);
      boolean validStart = firstStep != null
         && firstStep.commands != null
         && !firstStep.commands.isEmpty()
         && isSpawnCommand(firstStep.commands.get(0));
      if (!validStart) {
         sendMessage(player, "机器「" + machine.name + "」开机流程第一个指令必须是假人spawn指令");
      } else {
         String firstCommand = firstStep.commands.get(0);
         String permissionError = MachineScheduler.checkCommandPermission(firstCommand, player.getGameProfile().name());
         if (permissionError != null) {
            sendMessage(player, "机器「" + machine.name + "」启动失败：无权限执行指令（" + permissionError + "）");
            MachineMod.LOGGER.warn("Machine '{}' boot blocked: {} — {}", new Object[]{machine.id, firstCommand, permissionError});
         } else {
            MachineScheduler.start(machine, machine.onTimeline, player.getGameProfile().name(), false);
         }
      }
   }

   private static void queryBlock(ServerPlayer player, MinecraftServer server, JsonObject action) {
      String dimension = getString(action, "dimension");
      if (dimension.isEmpty()) {
         dimension = "minecraft:overworld";
      }

      int x = action.has("x") ? action.get("x").getAsInt() : 0;
      int y = action.has("y") ? action.get("y").getAsInt() : 0;
      int z = action.has("z") ? action.get("z").getAsInt() : 0;
      long token = action.has("token") ? action.get("token").getAsLong() : -1L;
      JsonObject result = new JsonObject();
      result.addProperty("token", token);
      String blockId = null;
      Map<String, String> current = new HashMap<>();
      ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
      BlockPos pos = new BlockPos(x, y, z);
      if (level != null && level.hasChunkAt(pos)) {
         BlockState state = level.getBlockState(pos);
         blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();

         for (Value<?> value : state.getValues().toList()) {
            current.put(value.property().getName(), value.valueName());
         }
      } else if (level != null) {
         BlockStateFileReader.BlockStateEntry entry = MachineBlockCache.get(dimension, x, y, z);
         if (entry == null) {
            entry = BlockStateFileReader.read(server, dimension, x, y, z);
         }

         if (entry != null) {
            blockId = entry.blockId();
            current = entry.properties();
         }
      }

      if (blockId == null) {
         result.addProperty("found", false);
      } else {
         result.addProperty("found", true);
         result.addProperty("blockId", blockId);
         JsonObject properties = new JsonObject();
         Block block = (Block)BuiltInRegistries.BLOCK.getValue(Identifier.parse(blockId));
         if (block != null && block != Blocks.AIR) {
            for (Property<?> property : block.getStateDefinition().getProperties()) {
               JsonArray values = new JsonArray();

               for (Object value : property.getPossibleValues()) {
                  values.add(serializePropertyValue(property, value));
               }

               properties.add(property.getName(), values);
            }
         }

         result.add("properties", properties);
         JsonObject currentJson = new JsonObject();

         for (Entry<String, String> entry : current.entrySet()) {
            currentJson.addProperty(entry.getKey(), entry.getValue());
         }

         result.add("current", currentJson);
      }

      ServerPlayNetworking.send(player, new MachinePayloads.BlockQueryResultPayload(result.toString()));
   }

   private static void setModes(ServerPlayer player, MinecraftServer server, String machineId, List<String> desiredIds) {
      MachineConfig.MachineData machine = MachineConfig.getMachine(machineId);
      if (machine == null) {
         sendMessage(player, "机器不存在: " + machineId);
      } else if (!canToggle(player, machine)) {
         sendMessage(player, "你没有权限操作机器「" + machine.name + "」");
      } else {
         String editingBlock = editingLockedByOtherMessage(machine, player.getGameProfile().name());
         if (editingBlock != null) {
            sendMessage(player, editingBlock);
            return;
         }
         for (MachineConfig.ModeData busy : machine.modes) {
            if (MachineScheduler.isModeProcessRunning(machine.id, busy.id)) {
               sendMessage(player, "机器「" + machine.name + "」模式正在执行开机/关机流程，请稍候再切换");
               return;
            }
         }

         if (MachineScheduler.isRunning(machine.id)) {
            sendMessage(player, "机器「" + machine.name + "」正在开机/关机中，无法切换模式");
            return;
         }

         if (MachineModeChain.isActive(machine.id)) {
            sendMessage(player, "机器「" + machine.name + "」模式正在切换中，请稍候再操作");
         } else {
            int tick = server.getTickCount();

            for (MachineConfig.ModeData cooling : machine.modes) {
               int remaining = MachineScheduler.modeLockRemaining(machine.id, cooling.id, tick);
               if (remaining > 0) {
                  sendMessage(player, "机器「" + machine.name + "」模式切换冷却中（剩余 " + remaining + " tick），请稍后重试");
                  return;
               }
            }

            Set<String> desired = new HashSet<>(desiredIds);
            List<String> startIds = new ArrayList<>();
            List<String> stopIds = new ArrayList<>();
            int rejected = 0;

            for (MachineConfig.ModeData mode : machine.modes) {
               boolean selected = desired.contains(mode.id);
               MachineDetector.MachineState state = MachineDetector.evaluateDetection(mode.detection, server, true).state();
               MachineScheduler.updateDetectedModeState(machine.id, mode.id, state);
               if (state == MachineDetector.MachineState.ABNORMAL) {
                  if (selected) {
                     MachineDetector.DetectionResult abnormal = MachineDetector.evaluateDetection(mode.detection, server, true);
                     sendMessage(player, "模式「" + mode.name + "」检测异常，无法开启：" + (abnormal.reason() != null ? abnormal.reason() : "检测方块异常"));
                     MachineMod.LOGGER
                        .info("Mode '{}' of machine '{}' rejected by {}: detection abnormal", new Object[]{mode.id, machine.id, player.getGameProfile().name()});
                     rejected++;
                  }
               } else if (mode.singleSelect) {
                  boolean running = state == MachineDetector.MachineState.ON;
                  if (selected != running) {
                     if (!selected) {
                        stopIds.add(mode.id);
                     } else if (!validModeStart(mode)) {
                        sendMessage(player, "模式「" + mode.name + "」开启流程第一个指令必须是假人spawn指令");
                        MachineMod.LOGGER
                           .info("Mode '{}' of machine '{}' rejected by {}: invalid start", new Object[]{mode.id, machine.id, player.getGameProfile().name()});
                        rejected++;
                     } else {
                        startIds.add(mode.id);
                     }
                  }
               } else if (selected) {
                  if (state == MachineDetector.MachineState.ON) {
                     stopIds.add(mode.id);
                  } else if (!validModeStart(mode)) {
                     sendMessage(player, "模式「" + mode.name + "」开启流程第一个指令必须是假人spawn指令");
                     MachineMod.LOGGER
                        .info("Mode '{}' of machine '{}' rejected by {}: invalid start", new Object[]{mode.id, machine.id, player.getGameProfile().name()});
                     rejected++;
                  } else {
                     startIds.add(mode.id);
                  }
               }
            }

            MachineMod.LOGGER
               .info(
                  "Machine '{}' setModes by {}: start={} stop={} rejected={}",
                  new Object[]{machine.id, player.getGameProfile().name(), startIds, stopIds, rejected}
               );
            String triggerPlayer = player.getGameProfile().name();
            if (startIds.size() + stopIds.size() == 1) {
               if (stopIds.size() == 1) {
                  MachineScheduler.stopModeWithShutdown(machine, findMode(machine, stopIds.get(0)), triggerPlayer);
               } else {
                  MachineConfig.ModeData toStart = findMode(machine, startIds.get(0));
                  boolean needsReplacement = false;
                  if (toStart != null && toStart.singleSelect && machine.modes != null) {
                     for (MachineConfig.ModeData other : machine.modes) {
                        if (other.singleSelect && !other.id.equals(toStart.id) && MachineScheduler.isModeRunning(machine.id, other.id)) {
                           needsReplacement = true;
                           break;
                        }
                     }
                  }

                  if (needsReplacement) {
                     List<String> allStops = new ArrayList<>(stopIds);

                     for (MachineConfig.ModeData otherx : machine.modes) {
                        if (otherx.singleSelect && !otherx.id.equals(toStart.id) && MachineScheduler.isModeRunning(machine.id, otherx.id)) {
                           allStops.add(otherx.id);
                        }
                     }

                     MachineModeChain.buildChains(machine, startIds, allStops, triggerPlayer);
                     broadcastSync(server.getPlayerList());
                     return;
                  }

                  MachineScheduler.startMode(machine, toStart, triggerPlayer);
               }
            } else if (startIds.size() + stopIds.size() > 1) {
               MachineModeChain.buildChains(machine, startIds, stopIds, triggerPlayer);
            }

            if (startIds.size() + stopIds.size() == 0) {
               sendMessage(player, "机器「" + machine.name + "」没有需要变更的模式" + (rejected > 0 ? "（" + rejected + " 个被拒绝）" : ""));
            }

            broadcastSync(server.getPlayerList());
         }
      }
   }

   private static MachineConfig.ModeData findMode(MachineConfig.MachineData machine, String modeId) {
      if (machine.modes == null) {
         return null;
      } else {
         for (MachineConfig.ModeData mode : machine.modes) {
            if (mode.id != null && mode.id.equals(modeId)) {
               return mode;
            }
         }

         return null;
      }
   }

   private static void editSession(ServerPlayer player, MinecraftServer server, String machineId, boolean open) {
      String name = player.getGameProfile().name();
      if (open) {
         MachineConfig.MachineData machine = MachineConfig.getMachine(machineId);
         if (machine == null) {
            sendMessage(player, "机器不存在: " + machineId);
            return;
         }

         if (MachineScheduler.isRunning(machineId)) {
            sendMessage(player, "机器「" + machine.name + "」正在开机/关机中，无法编辑");
            return;
         }

         cleanupExpiredLocks();
         MachineManager.EditLock lock = editLocks.get(machineId);
         if (lock != null && !lock.editor().equals(name)) {
            sendMessage(player, "机器正在被 " + lock.editor() + " 编辑，无法打开编辑");
         } else {
            editLocks.put(machineId, new MachineManager.EditLock(name, System.currentTimeMillis()));
         }
      } else {
         MachineManager.EditLock lock = editLocks.get(machineId);
         if (lock != null && lock.editor().equals(name)) {
            editLocks.remove(machineId);
         }
      }

      broadcastSync(server.getPlayerList());
   }

   public static void onPlayerDisconnect(String name) {
      boolean removed = editLocks.entrySet().removeIf(entry -> entry.getValue().editor().equals(name));
      if (removed) {
         MinecraftServer server = MachineMod.getCurrentServer();
         if (server != null) {
            broadcastSync(server.getPlayerList());
         }
      }
   }

   /** 编辑锁兜底超时：60 分钟。正常流程由锁主显式释放（保存/关闭/断线）；客户端会每 10 分钟续期；
    *  子编辑器（时间线/模式等）停留时父屏 tick 暂停，因此阈值要足够宽松避免误清活跃编辑。 */
   private static final long EDIT_LOCK_TTL_MS = 3600000L;

   private static void cleanupExpiredLocks() {
      long now = System.currentTimeMillis();
      editLocks.entrySet().removeIf(entry -> now - entry.getValue().acquiredAt() > EDIT_LOCK_TTL_MS);
   }

   /**
    * 机器是否正被【其他】玩家编辑（编辑锁互斥）。
    * 返回被编辑时给请求者的提示文本；无他人编辑时返回 null。
    * 锁在自己名下（或无人持锁）视为可操作。
    */
   private static String editingLockedByOtherMessage(MachineConfig.MachineData machine, String requester) {
      MachineManager.EditLock lock = editLocks.get(machine.id);
      if (lock != null && !lock.editor().equals(requester)) {
         return "机器「" + machine.name + "」正在被 " + lock.editor() + " 编辑，请稍后再操作";
      }
      return null;
   }

   private static void addMachine(ServerPlayer player, MinecraftServer server, JsonObject machineJson) {
      if (!canEdit(player)) {
         sendMessage(player, "你没有权限编辑机器");
      } else {
         MachineConfig.MachineData machine = (MachineConfig.MachineData)GSON.fromJson(machineJson, MachineConfig.MachineData.class);
         if (machine == null) {
            sendMessage(player, "机器数据无效");
         } else {
            if (!isFullEditor(player)) {
               machine.permissionLevel = 2;
               machine.bannedPlayers = new ArrayList<>();
            }

            String error = validate(machine);
            if (error != null) {
               MachineMod.LOGGER.info("Machine add rejected by {}: {}", player.getGameProfile().name(), error);
               sendMessage(player, "机器配置无效: " + error);
            } else {
               machine.id = uniqueMachineId(machine.id);
               if (!MachineConfig.addMachine(machine)) {
                  sendMessage(player, "机器 id 已存在: " + machine.id);
               } else {
                  MachineBlockCache.rebuild();
                  sendMessage(player, "机器「" + machine.name + "」已添加");
                  broadcastSync(server.getPlayerList());
               }
            }
         }
      }
   }

   private static void editMachine(ServerPlayer player, MinecraftServer server, JsonObject machineJson, int baseRevision) {
      if (!canEdit(player)) {
         sendMessage(player, "你没有权限编辑机器");
      } else {
         MachineConfig.MachineData machine = (MachineConfig.MachineData)GSON.fromJson(machineJson, MachineConfig.MachineData.class);
         MachineConfig.MachineData existing = machine != null ? MachineConfig.getMachine(machine.id) : null;
         if (machine != null && existing != null) {
            if (!isFullEditor(player)) {
               machine.permissionLevel = existing.permissionLevel;
               machine.bannedPlayers = new ArrayList<>(existing.bannedPlayers);
            }

            MachineManager.EditLock lock = editLocks.get(machine.id);
            if (lock == null || !lock.editor().equals(player.getGameProfile().name())) {
               sendMessage(player, "机器「" + machine.name + "」正在被 " + (lock != null ? lock.editor() : "其他玩家") + " 编辑，无法保存");
            } else if (baseRevision >= 0 && baseRevision != existing.revision) {
               sendMessage(player, "机器已被其他人修改，请关闭后重新打开编辑");
            } else {
               String error = validate(machine);
               if (error != null) {
                  MachineMod.LOGGER.info("Machine edit rejected by {}: {}", player.getGameProfile().name(), error);
                  sendMessage(player, "机器配置无效: " + error);
               } else {
                  MachineConfig.updateMachine(machine);
                  MachineScheduler.invalidate(machine.id);
                  MachineModeChain.invalidate(machine.id);
                  MachineScheduler.clearSwitchTicks(machine.id);
                  MachineBlockCache.rebuild();
                  editLocks.remove(machine.id);
                  sendMessage(player, "机器「" + machine.name + "」已更新");
                  broadcastSync(server.getPlayerList());
               }
            }
         } else {
            sendMessage(player, "机器不存在: " + (machine != null ? machine.id : "?"));
         }
      }
   }

   private static void deleteMachine(ServerPlayer player, MinecraftServer server, String machineId) {
      if (!isFullEditor(player)) {
         sendMessage(player, "你没有权限删除机器（仅OP）");
      } else {
         MachineConfig.MachineData machine = MachineConfig.getMachine(machineId);
         if (machine == null) {
            sendMessage(player, "机器不存在: " + machineId);
         } else {
            String requester = player.getGameProfile().name();
            MachineManager.EditLock lock = editLocks.get(machineId);
            // 仅当锁被【其他】玩家持有时拦截；锁在自己名下（或无人持锁）允许删除
            if (lock != null && !lock.editor().equals(requester)) {
               sendMessage(player, "机器「" + machine.name + "」正在被 " + lock.editor() + " 编辑，无法删除");
            } else {
               editLocks.remove(machineId);
               MachineConfig.removeMachine(machineId);
               MachineScheduler.invalidate(machineId);
               MachineModeChain.invalidate(machineId);
               MachineScheduler.clearSwitchTicks(machineId);
               MachineBlockCache.rebuild();
               sendMessage(player, "机器「" + machine.name + "」已删除");
               broadcastSync(server.getPlayerList());
            }
         }
      }
   }

   private static String uniqueMachineId(String base) {
      if (MachineConfig.getMachine(base) == null) {
         return base;
      } else {
         int n = 2;

         while (MachineConfig.getMachine(base + "-" + n) != null) {
            n++;
         }

         return base + "-" + n;
      }
   }

   public static void syncTo(ServerPlayer player) {
      if (ServerPlayNetworking.canSend(player, MachinePayloads.SyncPayload.TYPE)) {
         String json = buildSyncJson(player);
         MachineMod.LOGGER.info("Sync machine list to {} ({} bytes)", player.getGameProfile().name(), json.length());
         ServerPlayNetworking.send(player, new MachinePayloads.SyncPayload(json));
      }
   }

   public static void broadcastSync(PlayerList playerList) {
      if (playerList != null) {
         for (ServerPlayer player : playerList.getPlayers()) {
            syncTo(player);
         }
      }
   }

   public static void tickStates(MinecraftServer server) {
      // PERF: 没人看面板 且 没有时序在跑 → 无需周期检测（该检测会落到磁盘读）。
      // hasRunning() 这一半不能省：本方法有副作用 MachineScheduler.updateDetectedModeState()，
      // 其消费者 MachineScheduler.isModeRunning() 是模式编排链的判断依据。
      if (!MachineMod.hasMachineStateSubscribers() && !MachineScheduler.hasRunning()) {
         return;
      }

      if (server.getTickCount() % 20 == 0) {
         StringBuilder sb = new StringBuilder();

         for (MachineConfig.MachineData machine : MachineConfig.getMachines()) {
            sb.append(machine.id).append('=').append(MachineScheduler.isRunning(machine.id)).append(',');
            if (machine.detection != null && machine.detection.enabled) {
               sb.append(MachineDetector.evaluate(machine, server).name()).append(',');
            }

            if (machine.modes != null) {
               for (MachineConfig.ModeData mode : machine.modes) {
                  if (mode.detection != null && mode.detection.enabled) {
                     MachineDetector.MachineState state = MachineDetector.evaluateDetection(mode.detection, server, true).state();
                     MachineScheduler.updateDetectedModeState(machine.id, mode.id, state);
                     sb.append(state);
                  } else {
                     MachineScheduler.updateDetectedModeState(machine.id, mode.id, MachineDetector.MachineState.DISABLED);
                     sb.append('D');
                  }

                  sb.append(MachineScheduler.isModeRunning(machine.id, mode.id));
               }
            }

            sb.append(';');
         }

         String signature = sb.toString();
         if (!signature.equals(lastStateSignature)) {
            lastStateSignature = signature;
            broadcastSync(server.getPlayerList());
         }
      }
   }

   public static void onMachineFinished(String machineId) {
      MachineConfig.MachineData machine = MachineConfig.getMachine(machineId);
      if (machine != null) {
         MachineBlockCache.invalidate(machine);
      }

      MinecraftServer server = MachineMod.getCurrentServer();
      if (server != null) {
         broadcastSync(server.getPlayerList());
      }
   }

   public static void onModeSwitchFinished(MachineConfig.MachineData machine, String triggerPlayer) {
      MachineMod.LOGGER.info("Machine '{}' mode switch finished (by {})", machine.id, triggerPlayer);
      if (triggerPlayer != null && !triggerPlayer.isEmpty()) {
         MinecraftServer server = MachineMod.getCurrentServer();
         if (server != null) {
            ServerPlayer player = server.getPlayerList().getPlayerByName(triggerPlayer);
            if (player != null) {
               sendMessage(player, "机器「" + machine.name + "」模式已切换");
            }
         }
      }
   }

   public static void onModeCommandFailed(
      MachineConfig.MachineData machine, String modeId, boolean isOffTimeline, String triggerPlayer, String command, String reason
   ) {
      String modeName = modeId;

      for (MachineConfig.ModeData mode : machine.modes) {
         if (mode.id != null && mode.id.equals(modeId)) {
            modeName = mode.name;
            break;
         }
      }

      String action = isOffTimeline ? "关闭" : "开启";
      MachineMod.LOGGER.warn("Mode '{}' of machine '{}' {} failed: {} ({})", new Object[]{modeId, machine.id, action, command, reason});
      String message = "模式「" + modeName + "」" + action + "失败：" + reason + "（" + command + "）";
      if (triggerPlayer != null && !triggerPlayer.isEmpty()) {
         MinecraftServer server = MachineMod.getCurrentServer();
         if (server != null) {
            ServerPlayer player = server.getPlayerList().getPlayerByName(triggerPlayer);
            if (player != null) {
               sendMessage(player, message);
            }
         }
      } else {
         broadcastSystem(message);
      }
   }

   public static void onSwitchCommandFailed(MachineConfig.MachineData machine, boolean isOffTimeline, String triggerPlayer, String command, String reason) {
      MachineMod.LOGGER.warn("Machine '{}' {} failed: {} ({})", new Object[]{machine.id, isOffTimeline ? "shutdown" : "boot", command, reason});
      if (triggerPlayer != null && !triggerPlayer.isEmpty()) {
         MinecraftServer server = MachineMod.getCurrentServer();
         if (server != null) {
            ServerPlayer player = server.getPlayerList().getPlayerByName(triggerPlayer);
            if (player != null) {
               sendMessage(player, "机器「" + machine.name + "」" + (isOffTimeline ? "关闭" : "启动") + "失败：" + reason + "（" + command + "）");
            }
         }
      } else {
         broadcastSystem("机器「" + machine.name + "」" + (isOffTimeline ? "关闭" : "启动") + "失败：" + reason + "（" + command + "）");
      }
   }

   public static void onSwitchTimelineFinished(MachineConfig.MachineData machine, boolean isOffTimeline, String triggerPlayer) {
      MachineMod.LOGGER.info("Machine '{}' {} finished", machine.id, isOffTimeline ? "shutdown" : "boot");
      if (triggerPlayer != null && !triggerPlayer.isEmpty()) {
         MinecraftServer server = MachineMod.getCurrentServer();
         if (server != null) {
            ServerPlayer player = server.getPlayerList().getPlayerByName(triggerPlayer);
            if (player != null) {
               sendMessage(player, "机器「" + machine.name + "」已" + (isOffTimeline ? "关闭" : "开启"));
            }
         }
      }
   }

   private static String buildSyncJson(ServerPlayer viewer) {
      // 注意：不要在这里 cleanupExpiredLocks()——sync 是全服周期广播触发的，
      // 若在此清理会把仍在编辑（只是子屏停留未 tick 续期）的玩家的锁误删。
      // 过期锁只在 editSession(open)（有人尝试抢锁）与超长兜底（见 cleanupExpiredLocks 阈值）时清理。
      JsonObject root = new JsonObject();
      root.addProperty("canEdit", canEdit(viewer));
      root.addProperty("canConfig", isFullEditor(viewer));
      MinecraftServer server = MachineMod.getCurrentServer();
      JsonArray machines = new JsonArray();

      for (MachineConfig.MachineData machine : MachineConfig.getMachines()) {
         JsonObject element = GSON.toJsonTree(machine).getAsJsonObject();
         boolean running = MachineScheduler.isRunning(machine.id);
         element.addProperty("running", running);
         element.addProperty("transition", running ? (MachineScheduler.isShuttingDown(machine.id) ? "off" : "on") : "");
         MachineManager.EditLock lock = editLocks.get(machine.id);
         element.addProperty("editingBy", lock != null ? lock.editor() : "");
         if (server != null) {
            MachineDetector.MachineState detected = MachineDetector.evaluate(machine, server);

            element.addProperty("detected", switch (detected) {
               case ON -> "on";
               case OFF -> "off";
               case ABNORMAL -> "abnormal";
               default -> "";
            });
         } else {
            element.addProperty("detected", "");
         }

         if (machine.modes != null) {
            JsonArray modes = element.getAsJsonArray("modes");
            if (modes != null) {
               for (int i = 0; i < modes.size() && i < machine.modes.size(); i++) {
                  JsonObject modeJson = modes.get(i).getAsJsonObject();
                  MachineConfig.ModeData mode = machine.modes.get(i);
                  String detected = "";
                  if (server != null && mode.detection != null && mode.detection.enabled) {
                     MachineDetector.MachineState modeDetected = MachineDetector.evaluateDetection(mode.detection, server, true).state();
                     MachineScheduler.updateDetectedModeState(machine.id, mode.id, modeDetected);

                     detected = switch (modeDetected) {
                        case ON -> "on";
                        case OFF -> "off";
                        case ABNORMAL -> "abnormal";
                        default -> "";
                     };
                  } else {
                     MachineScheduler.updateDetectedModeState(machine.id, mode.id, MachineDetector.MachineState.DISABLED);
                  }

                  boolean modeProcessing = MachineScheduler.isModeProcessRunning(machine.id, mode.id);
                  modeJson.addProperty("running", MachineScheduler.isModeRunning(machine.id, mode.id));
                  modeJson.addProperty("detected", detected);
                  modeJson.addProperty("processing", modeProcessing);
                  modeJson.addProperty("transition", modeProcessing ? (MachineScheduler.isModeShuttingDown(machine.id, mode.id) ? "off" : "on") : "");
               }
            }
         }

         machines.add(element);
      }

      root.add("machines", machines);
      return root.toString();
   }

   private static boolean canEdit(ServerPlayer player) {
      if (hasLevel(player, Commands.LEVEL_MODERATORS)) {
         return true;
      } else {
         for (String name : MachineConfig.getEditorWhitelist()) {
            if (name.equalsIgnoreCase(player.getGameProfile().name())) {
               return true;
            }
         }

         return false;
      }
   }

   private static boolean isFullEditor(ServerPlayer player) {
      return hasLevel(player, Commands.LEVEL_MODERATORS);
   }

   private static boolean canToggle(ServerPlayer player, MachineConfig.MachineData machine) {
      if (machine.bannedPlayers != null && machine.bannedPlayers.contains(player.getGameProfile().name())) {
         return false;
      } else {
         return switch (machine.permissionLevel) {
            case 0, 1 -> true;
            default -> hasLevel(player, Commands.LEVEL_MODERATORS);
            case 3 -> hasLevel(player, Commands.LEVEL_ADMINS);
            case 4 -> hasLevel(player, Commands.LEVEL_OWNERS);
         };
      }
   }

   private static boolean hasLevel(ServerPlayer player, PermissionCheck check) {
      return check.check(player.permissions());
   }

   private static String validate(MachineConfig.MachineData machine) {
      if (machine.id == null || machine.id.isBlank()) {
         return "id 不能为空";
      } else if (machine.name != null && !machine.name.isBlank()) {
         if (machine.category == null) {
            machine.category = "";
         }

         if (machine.category.length() > 30) {
            return "分类名称过长（最多30字符）";
         } else if (machine.bots == null || machine.bots.isEmpty()) {
            return "至少需要一个 bot 名称";
         } else if (machine.permissionLevel < 0 || machine.permissionLevel > 4) {
            return "权限等级必须在 0-4 之间";
         } else if (machine.switchInterval < 0 || machine.switchInterval > 1200) {
            return "开关间隔必须在 0-1200 tick 之间";
         } else if (machine.modeInterval < 0 || machine.modeInterval > 12000) {
            return "模式间隔必须在 0-12000 tick 之间";
         } else if (machine.stopModeInterval >= 0 && machine.stopModeInterval <= 12000) {
            for (int i = 0; i < machine.bots.size(); i++) {
               if (machine.bots.get(i) == null || machine.bots.get(i).isBlank()) {
                  return "bot 名称不能为空";
               }
            }

            String timelineError = validateTimeline(machine.onTimeline, machine.bots.size(), "开机流程");
            if (timelineError != null) {
               return timelineError;
            } else if (machine.onTimeline.steps.isEmpty()) {
               return "开机流程至少需要一个步骤";
            } else {
               MachineConfig.Step firstStep = firstStepEntry(machine.onTimeline);
               if (firstStep != null && firstStep.commands != null && !firstStep.commands.isEmpty() && isSpawnCommand(firstStep.commands.get(0))) {
                  timelineError = validateTimeline(machine.offTimeline, machine.bots.size(), "关闭流程");
                  if (timelineError != null) {
                     return timelineError;
                  } else {
                     if (machine.modes != null) {
                        Set<String> modeIds = new HashSet<>();

                        for (int ix = 0; ix < machine.modes.size(); ix++) {
                           MachineConfig.ModeData mode = machine.modes.get(ix);
                           String label = "模式" + (ix + 1);
                           if (mode == null) {
                              return label + "为空";
                           }

                           if (mode.id == null || mode.id.isBlank()) {
                              return label + " id 不能为空";
                           }

                           if (!modeIds.add(mode.id)) {
                              return label + " id 重复: " + mode.id;
                           }

                           if (mode.name == null || mode.name.isBlank()) {
                              return label + " 名称不能为空";
                           }

                           String modeError = validateTimeline(mode.onTimeline, machine.bots.size(), "模式「" + mode.name + "」");
                           if (modeError != null) {
                              return modeError;
                           }

                           if (mode.onTimeline.steps.isEmpty()) {
                              return "模式「" + mode.name + "」至少需要一个步骤";
                           }

                           mode.onTimeline.loopCount = 0;
                           mode.offTimeline.loopCount = 0;
                           if (mode.switchInterval < 0 || mode.switchInterval > 1200) {
                              return "模式「" + mode.name + "」开关间隔必须在 0-1200 tick 之间";
                           }

                           MachineConfig.Step modeFirst = firstStepEntry(mode.onTimeline);
                           if (modeFirst == null || modeFirst.commands == null || modeFirst.commands.isEmpty() || !isSpawnCommand(modeFirst.commands.get(0))) {
                              return "模式「" + mode.name + "」开启流程第一个指令必须是假人spawn指令";
                           }

                           String offError = validateTimeline(mode.offTimeline, machine.bots.size(), "模式「" + mode.name + "」关机流程");
                           if (offError != null) {
                              return offError;
                           }
                        }
                     }

                     if (machine.detection != null && machine.detection.enabled) {
                        String detectionError = validateDetection(machine.detection, "机器");
                        if (detectionError != null) {
                           return detectionError;
                        }
                     }

                     return null;
                  }
               } else {
                  return "开机流程第一个指令必须是假人spawn指令（如 /player {bot} spawn ...）";
               }
            }
         } else {
            return "停止间隔必须在 0-12000 tick 之间";
         }
      } else {
         return "名称不能为空";
      }
   }

   private static String validateDetection(MachineConfig.DetectionData detection, String label) {
      if (detection.dimension == null || detection.dimension.isBlank()) {
         return label + "检测维度不能为空";
      } else if (detection.blockId == null || detection.blockId.isBlank()) {
         return label + "检测方块不能为空";
      } else if ("minecraft:air".equals(MachineDetector.normalizeId(detection.blockId))) {
         return label + "检测方块不能是空气";
      } else if (detection.property == null || detection.property.isBlank()) {
         return label + "检测属性不能为空";
      } else if (detection.onValues != null && !detection.onValues.isEmpty()) {
         if (detection.offValues != null && !detection.offValues.isEmpty()) {
            Set<String> onValues = new HashSet<>(detection.onValues);

            for (String value : detection.offValues) {
               if (onValues.contains(value)) {
                  return label + "开机/关机状态不能重叠: " + value;
               }
            }

            return null;
         } else {
            return label + "关机状态至少配置一个";
         }
      } else {
         return label + "开机状态至少配置一个";
      }
   }

   private static String validateTimeline(MachineConfig.Timeline timeline, int botCount, String label) {
      if (timeline == null) {
         return label + "时间线为空";
      } else if (timeline.steps == null) {
         return label + "时间线步骤为空";
      } else if (timeline.loopCount < -1) {
         return label + "时间线循环次数不能小于 -1";
      } else {
         for (int i = 0; i < timeline.steps.size(); i++) {
            MachineConfig.Step step = timeline.steps.get(i);
            if (step == null) {
               return label + "时间线第 " + (i + 1) + " 步为空";
            }

            if (step.isDelay()) {
               if (step.delay < 1 || step.delay > 72000) {
                  return label + "第 " + (i + 1) + " 个延时条必须在 1-72000 tick 之间";
               }
            } else {
               if (step.commandDelay < 1 || step.commandDelay > 72000) {
                  return label + "时间线第 " + (i + 1) + " 步子指令延时必须在 1-72000 tick 之间";
               }

               if (step.bot < 0 || step.bot >= botCount) {
                  return label + "时间线第 " + (i + 1) + " 步 bot 下标越界 (0-" + (botCount - 1) + ")";
               }

               if (step.commands == null || step.commands.isEmpty()) {
                  return label + "时间线第 " + (i + 1) + " 步没有指令";
               }

               for (String command : step.commands) {
                  String error = validateTimelineCommand(command);
                  if (error != null) {
                     return label + "第 " + (i + 1) + " 步: " + error;
                  }
               }
            }
         }

         return null;
      }
   }

   public static MachineConfig.Step firstStepEntry(MachineConfig.Timeline timeline) {
      if (timeline != null && timeline.steps != null) {
         for (MachineConfig.Step step : timeline.steps) {
            if (step != null && !step.isDelay()) {
               return step;
            }
         }

         return null;
      } else {
         return null;
      }
   }

   private static String validateTimelineCommand(String command) {
      if (command != null && !command.isBlank()) {
         String cmd = command.trim();
         if (cmd.startsWith("/")) {
            cmd = cmd.substring(1);
         }

         String lower = cmd.toLowerCase();
         String root = lower.split("\\s+", 2)[0];
         switch (root) {
            case "op":
            case "deop":
            case "stop":
            case "save-all":
            case "save-off":
            case "save-on":
            case "ban":
            case "ban-ip":
            case "pardon":
            case "pardon-ip":
            case "kick":
            case "whitelist":
            case "seed":
            case "kill":
            case "gamemode":
            case "give":
            case "effect":
            case "execute":
            case "function":
            case "run":
            case "forceload":
            case "datapack":
            case "reload":
            case "publish":
            case "debug":
            case "perf":
            case "tick":
            case "jfr":
            case "msg":
               return "指令「" + root + "」不允许写入机器脚本";
            default:
               return null;
         }
      } else {
         return "指令为空";
      }
   }

   private static List<String> parseModeIds(JsonObject action) {
      List<String> result = new ArrayList<>();
      if (action.has("modeIds") && action.get("modeIds").isJsonArray()) {
         for (JsonElement element : action.getAsJsonArray("modeIds")) {
            result.add(element.getAsString());
         }
      }

      return result;
   }

   public static boolean validModeStart(MachineConfig.ModeData mode) {
      MachineConfig.Step firstStep = firstStepEntry(mode.onTimeline);
      return firstStep != null
         && firstStep.commands != null
         && !firstStep.commands.isEmpty()
         && isSpawnCommand(firstStep.commands.get(0));
   }

   private static boolean isSpawnCommand(String command) {
      if (command == null) {
         return false;
      } else {
         String cmd = command.trim().toLowerCase();
         if (cmd.startsWith("/")) {
            cmd = cmd.substring(1);
         }

         return cmd.startsWith("player ") && cmd.contains(" spawn");
      }
   }

   private static String serializePropertyValue(Property property, Object value) {
      return property.getName((Comparable)value);
   }

   private static String getString(JsonObject object, String key) {
      if (object.has(key)) {
         return object.get(key).getAsString();
      }
      return "";
   }

   private static void sendMessage(ServerPlayer player, String message) {
      player.sendSystemMessage(Component.literal(message));
   }

   public static void broadcastSystem(String message) {
      MinecraftServer server = MachineMod.getCurrentServer();
      if (server != null && server.getPlayerList() != null) {
         server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
      }
   }

   private static record EditLock(String editor, long acquiredAt) {
   }
}
