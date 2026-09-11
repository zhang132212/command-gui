package com.remrin.client.gui;

import com.remrin.client.config.CommandConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

public abstract class BaseCommandEditorScreen extends BaseParentedScreen<CommandGUIScreen> {
   protected static final String[] TYPE_KEYS = new String[]{
      "screen.command-gui.type.player_all_full",
      "screen.command-gui.type.player_other_full",
      "screen.command-gui.type.bot_full",
      "screen.command-gui.type.text_full",
      "screen.command-gui.type.number_full",
      "screen.command-gui.type.time_full",
      "screen.command-gui.type.coord_full"
   };
   public static final String[] PLACEHOLDERS = new String[]{"{player_all}", "{player}", "{bot}", "{name}", "{number}", "{time}", "{coords}"};
   protected static final int PLACEHOLDER_BTN_HEIGHT = 16;
   protected static final int COL_GAP = 10;
   protected static final int INPUT_HEIGHT = 16;
   protected static final int CONTENT_WIDTH = 300;
   protected static final int LABEL_TO_FIELD = 10;
   protected static final int ROW_GAP = 30;
   protected static final int Y_OFFSET = -20;
   protected static final int BTN_GAP = 4;
   protected static final int ADD_BTN_WIDTH = 120;
   protected static final int COMMAND_DELAY_FIELD_W = 48;
   protected static final int NAME_FIELD_Y = 44;
   protected static final int DESC_FIELD_Y = 74;
   protected static final int SHORTCUT_LABEL_Y = 94;
   protected static final int SHORTCUT_BTN_Y = 104;
   protected static final int CMD_FIELD_Y = 134;
   protected static final int CMD_FIELD_HEIGHT = 20;
   protected final List<String> commandList = new ArrayList<>();
   private final List<Button> commandRemoveButtons = new ArrayList<>();
   private final List<Button> commandMoveUpButtons = new ArrayList<>();
   private final List<Button> commandMoveDownButtons = new ArrayList<>();
   private final List<Button> commandCopyButtons = new ArrayList<>();
   private static final int MOVE_BTN_W = 48;
   private static final int COPY_BTN_W = 30;
   private static final int REMOVE_BTN_W = 30;
   private static final int ROW_ACTION_GAP = 1;
   private static final int SCROLLBAR_STRIP = 14;
   private static final int ROW_NUMBER_W = 18;
   protected boolean dirty = false;
   private boolean initializing = false;
   private Component cachedLabelCommandDelay;
   protected EditBox nameField;
   protected EditBox descriptionField;
   protected EditBox commandField;
   protected EditBox commandDelayField;
   private int commandDelay = 1;
   protected String shortcut = "";
   private boolean capturingShortcut = false;
   private Button shortcutButton;
   private Button shortcutResetButton;
   private String shortcutConflict = null;
   private final List<Integer> capturedKeys = new ArrayList<>();
   private int commandScroll = 0;
   private ScrollbarHandle commandScrollbar = null;
   private boolean draggingCommandScrollbar = false;
   private double commandScrollbarGrabOffset = 0.0;
   protected CommandSuggestions commandSuggestions;
   private Button addToListButton;
   private Component cachedLabelName;
   private Component cachedLabelDesc;
   private Component cachedLabelPlaceholder;
   private Component cachedLabelCommands;
   private Component cachedLabelCommand;
   private Component cachedLabelShortcut;

   protected String getInitialShortcut() {
      return "";
   }

   protected String getShortcutValue() {
      return this.shortcut;
   }

   protected BaseCommandEditorScreen(Component title, CommandGUIScreen parent) {
      super(title, parent);
   }

   protected int getFieldStartY(int centerY) {
      return 44;
   }

   protected int getTitleY(int centerY) {
      return Math.max(6, (34 - 9) / 2);
   }

   protected abstract String getInitialName();

   protected abstract String getInitialDescription();

   protected abstract String getInitialCommand();

   protected int getInitialCommandDelay() {
      return 1;
   }

   protected int getCommandDelayValue() {
      return Math.max(1, this.commandDelay);
   }

