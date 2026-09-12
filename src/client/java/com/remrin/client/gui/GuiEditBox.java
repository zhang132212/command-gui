package com.remrin.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * 保留原版文本编辑、选区、补全与朗读能力，只把外框换成与主界面搜索框一致的专用输入框图案
 * （圆角底板 + 细描边 + 焦点强调色 + 轻阴影），因此各内页的输入框外观与 GuiSearchBox 统一。
 */
public class GuiEditBox extends EditBox {
   /** 与 GuiSearchBox 相同的底板色，可用 config/command-gui/gui-tuning.json 的 GuiEditBox.BACKGROUND 覆盖。 */
   private static final int BACKGROUND = 0xFF1D2935;
   private static final int SHADOW = 0x50070B11;
   private boolean extractingText;
   private Component hint;
   private final Font font;

   public GuiEditBox(Font font, int x, int y, int width, int height, Component message) {
      super(font, x, y, width, height, message);
      this.font = font;
      this.setTextColor(GuiTheme.text());
      this.setTextColorUneditable(GuiTheme.disabled());
      this.setTextShadow(false);
   }

   @Override
   public void setHint(Component hint) {
      super.setHint(hint);
      this.hint = hint;
   }

   @Override
   public boolean isBordered() {
      // 只屏蔽原版贴图，保留原版 bordered 字段所控制的文字位置和点击定位。
      return !this.extractingText && super.isBordered();
   }

   @Override
   public int getInnerWidth() {
      // 原版通过 isBordered() 计算宽度；绘制贴图的开关不能改变文本布局。
      return Math.max(0, this.getWidth() - (super.isBordered() ? 8 : 0));
   }

   @Override
   public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
      if (!this.visible) return;
      if (!super.isBordered()) {
         super.extractWidgetRenderState(g, mouseX, mouseY, partialTick);
         return;
      }

      int x = this.getX();
      int y = this.getY();
      int width = Math.max(1, this.getWidth());
      int height = Math.max(1, this.getHeight());
      int stroke = this.isFocused() && this.active ? GuiTheme.accent()
         : this.isHovered() && this.active ? GuiTheme.muted() : GuiTheme.border();
      GuiTheme.rounded(g, x, y + 1, width, height, 6, SHADOW);
      GuiTheme.rounded(g, x, y, width, height, 6, stroke);
      GuiTheme.rounded(g, x + 1, y + 1, Math.max(1, width - 2), Math.max(1, height - 2), 5,
         GuiTuning.getColor("GuiEditBox.BACKGROUND", BACKGROUND));

      // 原版 hint 只在未聚焦时绘制，且固定 DARK_GRAY（深灰在深色底板上几乎看不见）。
      // 这里屏蔽原版那次绘制，改由我们按主题色在两种状态下统一绘制（与 GuiSearchBox 一致）。
      boolean showHint = this.getValue().isEmpty() && this.hint != null && !this.hint.getString().isEmpty();
      if (showHint) {
         super.setHint(Component.empty());
      }

      g.enableScissor(x + 1, y + 1, x + width - 1, y + height - 1);
      try {
         if (showHint) {
            g.text(this.font, this.font.plainSubstrByWidth(this.hint.getString(), this.getInnerWidth()),
               x + 4, y + (height - 8) / 2, GuiTheme.muted(), false);
         }
         this.extractingText = true;
         super.extractWidgetRenderState(g, mouseX, mouseY, partialTick);
      } finally {
         this.extractingText = false;
         g.disableScissor();
         if (showHint) {
            super.setHint(this.hint);
         }
      }
   }
}
