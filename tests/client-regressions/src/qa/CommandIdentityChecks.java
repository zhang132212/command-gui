package qa;

import com.google.gson.Gson;
import com.remrin.client.config.CommandConfig;
import com.remrin.client.gui.AddCommandScreen;
import com.remrin.client.gui.CommandGUIScreen;
import com.remrin.client.gui.CommandShortcut;
import com.remrin.client.gui.SelectCategoryScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Run on the client thread at the title screen, in the isolated regression run directory. */
public final class CommandIdentityChecks {
   private static final Gson GSON = new Gson();
   private static int checks;

   @SuppressWarnings("unchecked")
   public static int run(Minecraft mc) throws Exception {
      checks = 0;
      check(mc.player == null, "identity checks require the isolated title-screen fixture");
      Field data = field(CommandConfig.class, "configData");
      Object originalData = data.get(null);
      Map<Object, Object> overrides = (Map<Object, Object>)field(CommandConfig.class, "pendingOverrides").get(null);
      Set<Object> removals = (Set<Object>)field(CommandConfig.class, "pendingRemovals").get(null);
      Map<Object, Object> originalOverrides = new LinkedHashMap<>(overrides);
      Set<Object> originalRemovals = new LinkedHashSet<>(removals);
      Path config = (Path)field(CommandConfig.class, "CONFIG_PATH").get(null);
      byte[] originalFile = Files.exists(config) ? Files.readAllBytes(config) : null;
      Screen originalScreen = mc.gui.screen();
      try {
         data.set(null, new CommandConfig.ConfigData());
         overrides.clear();
         removals.clear();
         categoryIdentity();
         conflicts();
         persistenceFailure(config);
         pendingIdentity();
         categoryNavigation(mc);
         System.out.println("COMMAND_IDENTITY_CHECKS_COMPLETE checks=" + checks);
         return checks;
      } finally {
         data.set(null, originalData);
         overrides.clear(); overrides.putAll(originalOverrides);
         removals.clear(); removals.addAll(originalRemovals);
         if (originalFile != null) Files.write(config, originalFile); else Files.deleteIfExists(config);
         mc.gui.setScreen(originalScreen);
      }
   }

   private static void seed() {
      CommandConfig.getCategories().clear();
      CommandConfig.getCategories().add(new CommandConfig.Category("default", "screen.command-gui.category.default"));
      CommandConfig.getCategories().add(new CommandConfig.Category("second", "screen.command-gui.category.default"));
      CommandConfig.getCommandsByCategory("default").put("Same", new CommandConfig.CommandEntry("/say first", "first"));
      CommandConfig.getCommandsByCategory("second").put("Same", new CommandConfig.CommandEntry("/say second", "second"));
      CommandConfig.save();
   }

   private static void categoryIdentity() {
      seed();
      check(CommandConfig.findCommandCategory("Same") == null, "legacy lookup rejects ambiguous names");
      check(CommandConfig.saveCommand("second", "Same", "second", "Same", List.of("/say second edited"), "edited", 10, "998") == null,
         "precise edit succeeds for a legacy duplicate name");
      check(command("default", "Same").equals("/say first") && command("second", "Same").equals("/say second edited"),
         "editing a duplicate name changes only its selected category");
      CommandConfig.load();
      check(command("default", "Same").equals("/say first") && command("second", "Same").equals("/say second edited"),
         "category identity survives persistence and reload");
      String before = state();
      CommandConfig.updateCommandMulti("Same", List.of("/say ambiguous"), "", 1);
      CommandConfig.removeCommand("Same");
      CommandConfig.moveCommand("Same", "second");
      check(state().equals(before), "legacy mutation APIs cannot modify an ambiguous name");
      CommandConfig.getCommand("default", "Same").shortcut = "999";
      check(CommandShortcut.findConflict("999", "Same", "second", "Same", -1) != null,
         "shortcut conflict scan includes a same-name command in a different category");
      check(CommandConfig.removeCommand("second", "Same") == null && CommandConfig.getCommand("second", "Same") == null
         && command("default", "Same").equals("/say first"), "precise deletion preserves the other same-name command");
      check(CommandConfig.moveCommand("default", "Same", "second") == null && CommandConfig.getCommand("default", "Same") == null
         && command("second", "Same").equals("/say first"), "precise movement preserves the command and its metadata");
      check(CommandConfig.getCommand("second", "Same").shortcut.equals("999"), "movement preserves the shortcut");
   }

