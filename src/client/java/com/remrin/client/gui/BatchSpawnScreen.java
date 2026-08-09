package com.remrin.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Screen for batch-spawning fake players, supporting two naming modes:
 * <ul>
 *   <li>Numbered mode: {@code prefix + starting index}, e.g. Bot_1, Bot_2, Bot_3</li>
 *   <li>English name mode: names are drawn in order from a built-in English name list (Alex, Ben, Carl, …)</li>
 * </ul>
 * The bottom of the screen shows a real-time preview of the generated fake player name range.
 */
public class BatchSpawnScreen extends BaseParentedScreen<Screen> {

  private static final String[] ENGLISH_NAMES = {
      "Alex", "Ben", "Carl", "David", "Eric", "Frank", "George", "Henry", "Ivan", "Jack",
      "Kevin", "Leo", "Mike", "Nick", "Oscar", "Paul", "Quinn", "Ryan", "Steve", "Tom",
      "Uma", "Victor", "Will", "Xavier", "York", "Zack"
  };
  private static final int FIELD_WIDTH = 150;
  private static final int LABEL_WIDTH = 80;
  private static final int ROW_GAP = 28;

  private EditBox prefixField;
  private EditBox startNumField;
  private EditBox countField;

  private String prefix = "Bot_";
  private int startNum = 1;
  private int count = 1;
  private boolean useEnglishNames = false;

  // Computed in init(), shared with render()
  private int layoutTopY;

  public BatchSpawnScreen(Screen parent) {
    super(Component.translatable("screen.command-gui.fakeplayer.batch.title"), parent);
  }

  /**
   * Vertically centers content with a slight upward offset to match the screen's visual center of
   * gravity
   */
  private int computeLayoutTopY() {
    // Rows: type + (prefix + start if numbered) + count = 2 or 4 rows
    int rows = useEnglishNames ? 2 : 4;
    // title(9) + gap(15) + rows*ROW_GAP + buttons(20) + gap(12) + preview(9)
    int contentH = 9 + 15 + rows * ROW_GAP + 20 + 12 + 9;
    return Math.max(20, (this.height - contentH) / 2 - 10);
  }

  @Override
  protected void init() {
    super.init();

    int centerX = this.width / 2;
    int maxCount = useEnglishNames ? ENGLISH_NAMES.length : 50;
    count = Math.max(1, Math.min(count, maxCount));

    layoutTopY = computeLayoutTopY();
    int y = layoutTopY + 24; // first row below title

    // Type toggle — rebuilds entire layout so spacing adapts to visible rows
    this.addRenderableWidget(Button.builder(
        getTypeLabel(),
        btn -> {
          useEnglishNames = !useEnglishNames;
          rebuildWidgets();
        }
    ).bounds(centerX - FIELD_WIDTH / 2, y, FIELD_WIDTH, 20).build());
    y += ROW_GAP;

    if (!useEnglishNames) {
      prefixField = new EditBox(this.font, centerX - FIELD_WIDTH / 2, y, FIELD_WIDTH, 20,
          Component.translatable("screen.command-gui.fakeplayer.batch.prefix"));
      prefixField.setMaxLength(20);
      prefixField.setValue(prefix);
      prefixField.setResponder(s -> prefix = s);
      this.addRenderableWidget(prefixField);
      y += ROW_GAP;

      startNumField = new EditBox(this.font, centerX - FIELD_WIDTH / 2, y, FIELD_WIDTH, 20,
          Component.translatable("screen.command-gui.fakeplayer.batch.start"));
      startNumField.setMaxLength(5);
      startNumField.setValue(String.valueOf(startNum));
      startNumField.setResponder(s -> {
        try {
          startNum = Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
        }
      });
      this.addRenderableWidget(startNumField);
      y += ROW_GAP;
    } else {
      prefixField = null;
      startNumField = null;
    }

    countField = new EditBox(this.font, centerX - FIELD_WIDTH / 2, y, FIELD_WIDTH, 20,
        Component.translatable("screen.command-gui.fakeplayer.batch.count"));
    countField.setMaxLength(3);
    countField.setValue(String.valueOf(count));
    countField.setResponder(s -> {
      try {
        count = Math.max(1, Math.min(maxCount, Integer.parseInt(s)));
      } catch (NumberFormatException ignored) {
      }
    });
    this.addRenderableWidget(countField);
    y += 40;

    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.fakeplayer.batch.spawn"),
        btn -> spawnBatch()
    ).bounds(centerX - 102, y, 100, 20).build());

    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.back"),
        btn -> this.minecraft.gui.setScreen(parent)
    ).bounds(centerX + 2, y, 100, 20).build());
  }

  private Component getTypeLabel() {
    return Component.translatable(useEnglishNames
        ? "screen.command-gui.fakeplayer.batch.type.english"
        : "screen.command-gui.fakeplayer.batch.type.numbered");
  }

  private void spawnBatch() {
    if (useEnglishNames) {
      int n = Math.min(count, ENGLISH_NAMES.length);
      for (int i = 0; i < n; i++) {
        CommandHelper.sendCommand("/player " + ENGLISH_NAMES[i] + " spawn");
      }
    } else {
      for (int i = 0; i < count; i++) {
        CommandHelper.sendCommand("/player " + prefix + (startNum + i) + " spawn");
      }
    }
    this.minecraft.gui.setScreen(parent);
  }

  private String buildPreview() {
    if (useEnglishNames) {
      int n = Math.min(count, ENGLISH_NAMES.length);
      if (n <= 1) {
        return ENGLISH_NAMES[0];
      }
      return ENGLISH_NAMES[0] + " ~ " + ENGLISH_NAMES[n - 1];
    } else {
      String first = prefix + startNum;
      return count <= 1 ? first : first + " ~ " + prefix + (startNum + count - 1);
    }
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
      float partialTick) {
    super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

    int centerX = this.width / 2;
    int labelX = Math.max(4, centerX - FIELD_WIDTH / 2 - LABEL_WIDTH / 2 - 5);
    int y = layoutTopY;

    guiGraphics.centeredText(this.font, this.title, centerX, y, 0xFFFFFFFF);
    y += 24;

    guiGraphics.text(this.font,
        Component.translatable("screen.command-gui.fakeplayer.batch.type"),
        labelX, y + 6, 0xFFFFFFFF);
    y += ROW_GAP;

    if (!useEnglishNames) {
      guiGraphics.text(this.font,
          Component.translatable("screen.command-gui.fakeplayer.batch.prefix"),
          labelX, y + 6, 0xFFFFFFFF);
      y += ROW_GAP;

      guiGraphics.text(this.font,
          Component.translatable("screen.command-gui.fakeplayer.batch.start"),
          labelX, y + 6, 0xFFFFFFFF);
      y += ROW_GAP;
    }

    guiGraphics.text(this.font,
        Component.translatable("screen.command-gui.fakeplayer.batch.count"),
        labelX, y + 6, 0xFFFFFFFF);
    y += 40 + 20 + 12; // count field height + button height + gap

    guiGraphics.centeredText(this.font,
        Component.translatable("screen.command-gui.fakeplayer.batch.preview", buildPreview()),
        centerX, y, 0xFF888888);
  }
}
