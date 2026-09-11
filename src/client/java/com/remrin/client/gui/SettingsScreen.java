package com.remrin.client.gui;

import com.remrin.client.config.SettingsConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.client.gui.components.Tooltip;

public class SettingsScreen extends BaseParentedScreen<Screen> {
   private static final int TITLE_X = 10;
   private static final int TITLE_Y = 6;
   private static final int TAB_Y = 22;
   private static final int TAB_HEIGHT = 20;
   private static final int TAB_GAP = 8;
   private static final int RIGHT_MARGIN = 16;
   private static final int SCROLLBAR_WIDTH = 12;
   private static final int CONTENT_SCROLL_STEP = 24;
   private static final int ROW_HEIGHT = 36;
   private static final int SETTING_BUTTON_WIDTH = 64;
   private static final int SETTING_BUTTON_HEIGHT = 20;
   private static final int CONTENT_TOP_GAP = 8;
   private final List<Button> sectionButtons = new ArrayList<>();
   private final List<AbstractWidget> contentWidgets = new ArrayList<>();
   private final List<SettingsScreen.SettingRow> settingRows = new ArrayList<>();
   private int selectedSection = 0;
   private ScreenRectangle contentArea;
   private int contentScrollOffset = 0;
   private int contentHeight = 0;
   private boolean draggingContentScrollbar = false;
   private double contentScrollbarGrabOffset = 0.0;
   private SettingsScreen.YesNoButton quickCommandKeepOpenToggle;
   private SettingsScreen.YesNoButton fakePlayerKeepOpenToggle;
   private SettingsScreen.YesNoButton quickCommandRememberViewToggle;
   private SettingsScreen.YesNoButton fakePlayerRememberViewToggle;
   private SettingsScreen.YesNoButton fakePlayerCustomCommandsToggle;
   private SettingsScreen.YesNoButton fakePlayerActionDropToggle;
   private SettingsScreen.YesNoButton fakePlayerActionDropStackToggle;
   private SettingsScreen.YesNoButton fakePlayerActionSprintToggle;
   private SettingsScreen.YesNoButton fakePlayerActionJumpContinuousToggle;
   private SettingsScreen.YesNoButton fakePlayerActionFacePlayerToggle;

   public SettingsScreen(Screen parent) {
      super(Component.translatable("screen.command-gui.settings.title"), parent);
   }

   protected void init() {
      super.init();
      int titleX = GuiTuning.getInt("SettingsScreen.TITLE_X", TITLE_X);
      int rightMargin = GuiTuning.getInt("SettingsScreen.RIGHT_MARGIN", RIGHT_MARGIN);
      int tabGap = GuiTuning.getInt("SettingsScreen.TAB_GAP", TAB_GAP);
      int tabWidth = Math.max(72, (this.width - titleX - rightMargin - (SettingsScreen.Section.values().length - 1) * tabGap) / SettingsScreen.Section.values().length);
      this.sectionButtons.clear();
      int x = titleX;

      for (int i = 0; i < SettingsScreen.Section.values().length; i++) {
         int index = i;
         DarkSelectButton button = new DarkSelectButton(x, GuiTuning.getInt("SettingsScreen.TAB_Y", TAB_Y), tabWidth, GuiTuning.getInt("SettingsScreen.TAB_HEIGHT", TAB_HEIGHT), SettingsScreen.Section.values()[i].title(), b -> this.selectSection(index));
         button.setDarkSelected(() -> this.selectedSection == index, -1);
         this.addRenderableWidget(button);
         this.sectionButtons.add(button);
         x += tabWidth + tabGap;
      }

      int contentTop = GuiTuning.getInt("SettingsScreen.CONTENT_TOP", 50);
      int scrollbarX = this.width - GuiTuning.getInt("SettingsScreen.SCROLLBAR_WIDTH", SCROLLBAR_WIDTH);
      this.contentArea = new ScreenRectangle(titleX, contentTop, scrollbarX - GuiTuning.getInt("SettingsScreen.CONTENT_TOP_GAP", CONTENT_TOP_GAP) - titleX, this.height - titleX - contentTop);
      this.buildSectionContent();
   }

