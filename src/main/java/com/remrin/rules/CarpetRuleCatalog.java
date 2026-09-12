package com.remrin.rules;

import java.util.*;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;

/** Optional Carpet API adapter: no hard dependency and no rule mutation through reflection. */
public final class CarpetRuleCatalog {
   private CarpetRuleCatalog() {}
   public static List<RuleData> read() throws ReflectiveOperationException {
      Class<?> carpet = Class.forName("carpet.CarpetServer");
      Class<?> managerApi = Class.forName("carpet.api.settings.SettingsManager");
      Class<?> ruleApi = Class.forName("carpet.api.settings.CarpetRule");
      Class<?> helper = Class.forName("carpet.api.settings.RuleHelper");
      List<Object> managers = new ArrayList<>();
      carpet.getMethod("forEachManager", Consumer.class).invoke(null, (Consumer<Object>)managers::add);
      List<RuleData> result = new ArrayList<>();
      for (Object manager : managers) {
         if (manager == null) continue;
         String id = (String)managerApi.getMethod("identifier").invoke(manager);
         boolean locked = (boolean)managerApi.getMethod("locked").invoke(manager);
         if (!RuleData.token(id)) continue;
         for (Object rule : (Collection<?>)managerApi.getMethod("getCarpetRules").invoke(manager)) {
            String name = (String)ruleApi.getMethod("name").invoke(rule);
            if (!RuleData.token(name)) continue;
            List<String> categories = strings(ruleApi.getMethod("categories").invoke(rule));
            List<String> labels = new ArrayList<>();
            for (String category : categories) labels.add((String)helper.getMethod("translatedCategory", String.class, String.class).invoke(null, id, category));
            String title = (String)helper.getMethod("translatedName", ruleApi).invoke(null, rule);
            String description = (String)helper.getMethod("translatedDescription", ruleApi).invoke(null, rule);
            for (Object info : (List<?>)ruleApi.getMethod("extraInfo").invoke(rule)) {
               if (info instanceof Component text) description += "\n" + text.getString();
            }
            result.add(new RuleData(id, name, title, description.substring(0, Math.min(3000, description.length())),
               categories, labels, strings(ruleApi.getMethod("suggestions").invoke(rule)),
               (String)helper.getMethod("toRuleString", Object.class).invoke(null, ruleApi.getMethod("value").invoke(rule)),
               (String)helper.getMethod("toRuleString", Object.class).invoke(null, ruleApi.getMethod("defaultValue").invoke(rule)),
               ((Class<?>)ruleApi.getMethod("type").invoke(rule)).getSimpleName(),
               (boolean)ruleApi.getMethod("strict").invoke(rule), locked));
         }
      }
      result.sort(Comparator.comparing(RuleData::manager).thenComparing(RuleData::name));
      return result;
   }
   private static List<String> strings(Object values) {
      return ((Collection<?>)values).stream().map(Object::toString).toList();
   }
}
