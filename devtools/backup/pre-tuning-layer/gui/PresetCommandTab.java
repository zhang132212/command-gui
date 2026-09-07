package com.remrin.client.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class PresetCommandTab extends AbstractCommandTab {
   private final String presetId;
   private final String nameKey;
   private final List<VanillaCommands.CommandGroup> allGroups = new ArrayList<>();
   private final List<VanillaCommands.VanillaCommand> filteredCommands = new ArrayList<>();
   private int selectedCategoryIndex = -1;

   public PresetCommandTab(Screen parent, String presetId, String nameKey) {
      super(parent);
      this.presetId = presetId;
      this.nameKey = nameKey;
      this.allGroups.addAll(VanillaCommands.getGroups(presetId));
      this.buildFilteredCommands();
   }

   public Component getTabTitle() {
      return Component.translatable(this.nameKey);
   }

   @Override
   protected int getFilteredCommandCount() {
      return this.filteredCommands.size();
   }

   @Override
   protected void buildFilteredCommands() {
      this.filteredCommands.clear();
      String search = this.searchText;

      for (int i = 0; i < this.allGroups.size(); i++) {
         if (this.selectedCategoryIndex < 0 || this.selectedCategoryIndex == i) {
            VanillaCommands.CommandGroup group = this.allGroups.get(i);

            for (VanillaCommands.VanillaCommand cmd : group.commands) {
               if (search.isEmpty() || cmd.getName().getString().toLowerCase().contains(search) || cmd.command.toLowerCase().contains(search)) {
                  this.filteredCommands.add(cmd);
               }
            }
         }
      }
   }

   @Override
   protected void buildAllCategoryButtons() {
      this.allCategoryButtons.clear();
      if (this.area != null) {
         int x = this.area.left();
         int y = this.area.top();
         Button allBtn = Button.builder(Component.translatable("screen.command-gui.category.all"), btn -> this.onCategoryButtonClick(-1))
            .bounds(x, y, 50, 16)
            .build();
         allBtn.active = this.selectedCategoryIndex != -1;
         this.allCategoryButtons.add(allBtn);

         for (int i = 0; i < this.allGroups.size(); i++) {
            VanillaCommands.CommandGroup group = this.allGroups.get(i);
            int index = i;
            Button catBtn = Button.builder(Component.translatable(group.nameKey), btn -> this.onCategoryButtonClick(index)).bounds(x, y, 50, 16).build();
            catBtn.active = this.selectedCategoryIndex != index;
            this.allCategoryButtons.add(catBtn);
         }
      }
   }

   @Override
   protected Button buildCommandButton(int index, int x, int y, int width, int height) {
      VanillaCommands.VanillaCommand cmd = this.filteredCommands.get(index);
      Button btn = Button.builder(cmd.getName(), b -> this.handleCommand(cmd)).bounds(x, y, width, height).build();
      Component desc = cmd.getDescription();
      if (desc != null) {
         btn.setTooltip(Tooltip.create(desc.copy().append("\n§7" + cmd.command)));
      } else {
         btn.setTooltip(Tooltip.create(Component.literal(cmd.command)));
      }

      return btn;
   }

   private void onCategoryButtonClick(int index) {
      if (this.selectedCategoryIndex != index) {
         this.selectCategory(index);
      }
   }

   private void updateCategoryButtonStates() {
      if (!this.allCategoryButtons.isEmpty()) {
         this.allCategoryButtons.get(0).active = this.selectedCategoryIndex != -1;

         for (int i = 1; i < this.allCategoryButtons.size(); i++) {
            this.allCategoryButtons.get(i).active = this.selectedCategoryIndex != i - 1;
         }
      }
   }

   private void selectCategory(int index) {
      this.notifyCategoryChange(() -> {
         this.selectedCategoryIndex = index;
         this.scrollOffset = 0;
         this.buildFilteredCommands();
         this.rebuildButtons();
         this.updateCategoryButtonStates();
      });
   }

   private void handleCommand(VanillaCommands.VanillaCommand cmd) {
      ChainedCommandExecutor.Config config = ChainedCommandExecutor.Config.defaultConfig();
      if (cmd.quickStrValues != null && cmd.quickStrValues.length > 0) {
         config.withTimeRange(cmd.minValue, cmd.maxValue, cmd.quickStrValues);
      } else if (cmd.minValue != null || cmd.maxValue != null || cmd.quickValues != null) {
         config.withNumberRange(cmd.minValue, cmd.maxValue, cmd.quickValues);
      }

      ChainedCommandExecutor.execute(this.parent, cmd.command, config);
   }

   public String getPresetId() {
      return this.presetId;
   }

   @Override
   public int getScrollOffset() {
      return this.scrollOffset;
   }

   public VanillaCommands.VanillaCommand getCommandAt(int index) {
      if (index >= 0 && index < this.filteredCommands.size()) {
         return this.filteredCommands.get(index);
      }
      return null;
   }

   public int getCommandIndexAtPosition(double mouseX, double mouseY) {
      if (this.area == null) {
         return -1;
      } else {
         int cmdAreaLeft = this.getCommandAreaLeft();
         int cmdAreaWidth = this.getCommandAreaWidth();
         if (mouseX < (double)cmdAreaLeft || mouseX > (double)(cmdAreaLeft + cmdAreaWidth)) {
            return -1;
         } else if (!(mouseY < (double)this.area.top()) && !(mouseY > (double)this.area.bottom())) {
            int colWidth = cmdAreaWidth / 3;
            int col = (int)((mouseX - (double)cmdAreaLeft) / (double)colWidth);
            int row = (int)((mouseY - (double)this.area.top()) / 24.0);
            int index = (this.scrollOffset + row) * 3 + col;
            if (index >= 0 && index < this.filteredCommands.size()) {
               return index;
            }
            return -1;
         } else {
            return -1;
         }
      }
   }
}
