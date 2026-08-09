package com.remrin.client.gui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Player selector screen that lists currently online players for the user to choose from, used to
 * fill in the {@code {player}} placeholder in a command.
 * <p>
 * Supports three filter modes ({@link FilterMode}):
 * <ul>
 *   <li>{@code ALL} - show all players (including self)</li>
 *   <li>{@code EXCLUDE_SELF} - exclude the local player</li>
 *   <li>{@code ONLY_FAKE_PLAYERS} - show only Carpet fake players</li>
 * </ul>
 * The selected player name is passed via the {@link #onPlayerSelected} callback (or Consumer parameter).
 */
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
  private final FilterMode filterMode;
  private final List<Button> playerButtons = new ArrayList<>();
  private List<PlayerInfo> players = new ArrayList<>();
  private int scrollOffset = 0;
  private boolean initialized = false;
  private ScrollbarHandle scrollbar = null;
  private boolean draggingScrollbar = false;
  private double scrollbarGrabOffset = 0;

  // Cached layout values, updated by computeLayout() in init() and mouseScrolled()
  private int layoutStartX;
  private int layoutMaxRowsVisible;
  private int layoutMaxItemsVisible;
  public PlayerSelectorScreen(Screen parent, Component title, String commandTemplate) {
    this(parent, title, commandTemplate, FilterMode.EXCLUDE_SELF, null);
  }
  public PlayerSelectorScreen(Screen parent, Component title, String commandTemplate,
      FilterMode filterMode) {
    this(parent, title, commandTemplate, filterMode, null);
  }

  public PlayerSelectorScreen(Screen parent, Component title, String commandTemplate,
      FilterMode filterMode, Consumer<String> onPlayerSelected) {
    super(title, parent);
    this.titleText = title;
    this.commandTemplate = commandTemplate;
    this.filterMode = filterMode;
    this.onPlayerSelected = onPlayerSelected;
  }

  private static boolean isFakePlayer(PlayerInfo playerInfo) {
    return CommandHelper.isFakePlayer(playerInfo);
  }

  @Override
  protected void init() {
    super.init();
    playerButtons.clear();

    if (!initialized) {
      loadPlayers();
      initialized = true;
    }
    computeLayout();
    buildPlayerButtons();

    int closeBtnY = this.height - 28;
    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.back"),
        button -> this.minecraft.gui.setScreen(parent)
    ).bounds(this.width / 2 - 75, closeBtnY, 150, 20).build());
  }

  /** Recomputes shared layout values from the current screen dimensions. */
  private void computeLayout() {
    int listHeight = this.height - LIST_START_Y - LIST_BOTTOM_MARGIN;
    layoutMaxRowsVisible = Math.max(1, listHeight / ITEM_HEIGHT);
    layoutMaxItemsVisible = layoutMaxRowsVisible * COLUMNS;
    int totalWidth = COLUMNS * ITEM_WIDTH + (COLUMNS - 1) * ITEM_GAP;
    layoutStartX = (this.width - totalWidth) / 2;
  }

  /**
   * Filters the online player list according to {@link FilterMode} to produce the set of players to
   * display. Only called on the first {@link #init()} to avoid resetting the list on every screen
   * resize.
   */
  private void loadPlayers() {
    players.clear();
    if (this.minecraft != null && this.minecraft.getConnection() != null) {
      UUID selfUUID = this.minecraft.player != null ? this.minecraft.player.getUUID() : null;
      Collection<PlayerInfo> onlinePlayers = this.minecraft.getConnection().getOnlinePlayers();
      for (PlayerInfo player : onlinePlayers) {
        boolean isSelf = selfUUID != null && player.getProfile().id().equals(selfUUID);

        switch (filterMode) {
          case ALL:
            players.add(player);
            break;
          case EXCLUDE_SELF:
            if (!isSelf) {
              players.add(player);
            }
            break;
          case ONLY_FAKE_PLAYERS:
            if (!isSelf && isFakePlayer(player)) {
              players.add(player);
            }
            break;
        }
      }
    }
  }

  private void buildPlayerButtons() {
    for (int i = 0; i < Math.min(layoutMaxItemsVisible, players.size() - scrollOffset * COLUMNS); i++) {
      int index = i + scrollOffset * COLUMNS;
      if (index >= players.size()) {
        break;
      }

      PlayerInfo playerInfo = players.get(index);
      String playerName = playerInfo.getProfile().name();

      int col = i % COLUMNS;
      int row = i / COLUMNS;
      int x = layoutStartX + col * (ITEM_WIDTH + ITEM_GAP);
      int y = LIST_START_Y + row * ITEM_HEIGHT;

      Button playerBtn = Button.builder(
          Component.literal("   " + playerName),
          btn -> selectPlayer(playerName)
      ).bounds(x, y, ITEM_WIDTH, ITEM_HEIGHT - 4).build();

      playerButtons.add(playerBtn);
      this.addRenderableWidget(playerBtn);
    }
  }

  protected void onPlayerSelected(String playerName) {
  }

  private void selectPlayer(String playerName) {
    if (onPlayerSelected != null) {
      onPlayerSelected.accept(playerName);
    } else {
      onPlayerSelected(playerName);
      if (commandTemplate != null) {
        String command = commandTemplate.replace("{player}", playerName);
        executeCommand(command);
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

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    int totalRows = (players.size() + COLUMNS - 1) / COLUMNS;

    if (totalRows > layoutMaxRowsVisible) {
      if (scrollY > 0 && scrollOffset > 0) {
        scrollOffset--;
        rebuildPlayerButtons();
      } else if (scrollY < 0 && scrollOffset < totalRows - layoutMaxRowsVisible) {
        scrollOffset++;
        rebuildPlayerButtons();
      }
    }
    return true;
  }

  private void rebuildPlayerButtons() {
    for (Button btn : playerButtons) {
      this.removeWidget(btn);
    }
    playerButtons.clear();
    buildPlayerButtons();
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
      float partialTick) {
    super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

    guiGraphics.centeredText(this.font, this.titleText, this.width / 2, 15, 0xFFFFFFFF);

    for (int i = 0; i < Math.min(layoutMaxItemsVisible, players.size() - scrollOffset * COLUMNS); i++) {
      int index = i + scrollOffset * COLUMNS;
      if (index >= players.size()) {
        break;
      }

      PlayerInfo playerInfo = players.get(index);

      int col = i % COLUMNS;
      int row = i / COLUMNS;
      int x = layoutStartX + col * (ITEM_WIDTH + ITEM_GAP);
      int y = LIST_START_Y + row * ITEM_HEIGHT;

      int btnCenterY = y + (ITEM_HEIGHT - 4) / 2;
      int faceY = btnCenterY - FACE_SIZE / 2;

      PlayerSkin skin = playerInfo.getSkin();
      PlayerFaceExtractor.extractRenderState(guiGraphics, skin, x + 4, faceY, FACE_SIZE);
    }

    if (players.isEmpty()) {
      guiGraphics.centeredText(this.font,
          Component.translatable("screen.command-gui.no_players"),
          this.width / 2, this.height / 2, 0xFF888888);
    }

    // Right-edge scrollbar for the player grid
    int totalRows = (players.size() + COLUMNS - 1) / COLUMNS;
    if (totalRows > layoutMaxRowsVisible) {
      int listBottom = this.height - LIST_BOTTOM_MARGIN;
      int gridRight = layoutStartX + COLUMNS * ITEM_WIDTH + (COLUMNS - 1) * ITEM_GAP;
      scrollbar = new ScrollbarHandle(gridRight + 10, LIST_START_Y, 12, listBottom - LIST_START_Y);
      boolean hovered = scrollbar.contains(mouseX, mouseY);
      scrollbar.render(guiGraphics, scrollOffset, totalRows - layoutMaxRowsVisible,
          layoutMaxRowsVisible, totalRows, hovered);
    } else {
      scrollbar = null;
    }

    int bottomY = this.height - 45;
    guiGraphics.fill(0, bottomY, this.width, bottomY + 1, 0xFF555555);
  }

  @Override
  public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent mouseEvent,
      boolean focused) {
    if (mouseEvent.button() == 0 && scrollbar != null
        && scrollbar.contains(mouseEvent.x(), mouseEvent.y())) {
      int totalRows = (players.size() + COLUMNS - 1) / COLUMNS;
      int thumbTop = scrollbar.thumbTop(scrollOffset, totalRows - layoutMaxRowsVisible,
          layoutMaxRowsVisible, totalRows);
      scrollbarGrabOffset = mouseEvent.y() - thumbTop;
      draggingScrollbar = true;
      return true;
    }
    return super.mouseClicked(mouseEvent, focused);
  }

  @Override
  public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent mouseEvent,
      double dragX, double dragY) {
    if (draggingScrollbar && scrollbar != null) {
      int totalRows = (players.size() + COLUMNS - 1) / COLUMNS;
      int offset = scrollbar.offsetFromY(mouseEvent.y(), scrollbarGrabOffset,
          totalRows - layoutMaxRowsVisible, layoutMaxRowsVisible, totalRows);
      scrollOffset = Math.max(0, Math.min(offset, totalRows - layoutMaxRowsVisible));
      rebuildPlayerButtons();
      return true;
    }
    return super.mouseDragged(mouseEvent, dragX, dragY);
  }

  @Override
  public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent mouseEvent) {
    draggingScrollbar = false;
    return super.mouseReleased(mouseEvent);
  }

  public enum FilterMode {
    ALL,
    EXCLUDE_SELF,
    ONLY_FAKE_PLAYERS
  }
}