   protected abstract void performSave();

   protected boolean showNameHint() {
      return false;
   }

   protected Component getCommandHint() {
      return null;
   }

   protected int initExtraRow(int fieldX, int currentY) {
      return currentY;
   }

   protected int renderExtraLabel(GuiGraphicsExtractor guiGraphics, int fieldX, int currentY) {
      return currentY;
   }

   protected void onBeforeResize() {
   }

   protected void onAfterResize() {
   }

   protected List<String> getInitialCommandList() {
      return List.of();
   }

   protected int effectiveContentWidth() {
      return Math.min(300, this.width - 40);
   }

   protected int effectiveFieldX() {
      return (this.width - this.effectiveContentWidth()) / 2;
   }

   protected int effectiveLeftColWidth() {
      int cw = this.effectiveContentWidth();
      return cw - cw / 5 - 10;
   }

   protected int effectiveRightColWidth() {
      return this.effectiveContentWidth() / 5;
   }

   protected int effectiveRightColX() {
      return this.effectiveFieldX() + this.effectiveLeftColWidth() + 10;
   }

   protected int getNameFieldWidth(int contentWidth) {
      return contentWidth;
   }

   protected int getShortcutResetX(int defaultX) {
      return defaultX;
   }

   protected int getShortcutButtonX(int defaultX) {
      return defaultX;
   }

   protected int getCommandFieldY() {
      return 134;
   }

   protected Component getCommandFieldLabel() {
      return Component.translatable("screen.command-gui.command");
   }

