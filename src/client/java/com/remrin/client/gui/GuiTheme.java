package com.remrin.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.locale.Language;

/**
 * 客户端绘制层：统一的「液态玻璃」外观。
 *
 * <p>面板和控件不再是黑色不透明色块，而是「半透明底板 + 1px 边缘高光 + 顶部光泽」，
 * 配合原版菜单模糊（{@code Options.getMenuBackgroundBlurriness()}）形成磨砂玻璃质感。
 * 颜色与圆角都可用 {@code config/command-gui/gui-tuning.json} 覆盖。</p>
 *
 * <p>绘制成本：{@link #rounded} 每个圆角行一次 fill。大面板半径大但数量少；
 * 列表行和按钮半径小、数量受视口限制，不会随内容量线性增长。</p>
 */
public final class GuiTheme {
   private GuiTheme() {}

   // ------------------------------------------------------------------
   // 调色板（键名保留在 GuiTuning 中，可在 gui-tuning.json 覆盖）
   // ------------------------------------------------------------------

   /** 不透明回退底色：只在拿不到世界背景时使用。 */
   public static int background() { return GuiTuning.getColor("GuiTheme.BACKGROUND", 0xE0070A0F); }
   /** 背景压暗层（上浅下深），保证玻璃面板与文字的对比度。 */
   public static int scrimTop() { return GuiTuning.getColor("GuiTheme.SCRIM_TOP", 0x2E050A12); }
   public static int scrimBottom() { return GuiTuning.getColor("GuiTheme.SCRIM_BOTTOM", 0x66050A12); }
   /** 玻璃卡片底板。 */
   public static int panel() { return GuiTuning.getColor("GuiTheme.PANEL", 0x4D16202E); }
   /** 更实的玻璃：悬浮提示、弹窗等需要看清文字的地方。 */
   public static int panelStrong() { return GuiTuning.getColor("GuiTheme.PANEL_STRONG", 0x8C151E2C); }
   /** 控件底板。 */
   public static int surface() { return GuiTuning.getColor("GuiTheme.SURFACE", 0x4D232E3D); }
   public static int hover() { return GuiTuning.getColor("GuiTheme.HOVER", 0x6B30404F); }
   public static int selected() { return GuiTuning.getColor("GuiTheme.SELECTED", 0x3D8DE0CC); }
   /** 边缘高光（玻璃的「棱」）。 */
   public static int border() { return GuiTuning.getColor("GuiTheme.BORDER", 0x3AFFFFFF); }
   /** 底边暗线，制造厚度。 */
   public static int borderDark() { return GuiTuning.getColor("GuiTheme.BORDER_DARK", 0x33000000); }
   /** 顶部光泽。 */
   public static int sheen() { return GuiTuning.getColor("GuiTheme.SHEEN", 0x1FFFFFFF); }
   public static int shadow() { return GuiTuning.getColor("GuiTheme.SHADOW", 0x4D000000); }
   public static int accent() { return GuiTuning.getColor("GuiTheme.ACCENT", 0xFF8DE0CC); }
   public static int accentSoft() { return GuiTuning.getColor("GuiTheme.ACCENT_SOFT", 0x3D8DE0CC); }
   public static int text() { return GuiTuning.getColor("GuiTheme.TEXT", 0xFFECF4FA); }
   public static int muted() { return GuiTuning.getColor("GuiTheme.MUTED", 0xB8A7B9CB); }
   public static int disabled() { return GuiTuning.getColor("GuiTheme.DISABLED", 0x8A76869A); }
   public static int danger() { return GuiTuning.getColor("GuiTheme.DANGER", 0xFFFF9BA3); }
   public static int warning() { return GuiTuning.getColor("GuiTheme.WARNING", 0xFFF2CE8B); }

   /** 控件圆角。 */
   public static int radius() { return GuiTuning.getInt("GuiTheme.RADIUS", 6); }
   /** 大面板圆角。 */
   public static int panelRadius() { return GuiTuning.getInt("GuiTheme.PANEL_RADIUS", 9); }
   /** 1 = 即使原版模糊设为关闭也强制磨砂（液态玻璃观感依赖它）。 */
   public static int blurEnabled() { return GuiTuning.getInt("GuiTheme.BLUR_ENABLED", 1); }

   /** 半径不能超过短边的一半，否则圆角会互相吃掉。 */
   public static int clampRadius(int radius, int width, int height) {
      if (width <= 0 || height <= 0) return 0;
      return Math.max(0, Math.min(radius, Math.min(width, height) / 2));
   }

