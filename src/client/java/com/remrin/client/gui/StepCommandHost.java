package com.remrin.client.gui;

import java.util.List;

public interface StepCommandHost {
   void appendCommand(String var1);

   void insertCommandSequence(List<String> var1);

   void selectBotByName(String var1);
}
