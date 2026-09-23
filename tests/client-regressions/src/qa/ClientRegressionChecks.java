package qa;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.remrin.client.config.CommandConfig;
import com.remrin.client.gui.ChainedCommandExecutor;
import com.remrin.client.gui.CommandHelper;
import com.remrin.client.gui.PlaceholderResolver;
import com.remrin.client.gui.TimedTaskManager;
import com.remrin.client.machine.MachineNetworkManager;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Run on the client thread at the title screen, in an isolated QA run directory. */
public final class ClientRegressionChecks {
   private static int checks;

   public static void run(Minecraft mc) throws Exception {
      check(mc.player == null, "run these isolated checks before joining a world");
      Screen previous = mc.gui.screen();
      try {
         coordinates();
         commandParsing();
         placeholders(mc);
         timerBoundaries();
         delayedBatches();
         commandMove();
         snapshots();
         disconnect(mc);
         System.out.println("CLIENT_REGRESSIONS_COMPLETE checks=" + checks);
      } finally {
         TimedTaskManager.clear();
         ChainedCommandExecutor.clearDelayed();
         invoke(MachineNetworkManager.class, "reset", new Class<?>[0]);
         mc.gui.setScreen(previous);
      }
   }

   private static void coordinates() {
      Locale previous = Locale.getDefault();
      try {
         for (Locale locale : List.of(Locale.US, Locale.GERMANY, Locale.FRANCE, Locale.forLanguageTag("ar-EG"))) {
            Locale.setDefault(locale);
            check(CommandHelper.formatX(12.125).equals("12.125"), "x coordinate in " + locale);
            check(CommandHelper.formatY(-3.125).equals("-3.12500"), "y coordinate in " + locale);
            check(CommandHelper.formatZ(0.25).equals("0.250"), "z coordinate in " + locale);
         }
      } finally {
         Locale.setDefault(previous);
      }
   }

   private static void commandParsing() throws Exception {
      CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
      int[] suggestions = {0};
      dispatcher.register(LiteralArgumentBuilder.<Object>literal("say")
         .then(RequiredArgumentBuilder.<Object, String>argument("message", StringArgumentType.greedyString())
            .suggests((context, builder) -> { suggestions[0]++; return new CompletableFuture<>(); })
            .executes(context -> 1)));
      dispatcher.register(LiteralArgumentBuilder.<Object>literal("route").redirect(dispatcher.getRoot()));
      check(parseError(dispatcher, "say arbitrary") == null, "valid free text accepted without suggestions");
      check(suggestions[0] == 0, "validation must not invoke asynchronous completion providers");
      check(parseError(dispatcher, "say") != null, "missing argument rejected");
      check(parseError(dispatcher, "unknown") != null, "unknown command rejected");
      check(parseError(dispatcher, "route say after redirect") == null, "redirected command accepted");
      check(parseError(dispatcher, "route say") != null, "redirected incomplete command rejected");
   }

   private static Object parseError(CommandDispatcher<Object> dispatcher, String command) throws Exception {
      return invoke(CommandHelper.class, "validateParse", new Class<?>[]{ParseResults.class}, dispatcher.parse(command, new Object()));
   }

