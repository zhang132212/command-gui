package com.remrin.client.gui;

import com.remrin.client.machine.MachineModels.Step;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Editor screen for a single timeline step: the fake player id, the delay in ticks and the command
 * list to execute.
 * <p>
 * The fake player id is shown as its real name; quick buttons insert commands using the CURRENT
 * bot's real name (the {@code {bot}} placeholder is resolved at insert time, and the command
 * field's cached text is cleared first). Switching the bot renames the already-typed commands.
 * The tab-completion popup follows the command field (see {@code CommandSuggestionsMixin}).
 * <p>
 * Edits the step object in place; "保存" just returns to the timeline editor (the parent rebuilds
 * its rows via the {@code onChanged} callback).
 */
public class StepEditorScreen extends BaseParentedScreen<TimelineEditorScreen> {

  private static final int FIELD_WIDTH = 360;
  private static final int FIELD_HEIGHT = 20;
  private static final int BTN_GAP = 4;
  private static final int COL_GAP = 6;
  private static final int COMMAND_WIDTH = 280;

  private static final String[] CUSTOM_SUGGESTIONS = {"{bot}", "{player}"};
  private static final int SUGGESTION_ROW_HEIGHT = 16;

  private final Step step;
  private final List<String> botNames;
  private final Runnable onChanged;
  private final List<String> commandList = new ArrayList<>();
  private final List<Button> removeButtons = new ArrayList<>();
  private EditBox botField;
  private EditBox delayField;
  private EditBox commandField;
  private CommandSuggestions commandSuggestions;
  /** Live text backups so typed input survives screen re-init (setScreen re-inits the target). */
  private String botText = "";
  private String delayText = "";
  private String commandText = "";
  /** Custom {bot}/{player} completion popup state. */
  private boolean customSuggestionsActive = false;
  private int customSuggestionIndex = 0;

  public StepEditorScreen(TimelineEditorScreen parent, Step step, List<String> botNames,
      Runnable onChanged) {
    super(Component.translatable("screen.command-gui.machine.step_title"), parent);
    this.step = step;
    this.botNames = botNames;
    this.onChanged = onChanged;
    if (step.commands != null) {
      this.commandList.addAll(step.commands);
    }
    this.botText = currentBotName();
    this.delayText = String.valueOf(step.delay);
  }