   private static void conflicts() throws Exception {
      seed();
      CommandConfig.getCommandsByCategory("default").put("Source", new CommandConfig.CommandEntry("/say source", "source"));
      CommandConfig.save();
      String before = state();
      Path config = (Path)field(CommandConfig.class, "CONFIG_PATH").get(null);
      byte[] saved = Files.readAllBytes(config);
      check(CommandConfig.saveCommand("default", "Source", "default", "Same", List.of("/say replacement"), "", 1, "") != null,
         "rename collision reports an error");
      check(state().equals(before), "rename collision keeps both the source and destination");
      check(CommandConfig.saveCommand(null, null, "default", "Same", List.of("/say replacement"), "", 1, "") != null
         && state().equals(before), "add collision does not replace an existing command");
      check(CommandConfig.moveCommand("default", "Same", "second") != null && state().equals(before),
         "move collision keeps both same-name commands");
      check(CommandConfig.saveCommand("default", "Source", "gone", "NewName", List.of("/say replacement"), "", 1, "") != null
         && state().equals(before), "missing destination leaves the source intact");
      check(CommandConfig.saveCommand("gone", "Source", "second", "NewName", List.of("/say replacement"), "", 1, "") != null
         && state().equals(before), "missing source cannot silently create a new command");
      check(java.util.Arrays.equals(saved, Files.readAllBytes(config)), "rejected changes never alter the saved file");
      check(CommandConfig.saveCommand("default", "Source", "second", "Renamed", List.of("/say updated"), "changed", 7, "997") == null,
         "rename and movement succeed in one operation");
      check(CommandConfig.getCommand("default", "Source") == null && command("second", "Renamed").equals("/say updated"),
         "successful rename and movement removes exactly its original record");
   }

   private static void persistenceFailure(Path config) throws Exception {
      seed();
      String before = state();
      byte[] saved = Files.readAllBytes(config);
      // A directory in place of the temporary output file forces a real write failure.
      Path blockedTemp = config.resolveSibling(config.getFileName() + ".tmp");
      Files.createDirectory(blockedTemp);
      try {
         check(CommandConfig.saveCommand("second", "Same", "default", "Moved", List.of("/say updated"), "", 1, "") != null,
            "write failure reports an error");
         check(state().equals(before), "write failure rolls back rename and movement in memory");
         check(java.util.Arrays.equals(saved, Files.readAllBytes(config)), "write failure preserves the previous saved file");
         check(CommandConfig.removeCommand("second", "Same") != null && state().equals(before),
            "write failure also rolls back deletion");
      } finally {
         Files.delete(blockedTemp);
      }
   }

   private static void pendingIdentity() {
      seed();
      CommandConfig.applyPendingCommand("default", "Same", List.of("/say first pending"), "", 2);
      CommandConfig.applyPendingCommand("second", "Same", List.of("/say second pending"), "", 3);
      check(CommandConfig.getPendingEntry("Same") == null, "legacy pending lookup rejects ambiguous names");
      check(CommandConfig.getPendingEntry("default", "Same").getCommands().equals(List.of("/say first pending"))
         && CommandConfig.getPendingEntry("second", "Same").getCommands().equals(List.of("/say second pending")),
         "pending edits keep their category identities");
      CommandConfig.applyPendingRemoval("Same");
      check(!CommandConfig.isPendingRemoval("default", "Same") && !CommandConfig.isPendingRemoval("second", "Same"),
         "ambiguous legacy pending removal is ignored");
      CommandConfig.commitPending();
      check(command("default", "Same").equals("/say first pending") && command("second", "Same").equals("/say second pending"),
         "pending commits update both precise category records");
      CommandConfig.applyPendingRemoval("second", "Same");
      CommandConfig.commitPending();
      check(CommandConfig.getCommand("second", "Same") == null && command("default", "Same").equals("/say first pending"),
         "pending deletion affects only its intended category");
      check(!CommandConfig.hasPending(), "successful pending operations are removed");
      CommandConfig.applyPendingCommand("second", "Draft", List.of("/say draft"), "", 1);
      check(CommandConfig.getPendingEntry("Draft") != null, "legacy lookup accepts a unique new pending command");
      CommandConfig.applyPendingRemoval("Draft");
      check(!CommandConfig.hasPending() && CommandConfig.getCommand("second", "Draft") == null,
         "legacy removal cancels a unique unsaved new command without queuing an impossible deletion");
   }