   protected void init() {
      super.init();
      this.initializing = true;
      this.cachedLabelName = Component.translatable("screen.command-gui.name");
      this.cachedLabelDesc = Component.translatable("screen.command-gui.description");
      this.cachedLabelPlaceholder = Component.translatable("screen.command-gui.placeholder_label");
      this.cachedLabelCommands = Component.translatable("screen.command-gui.commands_label");
      this.cachedLabelCommand = Component.translatable("screen.command-gui.command");
      this.cachedLabelCommandDelay = Component.translatable("screen.command-gui.command_delay_short");
      this.cachedLabelShortcut = Component.translatable("screen.command-gui.shortcut");
      int centerX = this.width / 2;
      int fieldX = this.effectiveFieldX();
      int contentWidth = this.effectiveContentWidth();
      int leftWidth = this.effectiveLeftColWidth();
      int rightWidth = this.effectiveRightColWidth();
      int rightColX = this.effectiveRightColX();
      int bottomBarY = this.height - 26;
      int nameFieldW = Math.max(60, this.getNameFieldWidth(leftWidth) - 48 - 4);
      this.nameField = new GuiEditBox(this.font, fieldX, 44, nameFieldW, 16, Component.translatable("screen.command-gui.name"));
      this.nameField.setMaxLength(50);
      this.nameField.setValue(this.getInitialName());
      this.nameField.setResponder(text -> this.markDirty());
      if (this.showNameHint()) {
         this.nameField.setHint(Component.translatable("screen.command-gui.name_hint"));
      }

      this.addRenderableWidget(this.nameField);
      this.commandDelayField = new DigitsOnlyEditBox(
         this.font, fieldX + nameFieldW + 4, 44, 48, 16, Component.translatable("screen.command-gui.command_delay_short")
      );
      this.commandDelayField.setMaxLength(5);
      this.commandDelayField.setValue(String.valueOf(this.getInitialCommandDelay()));
      this.commandDelayField.setHint(Component.literal("tick"));
      this.commandDelayField.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.command_delay_hint")));
      this.commandDelayField.setResponder(text -> {
         this.markDirty();

         try {
            this.commandDelay = Math.max(1, Math.min(72000, text.isEmpty() ? 1 : Integer.parseInt(text)));
         } catch (NumberFormatException var3x) {
            this.commandDelay = 1;
         }
      });
      this.addRenderableWidget(this.commandDelayField);
      int currentY = 74;
      currentY = this.initExtraRow(fieldX, currentY);
      this.descriptionField = new GuiEditBox(this.font, fieldX, currentY, leftWidth, 16, Component.translatable("screen.command-gui.description"));
      this.descriptionField.setMaxLength(100);
      this.descriptionField.setValue(this.getInitialDescription());
      this.descriptionField.setHint(Component.translatable("screen.command-gui.description_hint"));
      this.descriptionField.setResponder(text -> this.markDirty());
      this.addRenderableWidget(this.descriptionField);
      this.shortcut = CommandShortcut.normalize(this.getInitialShortcut());
      this.shortcutButton = GuiButton.themed(Component.literal(this.shortcutMessage()), btn -> this.toggleShortcutCapture())
         .bounds(this.getShortcutButtonX(fieldX), 104, 150, 18)
         .build();
      this.addRenderableWidget(this.shortcutButton);
      this.shortcutResetButton = GuiButton.themed(Component.translatable("screen.command-gui.shortcut.reset"), btn -> this.resetShortcut())
         .bounds(this.getShortcutResetX(fieldX + 154), 104, 44, 18)
         .build();
      this.shortcutResetButton.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.shortcut.reset_hint")));
      this.addRenderableWidget(this.shortcutResetButton);
      this.refreshShortcutConflict();
      this.commandField = new GuiEditBox(this.font, fieldX, this.getCommandFieldY(), leftWidth, 20, Component.translatable("screen.command-gui.command"));
      this.commandField.setMaxLength(256);
      this.commandField.setValue(this.getInitialCommand());
      Component cmdHint = this.getCommandHint();
      if (cmdHint != null) {
         this.commandField.setHint(cmdHint);
      }

      this.addRenderableWidget(this.commandField);
      this.setInitialFocus(this.commandField);
      this.commandSuggestions = new CommandSuggestions(this.minecraft, this, this.commandField, this.font, false, true, 0, 7, false, Integer.MIN_VALUE);
      this.commandSuggestions.setAllowSuggestions(true);
      this.commandSuggestions.updateCommandInfo();
      this.commandField.setResponder(text -> {
         this.markDirty();
         if (text.startsWith("/")) {
            this.commandSuggestions.setAllowSuggestions(true);
            this.commandSuggestions.updateCommandInfo();
         } else {
            this.hideCommandSuggestions();
         }
      });

      for (int i = 0; i < TYPE_KEYS.length; i++) {
         int index = i;
         int btnY = 44 + i * 17;
         PassiveButton typeBtn = new PassiveButton(rightColX, btnY, rightWidth, 16, Component.translatable(TYPE_KEYS[i]), btn -> this.appendPlaceholder(index));
         this.addRenderableWidget(typeBtn);
      }

      if (this.commandList.isEmpty()) {
         List<String> initial = this.getInitialCommandList();
         if (initial != null && !initial.isEmpty()) {
            this.commandList.addAll(initial);
         }
      }

      int extraCount = this.getExtraBottomBarButtonCount();
      int totalButtons = 3 + extraCount;
      int maxBtnW = Math.max(40, (contentWidth - 4 * (totalButtons - 1)) / totalButtons);
      int addBtnW = Math.min(120, maxBtnW);
      int saveCancelW = Math.min(80, maxBtnW);
      int barTotalW = addBtnW + saveCancelW * (totalButtons - 1) + 4 * (totalButtons - 1);
      int barStartX = fieldX + (contentWidth - barTotalW) / 2;
      this.addToListButton = GuiButton.themed(Component.translatable("screen.command-gui.add_command_line"), btn -> this.addCurrentCommandToList())
         .bounds(barStartX, bottomBarY, addBtnW, 20)
         .build();
      this.addRenderableWidget(this.addToListButton);
      int barX = barStartX + addBtnW + 4;
      Button saveButton = GuiButton.themed(Component.translatable("screen.command-gui.save"), btn -> this.saveAndClose())
         .bounds(barX, bottomBarY, saveCancelW, 20)
         .build();
      this.addRenderableWidget(saveButton);
      barX += saveCancelW + 4;

      for (int i = 0; i < extraCount; i++) {
         this.addExtraBottomBarButton(i, barX, bottomBarY, saveCancelW);
         barX += saveCancelW + 4;
      }

      Button cancelButton = GuiButton.themed(Component.translatable("screen.command-gui.cancel"), btn -> this.requestExit())
         .bounds(barX, bottomBarY, saveCancelW, 20)
         .build();
      this.addRenderableWidget(cancelButton);
      this.rebuildCommandListButtons();
      this.initializing = false;
   }

