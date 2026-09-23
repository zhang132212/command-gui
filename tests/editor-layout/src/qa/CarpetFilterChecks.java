package qa;

import com.remrin.client.gui.*;
import com.remrin.client.rules.CarpetRuleClient;
import com.remrin.rules.RuleData;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import java.util.*;

/** Exercises real checkbox callbacks and combined filters using an isolated snapshot. */
final class CarpetFilterChecks {
   static void run(CommandGUIScreen parent) throws Exception {
      var source = CarpetRuleClient.class.getDeclaredField("rules"); source.setAccessible(true);
      Object previous = source.get(null);
      var pending = new LinkedHashMap<>(CarpetRuleClient.pending);
      try {
         source.set(null, List.of(rule("base", "on", "true"), rule("base", "off", "false"),
            rule("base", "number", "0"), rule("ext", "text", "ops"), rule("ext", "upper", "TRUE")));
         CarpetRuleClient.pending.clear();
         var tab = new CarpetRulesTab(parent);
         tab.doLayout(new ScreenRectangle(12, 40, 420, 180));
         check(tab.getRulesTop() == 40 && tab.getRulesHeight() == 152, "filters no longer consume rule rows");
         check(tab.getButtons().get(0).getY() >= 220, "filters moved below rule area");
         expect(tab, "on", "off", "number", "text", "upper");
         check(((MarkCheckbox)tab.getButtons().get(0)).selected() && ((MarkCheckbox)tab.getButtons().get(1)).selected(), "default checked");
         tab.getButtons().get(0).onPress(null); expect(tab, "off", "number", "text");
         tab.getButtons().get(1).onPress(null); expect(tab, "number", "text");
         tab.doLayout(new ScreenRectangle(12, 40, 260, 140)); expect(tab, "number", "text");
         check(!((MarkCheckbox)tab.getButtons().get(0)).selected() && !((MarkCheckbox)tab.getButtons().get(1)).selected(), "resize preserves filters");
         tab.getButtons().get(0).onPress(null); expect(tab, "on", "number", "text", "upper");
         CarpetRuleClient.pending.put("base:off", new RuleData.Change("base:off", "false", "set", "true"));
         tab.tickRules(); expect(tab, "on", "number", "text", "upper");
         check(CarpetRuleClient.pending.containsKey("base:off"), "filter preserves pending changes");
         tab.getButtons().get(1).onPress(null); expect(tab, "on", "off", "number", "text", "upper");
         tab.setSearchText("number"); expect(tab, "number");
         tab.setSearchText("");
         tab.getCategoryButtons().stream().filter(b -> b.getMessage().getString().equals("ext")).findFirst().orElseThrow().onPress(null);
         expect(tab, "text", "upper");
         tab.getButtons().get(0).onPress(null); expect(tab, "text");
         tab.getButtons().get(1).onPress(null); expect(tab, "text");
         tab.setSearchText("missing"); expect(tab);
         var buttons = tab.getButtons();
         for (int i = 0; i < buttons.size(); i++) for (int j = i + 1; j < buttons.size(); j++) {
            var a = buttons.get(i); var b = buttons.get(j);
            check(!(a.getX() < b.getRight() && a.getRight() > b.getX() && a.getY() < b.getBottom() && a.getBottom() > b.getY()), "filter/footer overlap");
         }
         System.out.println("CARPET_FILTER_QA_COMPLETE combinations=4 search=ok category=ok pending=ok resize=ok");
      } finally {
         source.set(null, previous); CarpetRuleClient.pending.clear(); CarpetRuleClient.pending.putAll(pending);
      }
   }
   private static RuleData rule(String manager, String name, String value) {
      return new RuleData(manager, name, name, "", List.of(), List.of(), List.of(), value, value, "string", false, false);
   }
   private static void expect(CarpetRulesTab tab, String... names) throws Exception {
      var f = CarpetRulesTab.class.getDeclaredField("filtered"); f.setAccessible(true);
      var actual = ((List<RuleData>)f.get(tab)).stream().map(RuleData::name).collect(java.util.stream.Collectors.toSet());
      check(actual.equals(Set.of(names)), "expected " + Arrays.toString(names) + " got " + actual);
   }
   private static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
}
