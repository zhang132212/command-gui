package com.remrin.client.gui;

import com.remrin.client.config.SettingsConfig;
import com.remrin.client.machine.MachineNetworkManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

public class FakePlayerTab implements Tab {
   private static final int PLAYER_ITEM_WIDTH = 148;
   private static final int PLAYER_ITEM_HEIGHT = 26;
   private static final int ITEM_GAP = 0;
   private static final int FACE_SIZE = 16;
   private static final int ACTION_BUTTON_WIDTH = 80;
   private static final int ACTION_BUTTON_HEIGHT = 20;
   private static final int SEPARATOR_WIDTH = 1;
   private static final int PANEL_SCROLL_STEP = 20;
   private static final int FACE_PAD_LEFT = 4;
   private static final int NAME_PAD_LEFT = 4;
   private static final int CHECKBOX_SIZE = 12;
   private static final int CHECKBOX_X_OFFSET = 2;
   private static final int LIST_BOTTOM_RESERVE = 36;
   private static final Identifier BUTTON_SPRITE = Identifier.withDefaultNamespace("widget/button");
   private static final Identifier BUTTON_HIGHLIGHTED_SPRITE = Identifier.withDefaultNamespace("widget/button_highlighted");
   private static final Identifier TELEPORT_TO_PLAYER_SPRITE = Identifier.withDefaultNamespace("spectator/teleport_to_player");
   private static final Identifier REMOVE_PLAYER_SPRITE = Identifier.withDefaultNamespace("player_list/remove_player");
   private static final Identifier CLOCK_SPRITE = Identifier.parse("command-gui:icon/clock");
   private static final String[] ACTIONS = new String[]{
      "stop", "kill", "attack continuous", "attack once", "use continuous", "use once", "jump continuous", "sneak", "sprint", "drop", "dropStack"
   };
   private static final String[] ACTION_KEYS = new String[]{
      "screen.command-gui.fakeplayer.action.stop",
      "screen.command-gui.fakeplayer.action.kill",
      "screen.command-gui.fakeplayer.action.attack",
      "screen.command-gui.fakeplayer.action.attack_once",
      "screen.command-gui.fakeplayer.action.use",
      "screen.command-gui.fakeplayer.action.use_once",
      "screen.command-gui.fakeplayer.action.jump",
      "screen.command-gui.fakeplayer.action.sneak",
      "screen.command-gui.fakeplayer.action.sprint",
      "screen.command-gui.fakeplayer.action.drop",
      "screen.command-gui.fakeplayer.action.dropstack"
   };
   private static final Map<String, String> PERSISTENT_ACTION_KEYS = Map.of(
      "attack continuous", "attack", "use continuous", "use", "jump continuous", "jump", "sneak", "sneak", "sprint", "sprint"
   );
   private static final Map<String, String> PERSISTENT_ACTION_STOPS = Map.of(
      "attack continuous", "attack once", "use continuous", "use once", "jump continuous", "jump once", "sneak", "unsneak", "sprint", "unsprint"
   );
   private static String rememberedSelectedPlayer = null;
   private static int rememberedPlayerScrollOffset = 0;
   private boolean restoreRememberedScrollPending = false;
   private final CommandGUIScreen parent;
   private final List<Button> playerButtons = new ArrayList<>();
   private final List<Button> checkboxButtons = new ArrayList<>();
   private final List<Button> actionButtons = new ArrayList<>();
   private final List<Button> batchButtons = new ArrayList<>();
   private final List<Button> modeButtons = new ArrayList<>();
   private final List<EditBox> intervalFields = new ArrayList<>();
   private EditBox intervalField = null;
   private boolean intervalFieldLocked = false;
   private final List<String> displayList = new ArrayList<>();
   private final Map<String, PlayerInfo> playerInfoCache = new HashMap<>();
   private final Map<String, Integer> zeroLatencyStreak = new HashMap<>();
   private final Set<String> multiSelection = new LinkedHashSet<>();
   private ScreenRectangle area;
   private int scrollOffset = 0;
   private int panelScrollOffset = 0;
   private int panelContentBottom = 0;
   private final Map<String, Boolean> localActionStates = new HashMap<>();
   private final Map<String, Integer> localActionOverrideTicks = new HashMap<>();
   private String searchText = "";
   private String selectedPlayer = null;
   private int separatorX = 0;
   private static int intervalTicks = 20;
   private boolean intervalFieldTouched = false;
   private int panelIntervalLabelX = 0;
   private int panelIntervalLabelY = 0;
   private Runnable onBeforeRebuild;
   private Runnable onAfterRebuild;
   private int playerListX;
   private int playerItemWidth = 148;

   public FakePlayerTab(CommandGUIScreen parent) {
      this.parent = parent;
   }

   private boolean isRememberView() {
      return SettingsConfig.getBoolean("fakeplayer_remember_view");
   }

   public Component getTabTitle() {
      return Component.translatable("screen.command-gui.tab.fakeplayer");
   }

   public Component getTabExtraNarration() {
      return Component.empty();
   }

   public Layout getLayout() {
      return new FrameLayout();
   }

   public void visitChildren(Consumer consumer) {
      this.playerButtons.forEach(consumer);
      this.checkboxButtons.forEach(consumer);
      this.actionButtons.forEach(consumer);
      this.batchButtons.forEach(consumer);
      this.modeButtons.forEach(consumer);
      this.intervalFields.forEach(consumer);
   }

   public void doLayout(ScreenRectangle rectangle) {
      this.area = rectangle;
      this.scrollOffset = 0;
      this.restoreRememberedScrollPending = this.isRememberView() && rememberedSelectedPlayer != null;
      this.refresh();
   }