   private void selectSection(int index) {
      if (index != this.selectedSection) {
         this.selectedSection = index;
         this.buildSectionContent();
      }
   }

   private void buildSectionContent() {
      for (AbstractWidget widget : this.contentWidgets) {
         this.removeWidget(widget);
      }

      this.contentWidgets.clear();
      this.settingRows.clear();
      this.contentScrollOffset = 0;
      this.contentHeight = 0;
      if (this.contentArea != null) {
         int y = this.contentArea.top() + 4;
         switch (SettingsScreen.Section.values()[this.selectedSection]) {
            case QUICK_COMMANDS:
               this.buildQuickCommandsContent(y);
               break;
            case FAKE_PLAYERS:
               this.buildFakePlayersContent(y);
               break;
            case MACHINE_SWITCHES:
         }
         this.applyContentScroll(0);
      }
   }

   private void addContentWidget(AbstractWidget widget) {
      this.addRenderableWidget(widget);
      this.contentWidgets.add(widget);
      this.contentHeight = Math.max(this.contentHeight, widget.getY() + widget.getHeight() - this.contentArea.top());
   }

   private SettingsScreen.YesNoButton addSettingRow(Component label, int y, String configKey, boolean selected) {
      int buttonWidth = GuiTuning.getInt("SettingsScreen.SETTING_BUTTON_WIDTH", SETTING_BUTTON_WIDTH);
      int buttonHeight = GuiTuning.getInt("SettingsScreen.SETTING_BUTTON_HEIGHT", SETTING_BUTTON_HEIGHT);
      List<FormattedCharSequence> lines = this.font.split(label, Math.max(24, this.contentArea.width() - buttonWidth - 36));
      int rowHeight = Math.max(GuiTuning.getInt("SettingsScreen.ROW_HEIGHT", ROW_HEIGHT), lines.size() * 12 + 16);
      if (!this.settingRows.isEmpty()) {
         SettingRow previous = this.settingRows.getLast();
         y = previous.widget().getY() - (previous.height() - previous.widget().getHeight()) / 2 + previous.height() + 8;
      }
      SettingsScreen.YesNoButton toggle = new SettingsScreen.YesNoButton(this.contentArea.right() - buttonWidth - 10,
         y + (rowHeight - buttonHeight) / 2, buttonWidth, buttonHeight, selected, configKey, label);
      this.addContentWidget(toggle);
      this.settingRows.add(new SettingsScreen.SettingRow(lines, toggle, rowHeight));
      this.contentHeight = y + rowHeight + 4 - this.contentArea.top();
      return toggle;
   }

   private void buildQuickCommandsContent(int y) {
      this.quickCommandKeepOpenToggle = this.addSettingRow(
         Component.translatable("screen.command-gui.settings.keep_open"),
         y,
         "quick_command_keep_open_default",
         SettingsConfig.getBoolean("quick_command_keep_open_default")
      );
      y += GuiTuning.getInt("SettingsScreen.ROW_HEIGHT", ROW_HEIGHT);
      this.quickCommandRememberViewToggle = this.addSettingRow(
         Component.translatable("screen.command-gui.settings.remember_view"),
         y,
         "quick_command_remember_view",
         SettingsConfig.getBoolean("quick_command_remember_view")
      );
   }

