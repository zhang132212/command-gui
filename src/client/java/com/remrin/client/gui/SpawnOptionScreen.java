package com.remrin.client.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.lwjgl.glfw.GLFW;

/**
 * Spawn quick-config popup for the step editor, styled after the fake player command editor's
 * quick config: dimension buttons with a green selection overlay, position / facing shortcuts and
 * an optional look-at target. The content area is scrollable (mouse wheel + draggable scrollbar)
 * so the popup can extend vertically on small screens.
 * <ul>
 *   <li>spawn command (carpet syntax): {@code /player {bot} spawn at X Y Z facing Yaw Pitch in <dim>}</li>
 *   <li>optional look command: {@code /player {bot} look at X Y Z} (added right after the spawn)</li>
 * </ul>
 * "插入" writes the commands into the step editor's command list in order.
 */
public class SpawnOptionScreen extends BaseParentedScreen<StepEditorScreen> {

  private static final int FIELD_WIDTH = 180;
  private static final int FIELD_HEIGHT = 16;
  private static final int COORD_GAP = 6;
  private static final int XYZ_FIELD_WIDTH = (FIELD_WIDTH - 2 * COORD_GAP) / 3;
  private static final int HALF_FIELD_WIDTH = (FIELD_WIDTH - COORD_GAP) / 2;
  private static final int LABEL_COLOR = 0xFFAAAAAA;
  private static final int BTN_GAP = 4;
  private static final int SCROLLBAR_WIDTH = 12;
  private static final int VIEWPORT_TOP = 18;
  private static final int CONTENT_HEIGHT = 260;

  private static final String[] DIMENSIONS = {
      "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"
  };
  private static final String[] DIMENSION_NAMES = {
      "screen.command-gui.fakeplayer.dim.overworld",
      "screen.command-gui.fakeplayer.dim.nether",
      "screen.command-gui.fakeplayer.dim.end"
  };

  private final List<Button> dimensionButtons = new ArrayList<>();
  /** Content widgets (scrolled); paired with their layout Y so they can be repositioned. */
  private final List<AbstractWidget> contentWidgets = new ArrayList<>();
  private final List<Integer> contentWidgetYs = new ArrayList<>();
  private int dimensionIndex = 0;
  // Spawn position + facing
  private String xText = "";
  private String yText = "";
  private String zText = "";
  private String yawText = "";
  private String pitchText = "";
  // Look-at target (optional; empty = no look command)
  private String lookXText = "";
  private String lookYText = "";
  private String lookZText = "";
  private EditBox xField;
  private EditBox yField;
  private EditBox zField;
  private EditBox yawField;
  private EditBox pitchField;
  private EditBox lookXField;
  private EditBox lookYField;
  private EditBox lookZField;
  // Scroll state
  private int contentScroll = 0;
  private int viewportBottom;
  private boolean scrollbarDragging = false;
  private double scrollbarGrabOffset = 0;

  public SpawnOptionScreen(StepEditorScreen parent) {
    super(Component.translatable("screen.command-gui.machine.spawn_title"), parent);
    Minecraft mc = Minecraft.getInstance();
    if (mc.player != null) {
      net.minecraft.resources.ResourceKey<Level> dim = mc.player.level().dimension();
      if (dim.equals(Level.NETHER)) {
        dimensionIndex = 1;
      } else if (dim.equals(Level.END)) {
        dimensionIndex = 2;
      } else {
        dimensionIndex = 0;
      }
      // Only set the text backups here — the EditBoxes are created in init()
      xText = String.format("%.1f", Math.round(mc.player.getX() * 10.0) / 10.0);
      yText = String.format("%.1f", Math.round(mc.player.getY() * 10.0) / 10.0);
      zText = String.format("%.1f", Math.round(mc.player.getZ() * 10.0) / 10.0);
    }
  }