   protected int getExtraBottomBarButtonCount() {
      return 0;
   }

   protected void addExtraBottomBarButton(int index, int x, int y, int width) {
   }

   private void addCurrentCommandToList() {
      this.hideCommandSuggestions();
      String cmd = this.commandField.getValue().trim();
      if (!cmd.isEmpty()) {
         if (!cmd.startsWith("/")) {
            cmd = "/" + cmd;
         }

         this.commandList.add(cmd);
         this.commandField.setValue("");
         this.markDirty();
         this.rebuildCommandListButtons();
      }
   }

   protected void rebuildCommandListButtons() {
      this.hideCommandSuggestions();

      for (Button btn : this.commandRemoveButtons) {
         this.removeWidget(btn);
      }

      for (Button btn : this.commandMoveUpButtons) {
         this.removeWidget(btn);
      }

      for (Button btn : this.commandMoveDownButtons) {
         this.removeWidget(btn);
      }

      for (Button btn : this.commandCopyButtons) {
         this.removeWidget(btn);
      }

      this.commandRemoveButtons.clear();
      this.commandMoveUpButtons.clear();
      this.commandMoveDownButtons.clear();
      this.commandCopyButtons.clear();
      int fieldX = this.effectiveFieldX();
      int leftWidth = this.effectiveLeftColWidth();
      int listY = this.commandListTop();
      int visibleRows = this.commandListVisibleRows();
      this.commandScroll = Math.max(0, Math.min(this.commandScroll, this.commandListMaxScroll()));
      int buttonX = fieldX + leftWidth - 14 - 2 - 30;
      int copyX = buttonX - 30 - 1;
      int downX = copyX - 48 - 1;
      int upX = downX - 48 - 1;
      int start = this.commandScroll;
      int end = Math.min(this.commandList.size(), start + visibleRows);

      for (int i = start; i < end; i++) {
         int idx = i;
         int y = listY + (i - start) * 12;
         Button upBtn = GuiButton.themed(Component.translatable("screen.command-gui.step_up_short"), btn -> this.moveCommandUp(idx))
            .bounds(upX, y, 48, 12)
            .build();
         upBtn.active = idx > 0;
         upBtn.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.step_up")));
         this.commandMoveUpButtons.add(upBtn);
         this.addRenderableWidget(upBtn);
         Button downBtn = GuiButton.themed(Component.translatable("screen.command-gui.step_down_short"), btn -> this.moveCommandDown(idx))
            .bounds(downX, y, 48, 12)
            .build();
         downBtn.active = idx < this.commandList.size() - 1;
         downBtn.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.step_down")));
         this.commandMoveDownButtons.add(downBtn);
         this.addRenderableWidget(downBtn);
         Button copyBtn = GuiButton.themed(Component.translatable("screen.command-gui.step_copy_short"), btn -> this.copyCommandToClipboard(idx))
            .bounds(copyX, y, 30, 12)
            .build();
         copyBtn.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.step_copy_tip")));
         this.commandCopyButtons.add(copyBtn);
         this.addRenderableWidget(copyBtn);
         Button removeBtn = GuiButton.themed(Component.translatable("screen.command-gui.delete"), btn -> {
            this.commandList.remove(idx);
            this.markDirty();
            this.rebuildCommandListButtons();
         }).bounds(buttonX, y, 30, 12).build();
         this.commandRemoveButtons.add(removeBtn);
         this.addRenderableWidget(removeBtn);
      }
   }

   private void hideCommandSuggestions() {
      if (this.commandSuggestions != null) {
         this.commandSuggestions.setAllowSuggestions(false);
         this.commandSuggestions.hide();
         this.commandSuggestions.updateCommandInfo();
      }
   }

