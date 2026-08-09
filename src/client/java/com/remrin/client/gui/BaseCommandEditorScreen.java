package com.remrin.client.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Base class for command editor screens, providing a common UI framework for name / description /
 * placeholder insertion / command list fields.
 * <p>
 * Placeholder buttons allow inserting dynamic variables such as {@code {player}} or
 * {@code {number}} into the command text; at execution time {@link ChainedCommandExecutor} pops the
 * corresponding input screen for each placeholder.
 * <p>
 * Subclasses implement {@link #performSave()} to decide how data is written to the config, and may
 * inject extra rows below the description field via {@link #initExtraRow} /
 * {@link #renderExtraLabel}.
 */
public abstract class BaseCommandEditorScreen extends BaseParentedScreen<CommandGUIScreen> {

  /**
   * Placeholder type translation keys, shown on the right-column insert buttons; index-aligned with
   * {@link #PLACEHOLDERS}.
   */
  protected static final String[] TYPE_KEYS = {
      "screen.command-gui.type.player_all_full",
      "screen.command-gui.type.player_other_full",
      "screen.command-gui.type.player_fake_full",
      "screen.command-gui.type.text_full",
      "screen.command-gui.type.number_full",
      "screen.command-gui.type.time_full",
      "screen.command-gui.type.coord_full"
  };

  /**
   * Placeholder tokens, index-aligned with {@link #TYPE_KEYS}; clicking an insert button appends
   * the matching token to the command field. Also exposed to the tab-completion mixin so these
   * appear as suggestions while typing a {@code {…}} token.
   */
  public static final String[] PLACEHOLDERS = {
      "{player_all}",
      "{player}",
      "{player_fake}",
      "{name}",
      "{number}",
      "{time}",
      "{coords}"
  };

  protected static final int PLACEHOLDER_BTN_HEIGHT = 16;
  /** Gap between left form column and right placeholder column. */
  protected static final int COL_GAP = 10;
  protected static final int INPUT_HEIGHT = 16;
  /** Preferred maximum content width; actual width is clamped to screen width - 40. */
  protected static final int CONTENT_WIDTH = 300;
  /** Vertical gap from label top to its input field top (label sits above the field). */
  protected static final int LABEL_TO_FIELD = 10;
  protected static final int ROW_GAP = 36;
  protected static final int Y_OFFSET = -20;
  protected static final int BTN_GAP = 4;
  protected static final int ADD_BTN_WIDTH = 120;
  /** Y of the name field (top of the form). */
  protected static final int NAME_FIELD_Y = 44;
  /** Y of the description field (below the name field). */
  protected static final int DESC_FIELD_Y = NAME_FIELD_Y + ROW_GAP;
  /**
   * Y position and height of the command field, below the name / description fields. The
   * tab-completion popup is bottom-anchored for this editor, so its position is independent.
   */
  protected static final int CMD_FIELD_Y = DESC_FIELD_Y + INPUT_HEIGHT + 14;
  protected static final int CMD_FIELD_HEIGHT = 20;
  /**
   * Multi-command list (command sequence); commands are sent in order at execution time
   */
  protected final List<String> commandList = new ArrayList<>();
  private final List<Button> commandRemoveButtons = new ArrayList<>();
  protected EditBox nameField;
  protected EditBox descriptionField;
  protected EditBox commandField;
  protected CommandSuggestions commandSuggestions;
  private Button addToListButton;

  // Cached label Components — computed once in init(), reused every frame
  private Component cachedLabelName;
  private Component cachedLabelDesc;
  private Component cachedLabelPlaceholder;
  private Component cachedLabelCommands;
  private Component cachedLabelCommand;

  protected BaseCommandEditorScreen(Component title, CommandGUIScreen parent) {
    super(title, parent);
  }

  /**
   * Y coordinate of the name field relative to screen top (left column start).
   */
  protected int getFieldStartY(int centerY) {
    return NAME_FIELD_Y;
  }

  /**
   * Y coordinate of title text.
   */
  protected int getTitleY(int centerY) {
    return Math.max(6, (NAME_FIELD_Y - LABEL_TO_FIELD - this.font.lineHeight) / 2);
  }

  protected abstract String getInitialName();

  protected abstract String getInitialDescription();

  protected abstract String getInitialCommand();

  /**
   * Called to perform the actual save logic.
   */
  protected abstract void performSave();

  /**
   * Show placeholder hint on the name field. Default: false
   */
  protected boolean showNameHint() {
    return false;
  }

  /**
   * Hint to show on command field. Default: null (no hint)
   */
  protected Component getCommandHint() {
    return null;
  }

  /**
   * Initialise any extra widgets between name and description fields.
   * <p>
   * {@code currentY} is the Y at which the extra widget should be placed. Return
   * {@code currentY + ROW_GAP} if a widget was added, or {@code currentY} if not.
   */
  protected int initExtraRow(int fieldX, int currentY) {
    return currentY;
  }

  /**
   * Render any extra label between name and description labels.
   * <p>
   * {@code currentY} is the field Y for the extra row. Draw the label at
   * {@code currentY - LABEL_TO_FIELD}. Return {@code currentY + ROW_GAP} if rendered, or
   * {@code currentY} if not.
   */
  protected int renderExtraLabel(GuiGraphicsExtractor guiGraphics, int fieldX, int currentY) {
    return currentY;
  }

  /**
   * Called before super.resize(); subclass should save extra state.
   */
  protected void onBeforeResize() {
  }

  /**
   * Called after fields are restored; subclass should restore extra state.
   */
  protected void onAfterResize() {
  }

  /**
   * Get the initial list of commands for editing (multi-command entries). Override in subclass if
   * editing an existing multi-command entry.
   */
  protected List<String> getInitialCommandList() {
    return List.of();
  }

  /**
   * Actual content width for the current screen: capped at {@link #CONTENT_WIDTH} but shrinks on
   * narrow screens to maintain at least 20 px of horizontal margin on each side.
   */
  protected int effectiveContentWidth() {
    return Math.min(CONTENT_WIDTH, this.width - 40);
  }

  /** Left X of all form fields, centres the content block horizontally. */
  protected int effectiveFieldX() {
    return (this.width - effectiveContentWidth()) / 2;
  }

  /** Width of the left (form) column in the two-column layout (80% of content). */
  protected int effectiveLeftColWidth() {
    int cw = effectiveContentWidth();
    return cw - cw / 5 - COL_GAP;
  }

  /** Width of the right (placeholder) column (20% of content). */
  protected int effectiveRightColWidth() {
    return effectiveContentWidth() / 5;
  }

  /** X coordinate of the right (placeholder) column. */
  protected int effectiveRightColX() {
    return effectiveFieldX() + effectiveLeftColWidth() + COL_GAP;
  }

  /**
   * Width of the name field. Override to return a narrower value when adding an inline widget
   * (e.g. a category button) to the right of the name field on the same row.
   */
  protected int getNameFieldWidth(int contentWidth) {
    return contentWidth;
  }

  @Override
  protected void init() {
    super.init();

    // Cache label Components once per init/resize
    cachedLabelName        = Component.translatable("screen.command-gui.name");
    cachedLabelDesc        = Component.translatable("screen.command-gui.description");
    cachedLabelPlaceholder = Component.translatable("screen.command-gui.placeholder_label");
    cachedLabelCommands    = Component.translatable("screen.command-gui.commands_label");
    cachedLabelCommand     = Component.translatable("screen.command-gui.command");

    int centerX = this.width / 2;
    int fieldX = effectiveFieldX();
    int contentWidth = effectiveContentWidth();
    int leftWidth = effectiveLeftColWidth();
    int rightWidth = effectiveRightColWidth();
    int rightColX = effectiveRightColX();
    int bottomBarY = this.height - 26;

    // ── Row 1: name field (top of the form) ──
    nameField = new EditBox(this.font, fieldX, NAME_FIELD_Y, getNameFieldWidth(leftWidth),
        INPUT_HEIGHT, Component.translatable("screen.command-gui.name"));
    nameField.setMaxLength(50);
    nameField.setValue(getInitialName());
    if (showNameHint()) {
      nameField.setHint(Component.translatable("screen.command-gui.name_hint"));
    }
    this.addRenderableWidget(nameField);

    // ── Row 2: description field (with inline extra rows, e.g. the category button) ──
    int currentY = NAME_FIELD_Y + ROW_GAP;
    currentY = initExtraRow(fieldX, currentY);
    descriptionField = new EditBox(this.font, fieldX, currentY, leftWidth, INPUT_HEIGHT,
        Component.translatable("screen.command-gui.description"));
    descriptionField.setMaxLength(100);
    descriptionField.setValue(getInitialDescription());
    descriptionField.setHint(Component.translatable("screen.command-gui.description_hint"));
    this.addRenderableWidget(descriptionField);

    // ── Row 3: command field, below name / description ──
    commandField = new EditBox(this.font, fieldX, CMD_FIELD_Y, leftWidth, CMD_FIELD_HEIGHT,
        Component.translatable("screen.command-gui.command"));
    commandField.setMaxLength(256);
    commandField.setValue(getInitialCommand());
    Component cmdHint = getCommandHint();
    if (cmdHint != null) {
      commandField.setHint(cmdHint);
    }
    this.addRenderableWidget(commandField);
    this.setInitialFocus(commandField);

    this.commandSuggestions = new CommandSuggestions(this.minecraft, this, commandField,
        this.font, false, true, 0, 7, false, Integer.MIN_VALUE);
    this.commandSuggestions.setAllowSuggestions(true);
    this.commandSuggestions.updateCommandInfo();
    // Only enable tab completion when the command starts with "/": without the slash the vanilla
    // chat-suggestion path would pop player-name completions (26.2 default). Disabling the
    // suggestions entirely prevents that.
    commandField.setResponder(text -> {
      if (text.startsWith("/")) {
        this.commandSuggestions.setAllowSuggestions(true);
        this.commandSuggestions.updateCommandInfo();
      } else {
        this.commandSuggestions.hide();
        this.commandSuggestions.setAllowSuggestions(false);
      }
    });

    // Right column: placeholder insert buttons, parallel with the command field. The suggestion
    // popup (rendered on top) temporarily covers these when active. PassiveButton keeps the focus
    // on the command field after the click: vanilla would otherwise refocus the button, so the next
    // Space press would re-trigger the insert instead of typing a space.
    for (int i = 0; i < TYPE_KEYS.length; i++) {
      final int index = i;
      int btnY = CMD_FIELD_Y + i * (PLACEHOLDER_BTN_HEIGHT + 1);
      PassiveButton typeBtn = new PassiveButton(
          rightColX, btnY, rightWidth, PLACEHOLDER_BTN_HEIGHT,
          Component.translatable(TYPE_KEYS[i]),
          btn -> appendPlaceholder(index));
      this.addRenderableWidget(typeBtn);
    }

    // Initialize command list from initial data
    if (commandList.isEmpty()) {
      List<String> initial = getInitialCommandList();
      if (initial != null && !initial.isEmpty()) {
        commandList.addAll(initial);
      }
    }

    // Bottom bar: [Add to List] [Save] [Cancel]
    int addBtnW = Math.min(ADD_BTN_WIDTH, contentWidth / 2);
    int saveCancelW = Math.min(80, contentWidth / 4);
    int barTotalW = addBtnW + BTN_GAP + saveCancelW + BTN_GAP + saveCancelW;
    int barStartX = fieldX + (contentWidth - barTotalW) / 2;

    addToListButton = Button.builder(
        Component.translatable("screen.command-gui.add_command_line"),
        btn -> addCurrentCommandToList()
    ).bounds(barStartX, bottomBarY, addBtnW, 20).build();
    this.addRenderableWidget(addToListButton);

    Button saveButton = Button.builder(
        Component.translatable("screen.command-gui.save"),
        btn -> saveAndClose()
    ).bounds(barStartX + addBtnW + BTN_GAP, bottomBarY, saveCancelW, 20).build();
    this.addRenderableWidget(saveButton);

    Button cancelButton = Button.builder(
        Component.translatable("screen.command-gui.cancel"),
        btn -> this.minecraft.gui.setScreen(parent)
    ).bounds(barStartX + addBtnW + BTN_GAP + saveCancelW + BTN_GAP, bottomBarY, saveCancelW, 20).build();
    this.addRenderableWidget(cancelButton);

    rebuildCommandListButtons();
  }

  private void addCurrentCommandToList() {
    String cmd = commandField.getValue().trim();
    if (!cmd.isEmpty()) {
      if (!cmd.startsWith("/")) cmd = "/" + cmd;
      commandList.add(cmd);
      commandField.setValue("");
      rebuildCommandListButtons();
    }
  }

  private void rebuildCommandListButtons() {
    for (Button btn : commandRemoveButtons) {
      this.removeWidget(btn);
    }
    commandRemoveButtons.clear();

    int fieldX = effectiveFieldX();
    int leftWidth = effectiveLeftColWidth();
    int listY = getCommandListStartY();
    for (int i = 0; i < commandList.size(); i++) {
      final int idx = i;
      Button removeBtn = Button.builder(
          Component.translatable("screen.command-gui.remove_command"),
          btn -> {
            commandList.remove(idx);
            rebuildCommandListButtons();
          }
      ).bounds(fieldX + leftWidth - 14, listY + 12 + i * 12, 14, 12).build();
      commandRemoveButtons.add(removeBtn);
      this.addRenderableWidget(removeBtn);
    }
  }

  /**
   * Y coordinate of the command list label — just below the description field.
   */
  private int getCommandListStartY() {
    // Command field at CMD_FIELD_Y; list starts just below it
    return CMD_FIELD_Y + CMD_FIELD_HEIGHT + 6;
  }

  /**
   * Appends the placeholder to the end of the command input field and shifts focus to it. If the
   * current text does not end with a space, a space is prepended for readability.
   * <p>
   * Focus must be handed over with {@code setFocused} (the runtime keyboard-focus switch), not
   * {@code setInitialFocus} (which only configures the focus used on first screen init) — otherwise
   * the insert button keeps the focus and the next Space press re-triggers it.
   */
  protected void appendPlaceholder(int index) {
    String placeholder = PLACEHOLDERS[index];
    String current = commandField.getValue();
    if (!current.isEmpty() && !current.endsWith(" ")) {
      current += " ";
    }
    commandField.setValue(current + placeholder);
    this.setFocused(commandField);
  }

  /**
   * Get all commands: the list + any current text in the command field.
   * Ensures every command is prefixed with {@code /}.
   */
  protected List<String> getAllCommands() {
    List<String> all = new ArrayList<>(commandList);
    String current = commandField.getValue().trim();
    if (!current.isEmpty()) {
      if (!current.startsWith("/")) current = "/" + current;
      all.add(current);
    }
    return all;
  }

  protected final void saveAndClose() {
    String newName = nameField.getValue().trim();
    List<String> commands = getAllCommands();
    if (newName.isEmpty()) {
      newName = com.remrin.client.config.CommandConfig.nextDefaultCommandName();
      nameField.setValue(newName);
    }
    if (!newName.isEmpty() && !commands.isEmpty()) {
      performSave();
      parent.refresh();
      this.minecraft.gui.setScreen(parent);
    }
  }

  @Override
  public void resize(int width, int height) {
    String name = this.nameField.getValue();
    String description = this.descriptionField.getValue();
    String command = this.commandField.getValue();
    List<String> savedList = new ArrayList<>(this.commandList);
    onBeforeResize();
    super.resize(width, height);
    this.nameField.setValue(name);
    this.descriptionField.setValue(description);
    this.commandField.setValue(command);
    this.commandList.clear();
    this.commandList.addAll(savedList);
    onAfterResize();
    this.commandSuggestions.updateCommandInfo();
    rebuildCommandListButtons();
  }

  @Override
  public boolean keyPressed(KeyEvent keyEvent) {
    int keyCode = keyEvent.key();

    if (this.commandSuggestions.keyPressed(keyEvent)) {
      return true;
    }

    if (keyCode == GLFW.GLFW_KEY_TAB) {
      return true;
    }

    if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
      List<String> commands = getAllCommands();
      if (!commands.isEmpty()) {
        saveAndClose();
      }
      return true;
    }

    if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
      this.minecraft.gui.setScreen(parent);
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
    if (this.commandSuggestions.mouseClicked(mouseEvent)) {
      return true;
    }
    return super.mouseClicked(mouseEvent, focused);
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
      float partialTick) {
    super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

    int centerX = this.width / 2;
    int centerY = this.height / 2 + Y_OFFSET;
    int fieldX = effectiveFieldX();
    int leftWidth = effectiveLeftColWidth();
    int rightColX = effectiveRightColX();

    guiGraphics.centeredText(this.font, this.title, centerX, getTitleY(centerY), 0xFFFFFFFF);

    // Name field label
    guiGraphics.text(this.font, cachedLabelName,
        fieldX, NAME_FIELD_Y - LABEL_TO_FIELD, 0xFFAAAAAA);

    // Description label
    int descLabelY = NAME_FIELD_Y + ROW_GAP;
    descLabelY = renderExtraLabel(guiGraphics, fieldX, descLabelY);
    guiGraphics.text(this.font, cachedLabelDesc,
        fieldX, descLabelY - LABEL_TO_FIELD, 0xFFAAAAAA);

    // Command field label
    guiGraphics.text(this.font, cachedLabelCommand,
        fieldX, CMD_FIELD_Y - LABEL_TO_FIELD, 0xFFAAAAAA);

    // Right column: placeholder label aligned with command field label
    guiGraphics.text(this.font, cachedLabelPlaceholder,
        rightColX, CMD_FIELD_Y - LABEL_TO_FIELD, 0xFFAAAAAA);

    // Command list label and items
    if (!commandList.isEmpty()) {
      int listY = getCommandListStartY();
      guiGraphics.text(this.font, cachedLabelCommands, fieldX, listY, 0xFFAAAAAA);
      for (int i = 0; i < commandList.size(); i++) {
        String cmd = commandList.get(i);
        String display = this.font.plainSubstrByWidth(cmd, leftWidth - 20);
        guiGraphics.text(this.font, display, fieldX, listY + 12 + i * 12, 0xFF55FF55);
      }
    }

    this.commandSuggestions.extractRenderState(guiGraphics, mouseX, mouseY);
  }
}
