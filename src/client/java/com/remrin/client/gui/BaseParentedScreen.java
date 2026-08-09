package com.remrin.client.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Base screen class with a parent screen. Automatically returns to the parent screen on close, and
 * does not pause the game (to avoid pausing the world in singleplayer).
 * <p>
 * Child screens lay out entirely inside {@link #init()}, so when the window changes size they must
 * be rebuilt through the default resize pipeline. F11 fullscreen toggles do not always fire a GLFW
 * framebuffer-resize callback, so every tick the live window size is polled and the resize
 * pipeline is re-run whenever the cached size lags behind (see {@link #syncWindowSize()}).
 */
public abstract class BaseParentedScreen<P extends Screen> extends Screen {

  protected final P parent;

  protected BaseParentedScreen(Component title, P parent) {
    super(title);
    this.parent = parent;
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  @Override
  public void onClose() {
    this.minecraft.gui.setScreen(parent);
  }

  /**
   * Relays the resize to the parent screen first, so a fullscreen/windowed toggle while this child
   * screen is open also relayouts the parent (returning to it afterwards must not show stale
   * fullscreen coordinates). Same pattern as malilib's {@code GuiBase.resize}.
   */
  @Override
  public void resize(int width, int height) {
    if (this.parent != null) {
      this.parent.resize(width, height);
    }
    super.resize(width, height);
  }

  /**
   * Polls the LIVE window (framebuffer) size every tick and repairs the layout when the
   * framebuffer-resize callback was skipped — F11 fullscreen toggles do not always fire it on
   * Windows, so the cached size (and therefore this screen's width/height) would keep the old
   * fullscreen values and every widget would stay at fullscreen coordinates after switching to a
   * windowed mode. When the cached size lags behind the real window, the cached size is updated
   * and Minecraft's resize pipeline is re-run, which rebuilds this screen via
   * {@code rebuildWidgets()} (clear widgets + {@code init()}).
   */
  protected void syncWindowSize() {
    if (this.minecraft == null || this.minecraft.getWindow() == null) {
      return;
    }
    com.mojang.blaze3d.platform.Window window = this.minecraft.getWindow();
    int[] fbW = new int[1];
    int[] fbH = new int[1];
    org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(window.handle(), fbW, fbH);
    if (fbW[0] <= 0 || fbH[0] <= 0) {
      return;
    }
    if (fbW[0] != window.getWidth() || fbH[0] != window.getHeight()) {
      com.remrin.client.machine.MachineDebug.log("[Layout] syncWindowSize framebuffer lag: cached "
          + window.getWidth() + "x" + window.getHeight() + " live " + fbW[0] + "x" + fbH[0]
          + " screen " + this.width + "x" + this.height);
      window.setWidth(fbW[0]);
      window.setHeight(fbH[0]);
      this.minecraft.resizeGui();
      return;
    }
    if (this.width != window.getGuiScaledWidth() || this.height != window.getGuiScaledHeight()) {
      com.remrin.client.machine.MachineDebug.log("[Layout] syncWindowSize logical lag: screen "
          + this.width + "x" + this.height + " scaled " + window.getGuiScaledWidth() + "x"
          + window.getGuiScaledHeight());
      this.minecraft.resizeGui();
    }
  }

  @Override
  public void tick() {
    super.tick();
    syncWindowSize();
  }
}
