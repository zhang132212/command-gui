package qa;

import com.remrin.client.gui.CommandGUIScreen;
import com.remrin.client.gui.SettingsScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.screens.TitleScreen;

import java.util.List;

/** 视觉验收：在标题界面直接打开 Command-GUI 逐页截图，截完自动退出。 */
public final class GuiThemeQa implements ClientModInitializer {
   private int stage, ticks;
   private CommandGUIScreen screen;

   @Override
   public void onInitializeClient() {
      ClientTickEvents.END_CLIENT_TICK.register(mc -> {
         try {
            if (++ticks > 2400) throw new AssertionError("timeout stage=" + stage + " screen=" + mc.gui.screen());
            if (mc.gui.overlay() != null) return;
            switch (stage) {
               case 0 -> {
                  if (mc.gui.screen() instanceof TitleScreen && ticks > 40) {
                     screen = new CommandGUIScreen();
                     mc.gui.setScreen(screen);
                     next();
                  }
               }
               case 1 -> { if (ticks > 25) { shot(mc, "theme-1-custom"); next(); } }
               case 2 -> { if (ticks > 15) { selectTab(1); next(); } }
               case 3 -> { if (ticks > 15) { shot(mc, "theme-2-fakeplayer"); selectTab(2); next(); } }
               case 4 -> { if (ticks > 15) { shot(mc, "theme-3-machine"); selectTab(3); next(); } }
               case 5 -> { if (ticks > 15) { shot(mc, "theme-4-preset"); mc.gui.setScreen(new SettingsScreen(screen)); next(); } }
               case 6 -> { if (ticks > 30) { shot(mc, "theme-5-settings"); next(); } }
               case 7 -> { if (ticks > 5) { hoverFirstButton(mc); next(); } }
               case 8 -> { if (ticks > 8) { shot(mc, "theme-6-hover"); next(); } }
               case 9 -> {
                  if (ticks > 10) {
                     System.out.println("GUI_THEME_QA_COMPLETE dir=" + mc.gameDirectory);
                     mc.stop();
                     next();
                  }
               }
               default -> { }
            }
         } catch (Throwable error) {
            error.printStackTrace();
            System.out.println("GUI_THEME_QA_FAILED stage=" + stage + " screen=" + mc.gui.screen());
            mc.stop();
            stage = 99;
         }
      });
   }

   private void selectTab(int index) throws Exception {
      TabNavigationBar bar = (TabNavigationBar)field(screen, "tabNavigationBar");
      List<Tab> tabs = bar.getTabs();
      System.out.println("GUI_THEME_QA_TABS " + tabs.size());
      if (index < tabs.size()) {
         bar.selectTab(index, false);
         screen.tick();
      }
   }

   /** 把真实光标移到某个按钮上，验证 hover 高光（GLFW 窗口坐标 = GUI 坐标 x 缩放）。 */
   private void hoverFirstButton(Minecraft mc) {
      var button = mc.gui.screen().children().stream()
         .filter(c -> c instanceof net.minecraft.client.gui.components.Button)
         .map(c -> (net.minecraft.client.gui.components.AbstractWidget)c)
         .filter(w -> w.getWidth() >= 40 && w.getWidth() <= 120)
         .findFirst().orElse(null);
      if (button == null) return;
      double scale = mc.getWindow().getGuiScale();
      double x = (button.getX() + button.getWidth() / 2.0) * scale;
      double y = (button.getY() + button.getHeight() / 2.0) * scale;
      org.lwjgl.glfw.GLFW.glfwSetCursorPos(mc.getWindow().handle(), x, y);
      System.out.println("GUI_THEME_QA_HOVER " + x + "," + y + " over " + button.getMessage().getString());
   }

   private static Object field(Object target, String name) throws Exception {
      var field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return field.get(target);
   }

   private void shot(Minecraft mc, String name) {
      Screenshot.grab(mc.gameDirectory, name + ".png", mc.gameRenderer.mainRenderTarget(), 1, message -> { });
      System.out.println("GUI_THEME_QA_SHOT " + name);
   }

   private void next() { stage++; ticks = 0; }
}