   @SuppressWarnings("unchecked")
   private static void placeholders(Minecraft mc) throws Exception {
      ChainedCommandExecutor name = new ChainedCommandExecutor(null, "/say {name}");
      name.start();
      Method confirm = mc.gui.screen().getClass().getDeclaredMethod("onInputConfirmed", String.class);
      confirm.setAccessible(true);
      confirm.invoke(mc.gui.screen(), "$5\\folder");
      check(field(ChainedCommandExecutor.class, "currentCommand").get(name).equals("/say $5\\folder"), "literal replacement preserves dollars and slashes");

      ChainedCommandExecutor aliases = new ChainedCommandExecutor(null, "/say {player_fake} {bot}");
      aliases.start();
      Consumer<String> select = (Consumer<String>)field(mc.gui.screen().getClass(), "onBotSelected").get(mc.gui.screen());
      select.accept("FirstBot");
      select = (Consumer<String>)field(mc.gui.screen().getClass(), "onBotSelected").get(mc.gui.screen());
      select.accept("SecondBot");
      check(field(ChainedCommandExecutor.class, "currentCommand").get(aliases).equals("/say FirstBot SecondBot"), "legacy and current bot placeholders resolve in order");

      ChainedCommandExecutor coords = new ChainedCommandExecutor(null, "/say {coords} {name} {x} {y} {z}");
      List<?> types = (List<?>)field(ChainedCommandExecutor.class, "pendingTypes").get(coords);
      check(types.size() == 2, "shared coordinate placeholders request input once");
      check(PlaceholderResolver.findPlaceholders("tp {x} {y} {z}").size() == 3, "legacy coordinate triplet recognized");
      check(PlaceholderResolver.dummyFor("{x}").equals("1") && PlaceholderResolver.dummyFor("{y}").equals("1") && PlaceholderResolver.dummyFor("{z}").equals("1"), "legacy coordinate dummies remain scalar");
      check(CommandHelper.hasPlaceholders("tp ~ {y} ~"), "individual coordinate placeholder recognized");
      CommandHelper.sendCommand(null);
      CommandHelper.sendCommand(" ");
      CommandHelper.sendCommand("/");
   }