   private void buildFakePlayersContent(int y) {
      this.fakePlayerKeepOpenToggle = this.addSettingRow(
         Component.translatable("screen.command-gui.settings.keep_open"),
         y,
         "fakeplayer_keep_open_default",
         SettingsConfig.getBoolean("fakeplayer_keep_open_default")
      );
      y += GuiTuning.getInt("SettingsScreen.ROW_HEIGHT", ROW_HEIGHT);
      this.fakePlayerRememberViewToggle = this.addSettingRow(
         Component.translatable("screen.command-gui.settings.remember_fakeplayer_view"),
         y,
         "fakeplayer_remember_view",
         SettingsConfig.getBoolean("fakeplayer_remember_view")
      );
      y += GuiTuning.getInt("SettingsScreen.ROW_HEIGHT", ROW_HEIGHT);
      this.fakePlayerCustomCommandsToggle = this.addSettingRow(
         Component.translatable("screen.command-gui.settings.custom_fakeplayer_commands"),
         y,
         "fakeplayer_custom_commands_enabled",
         SettingsConfig.getBoolean("fakeplayer_custom_commands_enabled")
      );
      y += GuiTuning.getInt("SettingsScreen.ROW_HEIGHT", ROW_HEIGHT);
      this.fakePlayerActionDropToggle = this.addSettingRow(
         Component.translatable("screen.command-gui.settings.fakeplayer_action_drop"),
         y,
         "fakeplayer_action_drop",
         SettingsConfig.getBoolean("fakeplayer_action_drop")
      );
      y += GuiTuning.getInt("SettingsScreen.ROW_HEIGHT", ROW_HEIGHT);
      this.fakePlayerActionDropStackToggle = this.addSettingRow(
         Component.translatable("screen.command-gui.settings.fakeplayer_action_dropstack"),
         y,
         "fakeplayer_action_dropstack",
         SettingsConfig.getBoolean("fakeplayer_action_dropstack")
      );
      y += GuiTuning.getInt("SettingsScreen.ROW_HEIGHT", ROW_HEIGHT);
      this.fakePlayerActionSprintToggle = this.addSettingRow(
         Component.translatable("screen.command-gui.settings.fakeplayer_action_sprint"),
         y,
         "fakeplayer_action_sprint",
         SettingsConfig.getBoolean("fakeplayer_action_sprint")
      );
      y += GuiTuning.getInt("SettingsScreen.ROW_HEIGHT", ROW_HEIGHT);
      this.fakePlayerActionJumpContinuousToggle = this.addSettingRow(
         Component.translatable("screen.command-gui.settings.fakeplayer_action_jump_continuous"),
         y,
         "fakeplayer_action_jump_continuous",
         SettingsConfig.getBoolean("fakeplayer_action_jump_continuous")
      );
      y += GuiTuning.getInt("SettingsScreen.ROW_HEIGHT", ROW_HEIGHT);
      this.fakePlayerActionFacePlayerToggle = this.addSettingRow(
         Component.translatable("screen.command-gui.settings.fakeplayer_action_face_player"),
         y,
         "fakeplayer_action_face_player",
         SettingsConfig.getBoolean("fakeplayer_action_face_player")
      );
   }

   private int getMaxContentScroll() {
      if (this.contentArea == null) {
         return 0;
      }
      return Math.max(0, this.contentHeight - this.contentArea.height());
   }

   private void applyContentScroll(int newOffset) {
      int clamped = Math.max(0, Math.min(newOffset, this.getMaxContentScroll()));
      int delta = clamped - this.contentScrollOffset;
      if (delta != 0) {
         this.contentScrollOffset = clamped;

         for (AbstractWidget widget : this.contentWidgets) {
            widget.setY(widget.getY() - delta);
         }
      }
      for (AbstractWidget widget : this.contentWidgets) {
         widget.visible = widget.getY() >= this.contentArea.top() && widget.getY() + widget.getHeight() <= this.contentArea.bottom();
         widget.active = widget.visible;
      }
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      if (this.contentArea != null
         && this.getMaxContentScroll() > 0
         && mouseX >= (double)this.contentArea.left()
         && mouseX < (double)this.contentArea.right()
         && mouseY >= (double)this.contentArea.top()
         && mouseY < (double)this.contentArea.bottom()) {
         this.applyContentScroll(this.contentScrollOffset + (scrollY > 0.0 ? -24 : 24));
         return true;
      } else {
         return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
      }
   }

