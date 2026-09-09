package com.remrin.client.gui;

import com.remrin.client.config.CommandConfig;
import com.remrin.client.machine.MachineNetworkManager;
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
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

public class AddCommandScreen extends BaseParentedScreen<CommandGUIScreen> implements StepCommandHost {
   private static final int FIELD_WIDTH = 360;
   private static final int FIELD_HEIGHT = 20;
   private static final int BTN_GAP = 4;
   private static final int COL_GAP = 6;
   private static final int COMMAND_WIDTH = 280;
   private static final String[] CUSTOM_SUGGESTIONS = new String[]{"{bot}", "{player}"};
   private static final int SUGGESTION_ROW_HEIGHT = 16;
   private static final int TOP_FIELD_Y = 26;
   private static final int BOT_FIELD_Y = 64;
   private static final int QUICK_BTN_Y = 92;
   private static final int CMD_LABEL_Y = 116;
   private static final int CMD_FIELD_Y = 128;
   private static final int LIST_LABEL_Y = 158;
   private static final int LIST_TOP = 170;
   private static final int LIST_ROW_HEIGHT = 12;
   private static final int MOVE_BTN_W = 48;
   private static final int COPY_BTN_W = 30;
   private static final int REMOVE_BTN_W = 30;
   private static final int ROW_ACTION_GAP = 1;
   private static final int ROW_NUMBER_W = 18;
   private static final int RIGHT_RESERVED = 16;
   private final String initialCategoryId;
   private final String editingName;
   private final CommandConfig.CommandEntry editingEntry;
   private final boolean fakeMode;
   private List<CommandConfig.Category> categories;
   private int selectedCategoryIndex = 0;
   private String movedToCategory = null;
   private EditBox nameField;
   private EditBox descriptionField;
   private EditBox botField;
   private EditBox delayField;
   private EditBox commandField;
   private Button shortcutButton;
   private Button shortcutResetButton;
   private CommandSuggestions commandSuggestions;
   private String shortcut = "";
   private boolean capturingShortcut = false;
   private final List<Integer> capturedKeys = new ArrayList<>();
   private String shortcutConflict = null;
   private String nameText = "";
   private String descriptionText = "";
   private String botText = "";
   private String delayText = "1";
   private String commandText = "";
   private final List<String> commandList = new ArrayList<>();
   private final List<Boolean> commandValid = new ArrayList<>();
   private final List<Button> removeButtons = new ArrayList<>();
   private final List<Button> moveUpButtons = new ArrayList<>();
   private final List<Button> moveDownButtons = new ArrayList<>();
   private final List<Button> copyButtons = new ArrayList<>();
   private int commandScroll = 0;
   private ScrollbarHandle commandScrollbar = null;
   private boolean draggingCommandScrollbar = false;
   private double commandScrollbarGrabOffset = 0.0;
   private boolean customSuggestionsActive = false;
   private int customSuggestionIndex = 0;
   private boolean dirty = false;

   public AddCommandScreen(CommandGUIScreen parent) {
      this(parent, null, null, null, false);
   }

   public AddCommandScreen(CommandGUIScreen parent, String initialCategoryId) {
      this(parent, initialCategoryId, null, null, false);
   }

   public AddCommandScreen(CommandGUIScreen parent, String initialCategoryId, String editingName, CommandConfig.CommandEntry editingEntry) {
      this(parent, initialCategoryId, editingName, editingEntry, editingEntry != null && CommandHelper.isFakePlayerCommand(editingEntry));
   }

   public AddCommandScreen(CommandGUIScreen parent, String initialCategoryId, boolean fakeMode) {
      this(parent, initialCategoryId, null, null, fakeMode);
   }

   private AddCommandScreen(CommandGUIScreen parent, String initialCategoryId, String editingName, CommandConfig.CommandEntry editingEntry, boolean fakeMode) {
      super(Component.translatable(editingName != null ? "screen.command-gui.edit_title" : "screen.command-gui.add_title"), parent);
      this.initialCategoryId = initialCategoryId;
      this.editingName = editingName;
      this.editingEntry = editingEntry;
      this.fakeMode = fakeMode;
      if (editingEntry != null) {
         this.nameText = editingName != null ? editingName : "";
         this.descriptionText = editingEntry.description != null ? editingEntry.description : "";
         this.delayText = String.valueOf(Math.max(1, editingEntry.commandDelay));
         this.shortcut = CommandShortcut.normalize(editingEntry.shortcut != null ? editingEntry.shortcut : "");
         this.commandList.addAll(editingEntry.getCommands());

         for (String command : this.commandList) {
            this.commandValid.add(CommandHelper.validateCommandFormat(command) == null);
         }
      }
   }