   private void moveCommandUp(int index) {
      if (index > 0 && index < this.commandList.size()) {
         Collections.swap(this.commandList, index, index - 1);
         this.markDirty();
         this.rebuildCommandListButtons();
      }
   }

   private void moveCommandDown(int index) {
      if (index >= 0 && index < this.commandList.size() - 1) {
         Collections.swap(this.commandList, index, index + 1);
         this.markDirty();
         this.rebuildCommandListButtons();
      }
   }

   private void copyCommandToClipboard(int index) {
      if (index >= 0 && index < this.commandList.size()) {
         Minecraft mc = Minecraft.getInstance();
         mc.keyboardHandler.setClipboard(this.commandList.get(index));
         mc.gui.hud.getChat().addClientSystemMessage(Component.translatable("screen.command-gui.copied"));
      }
   }

   protected void markDirty() {
      if (!this.initializing) {
         this.dirty = true;
      }
   }

   private void requestExit() {
      if (!this.dirty) {
         this.minecraft.gui.setScreen(this.parent);
      } else {
         this.minecraft
            .gui
            .setScreen(
               new UnsavedExitScreen(
                  this,
                  Component.translatable("screen.command-gui.unsaved.title"),
                  Component.translatable("screen.command-gui.unsaved.message"),
                  this::trySaveFromExit,
                  () -> this.minecraft.gui.setScreen(this.parent)
               )
            );
      }
   }

   private void trySaveFromExit() {
      if (this.getAllCommands().isEmpty()) {
         this.minecraft.gui.setScreen(this);
      } else {
         this.saveAndClose();
      }
   }

   private int getCommandListStartY() {
      return this.getCommandFieldY() + 20 + 4;
   }

   private int commandListTop() {
      return this.getCommandListStartY();
   }

   private int commandListBottom() {
      return this.height - 32;
   }

   private int commandListVisibleRows() {
      return Math.max(1, (this.commandListBottom() - this.commandListTop()) / 12);
   }

   private int commandListMaxScroll() {
      return Math.max(0, this.commandList.size() - this.commandListVisibleRows());
   }

   private int commandScrollbarX() {
      return this.effectiveFieldX() + this.effectiveLeftColWidth() - 14;
   }

   protected void appendPlaceholder(int index) {
      String placeholder = PLACEHOLDERS[index];
      String current = this.commandField.getValue();
      int cursor = this.commandField.getCursorPosition();
      StringBuilder builder = new StringBuilder(current);
      if (cursor > 0 && builder.charAt(cursor - 1) != ' ') {
         builder.insert(cursor, " " + placeholder);
         cursor += 1 + placeholder.length();
      } else {
         builder.insert(cursor, placeholder);
         cursor += placeholder.length();
      }

      this.commandField.setValue(builder.toString());
      this.commandField.setCursorPosition(cursor);
      this.setFocused(this.commandField);
   }

   protected List<String> getAllCommands() {
      List<String> all = new ArrayList<>(this.commandList);
      String current = this.commandField.getValue().trim();
      if (!current.isEmpty()) {
         if (!current.startsWith("/")) {
            current = "/" + current;
         }

         all.add(current);
      }

      return all;
   }

   protected final void saveAndClose() {
      String newName = this.nameField.getValue().trim();
      List<String> commands = this.getAllCommands();
      if (newName.isEmpty()) {
         newName = CommandConfig.nextDefaultCommandName();
         this.nameField.setValue(newName);
      }

      if (!newName.isEmpty() && !commands.isEmpty()) {
         this.performSave();
         this.dirty = false;
         this.parent.refresh();
         this.minecraft.gui.setScreen(this.parent);
      }
   }

