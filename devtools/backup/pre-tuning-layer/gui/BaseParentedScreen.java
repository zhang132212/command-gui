package com.remrin.client.gui;

import com.mojang.blaze3d.platform.Window;
import com.remrin.client.machine.MachineDebug;
import net.minecraft.client.gui.screens.Screen;
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

   public void onClose() {
      this.minecraft.gui.setScreen(this.parent);
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