   /** 线性混色：用于从强调色推导玻璃色泽。 */
   public static int mix(int from, int to, float t) {
      float k = Math.max(0.0F, Math.min(1.0F, t));
      int a = (int)(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * k);
      int r = (int)(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * k);
      int g = (int)(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * k);
      int b = (int)((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * k);
      return (a << 24) | (r << 16) | (g << 8) | b;
   }

   /** 只改透明度。 */
   public static int alpha(int color, int alpha) {
      return ((Math.max(0, Math.min(255, alpha)) & 0xFF) << 24) | (color & 0x00FFFFFF);
   }

   // ------------------------------------------------------------------
   // 基础图元
   // ------------------------------------------------------------------

   public static void outline(GuiGraphicsExtractor g, int x, int y, int width, int height, int color) {
      if (width <= 0 || height <= 0) return;
      g.fill(x, y, x + width, y + 1, color);
      g.fill(x, y + height - 1, x + width, y + height, color);
      g.fill(x, y, x + 1, y + height, color);
      g.fill(x + width - 1, y, x + width, y + height, color);
   }

   /** 圆角矩形：中间一条整行 + 四角逐行内缩，任意 GUI 缩放下都保持清晰。 */
   public static void rounded(GuiGraphicsExtractor g, int x, int y, int width, int height, int radius, int color) {
      if (width <= 0 || height <= 0) return;
      int r = clampRadius(radius, width, height);
      if (r <= 0) {
         g.fill(x, y, x + width, y + height, color);
         return;
      }
      g.fill(x, y + r, x + width, y + height - r, color);
      for (int row = 0; row < r; row++) {
         double dy = r - row - 0.5;
         int inset = (int)Math.ceil(r - Math.sqrt(Math.max(0.0, (double)r * r - dy * dy)));
         g.fill(x + inset, y + row, x + width - inset, y + row + 1, color);
         g.fill(x + inset, y + height - row - 1, x + width - inset, y + height - row, color);
      }
   }

   // ------------------------------------------------------------------
   // 玻璃组件
   // ------------------------------------------------------------------

   /** 玻璃卡片：投影 + 亮边 + 半透明底板 + 顶部光泽。 */
   public static void panel(GuiGraphicsExtractor g, int x, int y, int width, int height) {
      panel(g, x, y, width, height, panelRadius());
   }

   public static void panel(GuiGraphicsExtractor g, int x, int y, int width, int height, int radius) {
      if (width <= 0 || height <= 0) return;
      int r = clampRadius(radius, width, height);
      if (height > 3) {
         rounded(g, x, y + 2, width, height - 2, r, shadow());
      }
      glass(g, x, y, width, height, r, panel(), border());
   }

   /** 悬浮提示 / 弹窗：比普通卡片更实，保证文字可读。 */
   public static void popup(GuiGraphicsExtractor g, int x, int y, int width, int height) {
      if (width <= 0 || height <= 0) return;
      int r = clampRadius(Math.min(radius(), 5), width, height);
      if (height > 3) {
         rounded(g, x, y + 2, width, height - 2, r, shadow());
      }
      glass(g, x, y, width, height, r, panelStrong(), mix(border(), 0xFFFFFFFF, 0.25F));
   }

   /**
    * 玻璃底座：先铺亮边再铺底板，得到 1px 棱边；再叠顶部光泽和底边暗线。
    * 这是所有液态玻璃控件的公共部分。
    */
   private static void glass(GuiGraphicsExtractor g, int x, int y, int width, int height, int r, int base, int rim) {
      rounded(g, x, y, width, height, r, rim);
      if (width > 2 && height > 2) {
         rounded(g, x + 1, y + 1, width - 2, height - 2, Math.max(0, r - 1), base);
      }
      if (height < 7 || width <= 2 * r + 2) return;
      // 底板太透时不再铺光泽：否则光泽会盖过底板，看起来像一条突兀的亮带（例如禁用按钮）
      if (((base >>> 24) & 0xFF) >= 0x40) {
         g.fillGradient(x + r + 1, y + 1, x + width - r - 1, y + Math.max(3, height / 2), sheen(), 0x00FFFFFF);
      }
      g.fill(x + r, y, x + width - r, y + 1, mix(rim, 0xFFFFFFFF, 0.45F));
      g.fill(x + r, y + height - 1, x + width - r, y + height, borderDark());
   }

   /** 列表行：玻璃底 + 悬停 / 选中态，不做投影，避免长列表开销。 */
   public static void row(GuiGraphicsExtractor g, int x, int y, int width, int height, boolean selected, boolean hovered) {
      int base = selected ? selected() : hovered ? hover() : panel();
      int rim = selected ? mix(accent(), 0xFFFFFFFF, 0.2F)
         : hovered ? border() : alpha(border(), Math.max(0x12, (border() >>> 24) / 2));
      glass(g, x, y, width, height, clampRadius(radius(), width, height), base, rim);
   }

   /** 按钮：胶囊圆角 + 玻璃底 + 悬停光泽；选中态用强调色染色并加外发光。 */
   public static void button(GuiGraphicsExtractor g, AbstractWidget widget, boolean selected, boolean enabled) {
      button(g, widget, selected, enabled, Integer.MIN_VALUE, Integer.MIN_VALUE);
   }

   public static void button(GuiGraphicsExtractor g, AbstractWidget widget, boolean selected, boolean enabled, int mouseX, int mouseY) {
      int x = widget.getX();
      int y = widget.getY();
      int w = widget.getWidth();
      int h = widget.getHeight();
      if (w <= 0 || h <= 0) return;

      boolean highlighted = enabled && (widget.isHovered() || widget.isFocused());
      boolean primary = selected && enabled;
      int r = clampRadius(radius() + (h <= 22 ? 2 : 1), w, h);

      int base = !enabled ? alpha(surface(), 0x33)
         : primary ? mix(surface(), accent(), 0.45F)
         : highlighted ? hover() : surface();
      int rim = !enabled ? alpha(border(), 0x14)
         : primary ? mix(accent(), 0xFFFFFFFF, 0.25F)
         : highlighted ? mix(border(), 0xFFFFFFFF, 0.35F) : border();

      if (h > 3) {
         rounded(g, x, y + 1, w, h - 1, r, shadow());
      }
      if (primary) {
         rounded(g, x - 1, y - 1, w + 2, h + 2, clampRadius(r + 1, w + 2, h + 2), alpha(accent(), 0x2E));
      }
      glass(g, x, y, w, h, r, base, rim);

      if (highlighted && h >= 8 && mouseX != Integer.MIN_VALUE && mouseX >= x && mouseX <= x + w) {
         specular(g, x, y, w, h, r, mouseX);
      }
   }

   /** 跟随光标的柔光带：hover 时玻璃表面被「照亮」一小条，制造流动感。 */
   private static void specular(GuiGraphicsExtractor g, int x, int y, int width, int height, int radius, int mouseX) {
      int band = Math.max(12, height);
      int x0 = Math.max(x + radius, mouseX - band);
      int x1 = Math.min(x + width - radius, mouseX + band);
      if (x1 <= x0) return;
      g.fillGradient(x0, y + 1, x1, y + height - 1, 0x14FFFFFF, 0x00FFFFFF);
   }

   /** 扁平分隔线：高光 + 暗线两层，避免硬边。 */
   public static void divider(GuiGraphicsExtractor g, int x, int y, int width) {
      g.fill(x, y, x + width, y + 1, alpha(borderDark(), 0x40));
      g.fill(x, y + 1, x + width, y + 2, alpha(border(), 0x14));
   }

   /** 截断标签但保留 Component 样式与完整朗读 / 提示文本。 */
   public static void label(GuiGraphicsExtractor g, AbstractWidget widget, Component label, int color, boolean centered) {
      Font font = Minecraft.getInstance().font;
      int inset = Math.min(6, Math.max(1, widget.getWidth() / 6));
      int maxWidth = Math.max(0, widget.getWidth() - inset * 2);
      int y = widget.getY() + (widget.getHeight() - 9) / 2;
      if (font.width(label) <= maxWidth) {
         int x = centered ? widget.getX() + (widget.getWidth() - font.width(label)) / 2 : widget.getX() + inset;
         g.text(font, label, x, y, color, false);
      } else {
         int dots = font.width("…");
         g.text(font, Language.getInstance().getVisualOrder(font.substrByWidth(label, Math.max(0, maxWidth - dots))), widget.getX() + inset, y, color, false);
         if (maxWidth >= dots) g.text(font, "…", widget.getX() + widget.getWidth() - inset - dots, y, color, false);
      }
   }

   // ------------------------------------------------------------------
   // 屏幕背景
   // ------------------------------------------------------------------

   /**
    * 磨砂玻璃底板：世界先被模糊，再压一层上浅下深的暗色，顶上留一条 1px 高光模拟环境光。
    * 调用方应先让原版画完背景（全景图 / 菜单底纹 / 字幕），本方法只负责玻璃层。
    */
   public static void screenBackground(GuiGraphicsExtractor g, int width, int height) {
      Minecraft minecraft = Minecraft.getInstance();
      int blurriness = minecraft.options == null ? 0 : minecraft.options.getMenuBackgroundBlurriness();
      // 原版只在模糊度 >= 1 时调用 blurBeforeThisStratum，这里补上「用户关掉模糊」的情况，
      // 保证液态玻璃始终有磨砂层。每帧只能模糊一次，所以这两个分支互斥。
      if (blurEnabled() != 0 && blurriness < 1) {
         g.blurBeforeThisStratum();
      }
      if (width <= 0 || height <= 0) return;
      g.fillGradient(0, 0, width, height, scrimTop(), scrimBottom());
      g.fill(0, 0, width, 1, mix(border(), 0xFFFFFFFF, 0.25F));
   }

   /** 内页背景与主界面共用同一套玻璃底板。 */
   public static void editorBackground(GuiGraphicsExtractor g, int width, int height) {
      screenBackground(g, width, height);
   }
}