   public void refresh() {
      this.playerButtons.clear();
      this.checkboxButtons.clear();
      this.actionButtons.clear();
      this.batchButtons.clear();
      this.modeButtons.clear();
      this.displayList.clear();
      this.playerInfoCache.clear();
      Minecraft mc = Minecraft.getInstance();
      if (mc.getConnection() != null) {
         for (PlayerInfo info : mc.getConnection().getListedOnlinePlayers()) {
            this.playerInfoCache.put(info.getProfile().name(), info);
         }
      }

      for (TimedTaskManager.TimedTask task : TimedTaskManager.getPendingSpawnTasks()) {
         if (this.searchText.isEmpty() || task.playerName.toLowerCase().contains(this.searchText.toLowerCase())) {
            this.displayList.add(task.playerName);
         }
      }

      for (Entry<String, PlayerInfo> entry : this.playerInfoCache.entrySet()) {
         String name = entry.getKey();
         int streak = entry.getValue().getLatency() == 0 ? this.zeroLatencyStreak.getOrDefault(name, 0) + 1 : 0;
         this.zeroLatencyStreak.put(name, streak);
      }

      this.zeroLatencyStreak.keySet().retainAll(this.playerInfoCache.keySet());
      if (MachineNetworkManager.isFakePlayerStatesSupported() && this.selectedPlayer != null) {
         for (String key : new ArrayList<>(this.localActionStates.keySet())) {
            boolean local = this.localActionStates.get(key);
            boolean server = MachineNetworkManager.fakeActionActive(this.selectedPlayer, key);
            if (server == local) {
               this.localActionStates.remove(key);
               this.localActionOverrideTicks.remove(key);
            } else {
               int age = this.localActionOverrideTicks.getOrDefault(key, 0) + 1;
               if (age >= 3) {
                  this.localActionStates.remove(key);
                  this.localActionOverrideTicks.remove(key);
               } else {
                  this.localActionOverrideTicks.put(key, age);
               }
            }
         }
      }

      for (Entry<String, PlayerInfo> entry : this.playerInfoCache.entrySet()) {
         String name = entry.getKey();
         boolean authoritative = MachineNetworkManager.isFakePlayerStatesSupported();
         boolean isFake = authoritative
            ? MachineNetworkManager.isServerFakePlayer(name)
            : CommandHelper.isFakePlayer(entry.getValue()) && this.zeroLatencyStreak.getOrDefault(name, 0) >= 2;
         if (isFake && !this.displayList.contains(name) && (this.searchText.isEmpty() || name.toLowerCase().contains(this.searchText.toLowerCase()))) {
            this.displayList.add(name);
         }
      }

      if (this.selectedPlayer != null && !this.displayList.contains(this.selectedPlayer)) {
         this.selectedPlayer = null;
      }

      if (this.selectedPlayer == null && this.isRememberView() && rememberedSelectedPlayer != null && this.displayList.contains(rememberedSelectedPlayer)) {
         this.selectedPlayer = rememberedSelectedPlayer;
      }

      if (this.restoreRememberedScrollPending
         && this.isRememberView()
         && rememberedSelectedPlayer != null
         && this.displayList.contains(rememberedSelectedPlayer)) {
         this.scrollOffset = rememberedPlayerScrollOffset;
      }

      this.restoreRememberedScrollPending = false;
      this.scrollOffset = Math.max(0, Math.min(this.scrollOffset, this.getMaxScroll()));
      this.multiSelection.removeIf(namex -> !this.displayList.contains(namex));
      if (this.area != null) {
         this.buildLayoutConstants();
         this.rebuildPlayerButtons();
         this.rebuildActionButtons();
         this.parent.updateFakePlayerFooter();
      }
   }

   private void buildLayoutConstants() {
      this.playerItemWidth = 8 + Math.max(50, this.area.width() / 4);
      this.playerListX = this.area.left();
      this.separatorX = this.area.left() + this.playerItemWidth + 20;
   }

   private int visiblePlayerRows() {
      if (this.area == null) {
         return 1;
      }
      return Math.max(1, (this.area.height() - 36) / 26);
   }

   private int listRowsBottom() {
      if (this.area != null) {
         return this.area.bottom() - 36;
      }
      return 0;
   }

   private void rebuildPlayerButtons() {
      this.playerButtons.clear();
      if (this.area != null) {
         int startY = this.area.top();
         int visibleRows = this.visiblePlayerRows();
         int startIndex = this.scrollOffset;
         int endIndex = Math.min(startIndex + visibleRows + 1, this.displayList.size());
         int checkboxX = this.playerListX + 2;
         int checkboxY = startY + 7;
         int buttonX = this.playerListX + 2 + 12 + 4;
         int buttonW = this.playerItemWidth - 18;

         for (int i = startIndex; i < endIndex; i++) {
            String playerName = this.displayList.get(i);
            int y = startY + (i - this.scrollOffset) * 26;
            if (y + 26 > this.listRowsBottom()) {
               break;
            }

            Button btn = Button.builder(Component.empty(), b -> this.selectPlayer(playerName)).bounds(buttonX, y, buttonW, 26).build();
            Font font = Minecraft.getInstance().font;
            int faceX = buttonX + 4;
            int nameX = faceX + 16 + 4;
            TimedTaskManager.TimedTask task = TimedTaskManager.getTask(playerName);
            int timerWidth = 0;
            if (task != null) {
               String timeStr = this.formatTime(task.getRemainingSeconds());
               int clockSize = 10;
               int clockTimerGap = 2;
               timerWidth = font.width(timeStr) + clockSize + clockTimerGap + 6;
            }

            int maxNameWidth = this.playerListX + this.playerItemWidth - nameX - timerWidth - 4;
            if (font.width(playerName) > maxNameWidth) {
               btn.setTooltip(Tooltip.create(Component.literal("名称:" + playerName)));
            }

            this.playerButtons.add(btn);
            Button checkbox = Button.builder(Component.empty(), b -> this.toggleMultiSelection(playerName))
               .bounds(checkboxX, checkboxY + (i - this.scrollOffset) * 26, 12, 12)
               .build();
            this.checkboxButtons.add(checkbox);
         }
      }
   }

