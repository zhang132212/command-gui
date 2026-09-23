package qa;

import com.remrin.client.gui.*;
import com.remrin.client.config.CommandConfig;
import com.remrin.client.config.SettingsConfig;
import com.remrin.client.machine.MachineModels;
import com.remrin.client.machine.MachineNetworkManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.TitleScreen;
import java.util.ArrayList;
import java.util.List;

/** Isolated visual/behavior checks; no server and no user config or save writes. */
public final class EditorLayoutQa implements ClientModInitializer {
    private int ticks, stage = -1, size;
    private CommandGUIScreen parent;
    private Button toggle;
    private static final String KEY = "quick_command_keep_open_default";

    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                if (++ticks > 1600) throw new AssertionError("timeout " + stage);
                if (mc.gui.overlay() != null) return;
                if (stage == -1) {
                    if (!(mc.gui.screen() instanceof TitleScreen) || ticks < 40) return;
                    for (String key : List.of("canEdit", "canConfig")) {
                        var f = MachineNetworkManager.class.getDeclaredField(key); f.setAccessible(true); f.setBoolean(null, true);
                    }
                    CarpetFilterChecks.run(new CommandGUIScreen());
                    configure(mc); openCommand(mc, false); advance(); return;
                }
                if (stage == 7) populateFake();
                if (ticks < 16) return;
                switch (stage) {
                    case 0 -> { checkBounds(mc); shot(mc, "command"); checkResizeDraft(mc); openCommand(mc, true); advance(); }
                    case 1 -> { var bot = AddCommandScreen.class.getDeclaredField("botField"); bot.setAccessible(true); if (bot.get(mc.gui.screen()) == null) throw new AssertionError("fake fixture not active"); checkBounds(mc); shot(mc, "fake-command"); mc.gui.setScreen(new AddCommandScreen(parent, -1, true, true)); advance(); }
                    case 2 -> { checkBounds(mc); shot(mc, "custom-fake-command"); openMachine(mc); advance(); }
                    case 3 -> { checkBounds(mc); shot(mc, "machine"); SettingsConfig.setBoolean(KEY, false); mc.gui.setScreen(new SettingsScreen(parent)); advance(); }
                    case 4 -> {
                        checkBounds(mc); toggle = (Button)mc.gui.screen().children().stream().filter(w -> w.getClass().getSimpleName().equals("YesNoButton")).findFirst().orElseThrow();
                        if (SettingsConfig.getBoolean(KEY)) throw new AssertionError("initial toggle");
                        shot(mc, "settings-off"); toggle.onPress(null); toggle.setFocused(true); advance();
                    }
                    case 5 -> {
                        if (!SettingsConfig.getBoolean(KEY) || !toggle.getMessage().getString().endsWith("是")) throw new AssertionError("toggle ON mismatch " + toggle.getMessage());
                        shot(mc, "settings-on-focused"); SettingsConfig.setBoolean(KEY, false); advance();
                    }
                    case 6 -> {
                        if (!toggle.getMessage().getString().endsWith("否")) throw new AssertionError("external state mismatch");
                        shot(mc, "settings-off-focused");
                        mc.gui.setScreen(parent);
                        var bar = (net.minecraft.client.gui.components.tabs.TabNavigationBar)field(parent, "tabNavigationBar");
                        bar.selectTab(1, false); parent.tick(); populateFake(); advance();
                    }
                    case 7 -> {
                        shot(mc, "fake-controls-smooth");
                        Object tab = field(parent, "fakePlayerTab");
                        var boxes = (List<Button>)field(tab, "checkboxButtons");
                        boxes.getFirst().onPress(null);
                        if (((java.util.Set<?>)field(tab, "multiSelection")).contains("Worker01")) throw new AssertionError("checkbox toggle failed");
                        if (++size < 3) { configure(mc); openCommand(mc, false); stage = 0; ticks = 0; }
                        else { mc.gui.setScreen(new TooltipPreview(parent)); advance(); }
                    }
                    case 8 -> { shot(mc, "tooltip-opaque"); System.out.println("EDITOR_LAYOUT_QA_COMPLETE"); mc.stop(); stage = 99; }
                    default -> {}
                }
            } catch (Throwable error) {
                error.printStackTrace(); System.out.println("EDITOR_LAYOUT_QA_FAILED stage=" + stage + " size=" + size); mc.stop(); stage = 99;
            }
        });
    }
    private static Object field(Object object, String name) throws Exception {
        var f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    private static void invoke(Object object, String name) throws Exception {
        var m = object.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(object);
    }
    private void populateFake() throws Exception {
        Object tab = field(parent, "fakePlayerTab");
        invoke(tab, "fireBeforeRebuild");
        var names = (List<String>)field(tab, "displayList"); names.clear();
        for (int i = 1; i <= 30; i++) names.add(String.format("Worker%02d", i));
        var selected = (java.util.Set<String>)field(tab, "multiSelection"); selected.clear(); selected.add("Worker01"); selected.add("Worker03");
        invoke(tab, "rebuildPlayerButtons"); invoke(tab, "fireAfterRebuild");
    }
    private void configure(Minecraft mc) {
        org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().handle(), size == 2 ? 960 : 1440, size == 2 ? 720 : 900);
        mc.options.guiScale().set(size == 0 ? 2 : 3); mc.resizeGui();
        parent = new CommandGUIScreen();
    }
    private void openCommand(Minecraft mc, boolean fake) {
        List<String> commands = new ArrayList<>();
        for (int i = 0; i < 12; i++) commands.add(fake ? "/player Worker" + i + " attack continuous" : "/say 示例指令 " + i + " {player}");
        if (fake) commands.set(0, "/player Worker0 spawn");
        var entry = new CommandConfig.CommandEntry(commands, fake ? "批量启动农场假人" : "常用维护指令与执行顺序");
        entry.commandDelay = 20;
        mc.gui.setScreen(new AddCommandScreen(parent, "default", fake ? "农场假人" : "维护工具", entry));
    }
    private void openMachine(Minecraft mc) {
        var machine = new MachineModels.MachineData();
        machine.id = "qa-editor-layout"; machine.name = "主世界刷铁机"; machine.description = "按顺序启动假人，停止后检查红石状态";
        machine.category = "资源生产"; machine.bots = new ArrayList<>(List.of("IronFarm", "Loader"));
        mc.gui.setScreen(new MachineEditorScreen(parent, machine));
    }
    private void checkResizeDraft(Minecraft mc) throws Exception {
        var screen = mc.gui.screen(); var f = AddCommandScreen.class.getDeclaredField("descriptionField"); f.setAccessible(true);
        ((EditBox)f.get(screen)).setValue("resize draft retained"); mc.resizeGui();
        if (!((EditBox)f.get(screen)).getValue().equals("resize draft retained")) throw new AssertionError("draft lost on resize");
        screen.mouseScrolled(40, 190, 0, -4);
        checkBounds(mc);
    }
    private void checkBounds(Minecraft mc) {
        var screen = mc.gui.screen();
        var widgets = screen.children().stream().filter(w -> w instanceof AbstractWidget).map(w -> (AbstractWidget)w).filter(w -> w.visible).toList();
        for (int i = 0; i < widgets.size(); i++) {
            var a = widgets.get(i);
            if (a.getWidth() <= 0 || a.getHeight() <= 0 || a.getX() < 0 || a.getY() < 0 || a.getRight() > screen.width || a.getBottom() > screen.height)
                throw new AssertionError("out of bounds " + a.getMessage() + " " + screen.width + "x" + screen.height);
            for (int j = i + 1; j < widgets.size(); j++) {
                var b = widgets.get(j);
                if (a.getX() < b.getRight() && a.getRight() > b.getX() && a.getY() < b.getBottom() && a.getBottom() > b.getY())
                    throw new AssertionError("overlap " + a.getMessage() + " / " + b.getMessage());
            }
        }
        System.out.println("EDITOR_LAYOUT_BOUNDS_OK " + screen.getClass().getSimpleName() + " " + screen.width + "x" + screen.height);
    }
    private void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, "editor-" + size + "-" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1, message -> {});
    }
    private void advance() { stage++; ticks = 0; }

    private static final class TooltipPreview extends SettingsScreen {
        TooltipPreview(CommandGUIScreen parent) { super(parent); }

        @Override
        public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            super.extractRenderState(g, mouseX, mouseY, delta);
            var font = Minecraft.getInstance().font;
            for (int y = 65; y < 150; y += 10) {
                g.text(font, "背景文字 Background text 1234567890", 25, y, 0xFFFFFFFF, false);
            }
            net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil.extractTooltipBackground(g, 40, 78, 220, 24, null);
            g.text(font, "悬浮提示：文字应清晰易读", 40, 78, 0xFFFFFFFF, false);
            g.text(font, "原版提示路径 · 95% 不透明", 40, 90, 0xFFFFFFFF, false);
            GuiTheme.popup(g, 37, 113, 226, 30);
            g.text(font, "自定义提示：统一深色底板", 40, 116, 0xFFFFFFFF, false);
            g.text(font, "背景文字不应干扰阅读", 40, 128, 0xFFFFFFFF, false);
        }
    }
}
