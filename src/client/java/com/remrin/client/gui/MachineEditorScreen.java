package com.remrin.client.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.remrin.client.machine.MachineDebug;
import com.remrin.client.machine.MachineModels;
import com.remrin.client.machine.MachineNetworkManager;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public class MachineEditorScreen extends BaseParentedScreen<CommandGUIScreen> {
   private static final Gson GSON = new GsonBuilder().create();
   private static final int FIELD_WIDTH = 300;
   private static final int FIELD_HEIGHT = 20;
   private static final int FIELD_GAP = 8;
   private static final int HALF_FIELD_WIDTH = 146;
   private MachineModels.MachineData machine;
   private MachineModels.MachineData formalMachine;
   private final String draftKey;
   private boolean dirty = false;
   private boolean modesEdited = false;
   private int syncVersionAtOpen = -1;
   private boolean reloadAfterDraftDelete = false;
   private boolean pendingDraftPrompt = false;
   private String draftTime = "";
   private boolean initializing = false;
   private long lastLockRenewAt = 0L;
   private final boolean isNewMachine;
   private final boolean isEditor;
   private final boolean isConfigEditor;
   private int baseRevision;
   private int rowGap = 30;
   private int buttonsRowY;
   private String errorMessage = "";
   private String nameText = "";
   private String descriptionText = "";
   private String categoryText = "";
   private String permissionText = "";
   private String intervalText = "";
   private EditBox nameField;
   private EditBox descriptionField;
   private EditBox categoryField;
   private EditBox botsField;
   private EditBox intervalField;
   private EditBox permissionField;
   private EditBox playersField;
   private Button onTimelineButton;
   private Button offTimelineButton;
   private Button modesButton;
   private Button detectionButton;

   public MachineEditorScreen(CommandGUIScreen parent, MachineModels.MachineData existing) {
      this(parent, existing, null);
   }

   public MachineEditorScreen(CommandGUIScreen parent, MachineModels.MachineData existing, String initialCategory) {
      super(Component.translatable(existing == null ? "screen.command-gui.machine.add_title" : "screen.command-gui.machine.edit_title"), parent);
      this.isNewMachine = existing == null;
      this.isEditor = MachineNetworkManager.canEdit();
      this.isConfigEditor = MachineNetworkManager.canConfig();
      this.machine = existing != null
         ? (MachineModels.MachineData)GSON.fromJson(GSON.toJson(existing), MachineModels.MachineData.class)
         : new MachineModels.MachineData();
      this.baseRevision = existing != null ? existing.revision : -1;
      this.formalMachine = existing != null ? (MachineModels.MachineData)GSON.fromJson(GSON.toJson(existing), MachineModels.MachineData.class) : null;
      this.syncVersionAtOpen = MachineNetworkManager.getSyncVersion();
      this.draftKey = existing != null ? "machine-" + existing.id : "machine-new-" + System.currentTimeMillis();
      String draftJson = DraftStore.load(this.draftKey);
      if (draftJson != null) {
         try {
            MachineModels.MachineData draft = (MachineModels.MachineData)GSON.fromJson(draftJson, MachineModels.MachineData.class);
            if (draft != null && draft.id != null && (existing == null || draft.id.equals(existing.id))) {
               this.machine = draft;
               this.pendingDraftPrompt = true;
               this.draftTime = DraftStore.lastModified(this.draftKey);
            }
         } catch (Exception var6) {
         }
      }

      this.nameText = this.machine.name != null ? this.machine.name : "";
      this.descriptionText = this.machine.description != null ? this.machine.description : "";
      this.categoryText = initialCategory != null ? initialCategory : (this.machine.category != null ? this.machine.category : "");
      if (initialCategory != null) {
         this.machine.category = initialCategory;
      }

      this.intervalText = String.valueOf(this.machine.switchInterval);
      this.permissionText = this.isNewMachine ? "" : String.valueOf(this.machine.permissionLevel);
   }

   public MachineModels.MachineData getMachine() {
      return this.machine;
   }

   protected void init() {
      super.init();
      this.initializing = true;
      if (!this.isNewMachine && this.machine.id != null && !this.machine.id.isEmpty()) {
         MachineNetworkManager.sendEditSession(this.machine.id, true);
      }

      int fieldX = (this.width - 300) / 2;
      this.rowGap = this.isConfigEditor ? 36 : 44;
      int fieldsEnd = this.isConfigEditor ? 4 : 3;
      this.buttonsRowY = 32 + this.rowGap * fieldsEnd;
      this.nameField = new EditBox(this.font, fieldX, 32, 146, 20, Component.translatable("screen.command-gui.machine.name"));
      this.nameField.setMaxLength(50);
      this.nameField.setValue(this.nameText);
      this.nameField.setResponder(text -> {
         this.nameText = text;
         this.setDirty();
      });
      this.addRenderableWidget(this.nameField);
      this.categoryField = new EditBox(this.font, fieldX + 146 + 8, 32, 146, 20, Component.translatable("screen.command-gui.machine.category"));
      this.categoryField.setMaxLength(30);
      this.categoryField.setValue(this.categoryText);
      this.categoryField.setHint(Component.translatable("screen.command-gui.machine.category_hint"));
      this.categoryField.setResponder(text -> {
         this.categoryText = text;
         this.setDirty();
      });
      this.addRenderableWidget(this.categoryField);
      this.descriptionField = new EditBox(this.font, fieldX, 32 + this.rowGap, 300, 20, Component.translatable("screen.command-gui.machine.description"));
      this.descriptionField.setMaxLength(200);
      this.descriptionField.setValue(this.descriptionText);
      this.descriptionField.setResponder(text -> {
         this.descriptionText = text;
         this.setDirty();
      });
      this.addRenderableWidget(this.descriptionField);
      this.botsField = new EditBox(this.font, fieldX, 32 + this.rowGap * 2, 224, 20, Component.translatable("screen.command-gui.machine.bots"));
      this.botsField.setMaxLength(200);
      this.botsField.setHint(Component.translatable("screen.command-gui.machine.bots_hint"));
      this.botsField.setValue(String.join(",", this.machine.bots));
      this.botsField.setResponder(text -> {
         this.machine.bots = parseList(text);
         this.setDirty();
      });
      this.addRenderableWidget(this.botsField);
      this.intervalField = new DigitsOnlyEditBox(
         this.font, fieldX + 300 - 72, 32 + this.rowGap * 2, 72, 20, Component.translatable("screen.command-gui.machine.switch_interval")
      );
      this.intervalField.setMaxLength(4);
      this.intervalField.setValue(this.intervalText);
      this.intervalField.setHint(Component.translatable("screen.command-gui.machine.switch_interval_hint"));
      this.intervalField.setResponder(text -> {
         this.intervalText = text;
         this.setDirty();
      });
      this.addRenderableWidget(this.intervalField);
      if (this.isConfigEditor) {
         int permRowY = 32 + this.rowGap * 3;
         this.permissionField = new EditBox(this.font, fieldX, permRowY, 60, 20, Component.translatable("screen.command-gui.machine.permission"));
         this.permissionField.setMaxLength(1);
         this.permissionField.setValue(this.permissionText);
         this.permissionField.setHint(Component.translatable("screen.command-gui.machine.permission_hint"));
         this.permissionField.setResponder(text -> {
            this.permissionText = text;
            this.setDirty();
         });
         this.addRenderableWidget(this.permissionField);
         this.playersField = new EditBox(this.font, fieldX + 68, permRowY, 232, 20, Component.translatable("screen.command-gui.machine.players"));
         this.playersField.setMaxLength(200);
         this.playersField.setHint(Component.translatable("screen.command-gui.machine.players_hint"));
         this.playersField.setValue(String.join(",", this.machine.bannedPlayers));
         this.playersField.setResponder(text -> {
            this.machine.bannedPlayers = parseList(text);
            this.setDirty();
         });
         this.addRenderableWidget(this.playersField);
      }

      int timelineWidth = 69;
      this.onTimelineButton = Button.builder(this.buildTimelineLabel(true), btn -> this.openTimelineEditor(true))
         .bounds(fieldX, this.buttonsRowY, timelineWidth, 18)
         .build();
      this.addRenderableWidget(this.onTimelineButton);
      this.offTimelineButton = Button.builder(this.buildTimelineLabel(false), btn -> this.openTimelineEditor(false))
         .bounds(fieldX + timelineWidth + 8, this.buttonsRowY, timelineWidth, 18)
         .build();
      this.addRenderableWidget(this.offTimelineButton);
      this.modesButton = Button.builder(this.buildModesLabel(), btn -> this.openModesEditor())
         .bounds(fieldX + (timelineWidth + 8) * 2, this.buttonsRowY, timelineWidth, 18)
         .build();
      this.addRenderableWidget(this.modesButton);
      this.detectionButton = Button.builder(this.buildDetectionLabel(), btn -> this.openDetectionEditor())
         .bounds(fieldX + (timelineWidth + 8) * 3, this.buttonsRowY, timelineWidth, 18)
         .build();
      this.addRenderableWidget(this.detectionButton);
      int barY = this.height - 22;
      int barWidth = Math.min(80, 100);
      int buttonCount = this.isNewMachine ? 2 : 3;
      int barStartX = fieldX + (300 - (barWidth * buttonCount + 8 * (buttonCount - 1))) / 2;
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.save"), btn -> this.saveAndClose()).bounds(barStartX, barY, barWidth, 18).build()
      );
      int barX = barStartX + barWidth + 8;
      if (!this.isNewMachine && this.isConfigEditor) {
         this.addRenderableWidget(
            Button.builder(Component.translatable("screen.command-gui.delete"), btn -> this.confirmDelete()).bounds(barX, barY, barWidth, 18).build()
         );
         barX += barWidth + 8;
      }

      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.back"), btn -> this.requestExit()).bounds(barX, barY, barWidth, 18).build()
      );
      if (this.pendingDraftPrompt) {
         this.pendingDraftPrompt = false;
         String time = this.draftTime;
         this.minecraft
            .gui
            .setScreen(
               new ConfirmScreen(
                  this,
                  Component.translatable("screen.command-gui.draft.title"),
                  Component.translatable("screen.command-gui.draft.restore"),
                  Component.translatable("screen.command-gui.draft.delete"),
                  () -> this.minecraft.gui.setScreen(this),
                  () -> {
                     DraftStore.clear(this.draftKey);
                     this.reloadAfterDraftDelete = true;
                     MachineNetworkManager.sendRequestSync();
                     this.minecraft.gui.setScreen(this);
                  },
                  Component.translatable("screen.command-gui.draft.message", new Object[]{time})
               )
            );
      }

      this.initializing = false;
   }

   private void openTimelineEditor(boolean onTimeline) {
      MachineDebug.log(
         "[MachineEditor.openTimeline] on="
            + onTimeline
            + " machineHash="
            + System.identityHashCode(this.machine)
            + " onTimelineHash="
            + System.identityHashCode(this.machine.onTimeline)
            + " steps="
            + this.machine.onTimeline.steps.size()
            + " firstCommands="
            + (this.machine.onTimeline.steps.isEmpty() ? "[]" : MachineDebug.commandsString(this.machine.onTimeline.steps.get(0).commands))
      );
      this.minecraft
         .gui
         .setScreen(
            new TimelineEditorScreen(
               this,
               Component.translatable(onTimeline ? "screen.command-gui.machine.boot_process_title" : "screen.command-gui.machine.shutdown_process_title"),
               onTimeline ? this.machine.onTimeline : this.machine.offTimeline,
               onTimeline,
               true,
               () -> this.updateTimelineButtons(),
               this.machine.bots
            )
         );
   }

   private void openModesEditor() {
      this.minecraft.gui.setScreen(new ModesEditorScreen(this, this.machine.modes, this.machine.bots, () -> {
         this.modesEdited = true;
         this.updateTimelineButtons();
      }));
   }

   private void openDetectionEditor() {
      this.minecraft.gui.setScreen(new DetectionScreen(this, this.machine.detection, detection -> {
         this.machine.detection = detection;
         this.detectionButton.setMessage(this.buildDetectionLabel());
         this.setDirty();
      }));
   }

   private void updateTimelineButtons() {
      this.setDirty();
      if (this.onTimelineButton != null) {
         this.onTimelineButton.setMessage(this.buildTimelineLabel(true));
      }

      if (this.offTimelineButton != null) {
         this.offTimelineButton.setMessage(this.buildTimelineLabel(false));
      }

      if (this.modesButton != null) {
         this.modesButton.setMessage(this.buildModesLabel());
      }

      if (this.detectionButton != null) {
         this.detectionButton.setMessage(this.buildDetectionLabel());
      }
   }

   private void setDirty() {
      if (!this.initializing) {
         this.dirty = true;
         this.syncWorkingFromText();
         DraftStore.save(this.draftKey, GSON.toJson(this.machine));
      }
   }

   private void syncWorkingFromText() {
      this.machine.name = this.nameText != null ? this.nameText.trim() : "";
      this.machine.description = this.descriptionText != null ? this.descriptionText.trim() : "";
      this.machine.category = this.categoryText != null ? this.categoryText.trim() : "";

      try {
         this.machine.switchInterval = Math.max(1, Math.min(1200, Integer.parseInt(this.intervalText.trim())));
      } catch (NumberFormatException var3) {
         this.machine.switchInterval = 20;
      }

      if (this.permissionText != null) {
         try {
            this.machine.permissionLevel = Integer.parseInt(this.permissionText.trim());
         } catch (NumberFormatException var2) {
            this.machine.permissionLevel = 0;
         }

         this.machine.permissionLevel = Math.max(0, Math.min(this.machine.permissionLevel, 4));
      }
   }

   private void restoreFormal() {
      this.machine = this.formalMachine != null
         ? (MachineModels.MachineData)GSON.fromJson(GSON.toJson(this.formalMachine), MachineModels.MachineData.class)
         : new MachineModels.MachineData();
      this.nameText = this.machine.name != null ? this.machine.name : "";
      this.descriptionText = this.machine.description != null ? this.machine.description : "";
      this.categoryText = this.machine.category != null ? this.machine.category : "";
      this.intervalText = String.valueOf(this.machine.switchInterval);
      this.permissionText = this.isNewMachine ? "" : String.valueOf(this.machine.permissionLevel);
      this.dirty = false;
   }

   @Override
   public void tick() {
      super.tick();
      // 编辑锁续期：服务端编辑锁约 15 分钟无活动即被清理（见 MachineManager.cleanupExpiredLocks）。
      // 长时间停留在本编辑器（或从子编辑器返回后 tick 恢复）时，每 10 分钟重发一次 editSession(open)，
      // 刷新服务端锁的 acquiredAt，避免被周期广播误清后他人抢占同一台机器的编辑权。
      if (!this.isNewMachine && this.machine.id != null && !this.machine.id.isEmpty()) {
         long now = System.currentTimeMillis();
         if (now - this.lastLockRenewAt > 600000L) {
            this.lastLockRenewAt = now;
            MachineNetworkManager.sendEditSession(this.machine.id, true);
         }
      }
      if (this.reloadAfterDraftDelete && MachineNetworkManager.getSyncVersion() != this.syncVersionAtOpen) {
         this.reloadAfterDraftDelete = false;
         MachineModels.MachineData fresh = MachineNetworkManager.getMachine(this.machine.id);
         if (fresh != null) {
            this.machine = (MachineModels.MachineData)GSON.fromJson(GSON.toJson(fresh), MachineModels.MachineData.class);
            this.formalMachine = (MachineModels.MachineData)GSON.fromJson(GSON.toJson(fresh), MachineModels.MachineData.class);
            this.baseRevision = fresh.revision;
            this.restoreFormal();
            this.rebuildWidgets();
         } else {
            this.restoreFormal();
         }
      }
   }

   private void requestExit() {
      if (!this.dirty) {
         this.closeEditor();
      } else {
         this.minecraft
            .gui
            .setScreen(
               new UnsavedExitScreen(
                  this,
                  Component.translatable("screen.command-gui.unsaved.title"),
                  Component.translatable("screen.command-gui.unsaved.message"),
                  this::trySaveFromExit,
                  () -> {
                     DraftStore.save(this.draftKey, GSON.toJson(this.machine));
                     this.releaseEditLock();
                     this.minecraft.gui.setScreen(this.parent);
                  }
               )
            );
      }
   }

   private void trySaveFromExit() {
      this.errorMessage = "";
      if (!this.validateMachine().isEmpty()) {
         this.minecraft.gui.setScreen(this);
      } else {
         this.saveAndClose();
      }
   }

   private void closeEditor() {
      this.releaseEditLock();
      this.minecraft.gui.setScreen(this.parent);
   }

   void releaseEditLock() {
      if (!this.isNewMachine && this.machine.id != null && !this.machine.id.isEmpty()) {
         MachineNetworkManager.sendEditSession(this.machine.id, false);
      }
   }

   private Component buildTimelineLabel(boolean onTimeline) {
      int steps = (onTimeline ? this.machine.onTimeline : this.machine.offTimeline).steps.size();
      return Component.translatable(
         onTimeline ? "screen.command-gui.machine.boot_process_short" : "screen.command-gui.machine.shutdown_process_short", new Object[]{steps}
      );
   }

   private Component buildModesLabel() {
      return Component.translatable("screen.command-gui.machine.modes_short", new Object[]{this.machine.modes.size()});
   }

   private Component buildDetectionLabel() {
      return Component.translatable("screen.command-gui.machine.detection_on_short");
   }

   private String validateMachine() {
      String name = this.nameField.getValue().trim();
      if (name.isEmpty()) {
         return Component.translatable("screen.command-gui.machine.error_name").getString();
      } else if (this.machine.bots.isEmpty()) {
         return Component.translatable("screen.command-gui.machine.error_bots").getString();
      } else if (!MachineModels.isSpawnCommand(MachineModels.firstCommand(this.machine.onTimeline))) {
         return Component.translatable("screen.command-gui.machine.spawn_required").getString();
      } else {
         String stepsError = MachineModels.validateSteps("开机流程", this.machine.onTimeline);
         if (stepsError != null) {
            return stepsError;
         } else {
            stepsError = MachineModels.validateSteps("关机流程", this.machine.offTimeline);
            if (stepsError != null) {
               return stepsError;
            } else {
               for (MachineModels.ModeData mode : this.machine.modes) {
                  if (mode.name == null || mode.name.isBlank()) {
                     return Component.translatable("screen.command-gui.machine.error_mode_name").getString();
                  }

                  if (!MachineModels.isSpawnCommand(MachineModels.firstCommand(mode.onTimeline))) {
                     return Component.translatable("screen.command-gui.machine.error_mode_spawn", new Object[]{mode.name}).getString();
                  }

                  stepsError = MachineModels.validateSteps("模式「" + mode.name + "」开启流程", mode.onTimeline);
                  if (stepsError != null) {
                     return stepsError;
                  }

                  stepsError = MachineModels.validateSteps("模式「" + mode.name + "」关机流程", mode.offTimeline);
                  if (stepsError != null) {
                     return stepsError;
                  }
               }

               return "";
            }
         }
      }
   }

   private void saveAndClose() {
      this.errorMessage = "";
      String error = this.validateMachine();
      if (!error.isEmpty()) {
         this.errorMessage = error;
      } else {
         String name = this.nameField.getValue().trim();
         this.machine.name = name;
         this.machine.description = this.descriptionField.getValue().trim();
         this.machine.category = this.categoryField.getValue().trim();

         try {
            this.machine.switchInterval = Math.max(1, Math.min(1200, Integer.parseInt(this.intervalField.getValue().trim())));
         } catch (NumberFormatException var5) {
            this.machine.switchInterval = 20;
         }

         if (this.permissionField != null) {
            try {
               this.machine.permissionLevel = Integer.parseInt(this.permissionField.getValue().trim());
            } catch (NumberFormatException var4) {
               this.machine.permissionLevel = 0;
            }

            this.machine.permissionLevel = Math.max(0, Math.min(this.machine.permissionLevel, 4));
         }

         boolean isNew = this.machine.id == null || this.machine.id.isEmpty();
         if (isNew) {
            this.machine.id = this.generateId(name);
         }

         if (isNew) {
            MachineNetworkManager.sendAdd(this.machine);
         } else {
            MachineNetworkManager.sendEdit(this.machine, this.baseRevision);
            MachineNetworkManager.sendEditSession(this.machine.id, false);
         }

         DraftStore.clear(this.draftKey);
         this.dirty = false;
         this.minecraft.gui.setScreen(this.parent);
      }
   }

   @Override
   public void onClose() {
      this.releaseEditLock();
      super.onClose();
   }

   private String generateId(String name) {
      String base = name.toLowerCase().replaceAll("[^a-z0-9_-]+", "-");
      if (base.isEmpty()) {
         base = "machine";
      }

      String candidate = base;

      for (int n = 2; MachineNetworkManager.getMachine(candidate) != null; n++) {
         candidate = base + "-" + n;
      }

      return candidate;
   }

   private static List<String> parseList(String text) {
      List<String> result = new ArrayList<>();
      if (text == null) {
         return result;
      } else {
         for (String part : text.split("[,，;；\\s]+")) {
            if (!part.isEmpty()) {
               result.add(part);
            }
         }

         return result;
      }
   }

   public boolean keyPressed(KeyEvent keyEvent) {
      if (keyEvent.key() != 257 && keyEvent.key() != 335) {
         if (keyEvent.key() == 256) {
            this.requestExit();
            return true;
         } else {
            return super.keyPressed(keyEvent);
         }
      } else if (this.isAnyFieldFocused()) {
         return true;
      } else {
         this.saveAndClose();
         return true;
      }
   }

   private boolean isAnyFieldFocused() {
      return this.nameField != null && this.nameField.isFocused()
         || this.categoryField != null && this.categoryField.isFocused()
         || this.descriptionField != null && this.descriptionField.isFocused()
         || this.botsField != null && this.botsField.isFocused()
         || this.intervalField != null && this.intervalField.isFocused()
         || this.permissionField != null && this.permissionField.isFocused()
         || this.playersField != null && this.playersField.isFocused();
   }

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      int fieldX = (this.width - 300) / 2;
      MutableComponent title = this.title.copy();
      if (this.dirty) {
         title.append(" ").append(Component.translatable("screen.command-gui.unsaved_badge").withColor(-22016));
      }

      guiGraphics.centeredText(this.font, title, this.width / 2, 4, -1);
      this.renderLabel(guiGraphics, fieldX, 32, Component.translatable("screen.command-gui.machine.name"));
      this.renderLabel(guiGraphics, fieldX + 146 + 8, 32, Component.translatable("screen.command-gui.machine.category"));
      this.renderLabel(guiGraphics, fieldX, 32 + this.rowGap, Component.translatable("screen.command-gui.machine.description"));
      this.renderLabel(guiGraphics, fieldX, 32 + this.rowGap * 2, Component.translatable("screen.command-gui.machine.bots"));
      this.renderLabel(guiGraphics, fieldX + 300 - 72, 32 + this.rowGap * 2, Component.translatable("screen.command-gui.machine.switch_interval"));
      if (this.isConfigEditor) {
         this.renderLabel(guiGraphics, fieldX, 32 + this.rowGap * 3, Component.translatable("screen.command-gui.machine.permission"));
         this.renderLabel(guiGraphics, fieldX + 68, 32 + this.rowGap * 3, Component.translatable("screen.command-gui.machine.players"));
      }

      if (!this.errorMessage.isEmpty()) {
         guiGraphics.text(this.font, Component.literal(this.errorMessage), fieldX, this.buttonsRowY + 26, -43691);
      }
   }

   private void renderLabel(GuiGraphicsExtractor guiGraphics, int x, int fieldY, Component label) {
      guiGraphics.text(this.font, label, x, fieldY - 12, -5592406);
   }

   private void confirmDelete() {
      this.minecraft
         .gui
         .setScreen(
            new ConfirmScreen(
               this,
               Component.translatable("screen.command-gui.machine.delete_title"),
               Component.translatable("screen.command-gui.machine.delete_confirm"),
               Component.translatable("screen.command-gui.cancel"),
               () -> {
                  MachineNetworkManager.sendDelete(this.machine.id);
                  this.closeEditor();
               },
               Component.translatable("screen.command-gui.machine.delete_message", new Object[]{this.machine.name}),
               Component.translatable("screen.command-gui.fakeplayer.remove_irreversible")
            )
         );
   }
}