   private int fieldX() {
      return (this.width - 360) / 2;
   }

   private List<String> botNames() {
      List<String> names = new ArrayList<>();
      Minecraft mc = Minecraft.getInstance();
      if (MachineNetworkManager.isFakePlayerStatesSupported()) {
         names.addAll(MachineNetworkManager.getServerFakePlayers());
      } else if (mc.getConnection() != null) {
         for (PlayerInfo info : mc.getConnection().getListedOnlinePlayers()) {
            if (CommandHelper.isFakePlayer(info)) {
               names.add(info.getProfile().name());
            }
         }
      }

      return names;
   }

   private String currentBotName() {
      if (this.botText.trim().isEmpty()) {
         return "{bot}";
      }
      return this.botText.trim();
   }

   protected void init() {
      super.init();
      this.categories = CommandConfig.getCategories();
      if (this.initialCategoryId != null) {
         for (int i = 0; i < this.categories.size(); i++) {
            if (this.categories.get(i).id.equals(this.initialCategoryId)) {
               this.selectedCategoryIndex = i;
               break;
            }
         }
      }

      int fieldX = this.fieldX();
      this.nameField = new EditBox(this.font, fieldX, 26, 94, 20, Component.translatable("screen.command-gui.name"));
      this.nameField.setMaxLength(50);
      this.nameField.setValue(this.nameText);
      this.nameField.setHint(Component.translatable("screen.command-gui.name_hint"));
      this.nameField.setResponder(text -> {
         this.nameText = text;
         this.markDirty();
         this.refreshShortcutConflict();
      });
      this.addRenderableWidget(this.nameField);
      int descWidth = this.fakeMode ? 110 : 262;
      this.descriptionField = new EditBox(this.font, fieldX + 94 + 4, 26, descWidth, 20, Component.translatable("screen.command-gui.description"));
      this.descriptionField.setMaxLength(100);
      this.descriptionField.setValue(this.descriptionText);
      this.descriptionField.setHint(Component.translatable("screen.command-gui.description_hint"));
      this.descriptionField.setResponder(text -> {
         this.descriptionText = text;
         this.markDirty();
      });
      this.addRenderableWidget(this.descriptionField);
      if (this.fakeMode) {
         this.shortcutButton = Button.builder(Component.literal(this.shortcutMessage()), btn -> this.toggleShortcutCapture())
            .bounds(fieldX + 94 + 4 + 110 + 4, 26, 100, 20)
            .build();
         this.shortcutButton.setTooltip(Tooltip.create(Component.literal(this.shortcutTooltip())));
         this.addRenderableWidget(this.shortcutButton);
         this.shortcutResetButton = Button.builder(Component.translatable("screen.command-gui.shortcut.reset"), btn -> this.resetShortcut())
            .bounds(fieldX + 94 + 4 + 110 + 4 + 100 + 4, 26, 42, 20)
            .build();
         this.shortcutResetButton.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.shortcut.reset_hint")));
         this.addRenderableWidget(this.shortcutResetButton);
      }

      this.refreshShortcutConflict();
      if (this.fakeMode) {
         this.botField = new EditBox(this.font, fieldX, 64, 110, 20, Component.translatable("screen.command-gui.machine.step_bot"));
         this.botField.setMaxLength(20);
         this.botField.setValue(this.botText);
         this.botField.setResponder(text -> {
            this.botText = text;
            this.markDirty();
         });
         this.addRenderableWidget(this.botField);
         this.addRenderableWidget(
            Button.builder(
                  Component.translatable("screen.command-gui.machine.step_pick"),
                  btn -> this.minecraft.gui.setScreen(new BotSelectScreen(this, this.botNames()))
               )
               .bounds(fieldX + 116, 64, 45, 20)
               .build()
         );
      }

      int quickRight = fieldX + 360;
      int delayFieldW = 110;
      int delayPickX = this.fakeMode ? quickRight - 45 : fieldX + 110 + 4;
      int delayFieldX = this.fakeMode ? delayPickX - 4 - delayFieldW : fieldX;
      this.delayField = new DigitsOnlyEditBox(this.font, delayFieldX, 64, delayFieldW, 20, Component.translatable("screen.command-gui.command_delay_short"));
      this.delayField.setMaxLength(7);
      this.delayField.setValue(this.delayText);
      this.delayField.setResponder(text -> {
         this.delayText = text;
         this.markDirty();
      });
      this.delayField.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.command_delay_hint")));
      this.addRenderableWidget(this.delayField);
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.machine.step_pick"), btn -> this.openDelayPicker()).bounds(delayPickX, 64, 45, 20).build()
      );
      if (!this.fakeMode) {
         int shortcutX = fieldX + 360 - 42 - 4 - 100;
         this.shortcutButton = Button.builder(Component.literal(this.shortcutMessage()), btn -> this.toggleShortcutCapture())
            .bounds(shortcutX, 64, 100, 20)
            .build();
         this.shortcutButton.setTooltip(Tooltip.create(Component.literal(this.shortcutTooltip())));
         this.addRenderableWidget(this.shortcutButton);
         this.shortcutResetButton = Button.builder(Component.translatable("screen.command-gui.shortcut.reset"), btn -> this.resetShortcut())
            .bounds(shortcutX + 100 + 4, 64, 42, 20)
            .build();
         this.shortcutResetButton.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.shortcut.reset_hint")));
         this.addRenderableWidget(this.shortcutResetButton);
      }