   @Override
   public void resize(int width, int height) {
      String name = this.nameField.getValue();
      String description = this.descriptionField.getValue();
      String command = this.commandField.getValue();
      int savedDelay = this.getCommandDelayValue();
      List<String> savedList = new ArrayList<>(this.commandList);
      String savedShortcut = this.shortcut;
      boolean savedCapturing = this.capturingShortcut;
      List<Integer> savedCapturedKeys = new ArrayList<>(this.capturedKeys);
      this.onBeforeResize();
      this.initializing = true;
      super.resize(width, height);
      this.nameField.setValue(name);
      this.descriptionField.setValue(description);
      this.commandField.setValue(command);
      this.commandDelayField.setValue(String.valueOf(savedDelay));
      this.commandDelay = savedDelay;
      this.commandList.clear();
      this.commandList.addAll(savedList);
      this.shortcut = savedShortcut;
      this.capturingShortcut = savedCapturing;
      this.capturedKeys.clear();
      this.capturedKeys.addAll(savedCapturedKeys);
      if (this.shortcutButton != null) {
         this.shortcutButton.setMessage(Component.literal(this.shortcutMessage()));
      }

      this.refreshShortcutConflict();
      this.initializing = false;
      this.onAfterResize();
      this.commandSuggestions.updateCommandInfo();
      this.rebuildCommandListButtons();
   }

   public boolean keyPressed(KeyEvent keyEvent) {
      int keyCode = keyEvent.key();
      if (this.capturingShortcut) {
         return this.captureShortcutKey(keyEvent);
      } else if (keyCode != 257 && keyCode != 335) {
         if (this.commandSuggestions.keyPressed(keyEvent)) {
            return true;
         } else if (keyCode == 258) {
            return true;
         } else if (keyCode == 256) {
            this.requestExit();
            return true;
         } else {
            return super.keyPressed(keyEvent);
         }
      } else {
         if (this.commandField.isFocused()) {
            this.addCurrentCommandToList();
         } else {
            List<String> commands = this.getAllCommands();
            if (!commands.isEmpty() && !this.nameField.isFocused() && !this.descriptionField.isFocused() && !this.commandDelayField.isFocused()) {
               this.saveAndClose();
            }
         }

         return true;
      }
   }

   private boolean captureShortcutKey(KeyEvent keyEvent) {
      int key = keyEvent.key();
      if (keyEvent.isEscape()) {
         this.capturingShortcut = false;
         this.capturedKeys.clear();
         this.shortcutButton.setMessage(Component.literal(this.shortcutMessage()));
         return true;
      } else if (key == 257 || key == 335) {
         this.finalizeShortcut();
         return true;
      } else if (key != 259 && key != 261) {
         if (this.capturedKeys.contains(key)) {
            this.capturedKeys.remove(Integer.valueOf(key));
         } else if (this.capturedKeys.size() < 3) {
            this.capturedKeys.add(key);
         }

         if (this.capturedKeys.size() >= 3) {
            this.finalizeShortcut();
         } else {
            this.shortcutButton.setMessage(Component.literal(this.shortcutMessage()));
         }

         return true;
      } else {
         if (!this.capturedKeys.isEmpty()) {
            this.capturedKeys.remove(this.capturedKeys.size() - 1);
         }

         this.shortcutButton.setMessage(Component.literal(this.shortcutMessage()));
         return true;
      }
   }

   private void toggleShortcutCapture() {
      if (this.capturingShortcut) {
         this.finalizeShortcut();
      } else {
         this.capturedKeys.clear();
         this.capturingShortcut = true;
         this.shortcutButton.setMessage(Component.literal(this.shortcutMessage()));
      }
   }

   private void finalizeShortcut() {
      if (!this.capturedKeys.isEmpty()) {
         this.shortcut = CommandShortcut.normalizeCodes(this.capturedKeys);
      }

      this.capturedKeys.clear();
      this.capturingShortcut = false;
      this.refreshShortcutConflict();
      this.markDirty();
      this.shortcutButton.setMessage(Component.literal(this.shortcutMessage()));
   }

   private void resetShortcut() {
      this.shortcut = "";
      this.capturedKeys.clear();
      this.capturingShortcut = false;
      this.refreshShortcutConflict();
      this.markDirty();
      this.shortcutButton.setMessage(Component.literal(this.shortcutMessage()));
   }

