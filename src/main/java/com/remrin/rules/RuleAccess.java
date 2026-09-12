package com.remrin.rules;

import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.PermissionSet;

public final class RuleAccess {
   private RuleAccess() {}
   public static boolean allowed(PermissionSet permissions, boolean integratedServer, boolean cheats) {
      return Commands.LEVEL_MODERATORS.check(permissions) && (!integratedServer || cheats);
   }
}
