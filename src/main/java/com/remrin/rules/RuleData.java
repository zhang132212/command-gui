package com.remrin.rules;

import java.util.List;

/** A server snapshot, not a locally inferred rule catalogue. */
public record RuleData(String manager, String name, String title, String description,
                       List<String> categories, List<String> categoryLabels, List<String> options,
                       String value, String builtinDefault, String type, boolean strict, boolean locked) {
   public String key() { return manager + ":" + name; }
   public record Change(String key, String expected, String operation, String target) {}
   public static boolean token(String value) { return value != null && value.matches("[A-Za-z0-9_.-]+"); }
   public String command(String operation, String target) {
      if (!token(manager) || !token(name)) throw new IllegalArgumentException("无效规则标识");
      String prefix = switch (operation) {
         case "set" -> manager + " " + name;
         case "setDefault" -> manager + " setDefault " + name;
         case "removeDefault" -> manager + " removeDefault " + name;
         default -> throw new IllegalArgumentException("未知规则操作");
      };
      if (operation.equals("removeDefault")) return prefix;
      if (target == null || target.isBlank() || target.chars().anyMatch(Character::isISOControl))
         throw new IllegalArgumentException("请输入有效规则值");
      if (strict && !options.contains(target)) throw new IllegalArgumentException("请选择规则支持的值");
      String command = prefix + " " + target;
      if (command.length() > 256) throw new IllegalArgumentException("规则指令不能超过 256 字符");
      return command;
   }
}