   private String shortcutMessage() {
      if (this.capturingShortcut) {
         String partial = CommandShortcut.displayCaptured(this.capturedKeys, 3);
         if (partial.isEmpty()) {
            return "…";
         }
         return partial;
      } else {
         if (this.shortcut.isEmpty()) {
            return Component.translatable("screen.command-gui.shortcut.none").getString();
         }
         return CommandShortcut.display(this.shortcut);
      }
   }

   private void refreshShortcutConflict() {
      String name = this.nameField != null ? this.nameField.getValue().trim() : "";
      this.shortcutConflict = this.shortcut.isEmpty() ? null : CommandShortcut.findConflict(this.shortcut, name);
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      if (this.commandSuggestions.mouseScrolled(scrollY)) {
         return true;
      } else {
         int fieldX = this.effectiveFieldX();
         int leftWidth = this.effectiveLeftColWidth();
         if (mouseX >= (double)fieldX
            && mouseX <= (double)(fieldX + leftWidth)
            && mouseY >= (double)this.commandListTop()
            && mouseY <= (double)this.commandListBottom()) {
            int maxScroll = this.commandListMaxScroll();
            if (scrollY > 0.0 && this.commandScroll > 0) {
               this.commandScroll--;
               this.rebuildCommandListButtons();
            } else if (scrollY < 0.0 && this.commandScroll < maxScroll) {
               this.commandScroll++;
               this.rebuildCommandListButtons();
            }

            return true;
         } else {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
         }
      }
   }

   public boolean mouseClicked(MouseButtonEvent mouseEvent, boolean focused) {
      if (this.capturingShortcut) {
         boolean insideCapture = this.shortcutButton != null && this.shortcutButton.isMouseOver(mouseEvent.x(), mouseEvent.y());
         boolean insideReset = this.shortcutResetButton != null && this.shortcutResetButton.isMouseOver(mouseEvent.x(), mouseEvent.y());
         if (!insideCapture && !insideReset) {
            this.finalizeShortcut();
         }
      }

      if (this.commandSuggestions.mouseClicked(mouseEvent)) {
         return true;
      } else {
         if (mouseEvent.button() == 0) {
            ScrollbarHandle handle = new ScrollbarHandle(this.commandScrollbarX(), this.commandListTop(), 12, this.commandListBottom() - this.commandListTop());
            if (handle.contains(mouseEvent.x(), mouseEvent.y()) && this.commandListMaxScroll() > 0) {
               int thumbTop = handle.thumbTop(
                  this.commandScroll, this.commandListMaxScroll(), this.commandListVisibleRows(), Math.max(1, this.commandList.size())
               );
               this.commandScrollbarGrabOffset = mouseEvent.y() - (double)thumbTop;
               this.draggingCommandScrollbar = true;
               return true;
            }
         }

         return super.mouseClicked(mouseEvent, focused);
      }
   }

   public boolean mouseDragged(MouseButtonEvent mouseEvent, double dragX, double dragY) {
      if (this.draggingCommandScrollbar) {
         ScrollbarHandle handle = new ScrollbarHandle(this.commandScrollbarX(), this.commandListTop(), 12, this.commandListBottom() - this.commandListTop());
         this.commandScroll = handle.offsetFromY(
            mouseEvent.y(), this.commandScrollbarGrabOffset, this.commandListMaxScroll(), this.commandListVisibleRows(), Math.max(1, this.commandList.size())
         );
         this.rebuildCommandListButtons();
         return true;
      } else {
         return super.mouseDragged(mouseEvent, dragX, dragY);
      }
   }

   public boolean mouseReleased(MouseButtonEvent mouseEvent) {
      this.draggingCommandScrollbar = false;
      return super.mouseReleased(mouseEvent);
   }

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      int centerY = this.height / 2 + -20;
      int fieldX = this.effectiveFieldX();
      int leftWidth = this.effectiveLeftColWidth();
      int rightColX = this.effectiveRightColX();
      MutableComponent title = this.title.copy();
      if (this.dirty) {
         title.append(" ").append(Component.translatable("screen.command-gui.unsaved_badge").withColor(-22016));
      }

