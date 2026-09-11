package com.remrin.client.gui;

import com.mojang.blaze3d.platform.Window;
import com.remrin.client.machine.MachineDebug;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public abstract class BaseParentedScreen<P extends Screen> extends Screen {
   protected final P parent;

   protected BaseParentedScreen(Component title, P parent) {
      super(title);
      this.parent = parent;
   }

   public boolean isPauseScreen() {
      return false;
   }

   @Override
   public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
      super.extractBackground(g, mouseX, mouseY, partialTick);
      GuiTheme.editorBackground(g, this.width, this.height);
   }

   public void onClose() {
      this.minecraft.gui.setScreen(this.parent);
   }

   /**
    * 玩家死亡时 Minecraft 用 setScreen 把当前界面强制切到死亡界面，旧屏幕只走 removed()
    * （Gui.setScreen 只调 Screen.removed，不调 onClose），编辑锁的释放（onClose）不会执行，
    * 会造成服务端编辑锁滞留到 TTL。这里在 removed() 里检测"玩家已死亡/濒死"，沿 parent 链
    * 冒泡到所属 MachineEditorScreen 释放机器编辑锁（死亡即永久离开编辑器，与子屏/父屏导航不同，
    * 导航时玩家存活不会触发）。
    */
   @Override
   public void removed() {
      super.removed();
      if (this.minecraft != null && this.minecraft.player != null && this.minecraft.player.isDeadOrDying()) {
         Screen screen = this;
         while (screen instanceof BaseParentedScreen<?> bp) {
            if (bp instanceof MachineEditorScreen editor) {
               editor.releaseEditLock();
               return;
            }
            screen = bp.parent;
         }
      }
   }

   public void resize(int width, int height) {
      if (this.parent != null) {
         this.parent.resize(width, height);
      }

      super.resize(width, height);
   }

   protected void syncWindowSize() {
      if (this.minecraft != null && this.minecraft.getWindow() != null) {
         Window window = this.minecraft.getWindow();
         int[] fbW = new int[1];
         int[] fbH = new int[1];
         GLFW.glfwGetFramebufferSize(window.handle(), fbW, fbH);
         if (fbW[0] > 0 && fbH[0] > 0) {
            if (fbW[0] == window.getWidth() && fbH[0] == window.getHeight()) {
               if (this.width != window.getGuiScaledWidth() || this.height != window.getGuiScaledHeight()) {
                  MachineDebug.log(
                     "[Layout] syncWindowSize logical lag: screen "
                        + this.width
                        + "x"
                        + this.height
                        + " scaled "
                        + window.getGuiScaledWidth()
                        + "x"
                        + window.getGuiScaledHeight()
                  );
                  this.minecraft.resizeGui();
               }
            } else {
               MachineDebug.log(
                  "[Layout] syncWindowSize framebuffer lag: cached "
                     + window.getWidth()
                     + "x"
                     + window.getHeight()
                     + " live "
                     + fbW[0]
                     + "x"
                     + fbH[0]
                     + " screen "
                     + this.width
                     + "x"
                     + this.height
               );
               window.setWidth(fbW[0]);
               window.setHeight(fbH[0]);
               this.minecraft.resizeGui();
            }
         }
      }
   }

   public void tick() {
      super.tick();
      this.syncWindowSize();
   }
}