   private void toggleMultiSelection(String playerName) {
      if (!this.multiSelection.add(playerName)) {
         this.multiSelection.remove(playerName);
      }

      this.fireBeforeRebuild();
      this.rebuildActionButtons();
      this.fireAfterRebuild();
      this.parent.updateFakePlayerFooter();
   }

   private boolean isPendingSpawn(String name) {
      TimedTaskManager.TimedTask task = TimedTaskManager.getTask(name);
      return task != null && task.type == TimedTaskManager.TaskType.SPAWN;
   }

   private boolean isOnlineFakePlayer(String name) {
      PlayerInfo info = this.playerInfoCache.get(name);
      if (info == null) {
         return false;
      } else {
         if (MachineNetworkManager.isFakePlayerStatesSupported()) {
            return MachineNetworkManager.isServerFakePlayer(name);
         }
         return CommandHelper.isFakePlayer(info);
      }
   }

   private void selectPlayer(String playerName) {
      if (playerName.equals(this.selectedPlayer)) {
         this.selectedPlayer = null;
      } else {
         this.selectedPlayer = playerName;
      }

      rememberedSelectedPlayer = this.isRememberView() ? this.selectedPlayer : null;
      this.panelScrollOffset = 0;
      this.intervalFieldTouched = false;
      intervalTicks = 0;
      this.localActionStates.clear();
      this.localActionOverrideTicks.clear();
      this.fireBeforeRebuild();
      this.refresh();
      this.fireAfterRebuild();
   }

   public void setOnRebuild(Runnable before, Runnable after) {
      this.onBeforeRebuild = before;
      this.onAfterRebuild = after;
   }

   private void fireBeforeRebuild() {
      if (this.onBeforeRebuild != null) {
         this.onBeforeRebuild.run();
      }
   }

   private void fireAfterRebuild() {
      if (this.onAfterRebuild != null) {
         this.onAfterRebuild.run();
      }
   }