   public boolean mouseClicked(MouseButtonEvent mouseEvent, boolean focused) {
      if (mouseEvent.button() == 0 && this.contentArea != null && this.getMaxContentScroll() > 0) {
         ScrollbarHandle handle = new ScrollbarHandle(this.width - GuiTuning.getInt("SettingsScreen.SCROLLBAR_WIDTH", SCROLLBAR_WIDTH), this.contentArea.top(), GuiTuning.getInt("SettingsScreen.SCROLLBAR_WIDTH", SCROLLBAR_WIDTH), this.contentArea.height());
         if (handle.contains(mouseEvent.x(), mouseEvent.y())) {
            int thumbTop = handle.thumbTop(this.contentScrollOffset, this.getMaxContentScroll(), this.contentArea.height(), this.contentHeight);
            this.contentScrollbarGrabOffset = mouseEvent.y() - (double)thumbTop;
            this.draggingContentScrollbar = true;
            return true;
         }
      }

      return super.mouseClicked(mouseEvent, focused);
   }

   public boolean mouseDragged(MouseButtonEvent mouseEvent, double dragX, double dragY) {
      if (this.draggingContentScrollbar && this.contentArea != null) {
         ScrollbarHandle handle = new ScrollbarHandle(this.width - GuiTuning.getInt("SettingsScreen.SCROLLBAR_WIDTH", SCROLLBAR_WIDTH), this.contentArea.top(), GuiTuning.getInt("SettingsScreen.SCROLLBAR_WIDTH", SCROLLBAR_WIDTH), this.contentArea.height());
         this.applyContentScroll(
            handle.offsetFromY(mouseEvent.y(), this.contentScrollbarGrabOffset, this.getMaxContentScroll(), this.contentArea.height(), this.contentHeight)
         );
         return true;
      } else {
         return super.mouseDragged(mouseEvent, dragX, dragY);
      }
   }

   public boolean mouseReleased(MouseButtonEvent mouseEvent) {
      this.draggingContentScrollbar = false;
      return super.mouseReleased(mouseEvent);
   }

   @Override
   public void onClose() {
      // 回到「来处」，而不是硬编码主界面：
      //   游戏内从主界面进来 -> parent 是那个 CommandGUIScreen 实例，回去还能保留其标签/滚动状态；
      //   模组菜单进来       -> parent 是模组列表，正常退回。
      // 原先无条件 new CommandGUIScreen()，导致在标题界面按 ESC 会跳进需要联机的界面。
      this.minecraft.gui.setScreen(this.parent);
   }

