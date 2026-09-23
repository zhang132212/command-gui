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
      // Resolve API methods once per snapshot, rather than for every rule/category.
      // Do not cache rule values: native Carpet commands can change them at any time.
      var identifier = managerApi.getMethod("identifier");
      var managerLocked = managerApi.getMethod("locked");
      var getRules = managerApi.getMethod("getCarpetRules");
      var ruleName = ruleApi.getMethod("name");
      var ruleCategories = ruleApi.getMethod("categories");
      var translatedCategory = helper.getMethod("translatedCategory", String.class, String.class);
      var translatedName = helper.getMethod("translatedName", ruleApi);
      var translatedDescription = helper.getMethod("translatedDescription", ruleApi);
      var extraInfo = ruleApi.getMethod("extraInfo");
      var suggestions = ruleApi.getMethod("suggestions");
      var toRuleString = helper.getMethod("toRuleString", Object.class);
      var value = ruleApi.getMethod("value");
      var defaultValue = ruleApi.getMethod("defaultValue");
      var type = ruleApi.getMethod("type");
      var strict = ruleApi.getMethod("strict");
      List<Object> managers = new ArrayList<>();
      carpet.getMethod("forEachManager", Consumer.class).invoke(null, (Consumer<Object>)managers::add);
      List<RuleData> result = new ArrayList<>();
      for (Object manager : managers) {
         if (manager == null) continue;
         String id = (String)identifier.invoke(manager);
         boolean locked = (boolean)managerLocked.invoke(manager);
         if (!RuleData.token(id)) continue;
         for (Object rule : (Collection<?>)getRules.invoke(manager)) {
            String name = (String)ruleName.invoke(rule);
            if (!RuleData.token(name)) continue;
            List<String> categories = strings(ruleCategories.invoke(rule));
            List<String> labels = new ArrayList<>();
            for (String category : categories) labels.add((String)translatedCategory.invoke(null, id, category));
            String title = (String)translatedName.invoke(null, rule);
            String description = (String)translatedDescription.invoke(null, rule);
            for (Object info : (List<?>)extraInfo.invoke(rule)) {
               if (info instanceof Component text) description += "\n" + text.getString();
            }
            result.add(new RuleData(id, name, title, description.substring(0, Math.min(3000, description.length())),
               categories, labels, strings(suggestions.invoke(rule)),
               (String)toRuleString.invoke(null, value.invoke(rule)),
               (String)toRuleString.invoke(null, defaultValue.invoke(rule)),
               ((Class<?>)type.invoke(rule)).getSimpleName(),
               (boolean)strict.invoke(rule), locked));
         }
      }
      result.sort(Comparator.comparing(RuleData::manager).thenComparing(RuleData::name));
      return result;
   }
   private static List<String> strings(Object values) {
      return ((Collection<?>)values).stream().map(Object::toString).toList();
   }
}