   private void rebuildActionButtons() {
      this.actionButtons.clear();
      this.batchButtons.clear();
      this.modeButtons.clear();
      if (this.area != null) {
         Minecraft mc = Minecraft.getInstance();
         int rightX = this.separatorX + 20;
         int actionCols = 2;
         int availWidth = Math.max(160, this.area.right() - rightX - 4);
         int actionBtnW = Math.min(160, (availWidth - (actionCols - 1) * 0) / actionCols);
         if (this.selectedPlayer == null) {
            this.panelContentBottom = 0;
            if (this.intervalField != null) {
               this.intervalField.visible = false;
            }

            this.intervalFieldLocked = false;
         } else {
            boolean isPending = this.isPendingSpawn(this.selectedPlayer);
            TimedTaskManager.TimedTask killTask = TimedTaskManager.getTask(this.selectedPlayer);
            boolean hasKillTask = killTask != null && killTask.type == TimedTaskManager.TaskType.KILL;
            int totalHeight;
            if (isPending) {
               totalHeight = 20;
            } else {
               int actionCount = 0;

               for (int i = 0; i < ACTIONS.length; i++) {
                  if (i != 1 || !hasKillTask) {
                     actionCount++;
                  }
               }

               int rows = (actionCount + 1) / actionCols;
               int intervalRowY = rows * 20 + 6;
               int timedKillY = intervalRowY + 20 + 0 + 6;
               totalHeight = timedKillY + 20;
            }

            this.panelContentBottom = this.area.top() + totalHeight;
            this.panelScrollOffset = Math.max(0, Math.min(this.panelScrollOffset, this.getPanelMaxScroll()));
            int contentY = this.area.top() - this.panelScrollOffset * 20;
            int panelBottom = this.area.bottom();
            if (isPending) {
               if (contentY + 20 <= panelBottom && contentY >= this.area.top()) {
                  Button cancelBtn = Button.builder(
                        Component.literal("x ")
                           .withStyle(ChatFormatting.RED)
                           .append(Component.translatable("screen.command-gui.fakeplayer.timed.cancel").withStyle(ChatFormatting.WHITE)),
                        b -> {
                           TimedTaskManager.removeTask(this.selectedPlayer);
                           this.selectedPlayer = null;
                           this.fireBeforeRebuild();
                           this.refresh();
                           this.fireAfterRebuild();
                        }
                     )
                     .bounds(rightX, contentY, actionBtnW * 2 + 0, 20)
                     .build();
                  this.actionButtons.add(cancelBtn);
               }

               if (this.intervalField != null) {
                  this.intervalField.visible = false;
               }

               this.intervalFieldLocked = false;
            } else {
               String player = this.selectedPlayer;
               int idx = 0;

               for (int ix = 0; ix < ACTIONS.length; ix++) {
                  if (ix != 1 || !hasKillTask) {
                     int row = idx / actionCols;
                     int col = idx % actionCols;
                     int x = rightX + col * (actionBtnW + 0);
                     int y = contentY + row * 20;
                     String action = ACTIONS[ix];
                     String persistentKey = PERSISTENT_ACTION_KEYS.get(action);
                     if (y + 20 <= panelBottom && y >= this.area.top()) {
                        Button btn;
                        if (persistentKey != null) {
                           DarkSelectButton toggle = new DarkSelectButton(
                              x, y, actionBtnW, 20, Component.translatable(ACTION_KEYS[ix]), b -> this.executePersistentAction(player, action, persistentKey)
                           );
                           toggle.setDarkSelected(() -> this.isActionActive(player, persistentKey), -1);
                           btn = toggle;
                        } else {
                           btn = Button.builder(Component.translatable(ACTION_KEYS[ix]), b -> this.executeAction(player, action))
                              .bounds(x, y, actionBtnW, 20)
                              .build();
                        }

                        this.actionButtons.add(btn);
                     }

                     idx++;
                  }
               }

               int rowsUsed = (idx + 1) / actionCols;
               int intervalRowY = contentY + rowsUsed * 20 + 6;
               int gap = 4;
               int intervalLabelW = 52;
               int intervalFieldW = 56;
               int intervalFixedW = intervalLabelW + gap + intervalFieldW;
               int intervalBtnW = Math.max(70, (availWidth - intervalFixedW - 3 * gap) / 2);
               if (intervalRowY + 20 <= panelBottom && intervalRowY >= this.area.top()) {
                  this.panelIntervalLabelX = rightX;
                  this.panelIntervalLabelY = intervalRowY;
                  boolean intervalActive = this.isActionActive(player, "attackInterval") || this.isActionActive(player, "useInterval");
                  int fieldX = rightX + intervalLabelW + gap;
                  if (this.intervalField == null) {
                     this.intervalField = new DigitsOnlyEditBox(mc.font, fieldX, intervalRowY, intervalFieldW, 20, Component.literal(""));
                     this.intervalField.setMaxLength(5);
                     this.intervalField.setHint(Component.literal("1-72000"));
                     this.intervalField.setResponder(s -> {
                        this.intervalFieldTouched = true;

                        try {
                           if (s.isEmpty()) {
                              intervalTicks = 0;
                           } else {
                              int parsed = Integer.parseInt(s);
                              if (parsed < 1) {
                                 intervalTicks = 0;
                                 this.intervalField.setValue("");
                              } else {
                                 intervalTicks = Math.min(72000, parsed);
                              }
                           }
                        } catch (NumberFormatException var3x) {
                           intervalTicks = 0;
                        }
                     });
                     this.intervalFields.add(this.intervalField);
                  } else if (!this.intervalFields.contains(this.intervalField)) {
                     this.intervalFields.add(this.intervalField);
                  }

                  this.intervalField.setX(fieldX);
                  this.intervalField.setY(intervalRowY);
                  this.intervalField.setWidth(intervalFieldW);
                  this.intervalField.setHeight(20);
                  this.intervalField.visible = true;
                  if (intervalActive) {
                     this.intervalFieldLocked = true;
                     int runningTicks = this.serverIntervalTicks(player);
                     if (runningTicks <= 0) {
                        runningTicks = intervalTicks;
                     }

                     if (runningTicks <= 0) {
                        runningTicks = 20;
                     }

                     this.intervalField.setValue(String.valueOf(runningTicks));
                     this.intervalField.setEditable(false);
                     this.intervalField.setFocused(false);
                  } else {
                     this.intervalFieldLocked = false;
                     this.intervalField.setEditable(true);
                     if (!this.intervalField.isFocused()) {
                        this.intervalField.setValue(this.intervalFieldTouched ? String.valueOf(Math.max(0, intervalTicks)) : "");
                     }
                  }

                  int btnX = fieldX + intervalFieldW + gap;
                  DarkSelectButton attackIntervalBtn = new DarkSelectButton(
                     btnX,
                     intervalRowY,
                     intervalBtnW,
                     20,
                     this.intervalButtonLabel("screen.command-gui.fakeplayer.action.attack_interval", player, "attackInterval", "attackIntervalTicks"),
                     b -> this.executeIntervalAction(player, "attack interval " + intervalTicks, "attackInterval")
                  );
                  attackIntervalBtn.setDarkSelected(() -> this.isActionActive(player, "attackInterval"), -1);
                  this.actionButtons.add(attackIntervalBtn);
                  DarkSelectButton useIntervalBtn = new DarkSelectButton(
                     btnX + intervalBtnW + gap,
                     intervalRowY,
                     intervalBtnW,
                     20,
                     this.intervalButtonLabel("screen.command-gui.fakeplayer.action.use_interval", player, "useInterval", "useIntervalTicks"),
                     b -> this.executeIntervalAction(player, "use interval " + intervalTicks, "useInterval")
                  );
                  useIntervalBtn.setDarkSelected(() -> this.isActionActive(player, "useInterval"), -1);
                  this.actionButtons.add(useIntervalBtn);
               } else {
                  this.panelIntervalLabelY = 0;
                  if (this.intervalField != null) {
                     this.intervalField.visible = false;
                  }

                  this.intervalFieldLocked = false;
               }

               int timedKillY = intervalRowY + 20 + 0 + 6;
               if (timedKillY + 20 <= panelBottom && timedKillY >= this.area.top()) {
                  if (hasKillTask) {
                     Button cancelKillBtn = Button.builder(
                           Component.literal("x ")
                              .withStyle(ChatFormatting.RED)
                              .append(Component.translatable("screen.command-gui.fakeplayer.timed.cancel").withStyle(ChatFormatting.WHITE)),
                           b -> {
                              TimedTaskManager.removeTask(this.selectedPlayer);
                              this.fireBeforeRebuild();
                              this.rebuildActionButtons();
                              this.fireAfterRebuild();
                           }
                        )
                        .bounds(rightX, timedKillY, actionBtnW * 2 + 0, 20)
                        .build();
                     this.actionButtons.add(cancelKillBtn);
                  } else {
                     Button timedKillBtn = Button.builder(
                           Component.literal("x ")
                              .withStyle(ChatFormatting.YELLOW)
                              .append(Component.translatable("screen.command-gui.fakeplayer.timed.kill.short").withStyle(ChatFormatting.WHITE)),
                           b -> this.openTimedKillScreen(this.selectedPlayer)
                        )
                        .bounds(rightX, timedKillY, actionBtnW * 2 + 0, 20)
                        .build();
                     this.actionButtons.add(timedKillBtn);
                  }
               }
            }
         }
      }
   }

