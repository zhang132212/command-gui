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

public class SettingsScreen extends BaseParentedScreen<Screen> {
   private static final int TITLE_X = 10;
   private static final int TITLE_Y = 6;
   private static final int TAB_Y = 22;
   private static final int TAB_HEIGHT = 20;
   private static final int TAB_GAP = 8;
   private static final int RIGHT_MARGIN = 16;
   private static final int SCROLLBAR_WIDTH = 12;
   private static final int CONTENT_SCROLL_STEP = 24;
   private static final int ROW_HEIGHT = 24;
   private static final int SETTING_BUTTON_WIDTH = 80;
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
      }
   }

   private void addContentWidget(AbstractWidget widget) {
      this.addRenderableWidget(widget);
      this.contentWidgets.add(widget);
      this.contentHeight = Math.max(this.contentHeight, widget.getY() + widget.getHeight() - this.contentArea.top());
   }

   private SettingsScreen.YesNoButton addSettingRow(Component label, int y, String configKey, boolean selected) {
      SettingsScreen.YesNoButton toggle = new SettingsScreen.YesNoButton(this.contentArea.right() - GuiTuning.getInt("SettingsScreen.SETTING_BUTTON_WIDTH", SETTING_BUTTON_WIDTH), y, GuiTuning.getInt("SettingsScreen.SETTING_BUTTON_WIDTH", SETTING_BUTTON_WIDTH), GuiTuning.getInt("SettingsScreen.SETTING_BUTTON_HEIGHT", SETTING_BUTTON_HEIGHT), selected, configKey);
      this.addContentWidget(toggle);
      this.settingRows.add(new SettingsScreen.SettingRow(label, toggle));
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

   public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
      String version = FabricLoader.getInstance().getModContainer("command-gui").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
      String titleText = this.title.getString() + " - " + version;
      guiGraphics.text(this.font, Component.literal(titleText), GuiTuning.getInt("SettingsScreen.TITLE_X", TITLE_X), GuiTuning.getInt("SettingsScreen.TITLE_Y", TITLE_Y), -1);
      guiGraphics.text(this.font, Component.literal("by Remrin；zhang"), GuiTuning.getInt("SettingsScreen.TITLE_X", TITLE_X) + this.font.width(titleText) + 6, GuiTuning.getInt("SettingsScreen.TITLE_Y", TITLE_Y), -10179);
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

         for (SettingsScreen.SettingRow row : this.settingRows) {
            AbstractWidget widget = row.widget();
            guiGraphics.text(this.font, row.label(), this.contentArea.left() + 2, widget.getY() + (widget.getHeight() - 9) / 2, -1);
         }

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

   private static record SettingRow(Component label, AbstractWidget widget) {
   }

   private final class YesNoButton extends Button {
      private boolean selected;
      private final String configKey;

      YesNoButton(int x, int y, int width, int height, boolean selected, String configKey) {
         super(x, y, width, height, Component.empty(), b -> {
         }, DEFAULT_NARRATION);
         this.selected = selected;
         this.configKey = configKey;
      }

      public void onPress(InputWithModifiers input) {
         this.selected = !this.selected;
         SettingsConfig.setBoolean(this.configKey, this.selected);
         SettingsConfig.save();
      }

      protected void extractContents(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
         this.extractDefaultSprite(guiGraphics);
         int color = this.selected ? -11141291 : -3145728;
         Component text = Component.translatable(this.selected ? "screen.command-gui.settings.yes" : "screen.command-gui.settings.no");
         Font font = Minecraft.getInstance().font;
         guiGraphics.centeredText(font, text, this.getX() + this.getWidth() / 2, this.getY() + (this.getHeight() - 8) / 2, color);
      }
   }
}
