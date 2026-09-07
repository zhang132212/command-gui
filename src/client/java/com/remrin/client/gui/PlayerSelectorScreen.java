package com.remrin.client.gui;

import com.mojang.authlib.GameProfile;
import com.remrin.client.machine.MachineNetworkManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;

public class PlayerSelectorScreen extends BaseParentedScreen<Screen> {
   private static final int ITEM_WIDTH = 90;
   private static final int ITEM_HEIGHT = 24;
   private static final int COLUMNS = 4;
   private static final int PADDING = 10;
   private static final int FACE_SIZE = 8;
   private static final int LIST_START_Y = 40;
   private static final int LIST_BOTTOM_MARGIN = 50;
   private static final int ITEM_GAP = 5;
   private final String commandTemplate;
   private final Consumer<String> onPlayerSelected;
   private final Component titleText;
   private final PlayerSelectorScreen.FilterMode filterMode;
   private final List<Button> playerButtons = new ArrayList<>();
   private List<PlayerInfo> players = new ArrayList<>();
   private int scrollOffset = 0;
   private boolean initialized = false;
   private ScrollbarHandle scrollbar = null;
   private boolean draggingScrollbar = false;
   private double scrollbarGrabOffset = 0.0;
   private int layoutStartX;
   private int layoutMaxRowsVisible;
   private int layoutMaxItemsVisible;

   public PlayerSelectorScreen(Screen parent, Component title, String commandTemplate) {
      this(parent, title, commandTemplate, PlayerSelectorScreen.FilterMode.EXCLUDE_SELF, null);
   }

   public PlayerSelectorScreen(Screen parent, Component title, String commandTemplate, PlayerSelectorScreen.FilterMode filterMode) {
      this(parent, title, commandTemplate, filterMode, null);
   }

   public PlayerSelectorScreen(
      Screen parent, Component title, String commandTemplate, PlayerSelectorScreen.FilterMode filterMode, Consumer<String> onPlayerSelected
   ) {
      super(title, parent);
      this.titleText = title;
      this.commandTemplate = commandTemplate;
      this.filterMode = filterMode;
      this.onPlayerSelected = onPlayerSelected;
   }

   private static boolean isFakePlayer(PlayerInfo playerInfo) {
      return CommandHelper.isFakePlayer(playerInfo);
   }

   protected void init() {
      super.init();
      this.playerButtons.clear();
      if (!this.initialized) {
         this.loadPlayers();
         this.initialized = true;
      }

      this.computeLayout();
      this.buildPlayerButtons();
      int closeBtnY = this.height - 28;
      this.addRenderableWidget(
         Button.builder(Component.translatable("screen.command-gui.back"), button -> this.minecraft.gui.setScreen(this.parent))
            .bounds(this.width / 2 - 75, closeBtnY, 150, 20)
            .build()
      );
   }

   private void computeLayout() {
      int listHeight = this.height - 40 - 50;
      this.layoutMaxRowsVisible = Math.max(1, listHeight / 24);
      this.layoutMaxItemsVisible = this.layoutMaxRowsVisible * 4;
      int totalWidth = 375;
      this.layoutStartX = (this.width - totalWidth) / 2;
   }

