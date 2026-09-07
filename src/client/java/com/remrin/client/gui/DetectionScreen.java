package com.remrin.client.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.remrin.client.machine.MachineModels;
import com.remrin.client.machine.MachineNetworkManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.joml.Matrix3x2fStack;

public class DetectionScreen extends BaseParentedScreen<Screen> {
   private static final String[] DIMENSIONS = new String[]{"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"};
   private static final String[] DIMENSION_KEYS = new String[]{
      "screen.command-gui.machine.detection_dim_overworld", "screen.command-gui.machine.detection_dim_nether", "screen.command-gui.machine.detection_dim_end"
   };
   private static final int VALUE_ROW_HEIGHT = 16;
   private static final int VALUE_TOP = 150;
   private static final int VALUE_ROWS = 5;
   private final MachineModels.DetectionData working;
   private final MachineModels.DetectionData initialSnapshot;
   private final Consumer<MachineModels.DetectionData> onApply;
   private String errorMessage = "";
   private String xText = "";
   private String yText = "";
   private String zText = "";
   private EditBox xField;
   private EditBox yField;
   private EditBox zField;
   private final List<DarkSelectButton> dimensionButtons = new ArrayList<>();
   private final List<Button> valueButtons = new ArrayList<>();
   private boolean searched = false;
   private ItemStack foundIcon = ItemStack.EMPTY;
   private final List<DetectionScreen.PropertyValue> allValueRows = new ArrayList<>();
   private boolean found = false;
   private String blockId = "";
   private final Map<String, List<String>> properties = new LinkedHashMap<>();
   private final Map<String, String> currentValues = new HashMap<>();
   private String selectedProperty = "";
   private int valueScroll = 0;
   private ScrollbarHandle valueScrollbar = null;
   private boolean draggingValueScrollbar = false;
   private double valueScrollbarGrabOffset = 0.0;
   private long queryToken = 0L;
   private static long queryCounter = 0L;

   private int getValueCount() {
      if (this.selectedProperty.isEmpty()) {
         return 0;
      }
      return this.properties.getOrDefault(this.selectedProperty, List.of()).size();
   }

   private String pickPreferredProperty() {
      String first = null;

      for (Entry<String, List<String>> entry : this.properties.entrySet()) {
         if (first == null) {
            first = entry.getKey();
         }

         List<String> values = entry.getValue();
         if (values.size() == 2 && values.contains("true") && values.contains("false")) {
            return entry.getKey();
         }
      }

      if (first != null) {
         return first;
      }
      return "";
   }

   public DetectionScreen(Screen parent, MachineModels.DetectionData initial, Consumer<MachineModels.DetectionData> onApply) {
      super(Component.translatable("screen.command-gui.machine.detection_title"), parent);
      this.working = initial != null ? copy(initial) : new MachineModels.DetectionData();
      this.working.enabled = true;
      this.initialSnapshot = copy(this.working);
      this.onApply = onApply;
      this.xText = String.valueOf(this.working.x);
      this.yText = String.valueOf(this.working.y);
      this.zText = String.valueOf(this.working.z);
   }

   protected void init() {
      super.init();
      int fieldX = (this.width - 360) / 2;
      int dimBtnW = 70;
      int dimY = 20;

      for (int i = 0; i < DIMENSIONS.length; i++) {
         int idx = i;
         DarkSelectButton btn = new DarkSelectButton(
            fieldX + i * (dimBtnW + 4), dimY, dimBtnW, 18, Component.translatable(DIMENSION_KEYS[i]), b -> this.working.dimension = DIMENSIONS[idx]
         );
         btn.setDarkSelected(() -> this.working.dimension.equals(DIMENSIONS[idx]), -1);
         this.dimensionButtons.add(btn);
         this.addRenderableWidget(btn);
      }

      int coordY = 48;
      int fieldWidth = 82;
      int labelW = 10;
      int gap = 2;
      int xFieldX = fieldX + labelW + gap;
      int yLabelX = xFieldX + fieldWidth + gap;
      int yFieldX = yLabelX + labelW + gap;
      int zLabelX = yFieldX + fieldWidth + gap;
      int zFieldX = zLabelX + labelW + gap;
      int findX = zFieldX + fieldWidth + gap;
      this.xField = new EditBox(this.font, xFieldX, coordY, fieldWidth, 18, Component.translatable("screen.command-gui.machine.detection_x"));
      this.xField.setMaxLength(10);
      this.xField.setValue(this.xText);
      this.xField.setResponder(text -> this.xText = text);
      this.addRenderableWidget(this.xField);
      this.yField = new EditBox(this.font, yFieldX, coordY, fieldWidth, 18, Component.translatable("screen.command-gui.machine.detection_y"));
      this.yField.setMaxLength(10);
      this.yField.setValue(this.yText);
      this.yField.setResponder(text -> this.yText = text);
      this.addRenderableWidget(this.yField);
      this.zField = new EditBox(this.font, zFieldX, coordY, fieldWidth, 18, Component.translatable("screen.command-gui.machine.detection_z"));
      this.zField.setMaxLength(10);
      this.zField.setValue(this.zText);
      this.zField.setResponder(text -> this.zText = text);
      this.addRenderableWidget(this.zField);
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.machine.detection_find"), btn -> this.findBlock())
            .bounds(findX, coordY, 360 - (findX - fieldX), 18)
            .build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.machine.detection_pick"), btn -> this.pickBlockUnderFeet())
            .bounds(fieldX, 78, 90, 18)
            .build()
      );
      this.rebuildValueButtons();
      if (this.working.isConfigured()) {
         this.findBlock();
      }

