/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.blaze3d.platform.InputConstants$Key
 *  com.mojang.blaze3d.platform.InputConstants$Type
 *  net.minecraft.client.KeyMapping
 *  net.minecraft.client.KeyMapping$Category
 *  org.lwjgl.glfw.GLFW
 */
package com.remrin.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.remrin.client.config.CommandConfig;
import com.remrin.client.config.SettingsConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class CommandShortcut {
    public static final int CTRL = 341;
    public static final int ALT = 342;
    public static final int SHIFT = 340;

    private CommandShortcut() {
    }

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        ArrayList<Integer> keys = new ArrayList<Integer>();
        for (String part : text.trim().toLowerCase(Locale.ROOT).split("\\+")) {
            int code = CommandShortcut.parseKeyCode(part.trim());
            if (code < 0) continue;
            keys.add(code);
        }
        return CommandShortcut.normalizeCodes(keys);
    }

    public static String normalizeCodes(List<Integer> keys) {
        if (keys == null || keys.isEmpty()) {
            return "";
        }
        boolean leftCtrl = keys.contains(341);
        boolean rightCtrl = keys.contains(345);
        boolean leftAlt = keys.contains(342);
        boolean rightAlt = keys.contains(346);
        boolean leftShift = keys.contains(340);
        boolean rightShift = keys.contains(344);
        boolean ctrl = leftCtrl || rightCtrl;
        boolean alt = leftAlt || rightAlt;
        boolean shift = leftShift || rightShift;
        ArrayList<Integer> main = new ArrayList<Integer>();
        for (int key : keys) {
            if (CommandShortcut.isModifierKey(key)) continue;
            main.add(key);
        }
        main.sort(null);
        int total = (ctrl ? 1 : 0) + (alt ? 1 : 0) + (shift ? 1 : 0) + main.size();
        if (total == 0 || total > 3) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (ctrl) {
            sb.append(leftCtrl ? 341 : 345).append('+');
        }
        if (alt) {
            sb.append(leftAlt ? 342 : 346).append('+');
        }
        if (shift) {
            sb.append(leftShift ? 340 : 344).append('+');
        }
        for (int i = 0; i < main.size(); ++i) {
            if (i > 0) {
                sb.append('+');
            }
            sb.append(main.get(i));
        }
        return sb.toString();
    }

    public static String display(String shortcut) {
        String normalized = CommandShortcut.normalize(shortcut);
        if (normalized.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : normalized.split("\\+")) {
            if (sb.length() > 0) {
                sb.append('+');
            }
            sb.append(CommandShortcut.keyDisplayName(Integer.parseInt(part)));
        }
        return sb.toString();
    }

    public static String displayCaptured(List<Integer> keys, int maxKeys) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); ++i) {
            if (i > 0) {
                sb.append('+');
            }
            sb.append(CommandShortcut.keyDisplayName(keys.get(i)));
        }
        if (keys.size() > 0 && keys.size() < maxKeys) {
            sb.append('+');
        }
        return sb.toString();
    }

    public static String findConflict(String shortcut, String selfName) {
        return findConflict(shortcut, selfName, selfName, -1);
    }

    public static String findConflict(String shortcut, String selfName, String selfOldName, int selfCustomIndex) {
        String normalized = CommandShortcut.normalize(shortcut);
        List<Integer> keys = CommandShortcut.parse(normalized);
        if (normalized.isEmpty() || keys.isEmpty()) {
            return null;
        }
        for (CommandConfig.Category category : CommandConfig.getCategories()) {
            for (Map.Entry<String, CommandConfig.CommandEntry> entry : category.commands.entrySet()) {
                if (selfOldName != null && entry.getKey().equals(selfOldName)) continue;
                if (!CommandShortcut.shortcutsConflict(shortcut, entry.getValue().shortcut)) continue;
                return "\u6307\u4ee4\u300c" + entry.getKey() + "\u300d";
            }
        }
        List<Map<String, Object>> customs = SettingsConfig.getCustomFakePlayerCommandList();
        for (int i = 0; i < customs.size(); i++) {
            if (selfCustomIndex >= 0 && i == selfCustomIndex) continue;
            Map<String, Object> custom = customs.get(i);
            String customName = custom.get("name") instanceof String name ? name : "";
            String customShortcut = custom.get("shortcut") instanceof String s ? s : "";
            if (!CommandShortcut.shortcutsConflict(shortcut, customShortcut)) continue;
            return "\u81ea\u5b9a\u4e49\u6307\u4ee4\u300c" + customName + "\u300d";
        }
        String vanilla = CommandShortcut.scanVanillaKeyMappings(keys);
        if (vanilla != null) {
            return vanilla;
        }
        return CommandShortcut.scanMalilibKeybinds(keys);
    }

    private static String scanVanillaKeyMappings(List<Integer> keys) {
        if (keys.isEmpty()) {
            return null;
        }
        LinkedHashSet<KeyMapping> mappings = new LinkedHashSet<KeyMapping>();
        try {
            for (Field field : KeyMapping.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || !Map.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object value = field.get(null);
                if (!(value instanceof Map)) continue;
                Map map = (Map)value;
                for (Object entry : map.values()) {
                    if (entry instanceof KeyMapping) {
                        KeyMapping mapping = (KeyMapping)entry;
                        mappings.add(mapping);
                        continue;
                    }
                    if (!(entry instanceof List)) continue;
                    List list = (List)entry;
                    for (Object item : list) {
                        if (!(item instanceof KeyMapping)) continue;
                        KeyMapping mapping = (KeyMapping)item;
                        mappings.add(mapping);
                    }
                }
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
        boolean hasMainKey = false;
        for (int key : keys) {
            if (!CommandShortcut.isModifierKey(key)) {
                hasMainKey = true;
                break;
            }
        }
        for (KeyMapping mapping : mappings) {
            if (mapping.isUnbound()) continue;
            for (int key : keys) {
                InputConstants.Key target;
                if ((CommandShortcut.isModifierKey(key) && hasMainKey) || !mapping.matches(target = InputConstants.Type.KEYSYM.getOrCreate(key))) continue;
                String category = "?";
                try {
                    KeyMapping.Category cat = mapping.getCategory();
                    if (cat != null && cat.id() != null) {
                        category = cat.id().toString();
                    }
                }
                catch (Exception exception) {
                    // empty catch block
                }
                return "\u6309\u952e " + CommandShortcut.keyDisplayName(key) + "\uff08" + category + "/" + mapping.getTranslatedKeyMessage().getString() + "\uff09";
            }
        }
        return null;
    }

    private static String scanMalilibKeybinds(List<Integer> keys) {
        if (keys.isEmpty()) {
            return null;
        }
        Set<Integer> ours = CommandShortcut.normalizeKeySet(keys);
        try {
            Class<?> handlerClass = Class.forName("fi.dy.masa.malilib.event.InputEventHandler");
            Object manager = handlerClass.getMethod("getKeybindManager", new Class[0]).invoke(null, new Object[0]);
            if (manager == null) {
                return null;
            }
            Object categories = manager.getClass().getMethod("getKeybindCategories", new Class[0]).invoke(manager, new Object[0]);
            if (!(categories instanceof List)) {
                return null;
            }
            List categoryList = (List)categories;
            for (Object category : categoryList) {
                if (category == null) continue;
                String modName = String.valueOf(category.getClass().getMethod("getModName", new Class[0]).invoke(category, new Object[0]));
                Object hotkeys = category.getClass().getMethod("getHotkeys", new Class[0]).invoke(category, new Object[0]);
                if (!(hotkeys instanceof List)) continue;
                List hotkeyList = (List)hotkeys;
                for (Object hotkey : hotkeyList) {
                    List theirKeys;
                    Object keysObject;
                    Object keybind;
                    if (hotkey == null || (keybind = hotkey.getClass().getMethod("getKeybind", new Class[0]).invoke(hotkey, new Object[0])) == null || !((keysObject = keybind.getClass().getMethod("getKeys", new Class[0]).invoke(keybind, new Object[0])) instanceof List) || (theirKeys = (List)keysObject).isEmpty()) continue;
                    HashSet<Integer> theirs = new HashSet<Integer>();
                    for (Object o : theirKeys) {
                        if (!(o instanceof Integer)) continue;
                        Integer code = (Integer)o;
                        theirs.add(CommandShortcut.normalizeKeyCode(code));
                    }
                    if (theirs.isEmpty() || !CommandShortcut.isSubset(ours, theirs) && !CommandShortcut.isSubset(theirs, ours)) continue;
                    String name = "?";
                    try {
                        Object translated = hotkey.getClass().getMethod("getTranslatedName", new Class[0]).invoke(hotkey, new Object[0]);
                        if (translated != null) {
                            name = String.valueOf(translated);
                        }
                    }
                    catch (Exception translated) {
                        // empty catch block
                    }
                    String display = String.valueOf(keybind.getClass().getMethod("getKeysDisplayString", new Class[0]).invoke(keybind, new Object[0]));
                    return "\u6a21\u7ec4 " + modName + " \u7684\u300c" + name + "\u300d\uff08" + display + "\uff09";
                }
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
        return null;
    }

    private static Set<Integer> normalizeKeySet(List<Integer> keys) {
        HashSet<Integer> set = new HashSet<Integer>();
        for (int key : keys) {
            set.add(CommandShortcut.normalizeKeyCode(key));
        }
        set.removeIf(code -> code < 0);
        return set;
    }

    private static int normalizeKeyCode(int key) {
        if (key == 345) {
            return 341;
        }
        if (key == 346) {
            return 342;
        }
        if (key == 344) {
            return 340;
        }
        return key;
    }

    private static Set<Integer> rawKeySet(String shortcut) {
        return new HashSet<Integer>(CommandShortcut.parse(CommandShortcut.normalize(shortcut)));
    }

    private static boolean shortcutsConflict(String a, String b) {
        Set<Integer> setA = CommandShortcut.rawKeySet(a);
        Set<Integer> setB = CommandShortcut.rawKeySet(b);
        if (setA.isEmpty() || setB.isEmpty()) {
            return false;
        }
        return setA.containsAll(setB) || setB.containsAll(setA);
    }

    private static boolean isSubset(Set<Integer> a, Set<Integer> b) {
        return !a.isEmpty() && b.containsAll(a);
    }

    public static boolean isPressed(String shortcut, long window) {
        String normalized = CommandShortcut.normalize(shortcut);
        List<Integer> keys = CommandShortcut.parse(normalized);
        if (keys.isEmpty() || window == 0L) {
            return false;
        }
        for (int key : keys) {
            if (GLFW.glfwGetKey((long)window, (int)key) == 1) continue;
            return false;
        }
        return true;
    }

    public static boolean isModifierKey(int key) {
        return key == 341 || key == 345 || key == 342 || key == 346 || key == 340 || key == 344;
    }

    private static List<Integer> parse(String normalized) {
        ArrayList<Integer> keys = new ArrayList<Integer>();
        if (normalized == null || normalized.isEmpty()) {
            return keys;
        }
        for (String part : normalized.split("\\+")) {
            try {
                keys.add(Integer.parseInt(part));
            }
            catch (NumberFormatException numberFormatException) {
                // empty catch block
            }
        }
        return keys;
    }

    private static String keyDisplayName(int keyCode) {
        try {
            return InputConstants.Type.KEYSYM.getOrCreate(keyCode).getDisplayName().getString();
        }
        catch (Exception e) {
            return "Key " + keyCode;
        }
    }

    private static int parseKeyCode(String token) {
        try {
            return Integer.parseInt(token);
        }
        catch (NumberFormatException numberFormatException) {
            if (token.length() == 1 && token.charAt(0) >= 'a' && token.charAt(0) <= 'z') {
                return 65 + (token.charAt(0) - 97);
            }
            if (token.length() == 1 && token.charAt(0) >= '0' && token.charAt(0) <= '9') {
                return 48 + (token.charAt(0) - 48);
            }
            return switch (token) {
                case "ctrl", "control", "lctrl", "leftctrl", "lcontrol", "leftcontrol", "left ctrl", "left control" -> {
                    int var3_4;
                    yield var3_4 = 341;
                }
                case "rctrl", "rightctrl", "rcontrol", "rightcontrol", "right ctrl", "right control" -> {
                    int var3_4;
                    yield var3_4 = 345;
                }
                case "alt", "lalt", "leftalt", "left alt" -> {
                    int var3_5;
                    yield var3_5 = 342;
                }
                case "ralt", "rightalt", "right alt" -> {
                    int var3_5;
                    yield var3_5 = 346;
                }
                case "shift", "lshift", "leftshift", "left shift" -> {
                    int var3_6;
                    yield var3_6 = 340;
                }
                case "rshift", "rightshift", "right shift" -> {
                    int var3_6;
                    yield var3_6 = 344;
                }
                case "space" -> {
                    int var3_7;
                    yield var3_7 = 32;
                }
                case "enter", "return" -> {
                    int var3_8;
                    yield var3_8 = 257;
                }
                case "tab" -> {
                    int var3_9;
                    yield var3_9 = 258;
                }
                case "esc", "escape" -> {
                    int var3_10;
                    yield var3_10 = 256;
                }
                case "backspace" -> {
                    int var3_11;
                    yield var3_11 = 259;
                }
                case "up" -> {
                    int var3_12;
                    yield var3_12 = 265;
                }
                case "down" -> {
                    int var3_13;
                    yield var3_13 = 264;
                }
                case "left" -> {
                    int var3_14;
                    yield var3_14 = 263;
                }
                case "right" -> {
                    int var3_15;
                    yield var3_15 = 262;
                }
                case "home" -> {
                    int var3_16;
                    yield var3_16 = 268;
                }
                case "end" -> {
                    int var3_17;
                    yield var3_17 = 269;
                }
                case "pageup" -> {
                    int var3_18;
                    yield var3_18 = 266;
                }
                case "pagedown" -> {
                    int var3_19;
                    yield var3_19 = 267;
                }
                default -> {
                    int var3_21;
                    if (token.length() >= 2 && token.charAt(0) == 'f' && token.length() <= 3) {
                        try {
                            int n = Integer.parseInt(token.substring(1));
                            if (n >= 1 && n <= 12) {
                                int var3_20;
                                yield var3_20 = 290 + n - 1;
                            }
                        }
                        catch (NumberFormatException var4_23) {
                            // empty catch block
                        }
                    }
                    yield var3_21 = -1;
                }
            };
        }
    }
}