   void openBatchSpawnScreen() {
      Minecraft mc = Minecraft.getInstance();
      mc.gui.setScreen(new BatchSpawnScreen(this.parent));
   }

   void openTimedSpawnScreen() {
      Minecraft mc = Minecraft.getInstance();
      mc.gui.setScreen(new TimedSpawnSetupScreen(this.parent));
   }

   private void openTimedKillScreen(String playerName) {
      Minecraft mc = Minecraft.getInstance();
      mc.gui.setScreen(new TimedKillSetupScreen(this.parent, playerName));
   }

   void confirmRemoveSelected() {
      int count = this.multiSelection.size();
      if (count > 0) {
         Minecraft mc = Minecraft.getInstance();
         mc.gui
            .setScreen(
               new ConfirmScreen(
                  this.parent,
                  Component.translatable("screen.command-gui.fakeplayer.remove_selected_title"),
                  Component.translatable("screen.command-gui.fakeplayer.remove_selected_confirm"),
                  Component.translatable("screen.command-gui.cancel"),
                  this::removeSelectedFakePlayers,
                  Component.translatable("screen.command-gui.fakeplayer.remove_selected_msg", new Object[]{count}),
                  Component.translatable("screen.command-gui.fakeplayer.remove_irreversible")
               )
            );
      }
   }

   void confirmRemoveAll() {
      Minecraft mc = Minecraft.getInstance();
      int onlineCount = 0;

      for (String name : this.displayList) {
         if (this.isOnlineFakePlayer(name)) {
            onlineCount++;
         }
      }

      if (onlineCount > 0) {
         mc.gui
            .setScreen(
               new ConfirmScreen(
                  this.parent,
                  Component.translatable("screen.command-gui.fakeplayer.killall.title"),
                  Component.translatable("screen.command-gui.fakeplayer.killall.confirm"),
                  Component.translatable("screen.command-gui.cancel"),
                  this::killAllFakePlayers,
                  Component.translatable("screen.command-gui.fakeplayer.killall.count", new Object[]{onlineCount}),
                  Component.translatable("screen.command-gui.fakeplayer.remove_irreversible")
               )
            );
      }
   }

   private void removeSelectedFakePlayers() {
      Minecraft mc = Minecraft.getInstance();
      int sent = 0;
      Set<String> removed = new LinkedHashSet<>(this.multiSelection);

      for (String playerName : removed) {
         if (this.isOnlineFakePlayer(playerName)) {
            CommandHelper.sendCommand("/player " + playerName + " kill");
            MachineNetworkManager.removeLocalFakePlayer(playerName);
            sent++;
         }
      }

      this.multiSelection.clear();
      if (rememberedSelectedPlayer != null && removed.contains(rememberedSelectedPlayer)) {
         rememberedSelectedPlayer = null;
      }

      if (this.selectedPlayer != null && removed.contains(this.selectedPlayer)) {
         this.selectedPlayer = null;
      }

      this.showFeedback("screen.command-gui.fakeplayer.remove_selected_result", sent);
      this.fireBeforeRebuild();
      this.refresh();
      this.fireAfterRebuild();
   }

   private void killAllFakePlayers() {
      Minecraft mc = Minecraft.getInstance();
      int sent = 0;

      for (String playerName : this.displayList) {
         if (this.isOnlineFakePlayer(playerName)) {
            CommandHelper.sendCommand("/player " + playerName + " kill");
            MachineNetworkManager.removeLocalFakePlayer(playerName);
            if (playerName.equals(rememberedSelectedPlayer)) {
               rememberedSelectedPlayer = null;
            }

            if (playerName.equals(this.selectedPlayer)) {
               this.selectedPlayer = null;
            }

            sent++;
         }
      }

      this.multiSelection.clear();
      this.showFeedback("screen.command-gui.fakeplayer.killall.result", sent);
      this.fireBeforeRebuild();
      this.refresh();
      this.fireAfterRebuild();
   }