   private void loadPlayers() {
      this.players.clear();
      if (this.minecraft != null && this.minecraft.getConnection() != null) {
         UUID selfUUID = this.minecraft.player != null ? this.minecraft.player.getUUID() : null;

         for (PlayerInfo player : this.minecraft.getConnection().getOnlinePlayers()) {
            boolean isSelf = selfUUID != null && player.getProfile().id().equals(selfUUID);
            switch (this.filterMode) {
               case ALL:
                  this.players.add(player);
                  break;
               case NORMAL:
                  if (!isFakePlayer(player)) {
                     this.players.add(player);
                  }
                  break;
               case EXCLUDE_SELF:
                  if (!isSelf) {
                     this.players.add(player);
                  }
                  break;
               case ONLY_FAKE_PLAYERS:
                  if (!isSelf && isFakePlayer(player)) {
                     this.players.add(player);
                  }
            }
         }

         if ((this.filterMode == PlayerSelectorScreen.FilterMode.ALL || this.filterMode == PlayerSelectorScreen.FilterMode.ONLY_FAKE_PLAYERS)
            && MachineNetworkManager.isFakePlayerStatesSupported()) {
            Set<String> existing = new HashSet<>();
            for (PlayerInfo player : this.players) {
               existing.add(player.getProfile().name());
            }

            for (String name : MachineNetworkManager.getServerFakePlayers()) {
               if (existing.add(name)) {
                  this.players.add(new PlayerInfo(new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name), false));
               }
            }
         }
      }
   }

   private void buildPlayerButtons() {
      for (int i = 0; i < Math.min(this.layoutMaxItemsVisible, this.players.size() - this.scrollOffset * 4); i++) {
         int index = i + this.scrollOffset * 4;
         if (index >= this.players.size()) {
            break;
         }

         PlayerInfo playerInfo = this.players.get(index);
         String playerName = playerInfo.getProfile().name();
         int col = i % 4;
         int row = i / 4;
         int x = this.layoutStartX + col * 95;
         int y = 40 + row * 24;
         Button playerBtn = Button.builder(Component.literal("   " + playerName), btn -> this.selectPlayer(playerName)).bounds(x, y, 90, 20).build();
         this.playerButtons.add(playerBtn);
         this.addRenderableWidget(playerBtn);
      }
   }

   protected void onPlayerSelected(String playerName) {
   }

   private void selectPlayer(String playerName) {
      if (this.onPlayerSelected != null) {
         this.onPlayerSelected.accept(playerName);
      } else {
         this.onPlayerSelected(playerName);
         if (this.commandTemplate != null) {
            String command = this.commandTemplate.replace("{player}", playerName);
            this.executeCommand(command);
         }
      }
   }

   private void executeCommand(String command) {
      Minecraft mc = Minecraft.getInstance();
      if (mc != null && mc.player != null) {
         mc.gui.setScreen(null);
         ChainedCommandExecutor.sendCommand(command);
      }
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      int totalRows = (this.players.size() + 4 - 1) / 4;
      if (totalRows > this.layoutMaxRowsVisible) {
         if (scrollY > 0.0 && this.scrollOffset > 0) {
            this.scrollOffset--;
            this.rebuildPlayerButtons();
         } else if (scrollY < 0.0 && this.scrollOffset < totalRows - this.layoutMaxRowsVisible) {
            this.scrollOffset++;
            this.rebuildPlayerButtons();
         }
      }

      return true;
   }

   private void rebuildPlayerButtons() {
      for (Button btn : this.playerButtons) {
         this.removeWidget(btn);
      }

      this.playerButtons.clear();
      this.buildPlayerButtons();
   }

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      guiGraphics.centeredText(this.font, this.titleText, this.width / 2, 15, -1);

      for (int i = 0; i < Math.min(this.layoutMaxItemsVisible, this.players.size() - this.scrollOffset * 4); i++) {
         int index = i + this.scrollOffset * 4;
         if (index >= this.players.size()) {
            break;
         }

         PlayerInfo playerInfo = this.players.get(index);
         int col = i % 4;
         int row = i / 4;
         int x = this.layoutStartX + col * 95;
         int y = 40 + row * 24;
         int btnCenterY = y + 10;
         int faceY = btnCenterY - 4;
         PlayerSkin skin = playerInfo.getSkin();
         PlayerFaceExtractor.extractRenderState(guiGraphics, skin, x + 4, faceY, 8);
      }

      if (this.players.isEmpty()) {
         guiGraphics.centeredText(this.font, Component.translatable("screen.command-gui.no_players"), this.width / 2, this.height / 2, -7829368);
      }

      int totalRows = (this.players.size() + 4 - 1) / 4;
      if (totalRows > this.layoutMaxRowsVisible) {
         int listBottom = this.height - 50;
         int gridRight = this.layoutStartX + 360 + 15;
         this.scrollbar = new ScrollbarHandle(gridRight + 10, 40, 12, listBottom - 40);
         boolean hovered = this.scrollbar.contains((double)mouseX, (double)mouseY);
         this.scrollbar.render(guiGraphics, this.scrollOffset, totalRows - this.layoutMaxRowsVisible, this.layoutMaxRowsVisible, totalRows, hovered);
      } else {
         this.scrollbar = null;
      }

      int bottomY = this.height - 45;
      guiGraphics.fill(0, bottomY, this.width, bottomY + 1, -11184811);
   }

   public boolean mouseClicked(MouseButtonEvent mouseEvent, boolean focused) {
      if (mouseEvent.button() == 0 && this.scrollbar != null && this.scrollbar.contains(mouseEvent.x(), mouseEvent.y())) {
         int totalRows = (this.players.size() + 4 - 1) / 4;
         int thumbTop = this.scrollbar.thumbTop(this.scrollOffset, totalRows - this.layoutMaxRowsVisible, this.layoutMaxRowsVisible, totalRows);
         this.scrollbarGrabOffset = mouseEvent.y() - (double)thumbTop;
         this.draggingScrollbar = true;
         return true;
      } else {
         return super.mouseClicked(mouseEvent, focused);
      }
   }

   public boolean mouseDragged(MouseButtonEvent mouseEvent, double dragX, double dragY) {
      if (this.draggingScrollbar && this.scrollbar != null) {
         int totalRows = (this.players.size() + 4 - 1) / 4;
         int offset = this.scrollbar
            .offsetFromY(mouseEvent.y(), this.scrollbarGrabOffset, totalRows - this.layoutMaxRowsVisible, this.layoutMaxRowsVisible, totalRows);
         this.scrollOffset = Math.max(0, Math.min(offset, totalRows - this.layoutMaxRowsVisible));
         this.rebuildPlayerButtons();
         return true;
      } else {
         return super.mouseDragged(mouseEvent, dragX, dragY);
      }
   }

   public boolean mouseReleased(MouseButtonEvent mouseEvent) {
      this.draggingScrollbar = false;
      return super.mouseReleased(mouseEvent);
   }

   public static enum FilterMode {
      ALL,
      NORMAL,
      EXCLUDE_SELF,
      ONLY_FAKE_PLAYERS;
   }
}