      if (this.fakeMode) {
         int baseBtnW = 56;
         int leftover = 340 - baseBtnW * 6;
         int x = this.addQuickButton(fieldX, 92, baseBtnW + (leftover-- > 0 ? 1 : 0), "spawn", () -> this.minecraft.gui.setScreen(new SpawnOptionScreen(this)));
         x = this.addQuickButton(x, 92, baseBtnW + (leftover-- > 0 ? 1 : 0), "kill", () -> this.insertQuickCommand("/player {bot} kill"));
         x = this.addQuickButton(
            x,
            92,
            baseBtnW + (leftover-- > 0 ? 1 : 0),
            Component.translatable("screen.command-gui.machine.step_attack_use"),
            () -> this.minecraft.gui.setScreen(new ActionOptionScreen(this))
         );
         x = this.addQuickButton(
            x,
            92,
            baseBtnW + (leftover-- > 0 ? 1 : 0),
            Component.translatable("screen.command-gui.machine.step_sneak"),
            () -> this.insertQuickCommand("/player {bot} sneak")
         );
         x = this.addQuickButton(
            x,
            92,
            baseBtnW + (leftover-- > 0 ? 1 : 0),
            Component.translatable("screen.command-gui.machine.step_mount"),
            () -> this.insertQuickCommand("/player {bot} mount")
         );
         this.addQuickButton(
            x,
            92,
            baseBtnW + (leftover-- > 0 ? 1 : 0),
            Component.translatable("screen.command-gui.machine.step_stop"),
            () -> this.insertQuickCommand("/player {bot} stop")
         );
      } else {
         this.buildPlaceholderRow(fieldX);
      }