   private void showFeedback(String key, int count) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player != null) {
         mc.gui.hud.getChat().addClientSystemMessage(Component.translatable(key, new Object[]{count}).withStyle(ChatFormatting.GREEN));
      }
   }

   private void executeAction(String player, String action) {
      String command = "/player " + player + " " + action;
      this.executeCommand(command);
      if (action.equals("stop")) {
         this.localActionStates.clear();
         this.localActionOverrideTicks.clear();
         intervalTicks = 0;
         this.intervalFieldTouched = false;
         this.fireBeforeRebuild();
         this.rebuildActionButtons();
         this.fireAfterRebuild();
      }

      if (action.equals("kill")) {
         TimedTaskManager.removeTask(player);
         MachineNetworkManager.removeLocalFakePlayer(player);
         if (player.equals(rememberedSelectedPlayer)) {
            rememberedSelectedPlayer = null;
         }

         this.selectedPlayer = null;
         this.fireBeforeRebuild();
         this.refresh();
         this.fireAfterRebuild();
      }
   }

   private void executePersistentAction(String player, String action, String stateKey) {
      boolean active = this.isActionActive(player, stateKey);
      if (active) {
         String stop = PERSISTENT_ACTION_STOPS.get(action);
         if (stop != null) {
            this.executeCommand("/player " + player + " " + stop);
         }

         this.localActionStates.put(stateKey, false);
      } else {
         this.executeCommand("/player " + player + " " + action);
         this.localActionStates.put(stateKey, true);
      }

      this.fireBeforeRebuild();
      this.rebuildActionButtons();
      this.fireAfterRebuild();
   }

   private void executeIntervalAction(String player, String startCommand, String stateKey) {
      boolean active = this.isActionActive(player, stateKey);
      if (active) {
         String stop = "attackInterval".equals(stateKey) ? "attack once" : "use once";
         this.executeCommand("/player " + player + " " + stop);
         this.localActionStates.put(stateKey, false);
         intervalTicks = 0;
         this.intervalFieldTouched = false;
      } else {
         if (intervalTicks <= 0) {
            return;
         }

         this.executeCommand("/player " + player + " " + startCommand);
         this.localActionStates.put(stateKey, true);
         this.intervalFieldTouched = false;
      }

      this.fireBeforeRebuild();
      this.rebuildActionButtons();
      this.fireAfterRebuild();
   }

   private int serverIntervalTicks(String player) {
      if (!MachineNetworkManager.isFakePlayerStatesSupported()) {
         return 0;
      } else if (MachineNetworkManager.fakeActionActive(player, "attackInterval")) {
         return MachineNetworkManager.fakeActionIntervalTicks(player, "attackIntervalTicks");
      } else {
         if (MachineNetworkManager.fakeActionActive(player, "useInterval")) {
            return MachineNetworkManager.fakeActionIntervalTicks(player, "useIntervalTicks");
         }
         return 0;
      }
   }

   private Component intervalButtonLabel(String key, String player, String stateKey, String ticksKey) {
      Component base = Component.translatable(key);
      if (this.isActionActive(player, stateKey) && MachineNetworkManager.isFakePlayerStatesSupported()) {
         int ticks = MachineNetworkManager.fakeActionIntervalTicks(player, ticksKey);
         if (ticks > 0) {
            return base.copy().append(Component.literal("(" + ticks + ")"));
         }
      }

      return base;
   }

   private boolean isActionActive(String player, String stateKey) {
      if (this.localActionStates.containsKey(stateKey)) {
         return Boolean.TRUE.equals(this.localActionStates.get(stateKey));
      } else {
         if (MachineNetworkManager.isFakePlayerStatesSupported()) {
            return MachineNetworkManager.fakeActionActive(player, stateKey);
         }
         return false;
      }
   }

   public void render(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
      if (this.area != null) {
         Minecraft mc = Minecraft.getInstance();
         if (this.displayList.isEmpty()) {
            guiGraphics.centeredText(
               mc.font,
               Component.translatable("screen.command-gui.fakeplayer.empty"),
               this.playerListX + this.playerItemWidth / 2,
               this.area.top() + this.area.height() / 2 - 4,
               -5592406
            );
         } else {
            int startY = this.area.top();
            int visibleRows = this.visiblePlayerRows();
            int endIndex = Math.min(this.scrollOffset + visibleRows + 1, this.displayList.size());

            for (int i = this.scrollOffset; i < endIndex; i++) {
               String name = this.displayList.get(i);
               int y = startY + (i - this.scrollOffset) * 26;
               if (y + 26 > this.listRowsBottom()) {
                  break;
               }

               boolean isSelected = name.equals(this.selectedPlayer);
               int buttonX = this.playerListX + 2 + 12 + 4;
               int buttonW = this.playerItemWidth - 18;
               boolean isHovered = !isSelected && mouseX >= buttonX && mouseX < buttonX + buttonW && mouseY >= y && mouseY < y + 26;
               Identifier sprite = !isSelected && !isHovered ? BUTTON_SPRITE : BUTTON_HIGHLIGHTED_SPRITE;
               guiGraphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, buttonX, y, buttonW, 26);
               if (isSelected) {
                  guiGraphics.fill(buttonX, y, buttonX + buttonW, y + 26, 855681536);
               }

               int boxX = this.playerListX + 2;
               int boxY = y + 7;
               boolean checked = this.multiSelection.contains(name);
               guiGraphics.fill(boxX, boxY, boxX + 12, boxY + 12, -16777216);
               guiGraphics.fill(boxX + 1, boxY + 1, boxX + 12 - 1, boxY + 12 - 1, -13421773);
               if (checked) {
                  guiGraphics.blitSprite(RenderPipelines.GUI_TEXTURED, Identifier.parse("minecraft:icon/checkmark"), boxX + 2, boxY + 2, 8, 8, -11141291);
               }
            }
         }

         if (this.selectedPlayer == null) {
            int rightX = this.separatorX + 20;
            int rightWidth = Math.max(80, this.area.right() - rightX - 8);
            guiGraphics.centeredText(
               mc.font,
               Component.translatable("screen.command-gui.fakeplayer.select_hint"),
               rightX + rightWidth / 2,
               this.area.top() + this.area.height() / 2 - 4,
               -7829368
            );
         } else if (this.panelIntervalLabelY > 0) {
            guiGraphics.text(
               mc.font,
               Component.translatable("screen.command-gui.fakeplayer.interval_label"),
               this.panelIntervalLabelX,
               this.panelIntervalLabelY + 6,
               -5592406
            );
         }
      }
   }

   public int getScrollbarX() {
      if (this.area != null) {
         return this.playerListX + this.playerItemWidth + 2;
      }
      return 0;
   }

   public void renderScrollbar(GuiGraphicsExtractor guiGraphics) {
      if (this.area != null) {
         int maxScroll = this.getMaxScroll();
         int scrollbarWidth = 12;
         int scrollbarX = this.getScrollbarX();
         int scrollbarTop = this.area.top();
         int scrollbarHeight = this.area.height();
         ScrollbarHandle handle = new ScrollbarHandle(scrollbarX, scrollbarTop, scrollbarWidth, scrollbarHeight);
         handle.render(guiGraphics, this.scrollOffset, maxScroll, this.getVisibleRowCount(), this.getTotalRowCount(), false);
      }
   }

   public void renderFaces(GuiGraphicsExtractor guiGraphics) {
      if (this.area != null) {
         Minecraft mc = Minecraft.getInstance();
         if (mc.getConnection() != null) {
            int startY = this.area.top();
            int buttonX = this.playerListX + 2 + 12 + 4;
            int faceX = buttonX + 4;
            int nameX = faceX + 16 + 4;
            int faceVertOffset = 5;
            int textVertOffset = 8;

            for (int i = 0; i < this.displayList.size(); i++) {
               String playerName = this.displayList.get(i);
               int y = startY + (i - this.scrollOffset) * 26;
               if (y + 26 > this.area.top() && y + 26 <= this.listRowsBottom()) {
                  boolean isPending = this.isPendingSpawn(playerName);
                  boolean isSelected = playerName.equals(this.selectedPlayer);
                  TimedTaskManager.TimedTask task = TimedTaskManager.getTask(playerName);
                  int faceY = y + faceVertOffset;
                  if (isPending) {
                     guiGraphics.blitSprite(RenderPipelines.GUI_TEXTURED, TELEPORT_TO_PLAYER_SPRITE, faceX, faceY, 16, 16);
                  } else {
                     PlayerInfo playerInfo = this.getPlayerInfo(playerName);
                     if (playerInfo != null) {
                        PlayerSkin skin = playerInfo.getSkin();
                        PlayerFaceExtractor.extractRenderState(guiGraphics, skin, faceX, faceY, 16);
                     } else {
                        guiGraphics.fill(faceX, faceY, faceX + 16, faceY + 16, -11184811);
                     }

                     if (task != null && task.type == TimedTaskManager.TaskType.KILL) {
                        guiGraphics.fill(faceX, faceY, faceX + 16, faceY + 16, -1144249822);
                        int iconSize = 10;
                        guiGraphics.blitSprite(
                           RenderPipelines.GUI_TEXTURED, REMOVE_PLAYER_SPRITE, faceX + (16 - iconSize) / 2, faceY + (16 - iconSize) / 2, iconSize, iconSize
                        );
                     }
                  }

                  int timerWidth = 0;
                  if (task != null) {
                     String timeStr = this.formatTime(task.getRemainingSeconds());
                     int clockSize = 10;
                     int clockTimerGap = 2;
                     timerWidth = mc.font.width(timeStr) + clockSize + clockTimerGap + 6;
                     int timeColor = task.type == TimedTaskManager.TaskType.SPAWN ? -11141291 : -43691;
                     int textX = this.playerListX + this.playerItemWidth - mc.font.width(timeStr) - 4;
                     int clockX = textX - clockSize - clockTimerGap;
                     int clockY = y + (26 - clockSize) / 2;
                     guiGraphics.blitSprite(RenderPipelines.GUI_TEXTURED, CLOCK_SPRITE, clockX, clockY, clockSize, clockSize);
                     guiGraphics.text(mc.font, timeStr, textX, y + textVertOffset, timeColor);
                  }

                  int maxNameWidth = this.playerListX + this.playerItemWidth - nameX - timerWidth - 4;
                  String displayName = mc.font.plainSubstrByWidth(playerName, maxNameWidth);
                  if (mc.font.width(playerName) > maxNameWidth) {
                     displayName = displayName + "...";
                  }

                  int nameColor = isPending ? -11141291 : (isSelected ? -1 : -2236963);
                  guiGraphics.text(mc.font, displayName, nameX, y + textVertOffset, nameColor);
               }
            }
         }
      }
   }

   private String formatTime(int seconds) {
      return CommandHelper.formatTime(seconds);
   }

   private PlayerInfo getPlayerInfo(String name) {
      return this.playerInfoCache.get(name);
   }

   private void executeCommand(String command) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player != null) {
         CommandHelper.sendCommand(command);
      }

      boolean panelKeepsOpen = SettingsConfig.getBoolean("fakeplayer_keep_open_default");
      if (!panelKeepsOpen && !CommandGUIScreen.shouldKeepOpen()) {
         mc.gui.setScreen(null);
      }
   }

   public void scroll(double amount) {
      int maxScroll = this.getMaxScroll();
      int newOffset = Math.max(0, Math.min(maxScroll, this.scrollOffset - (int)amount));
      if (newOffset != this.scrollOffset) {
         this.scrollOffset = newOffset;
         if (this.isRememberView()) {
            rememberedPlayerScrollOffset = this.scrollOffset;
         }

         this.fireBeforeRebuild();
         this.rebuildPlayerButtons();
         this.fireAfterRebuild();
      }
   }

   public int getScrollOffset() {
      return this.scrollOffset;
   }

   public void setScrollOffset(int offset) {
      int maxScroll = this.getMaxScroll();
      int newOffset = Math.max(0, Math.min(maxScroll, offset));
      if (newOffset != this.scrollOffset) {
         this.scrollOffset = newOffset;
         if (this.isRememberView()) {
            rememberedPlayerScrollOffset = this.scrollOffset;
         }

         this.fireBeforeRebuild();
         this.rebuildPlayerButtons();
         this.fireAfterRebuild();
      }
   }

   public int getVisibleRowCount() {
      return this.visiblePlayerRows();
   }

   public int getTotalRowCount() {
      return Math.max(1, this.displayList.size());
   }

   public int getMaxScroll() {
      int rows = this.displayList.size();
      int visibleRows = this.visiblePlayerRows();
      return Math.max(0, rows - visibleRows);
   }

   public boolean isInPanelArea(double mouseX, double mouseY) {
      return this.area == null
         ? false
         : mouseX >= (double)this.separatorX && mouseX < (double)this.area.right() && mouseY >= (double)this.area.top() && mouseY < (double)this.area.bottom();
   }

   public int getPanelScrollbarX() {
      if (this.area != null) {
         return this.area.right() + 8;
      }
      return 0;
   }

   public int getPanelScrollOffset() {
      return this.panelScrollOffset;
   }

   public int getPanelMaxScroll() {
      if (this.area != null && this.selectedPlayer != null && this.panelContentBottom > 0) {
         int overflow = this.panelContentBottom - this.area.bottom();
         if (overflow <= 0) {
            return 0;
         }
         return (overflow + 20 - 1) / 20;
      } else {
         return 0;
      }
   }

   public int getPanelVisibleRowCount() {
      if (this.area == null) {
         return 1;
      }
      return Math.max(1, this.area.height() / 20);
   }

   public int getPanelContentRowCount() {
      if (this.area != null && this.selectedPlayer != null && this.panelContentBottom > 0) {
         int contentRows = (this.panelContentBottom - this.area.top() + 20 - 1) / 20;
         return Math.max(this.getPanelVisibleRowCount(), contentRows);
      } else {
         return 1;
      }
   }

   public void scrollPanel(double amount) {
      int maxScroll = this.getPanelMaxScroll();
      int newOffset = Math.max(0, Math.min(maxScroll, this.panelScrollOffset - (int)amount));
      if (newOffset != this.panelScrollOffset) {
         this.panelScrollOffset = newOffset;
         this.fireBeforeRebuild();
         this.rebuildActionButtons();
         this.fireAfterRebuild();
      }
   }

   public void setPanelScrollOffset(int offset) {
      int maxScroll = this.getPanelMaxScroll();
      int newOffset = Math.max(0, Math.min(maxScroll, offset));
      if (newOffset != this.panelScrollOffset) {
         this.panelScrollOffset = newOffset;
         this.fireBeforeRebuild();
         this.rebuildActionButtons();
         this.fireAfterRebuild();
      }
   }

   public void renderPanelScrollbar(GuiGraphicsExtractor guiGraphics) {
      if (this.area != null) {
         ScrollbarHandle handle = new ScrollbarHandle(this.getPanelScrollbarX(), this.area.top(), 12, this.area.height());
         handle.render(
            guiGraphics, this.getPanelScrollOffset(), this.getPanelMaxScroll(), this.getPanelVisibleRowCount(), this.getPanelContentRowCount(), false
         );
      }
   }

   public int getPlayerListX() {
      return this.playerListX;
   }

   public int getPlayerListWidth() {
      return this.playerItemWidth;
   }

   private void updateButtonPositions() {
      this.rebuildPlayerButtons();
   }

   public void setSearchText(String text) {
      this.searchText = text;
      this.scrollOffset = 0;
      this.fireBeforeRebuild();
      this.refresh();
      this.fireAfterRebuild();
   }

   public List<Button> getButtons() {
      List<Button> all = new ArrayList<>();
      all.addAll(this.playerButtons);
      all.addAll(this.checkboxButtons);
      all.addAll(this.actionButtons);
      all.addAll(this.batchButtons);
      all.addAll(this.modeButtons);
      return all;
   }

   public List<EditBox> getIntervalFields() {
      return this.intervalFields;
   }

   public boolean isIntervalFieldFocused() {
      return this.intervalField != null && this.intervalField.isFocused();
   }

   public void restoreIntervalFieldFocus() {
      if (this.intervalField != null && !this.intervalFieldLocked) {
         this.intervalField.setFocused(true);
         CommandGUIScreen var2 = this.parent;
         if (var2 instanceof CommandGUIScreen) {
            var2.setFocused(this.intervalField);
         }
      }
   }

   public List<Button> getPlayerButtons() {
      return this.playerButtons;
   }

   public List<Button> getActionButtons() {
      return this.actionButtons;
   }

   public List<Button> getBatchButtons() {
      return this.batchButtons;
   }

   public ScreenRectangle getArea() {
      return this.area;
   }

   public boolean isEmpty() {
      return this.displayList.isEmpty();
   }

   public String getSelectedPlayer() {
      return this.selectedPlayer;
   }

   public int getMultiSelectionCount() {
      return this.multiSelection.size();
   }

   public void clearSelection() {
      this.selectedPlayer = null;
      rememberedSelectedPlayer = null;
      rememberedPlayerScrollOffset = 0;
      this.multiSelection.clear();
      this.actionButtons.clear();
      this.batchButtons.clear();
      this.localActionStates.clear();
      this.localActionOverrideTicks.clear();
      this.intervalFieldTouched = false;
      intervalTicks = 0;
      this.panelScrollOffset = 0;
      this.panelContentBottom = 0;
      if (this.intervalField != null) {
         this.intervalField.setValue("");
         this.intervalField.setEditable(true);
         this.intervalField.setFocused(false);
         this.intervalField.visible = false;
      }

      this.intervalFieldLocked = false;
   }
}