   @Override
   public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
      super.extractBackground(g, mouseX, mouseY, partialTick);
      if (this.contentArea == null) return;
      g.enableScissor(this.contentArea.left(), this.contentArea.top(), this.contentArea.right(), this.contentArea.bottom());
      for (SettingRow row : this.settingRows) {
         int top = row.widget().getY() - (row.height() - row.widget().getHeight()) / 2;
         GuiTheme.panel(g, this.contentArea.left(), top, this.contentArea.width(), row.height());
      }
      g.disableScissor();
   }

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      String version = FabricLoader.getInstance().getModContainer("command-gui").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
      guiGraphics.text(this.font, this.title, GuiTuning.getInt("SettingsScreen.TITLE_X", TITLE_X), GuiTuning.getInt("SettingsScreen.TITLE_Y", TITLE_Y), GuiTheme.text(), false);
      guiGraphics.text(this.font, version, this.width - 16 - this.font.width(version), GuiTuning.getInt("SettingsScreen.TITLE_Y", TITLE_Y), GuiTheme.muted(), false);
      if (this.contentArea != null) {
         if (this.getMaxContentScroll() > 0) {
            ScrollbarHandle scrollbar = new ScrollbarHandle(this.width - GuiTuning.getInt("SettingsScreen.SCROLLBAR_WIDTH", SCROLLBAR_WIDTH), this.contentArea.top(), GuiTuning.getInt("SettingsScreen.SCROLLBAR_WIDTH", SCROLLBAR_WIDTH), this.contentArea.height());
            scrollbar.render(
               guiGraphics,
               this.contentScrollOffset,
               this.getMaxContentScroll(),
               this.contentArea.height(),
               Math.max(this.contentHeight, this.contentArea.height()),
               scrollbar.contains((double)mouseX, (double)mouseY)
            );
         }

         guiGraphics.enableScissor(this.contentArea.left(), this.contentArea.top(), this.contentArea.right(), this.contentArea.bottom());
         for (SettingsScreen.SettingRow row : this.settingRows) {
            AbstractWidget widget = row.widget();
            int labelY = widget.getY() + (widget.getHeight() - row.lines().size() * 12) / 2 + 2;
            for (FormattedCharSequence line : row.lines()) {
               guiGraphics.text(this.font, line, this.contentArea.left() + 10, labelY, GuiTheme.text(), false);
               labelY += 12;
            }
         }
         guiGraphics.disableScissor();

         if (this.contentWidgets.isEmpty()) {
            guiGraphics.centeredText(
               this.font,
               Component.translatable("screen.command-gui.settings.empty"),
               this.contentArea.left() + this.contentArea.width() / 2,
               this.contentArea.top() + this.contentArea.height() / 2 - 4,
               -8355712
            );
         }
      }
   }

   private static enum Section {
      QUICK_COMMANDS("screen.command-gui.settings.section.quick_commands"),
      FAKE_PLAYERS("screen.command-gui.settings.section.fake_players"),
      MACHINE_SWITCHES("screen.command-gui.settings.section.machine_switches");

      private final String titleKey;

      private Section(String titleKey) {
         this.titleKey = titleKey;
      }

      Component title() {
         return Component.translatable(this.titleKey);
      }
   }

   private static record SettingRow(List<FormattedCharSequence> lines, AbstractWidget widget, int height) {
   }

   private final class YesNoButton extends Button {
      private boolean selected;
      private final String configKey;
      private final Component label;

      YesNoButton(int x, int y, int width, int height, boolean selected, String configKey, Component label) {
         super(x, y, width, height, Component.empty(), b -> {
         }, DEFAULT_NARRATION);
         this.selected = selected;
         this.configKey = configKey;
         this.label = label;
         this.updateMessage();
         this.setTooltip(Tooltip.create(label));
      }

      private void updateMessage() {
         this.setMessage(this.label.copy().append(": ").append(Component.translatable(this.selected ? "screen.command-gui.settings.yes" : "screen.command-gui.settings.no")));
      }

      public void onPress(InputWithModifiers input) {
         this.selected = !this.selected;
         this.updateMessage();
         SettingsConfig.setBoolean(this.configKey, this.selected);
         SettingsConfig.save();
      }

      protected void extractContents(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
         int color = this.selected ? GuiTheme.accent() : GuiTheme.muted();
         Component text = Component.translatable(this.selected ? "screen.command-gui.settings.yes" : "screen.command-gui.settings.no");
         Font font = Minecraft.getInstance().font;
         int switchX = this.getX() + this.getWidth() - 28;
         int switchY = this.getY() + (this.getHeight() - 14) / 2;
         GuiTheme.rounded(guiGraphics, switchX, switchY, 28, 14, 7, this.selected ? GuiTheme.selected() : GuiTheme.surface());
         GuiTheme.rounded(guiGraphics, switchX + (this.selected ? 16 : 2), switchY + 2, 10, 10, 5, color);
         guiGraphics.text(font, text, this.getX() + 4, this.getY() + (this.getHeight() - 9) / 2, color, false);
         if (this.isHovered() || this.isFocused()) GuiTheme.outline(guiGraphics, this.getX(), this.getY(), this.getWidth(), this.getHeight(), GuiTheme.accent());
      }
   }
}