   private static void timerBoundaries() {
      TimedTaskManager.clear();
      TimedTaskManager.addSpawnTask("LongBot", 99999, 99999, 99999);
      var timer = TimedTaskManager.getTask("LongBot");
      long expected = (99999 * 3600L + 99999 * 60L + 99999) * 20L;
      check(timer != null && timer.remainingTicks == expected, "maximum UI duration does not overflow ticks");
      check(timer.getRemainingSeconds() == expected / 20, "maximum UI duration formats correctly");
      TimedTaskManager.addKillTask("LongBot", 0, 0, 1);
      check(TimedTaskManager.getAllTasks().size() == 1 && TimedTaskManager.getTask("LongBot").type == TimedTaskManager.TaskType.KILL, "rescheduling replaces timer");
      timer = TimedTaskManager.getTask("LongBot");
      timer.remainingTicks = 21;
      check(timer.getRemainingSeconds() == 2, "remaining seconds round up");
      timer.remainingTicks = 0;
      check(timer.getRemainingSeconds() == 0, "expired timer display");
      TimedTaskManager.addKillTask("NegativeBot", -1, 60, 1);
      TimedTaskManager.addKillTask("ZeroBot", 0, 0, 0);
      check(TimedTaskManager.getTask("NegativeBot") == null && TimedTaskManager.getTask("ZeroBot") == null, "invalid timers rejected");
      TimedTaskManager.addKillTask("HugeBot", Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
      check(TimedTaskManager.getTask("HugeBot").remainingTicks > 0 && TimedTaskManager.getTask("HugeBot").getRemainingSeconds() == Integer.MAX_VALUE, "API duration saturates display without overflow");
   }

   private static void delayedBatches() throws Exception {
      ChainedCommandExecutor.clearDelayed();
      int[] calls = {0};
      for (int i = 0; i < 200; i++) queue(() -> calls[0]++, 2);
      queue(() -> {
         calls[0]++;
         try { queue(() -> calls[0]++, 1); } catch (Exception e) { throw new RuntimeException(e); }
      }, 1);
      ChainedCommandExecutor.tickDelayed();
      check(calls[0] == 1, "newly queued batches wait until the following tick");
      ChainedCommandExecutor.tickDelayed();
      check(calls[0] == 202, "all due batches run exactly once");
      ChainedCommandExecutor.tickDelayed();
      check(calls[0] == 202, "completed batches removed");
   }

   @SuppressWarnings("unchecked")
   private static void queue(Runnable action, int delay) throws Exception {
      Class<?> batch = Class.forName("com.remrin.client.gui.ChainedCommandExecutor$DelayedBatch");
      var ctor = batch.getDeclaredConstructor(Runnable.class, int.class);
      ctor.setAccessible(true);
      ((List<Object>)field(ChainedCommandExecutor.class, "delayedQueue").get(null)).add(ctor.newInstance(action, delay));
   }

   private static void commandMove() throws Exception {
      Field config = field(CommandConfig.class, "configData");
      Object previous = config.get(null);
      try {
         var testConfig = new CommandConfig.ConfigData();
         var entry = new CommandConfig.CommandEntry("/say keep", "");
         testConfig.categories.get(0).commands.put("keep", entry);
         config.set(null, testConfig);
         CommandConfig.moveCommand("keep", "deleted-target");
         check(CommandConfig.getCommands().get("keep") == entry, "missing destination must not remove the source command");
      } finally {
         config.set(null, previous);
      }
   }

   private static void snapshots() throws Exception {
      invoke(MachineNetworkManager.class, "reset", new Class<?>[0]);
      sync("{\"canEdit\":true,\"canConfig\":true,\"machines\":[{\"id\":\"kept\",\"name\":\"Machine\"}]}");
      int version = MachineNetworkManager.getSyncVersion();
      var machine = MachineNetworkManager.getMachine("kept");
      check(machine != null && MachineNetworkManager.canEdit(), "valid machine snapshot applied");
      sync("{\"canEdit\":false,\"machines\":[{\"id\":\"partial\"},7]}");
      check(MachineNetworkManager.getMachine("kept") == machine && MachineNetworkManager.getMachine("partial") == null, "malformed snapshot cannot partially replace machines");
      check(MachineNetworkManager.canEdit() && MachineNetworkManager.getSyncVersion() == version, "malformed snapshot preserves permissions and version");
      fakeStates("{\"supported\":true,\"players\":{\"Bot\":{\"attack\":true}}}");
      int fakeVersion = MachineNetworkManager.getFakeStatesVersion();
      fakeStates("{\"supported\":true,\"players\":{\"Bot\":{\"attack\":true}}}");
      check(MachineNetworkManager.getFakeStatesVersion() == fakeVersion, "identical fake state does not trigger widget rebuilds");
      fakeStates("{\"supported\":false,\"players\":{\"partial\":{},\"bad\":[]}}");
      check(MachineNetworkManager.isFakePlayerStatesSupported() && MachineNetworkManager.isServerFakePlayer("Bot") && !MachineNetworkManager.isServerFakePlayer("partial"), "malformed fake state remains atomic");
      MachineNetworkManager.removeLocalFakePlayer("Bot");
      check(MachineNetworkManager.getFakeStatesVersion() == fakeVersion + 1, "local removal invalidates state consumers");
   }

   private static void sync(String json) throws Exception {
      invoke(MachineNetworkManager.class, "applySync", new Class<?>[]{String.class}, json);
   }

   private static void fakeStates(String json) throws Exception {
      invoke(MachineNetworkManager.class, "applyFakePlayerStates", new Class<?>[]{String.class}, json);
   }

   private static void disconnect(Minecraft mc) throws Exception {
      int[] calls = {0};
      queue(() -> calls[0]++, 1);
      TimedTaskManager.addKillTask("OldServerBot", 0, 1, 0);
      MachineNetworkManager.setBlockQueryCallback(json -> calls[0]++);
      ClientPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(null, mc);
      check(TimedTaskManager.getAllTasks().isEmpty(), "disconnect clears timed commands");
      ChainedCommandExecutor.tickDelayed();
      check(calls[0] == 0, "disconnect cancels queued commands before another world");
      check(field(MachineNetworkManager.class, "blockQueryCallback").get(null) == null, "disconnect clears block query callback");
      check(!MachineNetworkManager.isServerSupported() && MachineNetworkManager.getMachines().isEmpty(), "disconnect clears server state");
   }

   private static Field field(Class<?> type, String name) throws Exception {
      Field field = type.getDeclaredField(name);
      field.setAccessible(true);
      return field;
   }

   private static Object invoke(Class<?> type, String name, Class<?>[] parameters, Object... arguments) throws Exception {
      Method method = type.getDeclaredMethod(name, parameters);
      method.setAccessible(true);
      return method.invoke(null, arguments);
   }

   private static void check(boolean condition, String message) {
      if (!condition) throw new AssertionError(message);
      checks++;
   }
}
