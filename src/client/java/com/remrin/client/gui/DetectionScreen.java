package com.remrin.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.remrin.client.machine.MachineModels.DetectionData;
import com.remrin.client.machine.MachineNetworkManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Block-state detection config screen for a machine. The player enters coordinates (or picks the
 * block under their feet) and queries the server, which reads the block from memory or directly
 * from the region files. The screen then shows the block's property values, each assignable to the
 * ON / OFF categories via a 3-state cycle button (everything else counts as ABNORMAL).
 * <p>
 * Edits a working copy of the detection config; "保存" commits it to the machine being edited.
 */
public class DetectionScreen extends BaseParentedScreen<Screen> {

  private static final String[] DIMENSIONS = {
      "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"
  };
  private static final int VALUE_ROW_HEIGHT = 16;
  private static final int VALUE_TOP = 116;
  private static final int VALUE_ROWS = 5;

  private final DetectionData working;
  private final java.util.function.Consumer<DetectionData> onApply;
  private String errorMessage = "";
  /** Live text backups so typed input survives screen re-init / resize. */
  private String xText = "";
  private String yText = "";
  private String zText = "";

  private EditBox xField;
  private EditBox yField;
  private EditBox zField;
  private Button dimensionButton;
  private Button propertyButton;
  private Checkbox enabledCheckbox;
  private final List<Button> valueButtons = new ArrayList<>();

  // Query result state
  private boolean found = false;
  private String blockId = "";
  private final Map<String, List<String>> properties = new LinkedHashMap<>();
  private final Map<String, String> currentValues = new HashMap<>();
  private String selectedProperty = "";
  private int valueScroll = 0;
  /** Token of the latest query, used to discard stale replies. */
  private long queryToken = 0;
  private static long queryCounter = 0;

  /**
   * Number of possible values of the selected property (0 when nothing is selected).
   */
  private int getValueCount() {
    if (selectedProperty.isEmpty()) {
      return 0;
    }
    return properties.getOrDefault(selectedProperty, List.of()).size();
  }

  /**
   * Picks the property to detect: prefers a boolean (true/false) property such as
   * {@code powered} / {@code lit} so powered/unpowered blocks select the meaningful state
   * automatically; falls back to the first property.
   */
  private String pickPreferredProperty() {
    String first = null;
    for (Map.Entry<String, List<String>> entry : properties.entrySet()) {
      if (first == null) {
        first = entry.getKey();
      }
      List<String> values = entry.getValue();
      if (values.size() == 2 && values.contains("true") && values.contains("false")) {
        return entry.getKey();
      }
    }
    return first != null ? first : "";
  }

  /**
   * Opens the detection editor for a working copy of a detection config (machine or mode).
   * {@code onApply} receives the committed detection config (or {@code null} when disabled).
   */
  public DetectionScreen(Screen parent, DetectionData initial,
      java.util.function.Consumer<DetectionData> onApply) {
    super(Component.translatable("screen.command-gui.machine.detection_title"), parent);
    this.working = initial != null ? copy(initial) : new DetectionData();
    if (initial == null) {
      // A freshly created detection is enabled by default
      this.working.enabled = true;
    }
    this.onApply = onApply;
    this.xText = String.valueOf(working.x);
    this.yText = String.valueOf(working.y);
    this.zText = String.valueOf(working.z);
  }