      this.commandField = new EditBox(this.font, fieldX, 128, 280, 20, Component.translatable("screen.command-gui.command"));
      this.commandField.setMaxLength(256);
      this.commandField.setValue(this.commandText);
      this.addRenderableWidget(this.commandField);
      this.commandSuggestions = new CommandSuggestions(this.minecraft, this, this.commandField, this.font, false, true, 0, 7, false, Integer.MIN_VALUE);
      this.commandSuggestions.setAllowSuggestions(true);
      this.commandSuggestions.updateCommandInfo();
      this.commandField.setResponder(text -> {
         this.commandText = text;
         this.markDirty();
         this.commandSuggestions.updateCommandInfo();
         this.updateCustomSuggestions(text);
      });
      this.updateCustomSuggestions(this.commandText);
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.add_command_line"), btn -> this.addCommand()).bounds(fieldX + 280 + 6, 128, 74, 20).build()
      );
      this.rebuildListButtons();
      this.buildBottomBar(fieldX);
   }

   private int addQuickButton(int x, int y, int width, String label, Runnable action) {
      return this.addQuickButton(x, y, width, Component.literal(label), action);
   }

   private int addQuickButton(int x, int y, int width, Component label, Runnable action) {
      Button button = Button.builder(label, btn -> action.run()).bounds(x, y, width, 20).build();
      this.addRenderableWidget(button);
      return x + width + 4;
   }

   private void buildPlaceholderRow(int x) {
      int btnW = 48;

      for (int i = 0; i < BaseCommandEditorScreen.PLACEHOLDERS.length; i++) {
         int index = i;
         Button btn = Button.builder(Component.translatable(BaseCommandEditorScreen.TYPE_KEYS[i]), b -> this.appendPlaceholder(index))
            .bounds(x + i * (btnW + 4), 92, btnW, 20)
            .build();
         btn.setTooltip(Tooltip.create(Component.literal(BaseCommandEditorScreen.PLACEHOLDERS[i])));
         this.addRenderableWidget(btn);
      }
   }

   private void appendPlaceholder(int index) {
      String placeholder = BaseCommandEditorScreen.PLACEHOLDERS[index];
      String current = this.commandField.getValue();
      if (!current.isEmpty() && !current.endsWith(" ")) {
         current = current + " ";
      }

      this.commandField.setValue(current + placeholder);
      this.setFocused(this.commandField);
   }

   private void buildBottomBar(int fieldX) {
      int barY = this.height - 24;
      if (this.editingName != null) {
         int btnW = 76;
         int gap = 4;
         int startX = fieldX + (360 - btnW * 4 - gap * 3) / 2;
         this.addRenderableWidget(
            Button.builder(Component.translatable("screen.command-gui.save"), btn -> this.saveAndClose()).bounds(startX, barY, btnW, 20).build()
         );
         this.addRenderableWidget(
            Button.builder(
                  Component.translatable("screen.command-gui.action.move"),
                  btn -> this.minecraft.gui.setScreen(new MoveCategoryScreen(this, this.editingName, category -> this.movedToCategory = category))
               )
               .bounds(startX + btnW + gap, barY, btnW, 20)
               .build()
         );
         this.addRenderableWidget(
            Button.builder(Component.translatable("screen.command-gui.action.delete"), btn -> this.deleteAndClose())
               .bounds(startX + (btnW + gap) * 2, barY, btnW, 20)
               .build()
         );
         this.addRenderableWidget(
            Button.builder(Component.translatable("screen.command-gui.cancel"), btn -> this.requestExit())
               .bounds(startX + (btnW + gap) * 3, barY, btnW, 20)
               .build()
         );
      } else {
         boolean multi = this.categories.size() > 1;
         int total = multi ? 3 : 2;
         int btnW = multi ? 104 : 140;
         int gap = 8;
         int startX = fieldX + (360 - btnW * total - gap * (total - 1)) / 2;
         this.addRenderableWidget(
            Button.builder(Component.translatable("screen.command-gui.save"), btn -> this.saveAndClose()).bounds(startX, barY, btnW, 20).build()
         );
         int nextX = startX + btnW + gap;
         if (multi) {
            this.addRenderableWidget(
               Button.builder(Component.translatable("screen.command-gui.save_to_category_short"), btn -> this.openCategoryPicker())
                  .bounds(nextX, barY, btnW, 20)
                  .build()
            );
            nextX += btnW + gap;
         }

         this.addRenderableWidget(
            Button.builder(Component.translatable("screen.command-gui.cancel"), btn -> this.requestExit()).bounds(nextX, barY, btnW, 20).build()
         );
      }
   }

   private void openDelayPicker() {
      NumberInputScreen picker = new NumberInputScreen(
         this, Component.translatable("screen.command-gui.command_delay_short"), null, 1, 72000, new int[]{1, 5, 10, 20, 40, 100, 200, 400, 600, 1200, 2400}
      ) {

         @Override
         protected void onNumberConfirmed(String number) {
            AddCommandScreen.this.delayField.setValue(number);
            AddCommandScreen.this.minecraft.gui.setScreen(AddCommandScreen.this);
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

         this.markDirty();
         this.commandScroll = Math.max(0, this.commandList.size() - this.getMaxListRows());
         this.rebuildListButtons();
      }
   }

   @Override
   public void selectBotByName(String name) {
      String oldName = this.currentBotName();
      this.botText = name;
      this.botField.setValue(name);
      if (!oldName.isEmpty() && !oldName.equals("{bot}") && !oldName.equals(name)) {
         this.replaceBotNameInCommands(oldName, name);
      }

      this.markDirty();
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
         this.markDirty();
         this.commandScroll = Math.max(0, this.commandList.size() - this.getMaxListRows());
         this.rebuildListButtons();
      }
   }

   private int getMaxListRows() {
      return Math.max(1, (this.getListBottom() - 170) / 12);
   }

   private int getListBottom() {
      return this.height - 24 - 4;
   }

   private void rebuildListButtons() {
      for (Button btn : this.removeButtons) {
         this.removeWidget(btn);
      }

      for (Button btn : this.moveUpButtons) {
         this.removeWidget(btn);
      }

      for (Button btn : this.moveDownButtons) {
         this.removeWidget(btn);
      }

      for (Button btn : this.copyButtons) {
         this.removeWidget(btn);
      }

      this.removeButtons.clear();
      this.moveUpButtons.clear();
      this.moveDownButtons.clear();
      this.copyButtons.clear();
      int fieldX = this.fieldX();
      int maxRows = this.getMaxListRows();
      this.commandScroll = Math.max(0, Math.min(this.commandScroll, this.commandList.size() - maxRows));
      int removeX = fieldX + 360 - 16 - 30;
      int copyX = removeX - 30 - 1;
      int downX = copyX - 48 - 1;
      int upX = downX - 48 - 1;

      for (int i = 0; i < maxRows; i++) {
         int index = this.commandScroll + i;
         if (index >= this.commandList.size()) {
            break;
         }

         int y = 170 + i * 12;
         Button upBtn = Button.builder(Component.translatable("screen.command-gui.step_up_short"), btn -> this.moveCommandUp(index))
            .bounds(upX, y, 48, 12)
            .build();
         upBtn.active = index > 0;
         this.moveUpButtons.add(upBtn);
         this.addRenderableWidget(upBtn);
         Button downBtn = Button.builder(Component.translatable("screen.command-gui.step_down_short"), btn -> this.moveCommandDown(index))
            .bounds(downX, y, 48, 12)
            .build();
         downBtn.active = index < this.commandList.size() - 1;
         this.moveDownButtons.add(downBtn);
         this.addRenderableWidget(downBtn);
         Button copyBtn = Button.builder(Component.translatable("screen.command-gui.step_copy_short"), btn -> this.copyCommandToClipboard(index))
            .bounds(copyX, y, 30, 12)
            .build();
         this.copyButtons.add(copyBtn);
         this.addRenderableWidget(copyBtn);
         Button removeBtn = Button.builder(Component.translatable("screen.command-gui.delete"), btn -> {
            this.commandList.remove(index);
            this.commandValid.remove(index);
            this.markDirty();
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
         this.markDirty();
         this.rebuildListButtons();
      }
   }

   private void moveCommandDown(int index) {
      if (index >= 0 && index < this.commandList.size() - 1) {
         Collections.swap(this.commandList, index, index + 1);
         Collections.swap(this.commandValid, index, index + 1);
         this.markDirty();
         this.rebuildListButtons();
      }
   }

   private void copyCommandToClipboard(int index) {
      if (index >= 0 && index < this.commandList.size()) {
         this.minecraft.keyboardHandler.setClipboard(this.commandList.get(index));
         this.minecraft.gui.hud.getChat().addClientSystemMessage(Component.translatable("screen.command-gui.copied"));
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
         int fieldX = this.fieldX();
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

   private int parseDelay() {
      try {
         return Math.max(1, Math.min(72000, this.delayText.trim().isEmpty() ? 1 : Integer.parseInt(this.delayText.trim())));
      } catch (NumberFormatException var2) {
         return 1;
      }
   }

   private List<String> getAllCommands() {
      List<String> all = new ArrayList<>(this.commandList);
      String current = this.commandText.trim();
      if (!current.isEmpty()) {
         if (!current.startsWith("/")) {
            current = "/" + current;
         }

         all.add(current);
      }

      return all;
   }

   private void saveAndClose() {
      String name = this.nameText.trim();
      if (name.isEmpty()) {
         name = CommandConfig.nextDefaultCommandName();
         this.nameText = name;
         this.nameField.setValue(name);
      }

      List<String> commands = this.getAllCommands();
      if (!name.isEmpty() && !commands.isEmpty()) {
         String categoryId = this.categories.isEmpty() ? "default" : this.categories.get(this.selectedCategoryIndex).id;
         if (this.editingName != null) {
            boolean renamed = !name.equals(this.editingName);
            String target = this.movedToCategory != null ? this.movedToCategory : (renamed ? CommandConfig.findCommandCategory(this.editingName) : null);
            if (!renamed && this.movedToCategory == null) {
               CommandConfig.updateCommandMulti(name, commands, this.descriptionText, this.parseDelay(), this.shortcut);
            } else {
               CommandConfig.removeCommand(this.editingName);
               CommandConfig.addCommandMulti(target != null ? target : categoryId, name, commands, this.descriptionText, this.parseDelay(), this.shortcut);
            }
         } else {
            CommandConfig.addCommandMulti(categoryId, name, commands, this.descriptionText, this.parseDelay(), this.shortcut);
         }

         this.dirty = false;
         this.parent.refresh();
         this.minecraft.gui.setScreen(this.parent);
      }
   }

   private void deleteAndClose() {
      this.minecraft
         .gui
         .setScreen(
            new ConfirmScreen(
               this,
               Component.translatable("screen.command-gui.delete_command_confirm_title"),
               Component.translatable("screen.command-gui.action.delete"),
               Component.translatable("screen.command-gui.cancel"),
               () -> {
                  CommandConfig.removeCommand(this.editingName);
                  if (this.parent != null) {
                     this.parent.refresh();
                  }

                  this.minecraft.gui.setScreen(this.parent);
               },
               Component.translatable("screen.command-gui.delete_command_confirm_message", new Object[]{this.editingName})
            )
         );
   }

   private void markDirty() {
      this.dirty = true;
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
                  this::saveAndClose,
                  () -> this.minecraft.gui.setScreen(this.parent)
               )
            );
      }
   }

   private void openCategoryPicker() {
      String currentId = this.categories.isEmpty() ? "default" : this.categories.get(this.selectedCategoryIndex).id;
      this.minecraft.gui.setScreen(new SelectCategoryScreen(this, currentId, categoryId -> {
         for (int i = 0; i < this.categories.size(); i++) {
            if (this.categories.get(i).id.equals(categoryId)) {
               this.selectedCategoryIndex = i;
               break;
            }
         }
      }));
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

   private String shortcutTooltip() {
      if (this.shortcut.isEmpty()) {
         return Component.translatable("screen.command-gui.shortcut.empty_hint").getString();
      } else {
         return this.shortcutConflict != null
            ? Component.translatable("screen.command-gui.shortcut.conflict_prefix").getString() + this.shortcutConflict
            : Component.translatable("screen.command-gui.shortcut.no_conflict").getString();
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
      this.shortcutButton.setMessage(Component.literal(this.shortcutMessage()));
      this.shortcutButton.setTooltip(Tooltip.create(Component.literal(this.shortcutTooltip())));
      this.markDirty();
   }

   private void resetShortcut() {
      this.shortcut = "";
      this.capturedKeys.clear();
      this.capturingShortcut = false;
      this.refreshShortcutConflict();
      this.shortcutButton.setMessage(Component.literal(this.shortcutMessage()));
      this.shortcutButton.setTooltip(Tooltip.create(Component.literal(this.shortcutTooltip())));
      this.markDirty();
   }

   private void refreshShortcutConflict() {
      this.shortcutConflict = this.shortcut.isEmpty() ? null : CommandShortcut.findConflict(this.shortcut, this.nameText);
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

   public boolean keyPressed(KeyEvent keyEvent) {
      int keyCode = keyEvent.key();
      if (this.capturingShortcut) {
         return this.captureShortcutKey(keyEvent);
      } else if (keyCode != 257 && keyCode != 335) {
         if (this.commandSuggestions.keyPressed(keyEvent)) {
            return true;
         } else if (keyCode == 258) {
            if (this.customSuggestionsActive) {
               this.insertCustomSuggestion();
            }

            return true;
         } else if (keyCode == 256) {
            this.requestExit();
            return true;
         } else {
            return super.keyPressed(keyEvent);
         }
      } else {
         if (this.commandField.isFocused()) {
            this.addCommand();
         } else if (!this.nameField.isFocused() && !this.descriptionField.isFocused() && !this.botField.isFocused() && !this.delayField.isFocused()) {
            this.saveAndClose();
         }

         return true;
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
         this.commandScroll = this.commandScrollbar
            .offsetFromY(mouseEvent.y(), this.commandScrollbarGrabOffset, maxScroll, this.getMaxListRows(), Math.max(1, this.commandList.size()));
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

   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      if (this.commandSuggestions.mouseScrolled(scrollY)) {
         return true;
      } else {
         int maxScroll = Math.max(0, this.commandList.size() - this.getMaxListRows());
         int newOffset = Math.max(0, Math.min(maxScroll, this.commandScroll - (int)scrollY));
         if (newOffset != this.commandScroll) {
            this.commandScroll = newOffset;
            this.rebuildListButtons();
         }

         return true;
      }
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
      int fieldX = this.fieldX();
      Component title = this.title.copy();
      if (this.dirty) {
         title = title.copy().append(" ").append(Component.translatable("screen.command-gui.unsaved_badge").withColor(-22016));
      }

      guiGraphics.centeredText(this.font, title, this.width / 2, 4, -1);
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.name"), fieldX, 14, -5592406);
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.description"), fieldX + 98, 14, -5592406);
      if (this.fakeMode) {
         guiGraphics.text(this.font, Component.translatable("screen.command-gui.shortcut"), fieldX + 212, 14, -5592406);
      } else {
         int shortcutX = fieldX + 360 - 42 - 4 - 100;
         guiGraphics.text(this.font, Component.translatable("screen.command-gui.shortcut"), shortcutX, 52, -5592406);
      }

      if (this.fakeMode) {
         guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.step_bot"), fieldX, 52, -5592406);
      }

      int labelDelayFieldX = this.fakeMode ? fieldX + 360 - 45 - 4 - 110 : fieldX;
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.step_delay_label"), labelDelayFieldX, 52, -5592406);
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.command_input"), fieldX, 116, -5592406);
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.commands_label"), fieldX, 158, -5592406);
      int legendX = fieldX + this.font.width(Component.translatable("screen.command-gui.commands_label")) + 6;
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.cmd_legend_green"), legendX, 158, -11141291);
      guiGraphics.text(
         this.font,
         Component.translatable("screen.command-gui.machine.cmd_legend_red"),
         legendX + this.font.width(Component.translatable("screen.command-gui.machine.cmd_legend_green")) + 4,
         158,
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
         int y = 170 + i * 12;
         guiGraphics.text(this.font, Component.literal("#" + (index + 1)), fieldX + 4, y, -5592406);
         guiGraphics.text(this.font, Component.literal(display), fieldX + 4 + 18, y, valid ? -11141291 : -43691);
         int textLeft = fieldX + 4 + 18;
         if (mouseY >= y && mouseY < y + 12 && mouseX >= textLeft && mouseX < textLeft + textWidth) {
            this.renderFullCommandTooltip(guiGraphics, command, (double)mouseX, (double)mouseY);
         }
      }

      int maxScroll = Math.max(0, this.commandList.size() - maxRows);
      int scrollbarX = fieldX + 360 - 12;
      this.commandScrollbar = new ScrollbarHandle(scrollbarX, 170, 12, this.getListBottom() - 170);
      this.commandScrollbar
         .render(
            guiGraphics,
            this.commandScroll,
            maxScroll,
            maxRows,
            Math.max(1, this.commandList.size()),
            this.commandScrollbar.contains((double)mouseX, (double)mouseY)
         );
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
