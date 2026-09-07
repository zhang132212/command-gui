package com.remrin.client.gui;

import com.remrin.client.machine.MachineDebug;
import com.remrin.client.machine.MachineModels;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public class ModeEditorScreen extends BaseParentedScreen<ModesEditorScreen> {
   private static final int FIELD_WIDTH = 300;
   private static final int FIELD_HEIGHT = 20;
   private final MachineModels.ModeData original;
   private final MachineModels.ModeData working;
   private final List<String> botNames;
   private final Runnable onChanged;
   private String errorMessage = "";
   private String nameText = "";
   private EditBox nameField;
   private Button onTimelineButton;
   private Button offTimelineButton;
   private Button detectionButton;
   private MarkCheckbox followBootCheckbox;

   public ModeEditorScreen(ModesEditorScreen parent, MachineModels.ModeData original, List<String> botNames, Runnable onChanged) {
      super(Component.translatable("screen.command-gui.machine.mode_title"), parent);
      this.original = original;
      this.working = copy(original);
      this.botNames = botNames;
      this.onChanged = onChanged;
      this.nameText = this.working.name != null ? this.working.name : "";
   }

   protected void init() {
      super.init();
      int fieldX = (this.width - 300) / 2;
      this.nameField = new EditBox(this.font, fieldX, 48, 224, 20, Component.translatable("screen.command-gui.machine.mode_name"));
      this.nameField.setMaxLength(30);
      this.nameField.setValue(this.nameText);
      this.nameField.setResponder(text -> this.nameText = text);
      this.addRenderableWidget(this.nameField);
      int processWidth = 94;
      this.onTimelineButton = Button.builder(this.buildProcessLabel(true), btn -> this.openProcessEditor(true)).bounds(fieldX, 90, processWidth, 18).build();
      this.addRenderableWidget(this.onTimelineButton);
      this.offTimelineButton = Button.builder(this.buildProcessLabel(false), btn -> this.openProcessEditor(false))
         .bounds(fieldX + processWidth + 8, 90, processWidth, 18)
         .build();
      this.addRenderableWidget(this.offTimelineButton);
      this.detectionButton = Button.builder(this.buildDetectionLabel(), btn -> this.openDetectionEditor())
         .bounds(fieldX + (processWidth + 8) * 2, 90, processWidth, 18)
         .build();
      this.addRenderableWidget(this.detectionButton);
      this.followBootCheckbox = new MarkCheckbox(fieldX, 114, 260, 14, Component.translatable("screen.command-gui.machine.follow_boot"), false, b -> {
         boolean next = !this.followBootCheckbox.selected();
         this.followBootCheckbox.setSelected(next);
         MachineDebug.log("[ModeEditor.followBoot] marked pending=" + next);
      });
      this.addRenderableWidget(this.followBootCheckbox);
      int barY = this.height - 22;
      int barWidth = Math.min(70, 100);
      int barStartX = fieldX + (300 - barWidth * 2 - 8) / 2;
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.save"), btn -> this.saveAndClose()).bounds(barStartX, barY, barWidth, 18).build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.back"), btn -> this.backAndClose())
            .bounds(barStartX + barWidth + 8, barY, barWidth, 18)
            .build()
      );
   }

   private void openProcessEditor(boolean onProcess) {
      MachineModels.Timeline timeline = onProcess ? this.working.onTimeline : this.working.offTimeline;
      this.minecraft
         .gui
         .setScreen(
            new TimelineEditorScreen(
               this,
               Component.translatable(onProcess ? "screen.command-gui.machine.mode_boot_title" : "screen.command-gui.machine.mode_shutdown_title"),
               timeline,
               onProcess,
               false,
               () -> this.updateProcessButtons(),
               this.botNames
            )
         );
   }

   private void openDetectionEditor() {
      this.minecraft.gui.setScreen(new DetectionScreen(this, this.working.detection, detection -> this.working.detection = detection));
   }

   private void importMachineBoot() {
      if (this.working.onTimeline != null && !this.working.onTimeline.steps.isEmpty()) {
         MachineModels.Timeline imported = new MachineModels.Timeline();
         copyTimelineInto(this.working.onTimeline, imported);
         this.working.offTimeline = imported;
         MachineDebug.log("[ModeEditor.followBoot] OK boot steps=" + this.working.onTimeline.steps.size() + " -> shutdown steps=" + imported.steps.size());
         this.errorMessage = "";
         this.updateProcessButtons();
      } else {
         this.errorMessage = Component.translatable("screen.command-gui.machine.error_follow_boot_empty").getString();
         MachineDebug.log("[ModeEditor.followBoot] FAILED: mode boot empty");
      }
   }

   private void updateProcessButtons() {
      if (this.onTimelineButton != null) {
         this.onTimelineButton.setMessage(this.buildProcessLabel(true));
      }

      if (this.offTimelineButton != null) {
         this.offTimelineButton.setMessage(this.buildProcessLabel(false));
      }

      if (this.detectionButton != null) {
         this.detectionButton.setMessage(this.buildDetectionLabel());
      }
   }

   private Component buildProcessLabel(boolean onProcess) {
      int steps = (onProcess ? this.working.onTimeline : this.working.offTimeline).steps.size();
      return Component.translatable(onProcess ? "screen.command-gui.machine.mode_boot" : "screen.command-gui.machine.mode_shutdown", new Object[]{steps});
   }

   private Component buildDetectionLabel() {
      return Component.translatable("screen.command-gui.machine.detection_on_short");
   }

   private void saveAndClose() {
      this.errorMessage = "";
      if (this.followBootCheckbox.selected()) {
         this.importMachineBoot();
         if (!this.errorMessage.isEmpty()) {
            return;
         }
      }

      String name = this.nameField.getValue().trim();
      if (name.isEmpty()) {
         this.errorMessage = Component.translatable("screen.command-gui.machine.error_mode_name").getString();
      } else {
         boolean onHasSteps = this.working.onTimeline != null && !this.working.onTimeline.steps.isEmpty();
         boolean offHasSteps = this.working.offTimeline != null && !this.working.offTimeline.steps.isEmpty();
         boolean detectionEnabled = this.working.detection != null && this.working.detection.enabled;
         if (onHasSteps && !detectionEnabled) {
            this.errorMessage = Component.translatable("screen.command-gui.machine.error_mode_boot_needs_detection").getString();
         } else if (offHasSteps && !detectionEnabled) {
            this.errorMessage = Component.translatable("screen.command-gui.machine.error_mode_shutdown_needs_detection").getString();
         } else {
            this.working.name = name;
            copyInto(this.working, this.original);
            if (this.onChanged != null) {
               this.onChanged.run();
            }

            this.minecraft.gui.setScreen(this.parent);
         }
      }
   }

   private void backAndClose() {
      if (!this.nameField.getValue().trim().isEmpty()) {
         this.working.name = this.nameField.getValue().trim();
         copyInto(this.working, this.original);
         if (this.onChanged != null) {
            this.onChanged.run();
         }
      }

      this.minecraft.gui.setScreen(this.parent);
   }

   private static MachineModels.ModeData copy(MachineModels.ModeData mode) {
      MachineModels.ModeData copy = new MachineModels.ModeData();
      copy.id = mode.id;
      copy.name = mode.name;
      copy.singleSelect = mode.singleSelect;
      copy.switchInterval = mode.switchInterval;
      copy.detection = copyDetection(mode.detection);
      copyTimelineInto(mode.onTimeline, copy.onTimeline);
      copyTimelineInto(mode.offTimeline, copy.offTimeline);
      return copy;
   }

   private static void copyInto(MachineModels.ModeData from, MachineModels.ModeData to) {
      to.id = from.id;
      to.name = from.name;
      to.singleSelect = from.singleSelect;
      to.switchInterval = from.switchInterval;
      to.detection = copyDetection(from.detection);
      copyTimelineInto(from.onTimeline, to.onTimeline);
      copyTimelineInto(from.offTimeline, to.offTimeline);
   }

   private static MachineModels.DetectionData copyDetection(MachineModels.DetectionData detection) {
      if (detection == null) {
         return null;
      } else {
         MachineModels.DetectionData copy = new MachineModels.DetectionData();
         copy.enabled = detection.enabled;
         copy.dimension = detection.dimension;
         copy.x = detection.x;
         copy.y = detection.y;
         copy.z = detection.z;
         copy.blockId = detection.blockId;
         copy.property = detection.property;
         copy.onValues = new ArrayList<>(detection.onValues);
         copy.offValues = new ArrayList<>(detection.offValues);
         copy.ignoreValues = new ArrayList<>(detection.ignoreValues);
         return copy;
      }
   }

   private static void copyTimelineInto(MachineModels.Timeline from, MachineModels.Timeline to) {
      to.loopCount = from.loopCount;
      to.steps = new ArrayList<>();

      for (MachineModels.Step step : from.steps) {
         MachineModels.Step stepCopy = new MachineModels.Step();
         stepCopy.kind = step.kind;
         stepCopy.delay = step.delay;
         stepCopy.commandDelay = step.commandDelay;
         stepCopy.bot = step.bot;
         stepCopy.commands = new ArrayList<>(step.commands);
         stepCopy.description = step.description;
         to.steps.add(stepCopy);
      }
   }

   public boolean keyPressed(KeyEvent keyEvent) {
      if (keyEvent.key() == 257 || keyEvent.key() == 335) {
         this.saveAndClose();
         return true;
      } else if (keyEvent.key() == 256) {
         this.backAndClose();
         return true;
      } else {
         return super.keyPressed(keyEvent);
      }
   }

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      int fieldX = (this.width - 300) / 2;
      guiGraphics.centeredText(this.font, this.title, this.width / 2, 4, -1);
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.mode_name"), fieldX, 36, -5592406);
      if (!this.errorMessage.isEmpty()) {
         guiGraphics.text(this.font, Component.literal(this.errorMessage), fieldX, 132, -43691);
      }
   }
}