  @Override
  protected void init() {
    super.init();

    int fieldX = (this.width - 360) / 2;
    int fieldWidth = 110;

    xField = new EditBox(this.font, fieldX, 24, fieldWidth, 18,
        Component.translatable("screen.command-gui.machine.detection_x"));
    xField.setMaxLength(10);
    xField.setValue(xText);
    xField.setResponder(text -> xText = text);
    this.addRenderableWidget(xField);

    yField = new EditBox(this.font, fieldX + fieldWidth + 8, 24, fieldWidth, 18,
        Component.translatable("screen.command-gui.machine.detection_y"));
    yField.setMaxLength(10);
    yField.setValue(yText);
    yField.setResponder(text -> yText = text);
    this.addRenderableWidget(yField);

    zField = new EditBox(this.font, fieldX + (fieldWidth + 8) * 2, 24, fieldWidth, 18,
        Component.translatable("screen.command-gui.machine.detection_z"));
    zField.setMaxLength(10);
    zField.setValue(zText);
    zField.setResponder(text -> zText = text);
    this.addRenderableWidget(zField);

    dimensionButton = Button.builder(buildDimensionLabel(), btn -> cycleDimension())
        .bounds(fieldX, 50, 100, 18).build();
    this.addRenderableWidget(dimensionButton);

    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.machine.detection_pick"),
        btn -> pickBlockUnderFeet()
    ).bounds(fieldX + 104, 50, 70, 18).build());

    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.machine.detection_copy"),
        btn -> copyCurrentPosition()
    ).bounds(fieldX + 178, 50, 70, 18).build());

    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.machine.detection_find"),
        btn -> findBlock()
    ).bounds(fieldX + 252, 50, 50, 18).build());

    propertyButton = Button.builder(buildPropertyLabel(), btn -> cycleProperty())
        .bounds(fieldX, 92, 200, 18).build();
    this.addRenderableWidget(propertyButton);

    rebuildValueButtons();

    // Auto-query the saved coordinates so an existing detection config is pre-filled and can be
    // saved / left without requiring a fresh manual query
    if (working.isConfigured()) {
      findBlock();
    }

    int barY = this.height - 22;
    int barWidth = Math.min(70, 100);
    int barStartX = fieldX + (360 - barWidth * 2 - 8) / 2;
    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.save"),
        btn -> saveAndClose()
    ).bounds(barStartX, barY, barWidth, 18).build());

    // Back always leaves: the detection validation (block found / mapping) can be a dead end
    // (e.g. the block cannot be found), so it must never trap the player in this screen.
    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.back"),
        btn -> backAndClose()
    ).bounds(barStartX + barWidth + 8, barY, barWidth, 18).build());

    // Enable checkbox, bottom-left
    enabledCheckbox = Checkbox.builder(
        Component.translatable("screen.command-gui.machine.detection_enable"),
        this.font
    ).pos(fieldX, barY + 1).selected(working.enabled)
        .onValueChange((checkbox, selected) -> working.enabled = selected)
        .build();
    this.addRenderableWidget(enabledCheckbox);
  }

  // ── Actions ─────────────────────────────────────────────────────

  private void cycleDimension() {
    int index = 0;
    for (int i = 0; i < DIMENSIONS.length; i++) {
      if (DIMENSIONS[i].equals(working.dimension)) {
        index = i;
        break;
      }
    }
    working.dimension = DIMENSIONS[(index + 1) % DIMENSIONS.length];
    dimensionButton.setMessage(buildDimensionLabel());
  }

  /**
   * Fills the coordinates with the block under the player's feet and queries it immediately.
   */
  private void pickBlockUnderFeet() {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null) {
      return;
    }
    BlockPos pos = mc.player.blockPosition().below();
    fillCoordinates(pos);
  }

  /**
   * Fills the coordinates with the player's current position (the block at their feet level) and
   * queries it immediately.
   */
  private void copyCurrentPosition() {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null) {
      return;
    }
    fillCoordinates(mc.player.blockPosition());
  }

  private void fillCoordinates(BlockPos pos) {
    xField.setValue(String.valueOf(pos.getX()));
    yField.setValue(String.valueOf(pos.getY()));
    zField.setValue(String.valueOf(pos.getZ()));
    working.dimension = Minecraft.getInstance().player.level().dimension().identifier().toString();
    dimensionButton.setMessage(buildDimensionLabel());
    findBlock();
  }

  private void findBlock() {
    int x = parseInt(xField.getValue(), working.x);
    int y = parseInt(yField.getValue(), working.y);
    int z = parseInt(zField.getValue(), working.z);
    working.x = x;
    working.y = y;
    working.z = z;
    errorMessage = "";
    found = false;
    properties.clear();
    currentValues.clear();
    selectedProperty = "";
    rebuildValueButtons();
    queryToken = ++queryCounter;
    MachineNetworkManager.setBlockQueryCallback(this::onQueryResult);
    MachineNetworkManager.sendBlockQuery(working.dimension, x, y, z, queryToken);
  }

  private void onQueryResult(String json) {
    try {
      JsonObject result = JsonParser.parseString(json).getAsJsonObject();
      // Discard stale replies from an older query
      if (result.has("token") && result.get("token").getAsLong() != queryToken) {
        return;
      }
      if (!result.has("found") || !result.get("found").getAsBoolean()) {
        errorMessage = Component.translatable(
            "screen.command-gui.machine.detection_not_found").getString();
        rebuildValueButtons();
        return;
      }
      blockId = result.get("blockId").getAsString();
      working.blockId = blockId;
      properties.clear();
      JsonObject props = result.has("properties") ? result.getAsJsonObject("properties")
          : new JsonObject();
      for (String key : props.keySet()) {
        List<String> values = new ArrayList<>();
        for (JsonElement element : props.getAsJsonArray(key)) {
          values.add(element.getAsString());
        }
        properties.put(key, values);
      }
      currentValues.clear();
      JsonObject current = result.has("current") ? result.getAsJsonObject("current")
          : new JsonObject();
      for (String key : current.keySet()) {
        currentValues.put(key, current.get(key).getAsString());
      }
      // Keep the previous property when it still exists, otherwise pick the preferred one
      // (boolean properties like powered/lit win, so lever detection works out of the box)
      if (properties.containsKey(working.property)) {
        selectedProperty = working.property;
      } else {
        selectedProperty = pickPreferredProperty();
        working.property = selectedProperty;
      }
      if (properties.isEmpty()) {
        errorMessage = Component.translatable(
            "screen.command-gui.machine.detection_single_state").getString();
      } else {
        errorMessage = "";
      }
      valueScroll = 0;
      found = true;
    } catch (Exception e) {
      errorMessage = Component.translatable(
          "screen.command-gui.machine.detection_not_found").getString();
    }
    rebuildValueButtons();
    propertyButton.setMessage(buildPropertyLabel());
  }

  private void cycleProperty() {
    if (properties.size() <= 1) {
      return;
    }
    List<String> names = new ArrayList<>(properties.keySet());
    int index = names.indexOf(selectedProperty);
    selectedProperty = names.get((index + 1) % names.size());
    working.property = selectedProperty;
    valueScroll = 0;
    rebuildValueButtons();
    propertyButton.setMessage(buildPropertyLabel());
  }

  /**
   * Cycles a value's category: abnormal -> ON -> OFF -> abnormal.
   */
  private void cycleValueCategory(String value) {
    if (working.onValues.contains(value)) {
      working.onValues.remove(value);
      working.offValues.add(value);
    } else if (working.offValues.contains(value)) {
      working.offValues.remove(value);
    } else {
      working.onValues.add(value);
    }
    rebuildValueButtons();
  }

  private void rebuildValueButtons() {
    for (Button button : valueButtons) {
      this.removeWidget(button);
    }
    valueButtons.clear();

    List<String> values = selectedProperty.isEmpty() ? List.of()
        : properties.getOrDefault(selectedProperty, List.of());
    int maxScroll = Math.max(0, values.size() - VALUE_ROWS);
    valueScroll = Math.min(valueScroll, maxScroll);

    int fieldX = (this.width - 360) / 2;
    for (int i = 0; i < VALUE_ROWS; i++) {
      int index = valueScroll + i;
      if (index >= values.size()) {
        break;
      }
      String value = values.get(index);
      int y = VALUE_TOP + i * VALUE_ROW_HEIGHT;
      String currentMark = value.equals(currentValues.get(selectedProperty)) ? "● " : "  ";
      Button valueLabel = Button.builder(
          Component.literal(currentMark + value), btn -> {
          }
      ).bounds(fieldX, y, 150, VALUE_ROW_HEIGHT - 2).build();
      valueLabel.active = false;
      valueButtons.add(valueLabel);
      this.addRenderableWidget(valueLabel);

      Button category = Button.builder(buildCategoryLabel(value),
          btn -> cycleValueCategory(value)
      ).bounds(fieldX + 158, y, 90, VALUE_ROW_HEIGHT - 2).build();
      valueButtons.add(category);
      this.addRenderableWidget(category);
    }
  }

  private Component buildCategoryLabel(String value) {
    if (working.onValues.contains(value)) {
      return Component.translatable("screen.command-gui.machine.detection_on")
          .copy().withColor(0xFF55FF55);
    }
    if (working.offValues.contains(value)) {
      return Component.translatable("screen.command-gui.machine.detection_off")
          .copy().withColor(0xFFFFFFFF);
    }
    return Component.translatable("screen.command-gui.machine.detection_abnormal")
        .copy().withColor(0xFFFF5555);
  }

  private Component buildDimensionLabel() {
    return Component.literal(working.dimension);
  }

  private Component buildPropertyLabel() {
    if (selectedProperty.isEmpty()) {
      return Component.translatable("screen.command-gui.machine.detection_property");
    }
    return Component.literal(selectedProperty + " (" + getValueCount() + "种状态)");
  }

  // ── Save ─────────────────────────────────────────────────────────

  private void saveAndClose() {
    errorMessage = "";
    // Apply the fresh query results when available; otherwise keep the previously saved config
    if (found && !blockId.isEmpty() && !selectedProperty.isEmpty()) {
      working.blockId = blockId;
      working.property = selectedProperty;
    }
    if (!working.isConfigured()) {
      errorMessage = Component.translatable(
          "screen.command-gui.machine.detection_need_block").getString();
      return;
    }
    if (working.onValues.isEmpty() || working.offValues.isEmpty()) {
      errorMessage = Component.translatable(
          "screen.command-gui.machine.detection_need_mapping").getString();
      return;
    }
    working.x = parseInt(xField.getValue(), working.x);
    working.y = parseInt(yField.getValue(), working.y);
    working.z = parseInt(zField.getValue(), working.z);
    if (onApply != null) {
      onApply.accept(working);
    }
    this.minecraft.gui.setScreen(parent);
  }

  /**
   * Always leaves the screen, even when nothing is configured or the block was never found.
   * The working copy is committed whenever it holds meaningful data (a prior detection existed
   * or the current one is configured), so nothing configured is silently discarded.
   */
  private void backAndClose() {
    if (working.isConfigured()) {
      if (onApply != null) {
        onApply.accept(working);
      }
    }
    this.minecraft.gui.setScreen(parent);
  }

  private static int parseInt(String text, int fallback) {
    try {
      return Integer.parseInt(text.trim());
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static DetectionData copy(DetectionData source) {
    DetectionData copy = new DetectionData();
    copy.enabled = source.enabled;
    copy.dimension = source.dimension;
    copy.x = source.x;
    copy.y = source.y;
    copy.z = source.z;
    copy.blockId = source.blockId;
    copy.property = source.property;
    copy.onValues = new ArrayList<>(source.onValues);
    copy.offValues = new ArrayList<>(source.offValues);
    return copy;
  }

  @Override
  public boolean keyPressed(KeyEvent keyEvent) {
    if (keyEvent.key() == GLFW.GLFW_KEY_ESCAPE) {
      backAndClose();
      return true;
    }
    return super.keyPressed(keyEvent);
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    if (mouseY >= VALUE_TOP && mouseY < VALUE_TOP + VALUE_ROWS * VALUE_ROW_HEIGHT) {
      int maxScroll = Math.max(0, getValueCount() - VALUE_ROWS);
      if (scrollY > 0 && valueScroll > 0) {
        valueScroll--;
        rebuildValueButtons();
      } else if (scrollY < 0 && valueScroll < maxScroll) {
        valueScroll++;
        rebuildValueButtons();
      }
    }
    return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
      float partialTick) {
    super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

    int fieldX = (this.width - 360) / 2;
    guiGraphics.centeredText(this.font, this.title, this.width / 2, 4, 0xFFFFFFFF);

    guiGraphics.text(this.font,
        Component.translatable("screen.command-gui.machine.detection_pos"), fieldX, 76, 0xFFAAAAAA);
    guiGraphics.text(this.font,
        Component.translatable("screen.command-gui.machine.detection_block", blockId.isEmpty()
            ? "--" : blockId), fieldX + 100, 76, 0xFF55FF55);

    if (!found && errorMessage.isEmpty() && blockId.isEmpty()) {
      guiGraphics.text(this.font,
          Component.translatable("screen.command-gui.machine.detection_hint"),
          fieldX, VALUE_TOP + 2, 0xFF888888);
    }

    if (!errorMessage.isEmpty()) {
      guiGraphics.text(this.font, Component.literal(errorMessage), fieldX, 202, 0xFFFF5555);
    }
  }
}