   @SuppressWarnings("unchecked")
   private static void categoryNavigation(Minecraft mc) throws Exception {
      seed();
      CommandGUIScreen parent = new CommandGUIScreen();
      mc.gui.setScreen(parent);
      AddCommandScreen editor = new AddCommandScreen(parent, "default");
      // No live player exists at the title screen. Populate the saved-command draft
      // instead of invoking vanilla command suggestions through a typed command.
      ((List<String>)field(AddCommandScreen.class, "commandList").get(editor)).add("/say created");
      ((List<Boolean>)field(AddCommandScreen.class, "commandValid").get(editor)).add(false);
      mc.gui.setScreen(editor);
      check(editor.getSelectedCategoryId().equals("default"), "new editor starts in the selected category");
      ((EditBox)field(AddCommandScreen.class, "nameField").get(editor)).setValue("Created");
      check(((EditBox)field(AddCommandScreen.class, "commandField").get(editor)).getValue().isEmpty()
         && ((String)field(AddCommandScreen.class, "commandText").get(editor)).isEmpty(),
         "title-screen category fixture avoids live command suggestions");
      invoke(editor, "openCategoryPicker");
      Screen picker = mc.gui.screen();
      check(picker instanceof SelectCategoryScreen, "save category opens its selection screen");
      invoke(picker, "select", new Class<?>[]{String.class}, "second");
      invoke(picker, "confirm");
      check(mc.gui.screen() == editor && editor.getSelectedCategoryId().equals("second"),
         "returning from the category picker retains the new destination");
      editor.resize(editor.width, editor.height);
      check(editor.getSelectedCategoryId().equals("second"), "resize retains the chosen destination");
      invoke(editor, "saveAndClose");
      check(CommandConfig.getCommand("default", "Created") == null && command("second", "Created").equals("/say created"),
         "new command is saved to the chosen category after navigation and resize");

      editor = new AddCommandScreen(parent, "default", "Same", CommandConfig.getCommand("default", "Same"));
      mc.gui.setScreen(editor);
      invoke(editor, "openCategoryPicker");
      picker = mc.gui.screen();
      invoke(picker, "select", new Class<?>[]{String.class}, "second");
      invoke(picker, "confirm");
      check(command("default", "Same").equals("/say first") && command("second", "Same").equals("/say second"),
         "choosing a movement destination does not persist the draft");
      invoke(editor, "saveAndClose");
      check(mc.gui.screen() == editor && !((String)field(AddCommandScreen.class, "saveError").get(editor)).isEmpty(),
         "UI collision displays an error and keeps the editor open");
      check(command("default", "Same").equals("/say first") && command("second", "Same").equals("/say second"),
         "failed UI movement preserves both commands");
      mc.gui.setScreen(parent);
      check(command("default", "Same").equals("/say first"), "discarding the movement draft preserves the saved source category");
   }

   private static String command(String categoryId, String name) {
      return CommandConfig.getCommand(categoryId, name).getCommands().getFirst();
   }

   private static String state() {
      return GSON.toJson(CommandConfig.getCategories());
   }

   private static Field field(Class<?> type, String name) throws Exception {
      Field field = type.getDeclaredField(name);
      field.setAccessible(true);
      return field;
   }

   private static void invoke(Object target, String name) throws Exception {
      invoke(target, name, new Class<?>[0]);
   }

   private static void invoke(Object target, String name, Class<?>[] types, Object... arguments) throws Exception {
      var method = target.getClass().getDeclaredMethod(name, types);
      method.setAccessible(true);
      method.invoke(target, arguments);
   }

   private static void check(boolean condition, String message) {
      if (!condition) throw new AssertionError(message);
      checks++;
   }
}