      guiGraphics.centeredText(this.font, title, centerX, this.getTitleY(centerY), -1);
      guiGraphics.text(this.font, this.cachedLabelName, fieldX, 34, -5592406);
      int descLabelY = 74;
      descLabelY = this.renderExtraLabel(guiGraphics, fieldX, descLabelY);
      guiGraphics.text(this.font, this.cachedLabelDesc, fieldX, descLabelY - 10, -5592406);
      int shortcutLabelY = 94;
      guiGraphics.text(this.font, this.cachedLabelShortcut, fieldX, shortcutLabelY, -5592406);
      int statusX = fieldX + this.font.width(this.cachedLabelShortcut) + 10;
      if (this.shortcut.isEmpty()) {
         guiGraphics.text(this.font, Component.translatable("screen.command-gui.shortcut.empty_hint"), statusX, shortcutLabelY, -7829368);
      } else if (this.shortcutConflict != null) {
         guiGraphics.text(this.font, Component.literal("冲突：" + this.shortcutConflict), statusX, shortcutLabelY, -43691);
      } else {
         guiGraphics.text(this.font, Component.translatable("screen.command-gui.shortcut.no_conflict"), statusX, shortcutLabelY, -11141291);
      }

      guiGraphics.text(this.font, this.getCommandFieldLabel(), fieldX, this.getCommandFieldY() - 10, -5592406);
      guiGraphics.text(this.font, this.cachedLabelPlaceholder, rightColX, 34, -5592406);
      guiGraphics.text(this.font, this.cachedLabelCommandDelay, fieldX + this.nameField.getWidth() + 4, 34, -5592406);
      if (!this.commandList.isEmpty()) {
         int maxW = leftWidth - 14 - 2 - 96 - 30 - 30 - 3 - 18 - 2;
         int start = this.commandScroll;
         int end = Math.min(this.commandList.size(), start + this.commandListVisibleRows());

         for (int i = start; i < end; i++) {
            String cmd = this.commandList.get(i);
            String display = this.font.plainSubstrByWidth(cmd, maxW);
            int y = this.commandListTop() + (i - start) * 12;
            guiGraphics.text(this.font, Component.literal("#" + (i + 1)), fieldX, y, -5592406);
            guiGraphics.text(this.font, Component.literal(display), fieldX + 18, y, -1);
            if (this.font.width(cmd) > maxW && mouseY >= y && mouseY < y + 12 && mouseX >= fieldX + 18 && mouseX <= fieldX + leftWidth - 14 - 2) {
               this.drawCommandHoverTooltip(guiGraphics, cmd, (double)mouseX, (double)mouseY);
            }
         }
      }

      ScrollbarHandle handle = new ScrollbarHandle(this.commandScrollbarX(), this.commandListTop(), 12, this.commandListBottom() - this.commandListTop());
      handle.render(
         guiGraphics,
         this.commandScroll,
         this.commandListMaxScroll(),
         this.commandListVisibleRows(),
         Math.max(1, this.commandList.size()),
         handle.contains((double)mouseX, (double)mouseY)
      );
      this.commandSuggestions.extractRenderState(guiGraphics, mouseX, mouseY);
   }

   private void drawCommandHoverTooltip(GuiGraphicsExtractor guiGraphics, String command, double mouseX, double mouseY) {
      int tipW = Math.min(320, this.width - 30);
      List<FormattedCharSequence> lines = this.font.split(Component.literal(command), tipW - 8);
      int tipH = 4 + lines.size() * 9 + 4;
      int tipX = (int)Math.min(mouseX + 8.0, (double)(this.width - tipW - 10));
      int tipY = (int)(mouseY + 8.0);
      if (tipY + tipH > this.height - 10) {
         tipY = (int)(mouseY - (double)tipH - 6.0);
      }

      guiGraphics.fill(tipX - 1, tipY - 1, tipX + tipW + 1, tipY + tipH + 1, -16777216);
      guiGraphics.fill(tipX, tipY, tipX + tipW, tipY + tipH, -14013910);

      for (int i = 0; i < lines.size(); i++) {
         guiGraphics.text(this.font, lines.get(i), tipX + 4, tipY + 4 + i * 9, -1);
      }
   }
}