  @Override
  protected void init() {
    super.init();

    int fieldX = (this.width - FIELD_WIDTH) / 2;

    // ── Row 1: bot name + delay on one line ──
    botField = new EditBox(this.font, fieldX, 26, 110, FIELD_HEIGHT,
        Component.translatable("screen.command-gui.machine.step_bot"));
    botField.setMaxLength(20);
    botField.setValue(botText);
    botField.setResponder(text -> {
      botText = text;
      onBotTextChanged(text);
    });
    this.addRenderableWidget(botField);

    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.machine.step_pick"),
        btn -> this.minecraft.gui.setScreen(new BotSelectScreen(this, botNames))
    ).bounds(fieldX + 116, 26, 45, FIELD_HEIGHT).build());

    delayField = new EditBox(this.font, fieldX + 167, 26, 110, FIELD_HEIGHT,
        Component.translatable("screen.command-gui.machine.step_delay"));
    delayField.setMaxLength(7);
    delayField.setValue(delayText);
    delayField.setResponder(text -> delayText = text);
    this.addRenderableWidget(delayField);

    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.machine.step_pick"),
        btn -> openDelayPicker()
    ).bounds(fieldX + 283, 26, 45, FIELD_HEIGHT).build());

    // ── Row 2: quick-command buttons on their own line ──
    int btnW = (FIELD_WIDTH - 5 * BTN_GAP) / 6;
    int x = fieldX;
    x = addQuickButton(x, 50, btnW, "spawn",
        () -> this.minecraft.gui.setScreen(new SpawnOptionScreen(this)));
    x = addQuickButton(x, 50, btnW, "kill",
        () -> insertQuickCommand("/player {bot} kill"));
    x = addQuickButton(x, 50, btnW,
        Component.translatable("screen.command-gui.machine.step_attack_use"),
        () -> this.minecraft.gui.setScreen(new ActionOptionScreen(this)));
    x = addQuickButton(x, 50, btnW,
        Component.translatable("screen.command-gui.machine.step_sneak"),
        () -> insertQuickCommand("/player {bot} sneak"));
    x = addQuickButton(x, 50, btnW,
        Component.translatable("screen.command-gui.machine.step_mount"),
        () -> insertQuickCommand("/player {bot} mount"));
    addQuickButton(x, 50, btnW,
        Component.translatable("screen.command-gui.machine.step_stop"),
        () -> insertQuickCommand("/player {bot} stop"));

    // ── Row 3: command field + add-to-list (the tab-completion popup follows the field) ──
    commandField = new EditBox(this.font, fieldX, 74, COMMAND_WIDTH, FIELD_HEIGHT,
        Component.translatable("screen.command-gui.command"));
    commandField.setMaxLength(256);
    commandField.setValue(commandText);
    this.addRenderableWidget(commandField);

    this.commandSuggestions = new CommandSuggestions(this.minecraft, this, commandField,
        this.font, false, true, 0, 7, false, Integer.MIN_VALUE);
    this.commandSuggestions.setAllowSuggestions(true);
    this.commandSuggestions.updateCommandInfo();
    commandField.setResponder(text -> {
      commandText = text;
      this.commandSuggestions.updateCommandInfo();
      updateCustomSuggestions(text);
    });
    updateCustomSuggestions(commandText);

    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.add_command_line"),
        btn -> addCommand()
    ).bounds(fieldX + COMMAND_WIDTH + COL_GAP, 74, FIELD_WIDTH - COMMAND_WIDTH - COL_GAP,
        FIELD_HEIGHT).build());

    rebuildRemoveButtons();

    int barY = this.height - 22;
    int barWidth = Math.min(70, FIELD_WIDTH / 4);
    int barStartX = fieldX + (FIELD_WIDTH - barWidth * 2 - 8) / 2;
    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.save"),
        btn -> saveAndClose()
    ).bounds(barStartX, barY, barWidth, 18).build());
    // Back commits the step: edits must never be silently discarded
    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.back"),
        btn -> saveAndClose()
    ).bounds(barStartX + barWidth + 8, barY, barWidth, 18).build());
  }

  private int addQuickButton(int x, int y, int width, String label, Runnable action) {
    return addQuickButton(x, y, width, Component.literal(label), action);
  }

  private int addQuickButton(int x, int y, int width, Component label, Runnable action) {
    Button button = Button.builder(label, btn -> action.run())
        .bounds(x, y, width, FIELD_HEIGHT).build();
    this.addRenderableWidget(button);
    return x + width + BTN_GAP;
  }

  // ── Bot name handling ────────────────────────────────────────────

  /**
   * The bot name commands should target: the real name of the step's current bot.
   */
  private String currentBotName() {
    if (step.bot >= 0 && step.bot < botNames.size()) {
      return botNames.get(step.bot);
    }
    return "bot" + step.bot;
  }

  /**
   * Called when the bot name text changes: resolves the name to the bot index and replaces the
   * previous bot's name inside the typed commands with the new bot's name.
   */
  private void onBotTextChanged(String text) {
    int index = botNames.indexOf(text.trim());
    if (index < 0 || index == step.bot) {
      return;
    }
    String oldName = currentBotName();
    step.bot = index;
    String newName = currentBotName();
    if (!oldName.isEmpty() && !oldName.equals(newName)) {
      replaceBotNameInCommands(oldName, newName);
    }
  }

  /**
   * Sets the step's bot by name (used by the bot picker screen), renaming any typed commands.
   */
  public void selectBotByName(String name) {
    int index = botNames.indexOf(name);
    if (index < 0 || index == step.bot) {
      return;
    }
    String oldName = currentBotName();
    step.bot = index;
    replaceBotNameInCommands(oldName, name);
    botField.setValue(name);
  }

  private void replaceBotNameInCommands(String oldName, String newName) {
    String pattern = "\\b" + Pattern.quote(oldName) + "\\b";
    String newCommandText = commandText.replaceAll(pattern, newName);
    if (!newCommandText.equals(commandText)) {
      commandText = newCommandText;
      commandField.setValue(commandText);
    }
    for (int i = 0; i < commandList.size(); i++) {
      commandList.set(i, commandList.get(i).replaceAll(pattern, newName));
    }
    if (step.commands != null) {
      for (int i = 0; i < step.commands.size(); i++) {
        step.commands.set(i, step.commands.get(i).replaceAll(pattern, newName));
      }
    }
  }

  // ── Custom {bot} / {player} completion ──────────────────────────

  private void updateCustomSuggestions(String text) {
    String token = lastToken(text);
    boolean active = token.startsWith("{") && commandField != null && commandField.isFocused();
    if (active && commandSuggestions != null) {
      commandSuggestions.hide();
    }
    customSuggestionsActive = active;
    if (!active) {
      customSuggestionIndex = 0;
    }
  }

  private static String lastToken(String text) {
    if (text == null) {
      return "";
    }
    int space = text.lastIndexOf(' ');
    return space >= 0 ? text.substring(space + 1) : text;
  }

  private void insertCustomSuggestion() {
    String candidate = CUSTOM_SUGGESTIONS[customSuggestionIndex % CUSTOM_SUGGESTIONS.length];
    String text = commandText;
    int space = text.lastIndexOf(' ');
    String newText = space >= 0 ? text.substring(0, space + 1) + candidate : candidate;
    commandField.setValue(newText);
    customSuggestionIndex = (customSuggestionIndex + 1) % CUSTOM_SUGGESTIONS.length;
    this.setFocused(commandField);
  }

  /**
   * Y of the custom {bot}/{player} popup: just below the command field, matching the relocated
   * vanilla popup.
   */
  private int suggestionPopupY() {
    return commandField.getY() + commandField.getHeight() + 4;
  }

  private void renderCustomSuggestions(GuiGraphicsExtractor guiGraphics) {
    if (!customSuggestionsActive || commandField == null || !commandField.isFocused()) {
      return;
    }
    int fieldX = (this.width - FIELD_WIDTH) / 2;
    int popupW = 100;
    int popupY = suggestionPopupY();
    int popupH = CUSTOM_SUGGESTIONS.length * SUGGESTION_ROW_HEIGHT;
    guiGraphics.fill(fieldX - 1, popupY - 1, fieldX + popupW + 1,
        popupY + popupH + 1, 0xFF000000);
    for (int i = 0; i < CUSTOM_SUGGESTIONS.length; i++) {
      int y = popupY + i * SUGGESTION_ROW_HEIGHT;
      if (i == customSuggestionIndex % CUSTOM_SUGGESTIONS.length) {
        guiGraphics.fill(fieldX, y, fieldX + popupW, y + SUGGESTION_ROW_HEIGHT, 0xFF3355CC);
      }
      guiGraphics.text(this.font, Component.literal(CUSTOM_SUGGESTIONS[i]),
          fieldX + 4, y + 3, 0xFFFFFFFF);
    }
  }

  // ── Quick inserts ───────────────────────────────────────────────

  private void openDelayPicker() {
    NumberInputScreen picker = new NumberInputScreen(
        this,
        Component.translatable("screen.command-gui.machine.step_delay"),
        null,
        0,
        72000,
        new int[]{0, 1, 5, 10, 20, 40, 100, 200, 400, 600, 1200, 2400}
    ) {
      @Override
      protected void onNumberConfirmed(String number) {
        delayField.setValue(number);
        StepEditorScreen.this.minecraft.gui.setScreen(StepEditorScreen.this);
      }
    };
    this.minecraft.gui.setScreen(picker);
  }

  /**
   * Inserts a quick command: clears the command field's cached text FIRST (commands already added
   * to the list are kept), then appends the resolved command.
   */
  private void insertQuickCommand(String command) {
    commandField.setValue("");
    appendCommand(command);
  }

  /**
   * Appends a complete command template to the command field, resolving the {@code {bot}}
   * placeholder to the CURRENT bot's real name.
   */
  public void appendCommand(String command) {
    String resolved = command.replace("{bot}", currentBotName());
    String current = commandField.getValue();
    if (!current.isEmpty()) {
      current += " ";
    }
    commandField.setValue(current + resolved);
    this.setFocused(commandField);
  }

  private void addCommand() {
    String command = commandField.getValue().trim();
    if (!command.isEmpty()) {
      if (!command.startsWith("/")) {
        command = "/" + command;
      }
      commandList.add(command);
      commandField.setValue("");
      rebuildRemoveButtons();
    }
  }

  /**
   * Inserts a sequence of commands directly into the step's command list (in order), resolving
   * {@code {bot}} to the CURRENT bot's real name. Used by the spawn / action popups. Clears the
   * command field's cached text first (list entries are kept).
   */
  public void insertCommandSequence(List<String> commands) {
    if (commands == null) {
      return;
    }
    commandField.setValue("");
    for (String command : commands) {
      if (command == null || command.isEmpty()) {
        continue;
      }
      String resolved = command.replace("{bot}", currentBotName());
      String cmd = resolved.startsWith("/") ? resolved : "/" + resolved;
      commandList.add(cmd);
    }
    rebuildRemoveButtons();
  }

  private void rebuildRemoveButtons() {
    for (Button button : removeButtons) {
      this.removeWidget(button);
    }
    removeButtons.clear();

    int fieldX = (this.width - FIELD_WIDTH) / 2;
    int maxRows = Math.max(1, (this.height - 22 - 116) / 12);
    for (int i = 0; i < commandList.size() && i < maxRows; i++) {
      final int index = i;
      Button removeBtn = Button.builder(
          Component.translatable("screen.command-gui.remove_command"),
          btn -> {
            commandList.remove(index);
            rebuildRemoveButtons();
          }
      ).bounds(fieldX + FIELD_WIDTH - 14, 116 + i * 12, 14, 12).build();
      removeButtons.add(removeBtn);
      this.addRenderableWidget(removeBtn);
    }
  }

  private void saveAndClose() {
    // Read the live widgets directly (the backups only serve re-init restoration)
    String botName = botField.getValue().trim();
    int botIndex = botNames.indexOf(botName);
    if (botIndex >= 0) {
      step.bot = botIndex;
    }
    step.bot = Math.max(0, Math.min(step.bot, Math.max(0, botNames.size() - 1)));
    try {
      step.delay = Integer.parseInt(delayField.getValue().trim());
    } catch (NumberFormatException ignored) {
    }
    step.delay = Math.max(0, step.delay);
    // Save both the added commands and the current command field text (like the base editor)
    List<String> all = new ArrayList<>(commandList);
    String current = commandField.getValue().trim();
    if (!current.isEmpty()) {
      if (!current.startsWith("/")) {
        current = "/" + current;
      }
      all.add(current);
    }
    step.commands = all;
    if (onChanged != null) {
      onChanged.run();
    }
    this.minecraft.gui.setScreen(parent);
  }

  @Override
  public boolean keyPressed(KeyEvent keyEvent) {
    int keyCode = keyEvent.key();

    if (keyCode == GLFW.GLFW_KEY_TAB) {
      if (customSuggestionsActive) {
        insertCustomSuggestion();
        return true;
      }
      if (this.commandSuggestions.keyPressed(keyEvent)) {
        return true;
      }
      return true;
    }

    if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
      if (customSuggestionsActive) {
        insertCustomSuggestion();
        return true;
      }
      saveAndClose();
      return true;
    }
    if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
      saveAndClose();
      return true;
    }
    return super.keyPressed(keyEvent);
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    if (this.commandSuggestions.mouseScrolled(scrollY)) {
      return true;
    }
    return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent mouseEvent, boolean focused) {
    if (customSuggestionsActive && mouseEvent.button() == 0) {
      int fieldX = (this.width - FIELD_WIDTH) / 2;
      int popupW = 100;
      int popupY = suggestionPopupY();
      if (mouseEvent.x() >= fieldX && mouseEvent.x() <= fieldX + popupW
          && mouseEvent.y() >= popupY
          && mouseEvent.y() < popupY
              + CUSTOM_SUGGESTIONS.length * SUGGESTION_ROW_HEIGHT) {
        int index = (int) ((mouseEvent.y() - popupY) / SUGGESTION_ROW_HEIGHT);
        customSuggestionIndex = index;
        insertCustomSuggestion();
        return true;
      }
    }
    if (this.commandSuggestions.mouseClicked(mouseEvent)) {
      return true;
    }
    return super.mouseClicked(mouseEvent, focused);
  }

  @Override
  public void resize(int width, int height) {
    super.resize(width, height);
    rebuildRemoveButtons();
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
      float partialTick) {
    super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

    int fieldX = (this.width - FIELD_WIDTH) / 2;
    guiGraphics.centeredText(this.font, this.title, this.width / 2, 4, 0xFFFFFFFF);

    guiGraphics.text(this.font,
        Component.translatable("screen.command-gui.machine.step_bot"), fieldX, 14, 0xFFAAAAAA);
    guiGraphics.text(this.font,
        Component.translatable("screen.command-gui.machine.step_delay"), fieldX + 167, 14,
        0xFFAAAAAA);

    guiGraphics.text(this.font,
        Component.translatable("screen.command-gui.commands_label"), fieldX, 104, 0xFFAAAAAA);
    int maxRows = Math.max(1, (this.height - 22 - 116) / 12);
    for (int i = 0; i < commandList.size() && i < maxRows; i++) {
      String display = this.font.plainSubstrByWidth(commandList.get(i), FIELD_WIDTH - 20);
      guiGraphics.text(this.font, display, fieldX + 4, 116 + i * 12, 0xFF55FF55);
    }

    this.commandSuggestions.extractRenderState(guiGraphics, mouseX, mouseY);
    renderCustomSuggestions(guiGraphics);
  }
}
