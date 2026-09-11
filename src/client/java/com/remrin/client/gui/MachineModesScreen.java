package com.remrin.client.gui;

import com.remrin.client.machine.MachineDebug;
import com.remrin.client.machine.MachineModels;
import com.remrin.client.machine.MachineNetworkManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public class MachineModesScreen extends BaseParentedScreen<CommandGUIScreen> {
   private static final long TICK_MS = 50L;
   private static final int CHIP_HEIGHT = 18;
   private static final int CHIP_GAP = 6;
   private static final int CHIPS_PER_ROW = 3;
   private static final int GRID_TOP = 46;
   private static final int GRID_BOTTOM_PAD = 8;
   private static final int VISIBLE_ROWS = 6;
   private final String machineId;
   private final List<MachineModels.ModeData> modes = new ArrayList<>();
   private final Set<String> pending = new HashSet<>();
   private final Map<String, Long> modeCooldownUntil = new HashMap<>();
   private final List<Button> chipButtons = new ArrayList<>();
   private int listLeft;
   private int listRight;
   private int listBottom;
   private int gridScrollOffset = 0;
   private int chipWidth;
   private ScrollbarHandle gridScrollbar = null;
   private boolean draggingGridScrollbar = false;
   private double gridScrollbarGrabOffset = 0.0;
   private int lastSyncVersion = -1;
   private boolean synced = false;
   private boolean playerSelected = false;

   public MachineModesScreen(CommandGUIScreen parent, MachineModels.MachineData machine) {
      super(Component.translatable("screen.command-gui.machine.modes_select_title", new Object[]{machine.name}), parent);
      this.machineId = machine.id;
      if (machine.modes != null) {
         this.modes.addAll(machine.modes);

         for (MachineModels.ModeData mode : machine.modes) {
            if (mode.singleSelect && "on".equals(mode.detected)) {
               this.pending.add(mode.id);
            }
         }

         StringBuilder diag = new StringBuilder("[Modes.open] " + machine.id + ":");

         for (MachineModels.ModeData modex : machine.modes) {
            diag.append(" ").append(modex.id).append("(single=").append(modex.singleSelect).append(",running=").append(modex.running).append(")");
         }

         MachineDebug.log(diag.toString());
      }
   }

   protected void init() {
      super.init();
      this.lastSyncVersion = MachineNetworkManager.getSyncVersion();
      this.synced = false;
      MachineNetworkManager.sendRequestSync();
      int listWidth = Math.min(340, this.width - 40);
      this.listLeft = (this.width - listWidth) / 2;
      this.listRight = this.listLeft + listWidth;
      this.listBottom = this.height - 30;
      this.chipWidth = (this.listRight - this.listLeft - 16 - 12) / 3;
      this.rebuildChips();
      this.addRenderableWidget(
         GuiButton.themed(Component.translatable("screen.command-gui.machine.refresh_detection"), b -> this.refreshDetection())
            .bounds(this.listRight - 100, 24, 100, 18)
            .build()
      );
      int barY = this.height - 24;
      int barWidth = Math.min(80, listWidth / 3);
      int barStartX = this.listLeft + (listWidth - barWidth * 2 - 8) / 2;
      this.addRenderableWidget(
         GuiButton.themed(Component.translatable("screen.command-gui.machine.confirm_modes"), b -> this.applyPending())
            .bounds(barStartX, barY, barWidth, 18)
            .build()
      );
      this.addRenderableWidget(
         GuiButton.themed(Component.translatable("screen.command-gui.back"), b -> this.minecraft.gui.setScreen(this.parent))
            .bounds(barStartX + barWidth + 8, barY, barWidth, 18)
            .build()
      );
   }

   private void rebuildChips() {
      for (Button button : this.chipButtons) {
         this.removeWidget(button);
      }

      this.chipButtons.clear();
      int totalRows = this.getTotalRows();
      int maxRows = this.getMaxScroll();
      this.gridScrollOffset = Math.max(0, Math.min(this.gridScrollOffset, maxRows));
      int visibleRows = Math.min(6, totalRows);
      Font font = this.minecraft.font;

      for (int i = this.gridScrollOffset * 3; i < Math.min(this.modes.size(), (this.gridScrollOffset + visibleRows) * 3); i++) {
         MachineModels.ModeData mode = this.modes.get(i);
         int gridIndex = i - this.gridScrollOffset * 3;
         int col = gridIndex % 3;
         int row = gridIndex / 3;
         int x = this.listLeft + col * (this.chipWidth + 6);
         int y = 46 + row * 24;
         boolean modeDetectionEnabled = mode.detection != null && mode.detection.enabled;
         String suffix = "";
         int stateColor = -1;
         boolean chipLocked = false;
         if (!modeDetectionEnabled) {
            chipLocked = true;
         } else if (mode.processing) {
            suffix = "off".equals(mode.transition) ? "（正在关闭...）" : "（正在开启...）";
            stateColor = -22016;
            chipLocked = true;
         } else {
            String chipText = mode.detected;

            suffix = switch (chipText) {
               case "on" -> "（已开启）";
               case "abnormal" -> "（异常）";
               default -> "（已关闭）";
            };
            if ("abnormal".equals(mode.detected)) {
               stateColor = -43691;
               chipLocked = true;
            } else if ("on".equals(mode.detected)) {
               stateColor = -11141291;
            }
         }

         Component chipText = Component.literal(font.plainSubstrByWidth(mode.name + suffix, this.chipWidth - 10));
         DarkSelectButton chip = new DarkSelectButton(x, y, this.chipWidth, 18, chipText, b -> this.togglePending(mode));
         if (!modeDetectionEnabled) {
            chip.setDarkSelected(() -> true, -1);
            chip.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.machine.mode_no_detection", new Object[]{mode.name})));
         } else if (chipLocked) {
            chip.setDarkSelected(() -> true, -1);
         } else {
            chip.setDarkSelected(() -> this.pending.contains(mode.id), -22016);
         }

         chip.setUnselectedTextColor(stateColor);
         boolean singleSelectRunning = mode.singleSelect && "on".equals(mode.detected);
         if (singleSelectRunning) {
            chip.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.machine.chip_single_running", new Object[]{mode.name})));
         } else if (modeDetectionEnabled) {
            chip.setTooltip(Tooltip.create(Component.translatable("screen.command-gui.machine.chip_hint", new Object[]{mode.name})));
         }

         if (chipLocked || singleSelectRunning || mode.processing || inCooldown(this.modeCooldownUntil.get(mode.id)) || !this.synced) {
            chip.active = false;
         }

         MachineDebug.log(
            "[Modes.chip] "
               + this.machineId
               + " "
               + mode.id
               + " single="
               + mode.singleSelect
               + " running="
               + mode.running
               + " processing="
               + mode.processing
               + " locked="
               + chipLocked
               + " cooldown="
               + inCooldown(this.modeCooldownUntil.get(mode.id))
               + " synced="
               + this.synced
               + " active="
               + chip.active
         );
         this.chipButtons.add(chip);
         this.addRenderableWidget(chip);
      }
   }

   private int getTotalRows() {
      return (this.modes.size() + 3 - 1) / 3;
   }

   private int getMaxScroll() {
      return Math.max(0, this.getTotalRows() - 6);
   }

   private void scrollGrid(double delta) {
      if (delta > 0.0 && this.gridScrollOffset > 0) {
         this.gridScrollOffset--;
         this.rebuildChips();
      } else if (delta < 0.0 && this.gridScrollOffset < this.getMaxScroll()) {
         this.gridScrollOffset++;
         this.rebuildChips();
      }
   }

   private void setGridScrollOffset(int offset) {
      this.gridScrollOffset = Math.max(0, Math.min(offset, this.getMaxScroll()));
      this.rebuildChips();
   }

   private boolean isOverGridScrollbar(double mouseX, double mouseY) {
      if (this.gridScrollbar == null) {
         return false;
      }
      return this.gridScrollbar.contains(mouseX, mouseY);
   }

   private void refreshDetection() {
      MachineNetworkManager.sendRefreshDetection(this.machineId);
      this.lastSyncVersion = MachineNetworkManager.getSyncVersion();
   }

   @Override
   public void tick() {
      super.tick();
      int current = MachineNetworkManager.getSyncVersion();
      if (this.lastSyncVersion >= 0 && current != this.lastSyncVersion) {
         this.lastSyncVersion = current;
         this.synced = true;
         MachineModels.MachineData fresh = MachineNetworkManager.getMachine(this.machineId);
         if (fresh != null && fresh.modes != null) {
            this.modes.clear();
            this.modes.addAll(fresh.modes);
            if (!this.playerSelected) {
               this.pending.clear();

               for (MachineModels.ModeData mode : this.modes) {
                  if (mode.singleSelect && "on".equals(mode.detected)) {
                     this.pending.add(mode.id);
                  }
               }
            }

            this.rebuildChips();
         }
      }
   }

   private static boolean inCooldown(Long until) {
      return until != null && System.currentTimeMillis() < until;
   }

   private void togglePending(MachineModels.ModeData clicked) {
      if ((!clicked.singleSelect || !"on".equals(clicked.detected)) && !clicked.processing) {
         this.playerSelected = true;
         if (clicked.singleSelect) {
            for (MachineModels.ModeData mode : this.modes) {
               if (mode.singleSelect && !mode.id.equals(clicked.id)) {
                  this.pending.remove(mode.id);
               }
            }
         }

         if (!this.pending.add(clicked.id)) {
            this.pending.remove(clicked.id);
         }

         this.rebuildChips();
      }
   }

   private void applyPending() {
      if (this.pending.isEmpty()) {
         this.minecraft.gui.setScreen(this.parent);
      } else {
         MachineModels.MachineData machine = MachineNetworkManager.getMachine(this.machineId);
         if (machine == null) {
            this.minecraft.gui.setScreen(this.parent);
         } else {
            long now = System.currentTimeMillis();
            int interval = machine.switchInterval;

            for (MachineModels.ModeData mode : machine.modes) {
               if (mode.switchInterval > 0) {
                  interval = mode.switchInterval;
                  break;
               }
            }

            for (String modeId : this.pending) {
               this.modeCooldownUntil.put(modeId, now + (long)interval * 50L);
            }

            MachineNetworkManager.sendSetModes(this.machineId, new ArrayList<>(this.pending));
            this.pending.clear();
            this.minecraft.gui.setScreen(this.parent);
         }
      }
   }

   public boolean keyPressed(KeyEvent keyEvent) {
      if (keyEvent.key() == 256) {
         this.minecraft.gui.setScreen(this.parent);
         return true;
      } else {
         return super.keyPressed(keyEvent);
      }
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      if (this.getMaxScroll() > 0) {
         this.scrollGrid(scrollY > 0.0 ? 1.0 : -1.0);
         return true;
      } else {
         return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
      }
   }

   public boolean mouseClicked(MouseButtonEvent mouseEvent, boolean focused) {
      if (mouseEvent.button() == 0 && this.isOverGridScrollbar(mouseEvent.x(), mouseEvent.y()) && this.getMaxScroll() > 0 && this.gridScrollbar != null) {
         int thumbTop = this.gridScrollbar.thumbTop(this.gridScrollOffset, this.getMaxScroll(), 6, this.getTotalRows());
         this.gridScrollbarGrabOffset = mouseEvent.y() - (double)thumbTop;
         this.draggingGridScrollbar = true;
         return true;
      } else {
         return super.mouseClicked(mouseEvent, focused);
      }
   }

   public boolean mouseDragged(MouseButtonEvent mouseEvent, double dragX, double dragY) {
      if (this.draggingGridScrollbar && this.gridScrollbar != null) {
         int offset = this.gridScrollbar.offsetFromY(mouseEvent.y(), this.gridScrollbarGrabOffset, this.getMaxScroll(), 6, this.getTotalRows());
         this.setGridScrollOffset(offset);
         return true;
      } else {
         return super.mouseDragged(mouseEvent, dragX, dragY);
      }
   }

   public boolean mouseReleased(MouseButtonEvent mouseEvent) {
      this.draggingGridScrollbar = false;
      return super.mouseReleased(mouseEvent);
   }

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      guiGraphics.centeredText(this.font, this.title, this.width / 2, 6, -1);
      if (this.modes.isEmpty()) {
         guiGraphics.centeredText(
            this.font, Component.translatable("screen.command-gui.machine.modes_empty"), this.width / 2, (46 + this.listBottom) / 2, -7829368
         );
      } else {
         int gridTop = 46;
         int gridH = 138;
         int scrollbarX = this.listRight - 12;
         this.gridScrollbar = new ScrollbarHandle(scrollbarX, gridTop, 12, gridH);
         boolean hovered = this.gridScrollbar.contains((double)mouseX, (double)mouseY);
         this.gridScrollbar.render(guiGraphics, this.gridScrollOffset, this.getMaxScroll(), 6, this.getTotalRows(), hovered);
      }
   }
}