  @Override
  protected void init() {
    super.init();

    int fieldX = (this.width - FIELD_WIDTH) / 2;
    viewportBottom = this.height - 28;

    // Dimension buttons (3 in a row); selected dimension turns dark
    dimensionButtons.clear();
    int dimensionBtnWidth = (FIELD_WIDTH - 2 * BTN_GAP) / 3;
    for (int i = 0; i < DIMENSIONS.length; i++) {
      final int idx = i;
      int btnX = fieldX + i * (dimensionBtnWidth + BTN_GAP);
      DarkSelectButton dimBtn = new DarkSelectButton(btnX, 34, dimensionBtnWidth, FIELD_HEIGHT,
          Component.translatable(DIMENSION_NAMES[i]), btn -> dimensionIndex = idx);
      dimBtn.setDarkSelected(() -> dimensionIndex == idx, 0xFFFFFFFF);
      dimensionButtons.add(dimBtn);
      registerContent(dimBtn);
    }

    // Position shortcuts: own feet coordinates / own yaw-pitch (the values are concatenated into
    // the generated spawn command)
    registerContent(Button.builder(
        Component.translatable("screen.command-gui.machine.spawn_own_feet"),
        btn -> fillFeet()
    ).bounds(fieldX, 58, HALF_FIELD_WIDTH, FIELD_HEIGHT).build());

    registerContent(Button.builder(
        Component.translatable("screen.command-gui.machine.spawn_own_rotation"),
        btn -> fillOwnRotation()
    ).bounds(fieldX + HALF_FIELD_WIDTH + BTN_GAP, 58, HALF_FIELD_WIDTH, FIELD_HEIGHT).build());

    // Spawn XYZ fields
    xField = new EditBox(this.font, fieldX, 94, XYZ_FIELD_WIDTH, FIELD_HEIGHT,
        Component.literal("X"));
    xField.setMaxLength(12);
    xField.setValue(xText);
    xField.setResponder(text -> xText = text);
    registerContent(xField);

    yField = new EditBox(this.font, fieldX + XYZ_FIELD_WIDTH + COORD_GAP, 94, XYZ_FIELD_WIDTH,
        FIELD_HEIGHT, Component.literal("Y"));
    yField.setMaxLength(12);
    yField.setValue(yText);
    yField.setResponder(text -> yText = text);
    registerContent(yField);

    zField = new EditBox(this.font, fieldX + (XYZ_FIELD_WIDTH + COORD_GAP) * 2, 94,
        XYZ_FIELD_WIDTH, FIELD_HEIGHT, Component.literal("Z"));
    zField.setMaxLength(12);
    zField.setValue(zText);
    zField.setResponder(text -> zText = text);
    registerContent(zField);

    // Facing (yaw / pitch)
    yawField = new EditBox(this.font, fieldX, 130, HALF_FIELD_WIDTH, FIELD_HEIGHT,
        Component.literal("Yaw"));
    yawField.setMaxLength(10);
    yawField.setValue(yawText);
    yawField.setResponder(text -> yawText = text);
    registerContent(yawField);

    pitchField = new EditBox(this.font, fieldX + HALF_FIELD_WIDTH + COORD_GAP, 130,
        HALF_FIELD_WIDTH, FIELD_HEIGHT, Component.literal("Pitch"));
    pitchField.setMaxLength(10);
    pitchField.setValue(pitchText);
    pitchField.setResponder(text -> pitchText = text);
    registerContent(pitchField);

    // Look-at target: pick shortcuts, then coordinates
    registerContent(Button.builder(
        Component.translatable("screen.command-gui.machine.spawn_look_target"),
        btn -> fillLookTarget()
    ).bounds(fieldX, 166, HALF_FIELD_WIDTH, FIELD_HEIGHT).build());

    registerContent(Button.builder(
        Component.translatable("screen.command-gui.machine.spawn_look_center"),
        btn -> fillLookCenter()
    ).bounds(fieldX + HALF_FIELD_WIDTH + BTN_GAP, 166, HALF_FIELD_WIDTH, FIELD_HEIGHT).build());

    lookXField = new EditBox(this.font, fieldX, 190, XYZ_FIELD_WIDTH, FIELD_HEIGHT,
        Component.literal("LX"));
    lookXField.setMaxLength(12);
    lookXField.setValue(lookXText);
    lookXField.setResponder(text -> lookXText = text);
    registerContent(lookXField);

    lookYField = new EditBox(this.font, fieldX + XYZ_FIELD_WIDTH + COORD_GAP, 190,
        XYZ_FIELD_WIDTH, FIELD_HEIGHT, Component.literal("LY"));
    lookYField.setMaxLength(12);
    lookYField.setValue(lookYText);
    lookYField.setResponder(text -> lookYText = text);
    registerContent(lookYField);

    lookZField = new EditBox(this.font, fieldX + (XYZ_FIELD_WIDTH + COORD_GAP) * 2, 190,
        XYZ_FIELD_WIDTH, FIELD_HEIGHT, Component.literal("LZ"));
    lookZField.setMaxLength(12);
    lookZField.setValue(lookZText);
    lookZField.setResponder(text -> lookZText = text);
    registerContent(lookZField);

    // Bottom bar (fixed, not scrolled)
    int barY = this.height - 22;
    int barWidth = Math.min(70, 100);
    int barStartX = fieldX + (FIELD_WIDTH - barWidth * 2 - 8) / 2;
    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.machine.spawn_insert"),
        btn -> insertAndClose()
    ).bounds(barStartX, barY, barWidth, 18).build());

    this.addRenderableWidget(Button.builder(
        Component.translatable("screen.command-gui.back"),
        btn -> this.minecraft.gui.setScreen(parent)
    ).bounds(barStartX + barWidth + 8, barY, barWidth, 18).build());

    applyContentScroll();
  }

  private void registerContent(AbstractWidget widget) {
    contentWidgets.add(widget);
    contentWidgetYs.add(widget.getY());
    this.addRenderableWidget(widget);
  }

  // ── Scrolling ───────────────────────────────────────────────────

  private int getMaxScroll() {
    return Math.max(0, CONTENT_HEIGHT - (viewportBottom - VIEWPORT_TOP));
  }

  private void setContentScroll(int scroll) {
    contentScroll = Math.max(0, Math.min(scroll, getMaxScroll()));
    applyContentScroll();
  }

  private void applyContentScroll() {
    for (int i = 0; i < contentWidgets.size(); i++) {
      contentWidgets.get(i).setY(contentWidgetYs.get(i) - contentScroll);
    }
  }

  private int scrollbarX() {
    return (this.width - FIELD_WIDTH) / 2 + FIELD_WIDTH + 8;
  }

  private boolean isOverScrollbar(double mouseX, double mouseY) {
    return mouseX >= scrollbarX() && mouseX <= scrollbarX() + SCROLLBAR_WIDTH
        && mouseY >= VIEWPORT_TOP && mouseY <= viewportBottom;
  }

  private double thumbTop() {
    int viewportH = viewportBottom - VIEWPORT_TOP;
    int maxScroll = getMaxScroll();
    if (maxScroll <= 0) {
      return VIEWPORT_TOP;
    }
    int thumbH = Math.max(12, viewportH * viewportH / CONTENT_HEIGHT);
    return VIEWPORT_TOP + (viewportH - thumbH) * contentScroll / maxScroll;
  }

  private double thumbHeight() {
    int viewportH = viewportBottom - VIEWPORT_TOP;
    int maxScroll = getMaxScroll();
    if (maxScroll <= 0) {
      return viewportH;
    }
    return Math.max(12, viewportH * viewportH / CONTENT_HEIGHT);
  }

  private void updateScrollFromMouseY(double mouseY) {
    int viewportH = viewportBottom - VIEWPORT_TOP;
    int maxScroll = getMaxScroll();
    double thumbH = thumbHeight();
    double scroll = (mouseY - VIEWPORT_TOP - scrollbarGrabOffset) * maxScroll
        / (viewportH - thumbH);
    setContentScroll((int) Math.round(scroll));
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    if (getMaxScroll() > 0) {
      setContentScroll(contentScroll - (scrollY > 0 ? 8 : -8));
      return true;
    }
    return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent mouseEvent, boolean focused) {
    if (getMaxScroll() > 0 && mouseEvent.button() == 0
        && isOverScrollbar(mouseEvent.x(), mouseEvent.y())) {
      scrollbarDragging = true;
      scrollbarGrabOffset = mouseEvent.y() - thumbTop();
      updateScrollFromMouseY(mouseEvent.y());
      return true;
    }
    return super.mouseClicked(mouseEvent, focused);
  }

  @Override
  public boolean mouseDragged(MouseButtonEvent mouseEvent, double dragX, double dragY) {
    if (scrollbarDragging) {
      updateScrollFromMouseY(mouseEvent.y());
      return true;
    }
    return super.mouseDragged(mouseEvent, dragX, dragY);
  }

  @Override
  public boolean mouseReleased(MouseButtonEvent mouseEvent) {
    scrollbarDragging = false;
    return super.mouseReleased(mouseEvent);
  }

  // ── Quick fills ─────────────────────────────────────────────────

  /**
   * Fills the spawn position with the block under the player's feet (integer block coords).
   */
  private void fillFeet() {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null) {
      return;
    }
    BlockPos pos = mc.player.blockPosition().below();
    setPosition(pos.getX(), pos.getY(), pos.getZ());
  }

  /**
   * Fills the yaw / pitch fields with the player's current rotation.
   */
  private void fillOwnRotation() {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null) {
      return;
    }
    yawText = String.format("%.1f", Math.round(mc.player.getYRot() * 10.0f) / 10.0f);
    pitchText = String.format("%.1f", Math.round(mc.player.getXRot() * 10.0f) / 10.0f);
    yawField.setValue(yawText);
    pitchField.setValue(pitchText);
  }

  /**
   * Fills the look-at target with the block currently under the player's crosshair.
   */
  private void fillLookTarget() {
    Minecraft mc = Minecraft.getInstance();
    if (mc.hitResult instanceof BlockHitResult blockHit) {
      BlockPos pos = blockHit.getBlockPos();
      lookXField.setValue(String.valueOf(pos.getX()));
      lookYField.setValue(String.valueOf(pos.getY()));
      lookZField.setValue(String.valueOf(pos.getZ()));
    }
  }

  /**
   * Forces the look-at coordinates to the CENTER of the block they point at: each coordinate
   * becomes {@code floor(value) + 0.5}, so integer block coords turn into the block's 体心.
   */
  private void fillLookCenter() {
    lookXText = blockCenterCoord(lookXText);
    lookYText = blockCenterCoord(lookYText);
    lookZText = blockCenterCoord(lookZText);
    lookXField.setValue(lookXText);
    lookYField.setValue(lookYText);
    lookZField.setValue(lookZText);
  }

  private static String blockCenterCoord(String text) {
    try {
      double value = Double.parseDouble(text.trim());
      return String.format("%.1f", Math.floor(value) + 0.5);
    } catch (NumberFormatException ignored) {
      return text;
    }
  }

  private void setPosition(double x, double y, double z) {
    xText = String.format("%.1f", Math.round(x * 10.0) / 10.0);
    yText = String.format("%.1f", Math.round(y * 10.0) / 10.0);
    zText = String.format("%.1f", Math.round(z * 10.0) / 10.0);
    xField.setValue(xText);
    yField.setValue(yText);
    zField.setValue(zText);
  }

  // ── Command building ────────────────────────────────────────────

  /**
   * Builds the spawn command using carpet's native syntax:
   * {@code /player {bot} spawn at X Y Z [facing Yaw Pitch] in <dimension>}.
   */
  private String buildCommand() {
    // Carpet 26.2's spawn syntax REQUIRES the facing argument: without it the command fails to
    // parse silently. Default to 0 0 (south, level) when the player left it blank.
    String cmd = "/player {bot} spawn at " + xText + " " + yText + " " + zText;
    String yaw = yawText.trim();
    String pitch = pitchText.trim();
    cmd += " facing " + (yaw.isEmpty() ? "0" : yaw) + " " + (pitch.isEmpty() ? "0" : pitch);
    cmd += " in " + DIMENSIONS[dimensionIndex];
    return cmd;
  }

  /**
   * Builds the look-at command, or {@code null} when no look target is configured.
   */
  private String buildLookCommand() {
    if (lookXText.isEmpty() || lookYText.isEmpty() || lookZText.isEmpty()) {
      return null;
    }
    return "/player {bot} look at " + lookXText + " " + lookYText + " " + lookZText;
  }

  private void insertAndClose() {
    List<String> commands = new ArrayList<>();
    commands.add(buildCommand());
    String look = buildLookCommand();
    if (look != null) {
      commands.add(look);
    }
    parent.insertCommandSequence(commands);
    this.minecraft.gui.setScreen(parent);
  }

  @Override
  public boolean keyPressed(KeyEvent keyEvent) {
    if (keyEvent.key() == GLFW.GLFW_KEY_ESCAPE) {
      this.minecraft.gui.setScreen(parent);
      return true;
    }
    return super.keyPressed(keyEvent);
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
      float partialTick) {
    super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

    int fieldX = (this.width - FIELD_WIDTH) / 2;
    guiGraphics.centeredText(this.font, this.title, this.width / 2, 4, 0xFFFFFFFF);

    // Labels (scrolled with the content)
    renderScrolledLabel(guiGraphics, fieldX,
        Component.translatable("screen.command-gui.fakeplayer.dimension"), 22);
    renderScrolledLabel(guiGraphics, fieldX,
        Component.translatable("screen.command-gui.fakeplayer.spawn_at"), 82);
    renderScrolledLabel(guiGraphics, fieldX,
        Component.translatable("screen.command-gui.machine.spawn_facing"), 118);
    renderScrolledLabel(guiGraphics, fieldX,
        Component.translatable("screen.command-gui.machine.spawn_look_at"), 154);
    renderScrolledLabel(guiGraphics, fieldX,
        Component.translatable("screen.command-gui.fakeplayer.command_preview"), 214);

    // Command preview lines (scrolled with the content)
    List<String> previewCommands = new ArrayList<>();
    previewCommands.add(buildCommand());
    String look = buildLookCommand();
    if (look != null) {
      previewCommands.add(look);
    }
    int previewMaxW = FIELD_WIDTH - 8;
    int lineY = 224 - contentScroll;
    for (String cmd : previewCommands) {
      String remaining = cmd;
      while (!remaining.isEmpty() && lineY < viewportBottom) {
        String line = this.font.plainSubstrByWidth(remaining, previewMaxW);
        if (line.isEmpty()) {
          line = remaining.substring(0, 1);
        }
        guiGraphics.text(this.font, line, fieldX + 4, lineY, 0xFF55FF55);
        lineY += 9;
        remaining = remaining.substring(line.length());
      }
    }

    // Scrollbar (track + thumb), styled like the main GUI scrollbars
    if (getMaxScroll() > 0) {
      int scrollbarX = scrollbarX();
      ScrollbarHandle handle = new ScrollbarHandle(scrollbarX, VIEWPORT_TOP, SCROLLBAR_WIDTH,
          viewportBottom - VIEWPORT_TOP);
      int viewportH = viewportBottom - VIEWPORT_TOP;
      handle.render(guiGraphics, contentScroll, getMaxScroll(), viewportH, CONTENT_HEIGHT, false);
    }
  }

  private void renderScrolledLabel(GuiGraphicsExtractor guiGraphics, int x, Component label,
      int y) {
    int scrolledY = y - contentScroll;
    if (scrolledY < VIEWPORT_TOP - 2 || scrolledY + 10 > viewportBottom) {
      return;
    }
    guiGraphics.text(this.font, label, x, scrolledY, LABEL_COLOR);
  }
}
