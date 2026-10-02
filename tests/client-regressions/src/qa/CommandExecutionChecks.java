package qa;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.remrin.client.gui.BotSelectScreen;
import com.remrin.client.gui.ChainedCommandExecutor;
import com.remrin.client.gui.GuiTuning;
import com.remrin.client.gui.PlayerSelectorScreen;
import com.remrin.client.gui.StepEditorScreen;
import com.remrin.client.machine.MachineModels;
import com.remrin.client.machine.MachineNetworkManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

/** Client-thread checks at the title screen; no real config files or world commands are changed. */
public final class CommandExecutionChecks {
   private static int checks;

   private CommandExecutionChecks() {
   }

   public static int run(Minecraft mc) throws Exception {
      checks = 0;
      check(mc.player == null, "execution checks must run before joining a world");
      Screen previous = mc.gui.screen();
      try {
         colors();
         spawnSpacing(mc);
         selectorSnapshots(mc);
         return checks;
      } finally {
         ChainedCommandExecutor.clearDelayed();
         invoke(MachineNetworkManager.class, "reset", new Class<?>[0]);
         mc.gui.setScreen(previous);
      }
   }

   private static void colors() throws Exception {
      Field values = field(GuiTuning.class, "values");
      Field loaded = field(GuiTuning.class, "loaded");
      Object previous = values.get(null);
      boolean wasLoaded = loaded.getBoolean(null);
      try {
         Map<String, Object> parsed = new Gson().fromJson(
            "{\"unsigned\":3758557711,\"opaque\":4294967295,\"signed\":-536409585,\"lower\":-2147483648,\"zero\":0,\"fraction\":123.5,\"tooHigh\":4294967296,\"tooLow\":-2147483649,\"text\":\"red\"}",
            new TypeToken<Map<String, Object>>() {}.getType());
         Map<String, Object> input = new HashMap<>(parsed);
         input.put("nan", Double.NaN);
         input.put("infinite", Double.POSITIVE_INFINITY);
         values.set(null, input);
         loaded.setBoolean(null, true);
         check(GuiTuning.getColor("unsigned", 7) == 0xE0070A0F, "unsigned shipped background preserves ARGB bits");
         check(GuiTuning.getColor("opaque", 7) == -1, "unsigned maximum ARGB remains opaque white");
         check(GuiTuning.getColor("signed", 7) == 0xE0070A0F, "signed and unsigned ARGB are equivalent");
         check(GuiTuning.getColor("lower", 7) == Integer.MIN_VALUE, "signed color lower boundary accepted");
         check(GuiTuning.getColor("zero", 7) == 0, "transparent black is a valid color");
         for (String key : List.of("fraction", "tooHigh", "tooLow", "text", "nan", "infinite", "missing")) {
            check(GuiTuning.getColor(key, 7) == 7, "invalid color falls back: " + key);
         }
         check(GuiTuning.getInt("unsigned", 7) == Integer.MAX_VALUE, "ordinary integer conversion semantics are unchanged");
      } finally {
         values.set(null, previous);
         loaded.setBoolean(null, wasLoaded);
      }
   }

   private static void spawnSpacing(Minecraft mc) throws Exception {
      for (int requested : new int[]{1, 40}) {
         ChainedCommandExecutor.clearDelayed();
         ChainedCommandExecutor.executeMulti(null, List.of("/player {name} spawn", "/say after spawn"), requested);
         Object executor = field(mc.gui.screen().getClass(), "this$0").get(mc.gui.screen());
         Method complete = ChainedCommandExecutor.class.getDeclaredMethod("onExecutionComplete");
         complete.setAccessible(true);
         complete.invoke(executor);
         List<?> queue = (List<?>)field(ChainedCommandExecutor.class, "delayedQueue").get(null);
         int expected = Math.max(20, requested);
         check(queue.size() == 1, "resolved spawn enqueues exactly one continuation");
         check(field(queue.get(0).getClass(), "remainingTicks").getInt(queue.get(0)) == expected,
            "spawn respects both safety minimum and requested command delay: " + requested);
         for (int tick = 1; tick < expected; tick++) ChainedCommandExecutor.tickDelayed();
         check(queue.size() == 1, "spawn continuation does not execute early: " + requested);
         ChainedCommandExecutor.tickDelayed();
         check(queue.isEmpty(), "spawn continuation becomes due at the requested safe interval: " + requested);
      }
      check((int)invoke(ChainedCommandExecutor.class, "sequenceDelay", new Class<?>[]{String.class, int.class}, "/say normal", 40) == 40,
         "ordinary commands retain their configured spacing");
   }