      int barY = this.height - 22;
      int barWidth = Math.min(70, 100);
      int barStartX = fieldX + (360 - barWidth * 2 - 8) / 2;
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.save"), btn -> this.saveAndClose()).bounds(barStartX, barY, barWidth, 18).build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.back"), btn -> this.backAndClose())
            .bounds(barStartX + barWidth + 8, barY, barWidth, 18)
            .build()
      );
   }

   private void pickBlockUnderFeet() {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player != null) {
         BlockPos pos = mc.player.blockPosition().below();
         this.fillCoordinates(pos);
      }
   }

   private void fillCoordinates(BlockPos pos) {
      this.xField.setValue(String.valueOf(pos.getX()));
      this.yField.setValue(String.valueOf(pos.getY()));
      this.zField.setValue(String.valueOf(pos.getZ()));
      this.working.dimension = Minecraft.getInstance().player.level().dimension().identifier().toString();
      this.findBlock();
   }

   private void findBlock() {
      this.searched = true;
      this.foundIcon = ItemStack.EMPTY;
      int x = parseInt(this.xField.getValue(), this.working.x);
      int y = parseInt(this.yField.getValue(), this.working.y);
      int z = parseInt(this.zField.getValue(), this.working.z);
      this.working.x = x;
      this.working.y = y;
      this.working.z = z;
      this.errorMessage = "";
      this.found = false;
      this.properties.clear();
      this.allValueRows.clear();
      this.currentValues.clear();
      this.selectedProperty = "";
      this.rebuildValueButtons();
      this.queryToken = ++queryCounter;
      MachineNetworkManager.setBlockQueryCallback(this::onQueryResult);
      MachineNetworkManager.sendBlockQuery(this.working.dimension, x, y, z, this.queryToken);
   }

   private void onQueryResult(String json) {
      try {
         JsonObject result = JsonParser.parseString(json).getAsJsonObject();
         if (result.has("token") && result.get("token").getAsLong() != this.queryToken) {
            return;
         }

         if (!result.has("found") || !result.get("found").getAsBoolean()) {
            this.errorMessage = Component.translatable("screen.command-gui.machine.detection_not_found").getString();
            this.rebuildValueButtons();
            return;
         }

         this.blockId = result.get("blockId").getAsString();
         this.working.blockId = this.blockId;

         try {
            Identifier id = Identifier.parse(this.blockId);
            Block block = (Block)BuiltInRegistries.BLOCK.getValue(id);
            this.foundIcon = block != null && block != Blocks.AIR ? new ItemStack(block.asItem()) : ItemStack.EMPTY;
         } catch (Exception var9) {
            this.foundIcon = ItemStack.EMPTY;
         }

         this.properties.clear();
         JsonObject props = result.has("properties") ? result.getAsJsonObject("properties") : new JsonObject();

         for (String key : props.keySet()) {
            List<String> values = new ArrayList<>();

            for (JsonElement element : props.getAsJsonArray(key)) {
               values.add(element.getAsString());
            }

            this.properties.put(key, values);
         }

         this.allValueRows.clear();

         for (Entry<String, List<String>> entry : this.properties.entrySet()) {
            for (String value : entry.getValue()) {
               this.allValueRows.add(new DetectionScreen.PropertyValue(entry.getKey(), value));
            }
         }

         this.currentValues.clear();
         JsonObject current = result.has("current") ? result.getAsJsonObject("current") : new JsonObject();

         for (String key : current.keySet()) {
            this.currentValues.put(key, current.get(key).getAsString());
         }

         if (this.properties.containsKey(this.working.property)) {
            this.selectedProperty = this.working.property;
         } else {
            this.selectedProperty = this.pickPreferredProperty();
            this.working.property = this.selectedProperty;
         }

         if (this.properties.isEmpty()) {
            this.errorMessage = Component.translatable("screen.command-gui.machine.detection_single_state").getString();
         } else {
            this.errorMessage = "";
         }

         this.valueScroll = 0;
         this.found = true;
         Set<String> existingOn = new HashSet<>(this.working.onValues);
         Set<String> existingOff = new HashSet<>(this.working.offValues);
         Set<String> existingIgnore = new HashSet<>(this.working.ignoreValues);
         this.working.onValues.clear();
         this.working.offValues.clear();
         this.working.ignoreValues.clear();

         for (DetectionScreen.PropertyValue row : this.allValueRows) {
            String key = row.property() + "=" + row.value();
            if (existingOn.contains(key)) {
               this.working.onValues.add(key);
            } else if (existingOff.contains(key)) {
               this.working.offValues.add(key);
            } else if (existingIgnore.contains(key)) {
               this.working.ignoreValues.add(key);
            } else {
               this.working.ignoreValues.add(key);
            }
         }
      } catch (Exception var10) {
         this.errorMessage = Component.translatable("screen.command-gui.machine.detection_not_found").getString();
      }

      this.rebuildValueButtons();
   }

   private void cycleValueCategory(DetectionScreen.PropertyValue row) {
      String key = row.property() + "=" + row.value();
      this.working.property = row.property();
      this.selectedProperty = row.property();
      if (this.working.onValues.contains(key)) {
         this.working.onValues.remove(key);
         this.working.offValues.add(key);
      } else if (this.working.offValues.contains(key)) {
         this.working.offValues.remove(key);
         this.working.ignoreValues.add(key);
      } else if (this.working.ignoreValues.contains(key)) {
         this.working.ignoreValues.remove(key);
      } else {
         this.working.onValues.add(key);
      }

      this.rebuildValueButtons();
   }

   private void rebuildValueButtons() {
      for (Button button : this.valueButtons) {
         this.removeWidget(button);
      }

      this.valueButtons.clear();
      int maxScroll = Math.max(0, this.allValueRows.size() - 5);
      this.valueScroll = Math.min(this.valueScroll, maxScroll);
      int fieldX = (this.width - 360) / 2;

      for (int i = 0; i < 5; i++) {
         int index = this.valueScroll + i;
         if (index >= this.allValueRows.size()) {
            break;
         }

         DetectionScreen.PropertyValue row = this.allValueRows.get(index);
         String value = row.value();
         int y = 150 + i * 16;
         String currentValue = this.currentValues.get(row.property());
         String currentMark = value.equals(currentValue) ? "● " : "  ";
         String labelText = row.property() + ":" + value;
         Button valueLabel = Button.builder(Component.literal(currentMark + labelText), btn -> {
         }).bounds(fieldX, y, 150, 14).build();
         valueLabel.active = false;
         valueLabel.setTooltip(Tooltip.create(Component.literal(row.property())));
         this.valueButtons.add(valueLabel);
         this.addRenderableWidget(valueLabel);
         Button category = Button.builder(this.buildCategoryLabel(row), btn -> this.cycleValueCategory(row)).bounds(fieldX + 158, y, 90, 14).build();
         category.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.machine.detection_cycle_hint")));
         this.valueButtons.add(category);
         this.addRenderableWidget(category);
      }
   }

   private Component buildCategoryLabel(DetectionScreen.PropertyValue row) {
      String key = row.property() + "=" + row.value();
      if (this.working.onValues.contains(key)) {
         return Component.translatable("screen.command-gui.machine.detection_on").copy().withColor(-11141291);
      } else if (this.working.offValues.contains(key)) {
         return Component.translatable("screen.command-gui.machine.detection_off").copy().withColor(-1);
      } else {
         return this.working.ignoreValues.contains(key)
            ? Component.translatable("screen.command-gui.machine.detection_ignore").copy().withColor(-7829368)
            : Component.translatable("screen.command-gui.machine.detection_abnormal").copy().withColor(-43691);
      }
   }

   private void saveAndClose() {
      this.errorMessage = "";
      this.working.enabled = true;
      if (this.found && !this.blockId.isEmpty() && !this.selectedProperty.isEmpty()) {
         this.working.blockId = this.blockId;
         this.working.property = this.selectedProperty;
      }

      if (!this.working.isConfigured()) {
         this.errorMessage = Component.translatable("screen.command-gui.machine.detection_need_block").getString();
      } else if (!this.working.onValues.isEmpty() && !this.working.offValues.isEmpty()) {
         this.working.x = parseInt(this.xField.getValue(), this.working.x);
         this.working.y = parseInt(this.yField.getValue(), this.working.y);
         this.working.z = parseInt(this.zField.getValue(), this.working.z);
         if (this.onApply != null && this.isModified()) {
            this.onApply.accept(this.working);
         }

         this.minecraft.gui.setScreen(this.parent);
      } else {
         this.errorMessage = Component.translatable("screen.command-gui.machine.detection_need_mapping").getString();
      }
   }

   private void backAndClose() {
      if (this.working.isConfigured() && this.onApply != null && this.isModified()) {
         this.onApply.accept(this.working);
      }

      this.minecraft.gui.setScreen(this.parent);
   }

   private static int parseInt(String text, int fallback) {
      try {
         return Integer.parseInt(text.trim());
      } catch (NumberFormatException var3) {
         return fallback;
      }
   }

   private boolean isModified() {
      return !sameDetection(this.working, this.initialSnapshot);
   }

   private static boolean sameDetection(MachineModels.DetectionData a, MachineModels.DetectionData b) {
      if (a == null || b == null) {
         return a == b;
      }

      return a.enabled == b.enabled
         && java.util.Objects.equals(a.dimension, b.dimension)
         && a.x == b.x
         && a.y == b.y
         && a.z == b.z
         && java.util.Objects.equals(a.blockId, b.blockId)
         && java.util.Objects.equals(a.property, b.property)
         && java.util.Objects.equals(a.onValues, b.onValues)
         && java.util.Objects.equals(a.offValues, b.offValues)
         && java.util.Objects.equals(a.ignoreValues, b.ignoreValues);
   }

   private static MachineModels.DetectionData copy(MachineModels.DetectionData source) {
      MachineModels.DetectionData copy = new MachineModels.DetectionData();
      copy.enabled = source.enabled;
      copy.dimension = source.dimension;
      copy.x = source.x;
      copy.y = source.y;
      copy.z = source.z;
      copy.blockId = source.blockId;
      copy.property = source.property;
      copy.onValues = new ArrayList<>(source.onValues);
      copy.offValues = new ArrayList<>(source.offValues);
      copy.ignoreValues = new ArrayList<>(source.ignoreValues);
      return copy;
   }

   private String blockDisplayName() {
      if (this.blockId != null && !this.blockId.isEmpty()) {
         try {
            Identifier id = Identifier.parse(this.blockId);
            Block block = (Block)BuiltInRegistries.BLOCK.getValue(id);
            if (block != null && block != Blocks.AIR) {
               return block.getName().getString();
            }
         } catch (Exception var3) {
         }

         return Component.translatable("screen.command-gui.machine.detection_unknown_block").getString();
      } else {
         return "--";
      }
   }

   public boolean keyPressed(KeyEvent keyEvent) {
      if (keyEvent.key() == 256) {
         this.backAndClose();
         return true;
      } else {
         return super.keyPressed(keyEvent);
      }
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      if (mouseY >= 150.0 && mouseY < 230.0) {
         int maxScroll = Math.max(0, this.allValueRows.size() - 5);
         if (scrollY > 0.0 && this.valueScroll > 0) {
            this.valueScroll--;
            this.rebuildValueButtons();
         } else if (scrollY < 0.0 && this.valueScroll < maxScroll) {
            this.valueScroll++;
            this.rebuildValueButtons();
         }
      }

      return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
   }

   public boolean mouseClicked(MouseButtonEvent mouseEvent, boolean focused) {
      if (mouseEvent.button() == 0 && this.valueScrollbar != null && this.valueScrollbar.contains(mouseEvent.x(), mouseEvent.y())) {
         int maxScroll = Math.max(0, this.allValueRows.size() - 5);
         int thumbTop = this.valueScrollbar.thumbTop(this.valueScroll, maxScroll, 5, Math.max(1, this.allValueRows.size()));
         this.valueScrollbarGrabOffset = mouseEvent.y() - (double)thumbTop;
         this.draggingValueScrollbar = true;
         return true;
      } else {
         return super.mouseClicked(mouseEvent, focused);
      }
   }

   public boolean mouseDragged(MouseButtonEvent mouseEvent, double dragX, double dragY) {
      if (this.draggingValueScrollbar && this.valueScrollbar != null) {
         int maxScroll = Math.max(0, this.allValueRows.size() - 5);
         int offset = this.valueScrollbar.offsetFromY(mouseEvent.y(), this.valueScrollbarGrabOffset, maxScroll, 5, Math.max(1, this.allValueRows.size()));
         this.valueScroll = Math.max(0, Math.min(offset, maxScroll));
         this.rebuildValueButtons();
         return true;
      } else {
         return super.mouseDragged(mouseEvent, dragX, dragY);
      }
   }

   public boolean mouseReleased(MouseButtonEvent mouseEvent) {
      this.draggingValueScrollbar = false;
      return super.mouseReleased(mouseEvent);
   }

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      int fieldX = (this.width - 360) / 2;
      guiGraphics.centeredText(this.font, this.title, this.width / 2, 4, -1);
      int fieldW = 82;
      int gap = 2;
      int labelW = 10;
      int xFieldX = fieldX + labelW + gap;
      int yLabelX = xFieldX + fieldW + gap;
      int yFieldX = yLabelX + labelW + gap;
      int zLabelX = yFieldX + fieldW + gap;
      int zFieldX = zLabelX + labelW + gap;
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.detection_x"), fieldX, 54, -5592406);
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.detection_y"), yLabelX, 54, -5592406);
      guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.detection_z"), zLabelX, 54, -5592406);
      if (this.found && !this.foundIcon.isEmpty()) {
         Matrix3x2fStack pose = guiGraphics.pose();
         pose.pushMatrix();
         pose.translate((float)fieldX + 96.0F, 80.0F);
         pose.scale(1.0F, 1.0F);
         guiGraphics.item(this.foundIcon, 0, 0);
         pose.popMatrix();
         String displayName = this.blockDisplayName();
         String rawId = this.blockId.isEmpty() ? "--" : this.blockId;
         int maxNameW = 236 - this.font.width(rawId) - 8;
         String name = this.font.plainSubstrByWidth(displayName, Math.max(20, maxNameW));
         guiGraphics.text(this.font, Component.literal(name), fieldX + 116, 84, -1);
         guiGraphics.text(this.font, Component.literal(rawId), fieldX + 116 + this.font.width(name) + 8, 84, -7829368);
      }

      if (!this.searched) {
         guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.detection_not_started"), fieldX, 110, -7829368);
      } else if (!this.errorMessage.isEmpty()) {
         guiGraphics.text(this.font, Component.literal(this.errorMessage), fieldX, 110, -43691);
      } else if (this.found) {
         int stateCount = this.totalStateCount();
         Component status = stateCount == 2
            ? Component.translatable("screen.command-gui.machine.detection_two_states")
            : Component.translatable("screen.command-gui.machine.detection_n_states", new Object[]{stateCount});
         guiGraphics.text(this.font, status, fieldX, 110, -11141291);
         guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.detection_dot_meaning"), fieldX + 170, 110, -5592406);
      }

      if (this.found) {
         int legendX = fieldX + 252;
         guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.detection_legend_abnormal"), legendX, 152, -43691);
         guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.detection_legend_on"), legendX, 168, -11141291);
         guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.detection_legend_off"), legendX, 184, -1);
         guiGraphics.text(this.font, Component.translatable("screen.command-gui.machine.detection_legend_ignore"), legendX, 200, -7829368);
      }

      int maxValueScroll = Math.max(0, this.allValueRows.size() - 5);
      int sbX = fieldX + 360 - 12;
      this.valueScrollbar = new ScrollbarHandle(sbX, 150, 12, 80);
      boolean hovered = this.valueScrollbar.contains((double)mouseX, (double)mouseY);
      this.valueScrollbar.render(guiGraphics, this.valueScroll, maxValueScroll, 5, Math.max(1, this.allValueRows.size()), hovered);
   }

   private int totalStateCount() {
      int total = 1;

      for (List<String> values : this.properties.values()) {
         if (values != null && !values.isEmpty()) {
            total *= values.size();
         }
      }

      return total;
   }

   private static record PropertyValue(String property, String value) {
   }
}
