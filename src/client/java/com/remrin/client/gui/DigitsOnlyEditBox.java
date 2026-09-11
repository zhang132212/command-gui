package com.remrin.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

public class DigitsOnlyEditBox extends GuiEditBox {
   public DigitsOnlyEditBox(Font font, int x, int y, int width, int height, Component message) {
      super(font, x, y, width, height, message);
   }

   public void insertText(String text) {
      if (text == null) {
         return;
      }
      super.insertText(text.replaceAll("\\D", ""));
   }
}