   private static void selectorSnapshots(Minecraft mc) throws Exception {
      invoke(MachineNetworkManager.class, "reset", new Class<?>[0]);
      BotSelectScreen bots = new BotSelectScreen(null, List.of("FallbackBot"), ignored -> {});
      mc.gui.setScreen(bots);
      check(botNames(bots).equals(List.of("FallbackBot")), "selector retains supplied fallback before the first snapshot");
      fakeStates("{\"supported\":true,\"players\":{\"FreshBot\":{}}}");
      bots.tick();
      check(botNames(bots).equals(List.of("FreshBot")), "open bot selector refreshes when its first snapshot arrives");
      fakeStates("{\"supported\":true,\"players\":{}}");
      bots.tick();
      check(botNames(bots).isEmpty(), "authoritative empty snapshot removes stale fallback bots");
      StepEditorScreen stepHost = new StepEditorScreen(null, new MachineModels.Step(), List.of("OfflineConfiguredBot"), () -> {});
      BotSelectScreen configuredBots = new BotSelectScreen(stepHost, List.of("OfflineConfiguredBot"));
      mc.gui.setScreen(configuredBots);
      configuredBots.tick();
      check(botNames(configuredBots).equals(List.of("OfflineConfiguredBot")), "step editor keeps configured offline bots when the authoritative live snapshot is empty");
      Object fixedLifecycle = field(BotSelectScreen.class, "fakePlayerSelection").get(configuredBots);
      check(!field(fixedLifecycle.getClass(), "snapshotRequested").getBoolean(fixedLifecycle), "configured bot picker does not subscribe to live-player snapshots");

      PlayerSelectorScreen players = new PlayerSelectorScreen(null, Component.literal("QA players"), null, PlayerSelectorScreen.FilterMode.ALL);
      mc.gui.setScreen(players);
      fakeStates("{\"supported\":true,\"players\":{\"FirstBot\":{},\"SecondBot\":{}}}");
      players.tick();
      check(playerNames(players).containsAll(List.of("FirstBot", "SecondBot")), "all-player selector receives fake players after opening");
      field(PlayerSelectorScreen.class, "scrollOffset").setInt(players, 100);
      fakeStates("{\"supported\":true,\"players\":{\"ReplacementBot\":{}}}");
      players.tick();
      check(playerNames(players).equals(List.of("ReplacementBot")), "player snapshot replacement removes departed entries");
      check(field(PlayerSelectorScreen.class, "scrollOffset").getInt(players) == 0, "snapshot shrink clamps the player scroll position");
      PlayerSelectorScreen onlyBots = new PlayerSelectorScreen(null, Component.literal("QA bots"), null, PlayerSelectorScreen.FilterMode.ONLY_FAKE_PLAYERS);
      mc.gui.setScreen(onlyBots);
      check(playerNames(onlyBots).equals(List.of("ReplacementBot")), "fake-only selector reads the available authoritative snapshot");
      fakeStates("{\"supported\":true,\"players\":{}}");
      onlyBots.tick();
      check(playerNames(onlyBots).isEmpty(), "fake-only selector updates to an empty snapshot");
      Object lifecycle = field(PlayerSelectorScreen.class, "fakePlayerSelection").get(onlyBots);
      field(lifecycle.getClass(), "snapshotRequested").setBoolean(lifecycle, true);
      mc.gui.setScreen(null);
      check(!field(lifecycle.getClass(), "snapshotRequested").getBoolean(lifecycle), "leaving the selector releases its subscription state");
   }

   @SuppressWarnings("unchecked")
   private static List<String> botNames(BotSelectScreen screen) throws Exception {
      return (List<String>)field(BotSelectScreen.class, "botNames").get(screen);
   }

   @SuppressWarnings("unchecked")
   private static List<String> playerNames(PlayerSelectorScreen screen) throws Exception {
      return ((List<PlayerInfo>)field(PlayerSelectorScreen.class, "players").get(screen)).stream().map(info -> info.getProfile().name()).toList();
   }

   private static void fakeStates(String json) throws Exception {
      invoke(MachineNetworkManager.class, "applyFakePlayerStates", new Class<?>[]{String.class}, json);
   }

   private static Field field(Class<?> type, String name) throws Exception {
      Field field = type.getDeclaredField(name);
      field.setAccessible(true);
      return field;
   }

   private static Object invoke(Class<?> type, String name, Class<?>[] types, Object... args) throws Exception {
      Method method = type.getDeclaredMethod(name, types);
      method.setAccessible(true);
      return method.invoke(null, args);
   }

   private static void check(boolean condition, String message) {
      if (!condition) throw new AssertionError(message);
      checks++;
   }
}
