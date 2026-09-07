package com.remrin.client.gui;

import com.remrin.client.machine.MachineModels;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public class StepEditorScreen extends BaseParentedScreen<TimelineEditorScreen> implements StepCommandHost {
   private static final int FIELD_WIDTH = 360;
   private static final int FIELD_HEIGHT = 20;
   private static final int BTN_GAP = 4;
   private static final int COL_GAP = 6;
   private static final int COMMAND_WIDTH = 280;
   private static final String[] CUSTOM_SUGGESTIONS = new String[]{"{bot}", "{player}"};
   private static final int SUGGESTION_ROW_HEIGHT = 16;
   private static final int DESC_FIELD_Y = 26;
   private static final int BOT_FIELD_Y = 64;
   private static final int QUICK_BTN_Y = 88;
   private static final int CMD_LABEL_Y = 112;
   private static final int CMD_FIELD_Y = 124;
   private static final int LIST_LABEL_Y = 154;
   private static final int LIST_TOP = 166;
   private static final int LIST_ROW_HEIGHT = 12;
   private static final int MOVE_BTN_W = 48;
   private static final int COPY_BTN_W = 30;
   private static final int REMOVE_BTN_W = 30;
   private static final int ROW_ACTION_GAP = 1;
   private static final int ROW_NUMBER_W = 18;
   private static final int RIGHT_RESERVED = 16;
   private final MachineModels.Step step;
   private final List<String> botNames;
   private final Runnable onChanged;
   private final List<String> commandList = new ArrayList<>();
   private final List<Boolean> commandValid = new ArrayList<>();
   private final List<Button> removeButtons = new ArrayList<>();
   private final List<Button> moveUpButtons = new ArrayList<>();
   private final List<Button> moveDownButtons = new ArrayList<>();
   private final List<Button> copyButtons = new ArrayList<>();
   private EditBox descriptionField;
   private EditBox botField;
   private EditBox delayField;
   private EditBox commandField;
   private CommandSuggestions commandSuggestions;
   private String descriptionText = "";
   private String botText = "";
   private String delayText = "";
   private String commandText = "";
   private int commandScroll = 0;
   private ScrollbarHandle commandScrollbar = null;
   private boolean draggingCommandScrollbar = false;
   private double commandScrollbarGrabOffset = 0.0;
   private boolean customSuggestionsActive = false;
   private int customSuggestionIndex = 0;

   public StepEditorScreen(TimelineEditorScreen parent, MachineModels.Step step, List<String> botNames, Runnable onChanged) {
      super(Component.translatable("screen.command-gui.machine.step_title"), parent);
      this.step = step;
      this.botNames = botNames;
      this.onChanged = onChanged;
      if (step.commands != null) {
         this.commandList.addAll(step.commands);
      }

      for (String command : this.commandList) {
         this.commandValid.add(CommandHelper.validateCommandFormat(command) == null);
      }

      this.descriptionText = step.description != null ? step.description : "";
      this.botText = this.currentBotName();
      this.delayText = String.valueOf(step.commandDelay);
   }

   protected void init() {
      super.init();
      int fieldX = (this.width - 360) / 2;
      this.descriptionField = new EditBox(this.font, fieldX, 26, 360, 20, Component.translatable("screen.command-gui.machine.step_description"));
      this.descriptionField.setMaxLength(100);
      this.descriptionField.setValue(this.descriptionText);
      this.descriptionField.setHint(Component.translatable("screen.command-gui.machine.step_description_hint"));
      this.descriptionField.setResponder(text -> this.descriptionText = text);
      this.addRenderableWidget(this.descriptionField);
      this.botField = new EditBox(this.font, fieldX, 64, 110, 20, Component.translatable("screen.command-gui.machine.step_bot"));
      this.botField.setMaxLength(20);
      this.botField.setValue(this.botText);
      this.botField.setResponder(text -> {
         this.botText = text;
         this.onBotTextChanged(text);
      });
      this.addRenderableWidget(this.botField);
      int pickBtnW = 45;
      this.addRenderableWidget(
         Button.builder(
               Component.translatable("screen.command-gui.machine.step_pick"), btn -> this.minecraft.gui.setScreen(new BotSelectScreen(this, this.botNames))
            )
            .bounds(fieldX + 116, 64, pickBtnW, 20)
            .build()
      );
      int quickRight = fieldX + 360;
      int delayFieldW = 110;
      int delayPickX = quickRight - pickBtnW;
      int delayFieldX = delayPickX - 4 - delayFieldW;
      this.delayField = new DigitsOnlyEditBox(this.font, delayFieldX, 64, delayFieldW, 20, Component.translatable("screen.command-gui.machine.step_delay"));
      this.delayField.setMaxLength(7);
      this.delayField.setValue(this.delayText);
      this.delayField.setResponder(text -> this.delayText = text);
      this.delayField.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.machine.step_delay_hint")));
      this.addRenderableWidget(this.delayField);
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.machine.step_pick"), btn -> this.openDelayPicker())
            .bounds(delayPickX, 64, pickBtnW, 20)
            .build()
      );
      int baseBtnW = 56;
      int leftover = 340 - baseBtnW * 6;
      int x = this.addQuickButton(fieldX, 88, baseBtnW + (leftover-- > 0 ? 1 : 0), "spawn", () -> this.minecraft.gui.setScreen(new SpawnOptionScreen(this)));
      x = this.addQuickButton(x, 88, baseBtnW + (leftover-- > 0 ? 1 : 0), "kill", () -> this.insertQuickCommand("/player {bot} kill"));
      x = this.addQuickButton(
         x,
         88,
         baseBtnW + (leftover-- > 0 ? 1 : 0),
         Component.translatable("screen.command-gui.machine.step_attack_use"),
         () -> this.minecraft.gui.setScreen(new ActionOptionScreen(this))
      );
      x = this.addQuickButton(
         x,
         88,
         baseBtnW + (leftover-- > 0 ? 1 : 0),
         Component.translatable("screen.command-gui.machine.step_sneak"),
         () -> this.insertQuickCommand("/player {bot} sneak")
      );
      x = this.addQuickButton(
         x,
         88,
         baseBtnW + (leftover-- > 0 ? 1 : 0),
         Component.translatable("screen.command-gui.machine.step_mount"),
         () -> this.insertQuickCommand("/player {bot} mount")
      );
      this.addQuickButton(
         x,
         88,
         baseBtnW + (leftover-- > 0 ? 1 : 0),
         Component.translatable("screen.command-gui.machine.step_stop"),
         () -> this.insertQuickCommand("/player {bot} stop")
      );
      this.commandField = new EditBox(this.font, fieldX, 124, 280, 20, Component.translatable("screen.command-gui.command"));
      this.commandField.setMaxLength(256);
      this.commandField.setValue(this.commandText);
      this.addRenderableWidget(this.commandField);
      this.commandSuggestions = new CommandSuggestions(this.minecraft, this, this.commandField, this.font, false, true, 0, 7, false, Integer.MIN_VALUE);
      this.commandSuggestions.setAllowSuggestions(true);
      this.commandSuggestions.updateCommandInfo();
      this.commandField.setResponder(text -> {
         this.commandText = text;
         this.commandSuggestions.updateCommandInfo();
         this.updateCustomSuggestions(text);
      });
      this.updateCustomSuggestions(this.commandText);
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.add_command_line"), btn -> this.addCommand()).bounds(fieldX + 280 + 6, 124, 74, 20).build()
      );
      this.rebuildListButtons();
      int barY = this.height - 22;
      int barWidth = Math.min(70, 90);
      int barStartX = fieldX + (360 - barWidth * 2 - 8) / 2;
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.save"), btn -> this.saveAndClose()).bounds(barStartX, barY, barWidth, 18).build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.back"), btn -> this.saveAndClose())
            .bounds(barStartX + barWidth + 8, barY, barWidth, 18)
            .build()
      );
   }

   private int addQuickButton(int x, int y, int width, String label, Runnable action) {
      return this.addQuickButton(x, y, width, Component.literal(label), action);
   }

   private int addQuickButton(int x, int y, int width, Component label, Runnable action) {
      Button button = Button.builder(label, btn -> action.run()).bounds(x, y, width, 20).build();
      this.addRenderableWidget(button);
      return x + width + 4;
   }

   private String currentBotName() {
      if (this.step.bot >= 0 && this.step.bot < this.botNames.size()) {
         return this.botNames.get(this.step.bot);
      }
      return "bot" + this.step.bot;
   }

   private void onBotTextChanged(String text) {
      int index = this.botNames.indexOf(text.trim());
      if (index >= 0 && index != this.step.bot) {
         String oldName = this.currentBotName();
         this.step.bot = index;
         String newName = this.currentBotName();
         if (!oldName.isEmpty() && !oldName.equals(newName)) {
            this.replaceBotNameInCommands(oldName, newName);
         }
      }
   }

   @Override
   public void selectBotByName(String name) {
      int index = this.botNames.indexOf(name);
      if (index >= 0 && index != this.step.bot) {
         String oldName = this.currentBotName();
         this.step.bot = index;
         this.replaceBotNameInCommands(oldName, name);
         this.botField.setValue(name);
      }
   }

   private void replaceBotNameInCommands(String oldName, String newName) {
      String pattern = "\\b" + Pattern.quote(oldName) + "\\b";
      String newCommandText = this.commandText.replaceAll(pattern, newName);
      if (!newCommandText.equals(this.commandText)) {
         this.commandText = newCommandText;
         this.commandField.setValue(this.commandText);
      }

      for (int i = 0; i < this.commandList.size(); i++) {
         this.commandList.set(i, this.commandList.get(i).replaceAll(pattern, newName));
      }

      if (this.step.commands != null) {
         for (int i = 0; i < this.step.commands.size(); i++) {
            this.step.commands.set(i, this.step.commands.get(i).replaceAll(pattern, newName));
         }
      }
   }

   private void updateCustomSuggestions(String text) {
      String token = lastToken(text);
      boolean active = token.startsWith("{") && this.commandField != null && this.commandField.isFocused();
      if (active && this.commandSuggestions != null) {
         this.commandSuggestions.hide();
      }

      this.customSuggestionsActive = active;
      if (!active) {
         this.customSuggestionIndex = 0;
      }
   }

   private static String lastToken(String text) {
      if (text == null) {
         return "";
      } else {
         int space = text.lastIndexOf(32);
         if (space >= 0) {
            return text.substring(space + 1);
         }
         return text;
      }
   }

   private void insertCustomSuggestion() {
      String candidate = CUSTOM_SUGGESTIONS[this.customSuggestionIndex % CUSTOM_SUGGESTIONS.length];
      String text = this.commandText;
      int space = text.lastIndexOf(32);
      String newText = space >= 0 ? text.substring(0, space + 1) + candidate : candidate;
      this.commandField.setValue(newText);
      this.customSuggestionIndex = (this.customSuggestionIndex + 1) % CUSTOM_SUGGESTIONS.length;
      this.setFocused(this.commandField);
   }

   private int suggestionPopupY() {
      return this.commandField.getY() + this.commandField.getHeight() + 4;
   }

   private void renderCustomSuggestions(GuiGraphicsExtractor guiGraphics) {
      if (this.customSuggestionsActive && this.commandField != null && this.commandField.isFocused()) {
         int fieldX = (this.width - 360) / 2;
         int popupW = 100;
         int popupY = this.suggestionPopupY();
         int popupH = CUSTOM_SUGGESTIONS.length * 16;
         guiGraphics.fill(fieldX - 1, popupY - 1, fieldX + popupW + 1, popupY + popupH + 1, -16777216);

         for (int i = 0; i < CUSTOM_SUGGESTIONS.length; i++) {
            int y = popupY + i * 16;
            if (i == this.customSuggestionIndex % CUSTOM_SUGGESTIONS.length) {
               guiGraphics.fill(fieldX, y, fieldX + popupW, y + 16, -13412916);
            }

            guiGraphics.text(this.font, Component.literal(CUSTOM_SUGGESTIONS[i]), fieldX + 4, y + 3, -1);
         }
      }
   }

   private void openDelayPicker() {
      NumberInputScreen picker = new NumberInputScreen(
         this, Component.translatable("screen.command-gui.machine.step_delay"), null, 1, 72000, new int[]{1, 5, 10, 20, 40, 100, 200, 400, 600, 1200, 2400}
      ) {

         @Override
         protected void onNumberConfirmed(String number) {
            StepEditorScreen.this.delayField.setValue(number);
            StepEditorScreen.this.minecraft.gui.setScreen(StepEditorScreen.this);
         }
      };
      this.minecraft.gui.setScreen(picker);
   }

   private void insertQuickCommand(String command) {
      this.commandField.setValue("");
      this.appendCommand(command);
   }

   @Override
   public void appendCommand(String command) {
      String resolved = command.replace("{bot}", this.currentBotName());
      String current = this.commandField.getValue();
      if (!current.isEmpty()) {
         current = current + " ";
      }

      this.commandField.setValue(current + resolved);
      this.setFocused(this.commandField);
   }

   private void addCommand() {
      String command = this.commandField.getValue().trim();
      if (!command.isEmpty()) {
         if (!command.startsWith("/")) {
            command = "/" + command;
         }

         this.commandList.add(command);
         this.commandValid.add(CommandHelper.validateCommandFormat(command) == null);
         this.commandField.setValue("");
         this.commandScroll = Math.max(0, this.commandList.size() - this.getMaxListRows());
         this.rebuildListButtons();
      }
   }

   @Override
   public void insertCommandSequence(List<String> commands) {
      if (commands != null) {
         this.commandField.setValue("");

         for (String command : commands) {
            if (command != null && !command.isEmpty()) {
               String resolved = command.replace("{bot}", this.currentBotName());
               String cmd = resolved.startsWith("/") ? resolved : "/" + resolved;
               this.commandList.add(cmd);
               this.commandValid.add(CommandHelper.validateCommandFormat(cmd) == null);
            }
         }

         this.commandScroll = Math.max(0, this.commandList.size() - this.getMaxListRows());
         this.rebuildListButtons();
      }
   }

   private int getMaxListRows() {
      return Math.max(1, (this.height - 22 - 166) / 12);
   }

   private int getListBottom() {
      return this.height - 22 - 4;
   }

   private void rebuildListButtons() {
      for (Button button : this.removeButtons) {
         this.removeWidget(button);
      }

      for (Button button : this.moveUpButtons) {
         this.removeWidget(button);
      }

      for (Button button : this.moveDownButtons) {
         this.removeWidget(button);
      }

      for (Button button : this.copyButtons) {
         this.removeWidget(button);
      }

      this.removeButtons.clear();
      this.moveUpButtons.clear();
      this.moveDownButtons.clear();
      this.copyButtons.clear();
      int fieldX = (this.width - 360) / 2;
      int maxRows = this.getMaxListRows();
      int maxScroll = Math.max(0, this.commandList.size() - maxRows);
      this.commandScroll = Math.min(this.commandScroll, maxScroll);
      int removeX = fieldX + 360 - 16 - 30;
      int copyX = removeX - 30 - 1;
      int downX = copyX - 48 - 1;
      int upX = downX - 48 - 1;

      for (int i = 0; i < maxRows; i++) {
         int index = this.commandScroll + i;
         if (index >= this.commandList.size()) {
            break;
         }

         int y = 166 + i * 12;
         Button upBtn = Button.builder(Component.translatable("screen.command-gui.step_up_short"), btn -> this.moveCommandUp(index))
            .bounds(upX, y, 48, 12)
            .build();
         upBtn.active = index > 0;
         upBtn.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.step_up")));
         this.moveUpButtons.add(upBtn);
         this.addRenderableWidget(upBtn);
         Button downBtn = Button.builder(Component.translatable("screen.command-gui.step_down_short"), btn -> this.moveCommandDown(index))
            .bounds(downX, y, 48, 12)
            .build();
         downBtn.active = index < this.commandList.size() - 1;
         downBtn.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.step_down")));
         this.moveDownButtons.add(downBtn);
         this.addRenderableWidget(downBtn);
         Button copyBtn = Button.builder(Component.translatable("screen.command-gui.step_copy_short"), btn -> this.copyCommandToClipboard(index))
            .bounds(copyX, y, 30, 12)
            .build();
         copyBtn.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.step_copy_tip")));
         this.copyButtons.add(copyBtn);
         this.addRenderableWidget(copyBtn);
         Button removeBtn = Button.builder(Component.translatable("screen.command-gui.delete"), btn -> {
            this.commandList.remove(index);
            this.commandValid.remove(index);
            this.rebuildListButtons();
         }).bounds(removeX, y, 30, 12).build();
         this.removeButtons.add(removeBtn);
         this.addRenderableWidget(removeBtn);
      }
   }

   private void moveCommandUp(int index) {
      if (index > 0 && index < this.commandList.size()) {
         Collections.swap(this.commandList, index, index - 1);
         Collections.swap(this.commandValid, index, index - 1);
         this.rebuildListButtons();
      }
   }

   private void moveCommandDown(int index) {
      if (index >= 0 && index < this.commandList.size() - 1) {
         Collections.swap(this.commandList, index, index + 1);
         Collections.swap(this.commandValid, index, index + 1);
         this.rebuildListButtons();
      }
   }

   private void copyCommandToClipboard(int index) {
      if (index >= 0 && index < this.commandList.size()) {
         Minecraft mc = Minecraft.getInstance();
         mc.keyboardHandler.setClipboard(this.commandList.get(index));
         mc.gui.hud.getChat().addClientSystemMessage(Component.translatable("screen.command-gui.copied"));
      }
   }

   private void saveAndClose() {
      this.step.description = this.descriptionField.getValue().trim();
      String botName = this.botField.getValue().trim();
      int botIndex = this.botNames.indexOf(botName);
      if (botIndex >= 0) {
         this.step.bot = botIndex;
      }

      this.step.bot = Math.max(0, Math.min(this.step.bot, Math.max(0, this.botNames.size() - 1)));

      try {
         this.step.commandDelay = Integer.parseInt(this.delayField.getValue().trim());
      } catch (NumberFormatException var5) {
      }

      this.step.commandDelay = Math.max(1, Math.min(72000, this.step.commandDelay));
      List<String> all = new ArrayList<>(this.commandList);
      String current = this.commandField.getValue().trim();
      if (!current.isEmpty()) {
         if (!current.startsWith("/")) {
            current = "/" + current;
         }

         all.add(current);
      }

      this.step.commands = all;
      if (this.onChanged != null) {
         this.onChanged.run();
      }

      this.minecraft.gui.setScreen(this.parent);
   }

   public boolean keyPressed(KeyEvent keyEvent) {
      int keyCode = keyEvent.key();
      if (keyCode == 258) {
         if (this.customSuggestionsActive) {
            this.insertCustomSuggestion();
            return true;
         } else {
            if (this.commandSuggestions.keyPressed(keyEvent)) {
               return true;
            }
            return true;
         }
      } else if (keyCode != 257 && keyCode != 335) {
         if (keyCode == 256) {
            this.saveAndClose();
            return true;
         } else {
            return super.keyPressed(keyEvent);
         }
      } else if (this.customSuggestionsActive) {
         this.insertCustomSuggestion();
         return true;
      } else if (this.commandField.isFocused()) {
         this.addCommand();
         return true;
      } else if (this.delayField.isFocused()) {
         this.applyDelayField();
         return true;
      } else if (!this.descriptionField.isFocused() && !this.botField.isFocused()) {
         this.saveAndClose();
         return true;
      } else {
         return true;
      }
   }

   private void applyDelayField() {
      try {
         this.step.commandDelay = Math.max(1, Math.min(72000, Integer.parseInt(this.delayField.getValue().trim())));
      } catch (NumberFormatException var2) {
      }

      this.delayField.setValue(String.valueOf(this.step.commandDelay));
      this.setFocused(this.delayField);
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      if (this.commandSuggestions.mouseScrolled(scrollY)) {
         return true;
      } else {
         int fieldX = (this.width - 360) / 2;
         if (mouseX >= (double)fieldX && mouseX <= (double)(fieldX + 360) && mouseY >= 166.0 && mouseY < (double)this.getListBottom()) {
            int maxScroll = Math.max(0, this.commandList.size() - this.getMaxListRows());
            if (scrollY > 0.0 && this.commandScroll > 0) {
               this.commandScroll--;
               this.rebuildListButtons();
            } else if (scrollY < 0.0 && this.commandScroll < maxScroll) {
               this.commandScroll++;
               this.rebuildListButtons();
            }

            return true;
         } else {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
         }
      }
   }

   public boolean mouseClicked(MouseButtonEvent mouseEvent, boolean focused) {
      if (this.customSuggestionsActive && mouseEvent.button() == 0) {
         int fieldX = (this.width - 360) / 2;
         int popupW = 100;
         int popupY = this.suggestionPopupY();
         if (mouseEvent.x() >= (double)fieldX
            && mouseEvent.x() <= (double)(fieldX + popupW)
            && mouseEvent.y() >= (double)popupY
            && mouseEvent.y() < (double)(popupY + CUSTOM_SUGGESTIONS.length * 16)) {
            int index = (int)((mouseEvent.y() - (double)popupY) / 16.0);
            this.customSuggestionIndex = index;
            this.insertCustomSuggestion();
            return true;
         }
      }

      if (this.commandSuggestions.mouseClicked(mouseEvent)) {
         return true;
      } else if (mouseEvent.button() == 0 && this.isOverCommandScrollbar(mouseEvent.x(), mouseEvent.y())) {
         int maxScroll = Math.max(0, this.commandList.size() - this.getMaxListRows());
         int thumbTop = this.commandScrollbar.thumbTop(this.commandScroll, maxScroll, this.getMaxListRows(), Math.max(1, this.commandList.size()));
         this.commandScrollbarGrabOffset = mouseEvent.y() - (double)thumbTop;
         this.draggingCommandScrollbar = true;
         return true;
      } else {
         return super.mouseClicked(mouseEvent, focused);
      }
   }

   public boolean mouseDragged(MouseButtonEvent mouseEvent, double dragX, double dragY) {
      if (this.draggingCommandScrollbar && this.commandScrollbar != null) {
         int maxScroll = Math.max(0, this.commandList.size() - this.getMaxListRows());
         int offset = this.commandScrollbar
            .offsetFromY(mouseEvent.y(), this.commandScrollbarGrabOffset, maxScroll, this.getMaxListRows(), Math.max(1, this.commandList.size()));
         this.commandScroll = Math.max(0, Math.min(offset, maxScroll));
         this.rebuildListButtons();
         return true;
      } else {
         return super.mouseDragged(mouseEvent, dragX, dragY);
      }
   }

   public boolean mouseReleased(MouseButtonEvent mouseEvent) {
      this.draggingCommandScrollbar = false;
      return super.mouseReleased(mouseEvent);
   }

   private boolean isOverCommandScrollbar(double mouseX, double mouseY) {
      if (this.commandScrollbar == null) {
         return false;
      }
      return this.commandScrollbar.contains(mouseX, mouseY);
   }

   @Override
   public void resize(int width, int height) {
      super.resize(width, height);
      this.rebuildListButtons();
   }

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      int fieldX = (this.width - 360) / 2;
      guiGraphics.centeredText(this.font, this.title, this.width / 2, 4, -1);
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.step_description"), fieldX, 14, -5592406);
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.step_bot"), fieldX, 52, -5592406);
      int labelQuickRight = fieldX + 360;
      int labelDelayFieldX = labelQuickRight - 45 - 4 - 110;
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.step_delay_label"), labelDelayFieldX, 52, -5592406);
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.command_input"), fieldX, 112, -5592406);
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.commands_label"), fieldX, 154, -5592406);
      int legendX = fieldX + this.font.width(Component.translatable("screen.command-gui.commands_label")) + 6;
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.cmd_legend_green"), legendX, 154, -11141291);
      guiGraphics.text(
         this.font,
         Component.translatable("screen.command-gui.machine.cmd_legend_red"),
         legendX + this.font.width(Component.translatable("screen.command-gui.machine.cmd_legend_green")) + 4,
         154,
         -43691
      );
      int maxRows = this.getMaxListRows();
      int textWidth = 155;

      for (int i = 0; i < maxRows; i++) {
         int index = this.commandScroll + i;
         if (index >= this.commandList.size()) {
            break;
         }

         String command = this.commandList.get(index);
         boolean valid = this.commandValid.get(index);
         String display = this.font.plainSubstrByWidth(command, textWidth);
         int y = 166 + i * 12;
         guiGraphics.text(this.font, Component.literal("#" + (index + 1)), fieldX + 4, y, -5592406);
         guiGraphics.text(this.font, Component.literal(display), fieldX + 4 + 18, y, valid ? -11141291 : -43691);
         int textLeft = fieldX + 4 + 18;
         if (mouseY >= y && mouseY < y + 12 && mouseX >= textLeft && mouseX < textLeft + textWidth) {
            this.renderFullCommandTooltip(guiGraphics, command, (double)mouseX, (double)mouseY);
         }
      }

      int maxScroll = Math.max(0, this.commandList.size() - maxRows);
      int scrollbarX = fieldX + 360 - 12;
      this.commandScrollbar = new ScrollbarHandle(scrollbarX, 166, 12, this.getListBottom() - 166);
      boolean hovered = this.commandScrollbar.contains((double)mouseX, (double)mouseY);
      this.commandScrollbar.render(guiGraphics, this.commandScroll, maxScroll, maxRows, Math.max(1, this.commandList.size()), hovered);
      this.commandSuggestions.extractRenderState(guiGraphics, mouseX, mouseY);
      this.renderCustomSuggestions(guiGraphics);
   }

   private void renderFullCommandTooltip(GuiGraphicsExtractor guiGraphics, String command, double mouseX, double mouseY) {
      int maxWidth = 352;
      List<String> lines = new ArrayList<>();
      String rest = command;

      while (!rest.isEmpty()) {
         String line = this.font.plainSubstrByWidth(rest, maxWidth);
         if (line.isEmpty()) {
            line = rest;
         }

         lines.add(line);
         rest = rest.substring(line.length());
      }

      if (lines.isEmpty()) {
         lines.add(command);
      }

      int boxW = maxWidth + 8;
      int boxH = lines.size() * 9 + 6;
      int boxX = (int)Math.max(4.0, Math.min(mouseX - (double)(boxW / 2), (double)(this.width - boxW - 4)));
      int boxY = (int)mouseY - boxH - 4;
      if (boxY < 4) {
         boxY = (int)mouseY + 8;
      }

      guiGraphics.fill(boxX, boxY, boxX + boxW, boxY + boxH, -301989888);
      guiGraphics.fill(boxX + 1, boxY + 1, boxX + boxW - 1, boxY + boxH - 1, -299752926);

      for (int i = 0; i < lines.size(); i++) {
         guiGraphics.text(this.font, Component.literal(lines.get(i)), boxX + 4, boxY + 3 + i * 9, -1);
      }
   }
}
