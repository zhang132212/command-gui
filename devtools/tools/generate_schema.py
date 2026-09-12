#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""根据已接入 GuiTuning 的 Java 源码，生成/刷新调优参数表。"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GUI_DIR = ROOT / "src" / "client" / "java" / "com" / "remrin" / "client" / "gui"
SCHEMA_PATH = ROOT / "devtools" / "tuning-schema.json"
TUNING_PATH = ROOT / "devtools" / "gui-tuning.json"

GROUPS = {
    "GuiTheme": "液态玻璃主题",
    "CommandGUIScreen": "主窗口与底栏",
    "AbstractCommandTab": "通用命令网格",
    "CustomCommandTab": "快捷指令页",
    "MachineSwitchTab": "机器开关页",
    "FakePlayerTab": "假人页",
    "SettingsScreen": "设置页",
    "ScrollbarHandle": "滚动条样式",
}

LABELS = {
    "GuiTheme.BACKGROUND": "回退底色",
    "GuiTheme.SCRIM_TOP": "背景压暗(上)",
    "GuiTheme.SCRIM_BOTTOM": "背景压暗(下)",
    "GuiTheme.PANEL": "玻璃底板",
    "GuiTheme.PANEL_STRONG": "玻璃底板(实)",
    "GuiTheme.SURFACE": "控件底板",
    "GuiTheme.HOVER": "控件悬停",
    "GuiTheme.SELECTED": "选中态",
    "GuiTheme.BORDER": "玻璃棱边",
    "GuiTheme.BORDER_DARK": "玻璃底边暗线",
    "GuiTheme.SHEEN": "顶部光泽",
    "GuiTheme.SHADOW": "投影",
    "GuiTheme.ACCENT": "强调色",
    "GuiTheme.ACCENT_SOFT": "强调色柔光",
    "GuiTheme.TEXT": "正文色",
    "GuiTheme.MUTED": "次要文字",
    "GuiTheme.DISABLED": "禁用色",
    "GuiTheme.DANGER": "危险色",
    "GuiTheme.WARNING": "警告色",
    "GuiTheme.RADIUS": "控件圆角",
    "GuiTheme.PANEL_RADIUS": "面板圆角",
    "GuiTheme.BLUR_ENABLED": "强制磨砂(0/1)",
    "CommandGUIScreen.FOOTER_HEIGHT": "底栏高度",
    "CommandGUIScreen.FOOTER_CONTROL_TOP_OFFSET": "底栏按钮垂直偏移",
    "CommandGUIScreen.PADDING": "页面左右留白",
    "CommandGUIScreen.SCROLLBAR_WIDTH": "主滚动条宽度",
    "CommandGUIScreen.RIGHT_MARGIN": "右侧边距",
    "CommandGUIScreen.SEARCH_WIDTH": "搜索框宽度",
    "CommandGUIScreen.CLOSE_BUTTON_WIDTH": "关闭按钮宽度",
    "CommandGUIScreen.BATCH_BUTTON_WIDTH": "批量假人按钮宽度",
    "CommandGUIScreen.TAB_AREA_TOP_GAP": "标签页下留白",
    "CommandGUIScreen.TAB_AREA_BOTTOM_GAP": "标签页底部留白",
    "AbstractCommandTab.ITEM_HEIGHT": "命令按钮行高",
    "AbstractCommandTab.COLUMNS": "命令网格列数",
    "AbstractCommandTab.CATEGORY_TAB_WIDTH": "分类标签基础宽度",
    "AbstractCommandTab.CATEGORY_TAB_HEIGHT": "分类标签高度",
    "AbstractCommandTab.CATEGORY_TAB_GAP": "分类标签间距",
    "AbstractCommandTab.CATEGORY_SCROLLBAR_WIDTH": "分类滚动条宽度",
    "AbstractCommandTab.CATEGORY_COMMAND_GAP": "分类区与命令区间距",
    "AbstractCommandTab.ITEM_VERTICAL_PAD": "命令按钮上下内缩",
    "AbstractCommandTab.ITEM_HORIZONTAL_PAD": "命令按钮左右内缩",
    "AbstractCommandTab.COLUMN_GAP": "命令列间距",
    "CustomCommandTab.SIDEBAR_OFFSET": "分类栏左边距",
    "CustomCommandTab.CATEGORY_MIN_WIDTH": "分类栏最小宽度",
    "CustomCommandTab.CATEGORY_WIDTH_DIVISOR": "分类栏宽度除数(屏宽/n)",
    "CustomCommandTab.CATEGORY_WIDTH_PERCENT": "分类栏缩放百分比",
    "CustomCommandTab.CATEGORY_INNER_MARGIN": "分类按钮内边距",
    "CustomCommandTab.CATEGORY_BOTTOM_RESERVE": "分类栏底部预留",
    "CustomCommandTab.MAX_CLUSTER_WIDTH": "右侧搜索/模式区宽度",
    "CustomCommandTab.COLUMNS": "快捷指令列数",
    "CustomCommandTab.VISIBLE_ROWS": "快捷指令可视行数",
    "CustomCommandTab.MIN_ROW_HEIGHT": "快捷指令最小行高",
    "CustomCommandTab.MIN_COLUMN_GAP": "快捷指令最小列间距",
    "CustomCommandTab.BUTTON_SCALE_PERCENT": "按钮缩放百分比",
    "MachineSwitchTab.SIDEBAR_OFFSET": "分类栏左边距",
    "MachineSwitchTab.CATEGORY_MIN_WIDTH": "分类栏最小宽度",
    "MachineSwitchTab.CATEGORY_WIDTH_DIVISOR": "分类栏宽度除数(屏宽/n)",
    "MachineSwitchTab.CATEGORY_INNER_MARGIN": "分类按钮内边距",
    "MachineSwitchTab.CATEGORY_BOTTOM_RESERVE": "分类栏底部预留",
    "MachineSwitchTab.MAX_CLUSTER_WIDTH": "右侧模式区宽度",
    "MachineSwitchTab.MODES_BTN_W": "模式按钮宽度",
    "MachineSwitchTab.REFRESH_BTN_W": "检测刷新按钮宽度",
    "MachineSwitchTab.CLUSTER_GAP": "按钮簇间距",
    "FakePlayerTab.PLAYER_ITEM_HEIGHT": "玩家条目高度",
    "FakePlayerTab.PLAYER_ITEM_MIN_WIDTH": "玩家列表最小宽度",
    "FakePlayerTab.PLAYER_ITEM_WIDTH_DIVISOR": "玩家列表宽度除数(屏宽/n)",
    "FakePlayerTab.PLAYER_LIST_SIDE_PAD": "玩家列表左侧内边距",
    "FakePlayerTab.LIST_BOTTOM_RESERVE": "玩家列表底部预留",
    "FakePlayerTab.CHECKBOX_SIZE": "多选框尺寸",
    "FakePlayerTab.CHECKBOX_X_OFFSET": "多选框左边距",
    "FakePlayerTab.CHECKBOX_TOP_PAD": "多选框上边距",
    "FakePlayerTab.FACE_SIZE": "头像尺寸",
    "FakePlayerTab.FACE_PAD_LEFT": "头像左边距",
    "FakePlayerTab.NAME_PAD_LEFT": "名字左边距",
    "FakePlayerTab.SEPARATOR_GAP": "列表与操作面板间距",
    "FakePlayerTab.PANEL_RIGHT_PAD": "操作面板右边距",
    "FakePlayerTab.PANEL_WIDTH_REFERENCE": "操作面板参考宽度",
    "FakePlayerTab.ACTION_BUTTON_HEIGHT": "操作按钮高度",
    "FakePlayerTab.PANEL_SCROLL_STEP": "面板滚动步长",
    "FakePlayerTab.SCROLLBAR_WIDTH": "假人页滚动条宽度",
    "SettingsScreen.TITLE_X": "标题 X",
    "SettingsScreen.TITLE_Y": "标题 Y",
    "SettingsScreen.TAB_Y": "设置分区标签 Y",
    "SettingsScreen.TAB_HEIGHT": "设置分区标签高度",
    "SettingsScreen.TAB_GAP": "设置分区标签间距",
    "SettingsScreen.RIGHT_MARGIN": "右侧边距",
    "SettingsScreen.SCROLLBAR_WIDTH": "滚动条宽度",
    "SettingsScreen.CONTENT_TOP": "内容区顶部 Y",
    "SettingsScreen.CONTENT_TOP_GAP": "内容区内边距",
    "SettingsScreen.ROW_HEIGHT": "设置行高",
    "SettingsScreen.SETTING_BUTTON_WIDTH": "设置开关宽度",
    "SettingsScreen.SETTING_BUTTON_HEIGHT": "设置开关高度",
    "ScrollbarHandle.TRACK_COLOR": "滚动条轨道颜色",
    "ScrollbarHandle.THUMB_COLOR": "滚动条滑块颜色",
    "ScrollbarHandle.THUMB_HOVER_COLOR": "滚动条滑块悬停颜色",
    "ScrollbarHandle.THUMB_OUTLINE": "滚动条滑块描边",
    "ScrollbarHandle.MIN_THUMB_HEIGHT": "滑块最小高度",
}

DECL_RE = re.compile(r'^(?:\s*)(?:private|protected|public)\s+static\s+final\s+(int|float|double)\s+([A-Z][A-Z0-9_]*)\s*=\s*([^;]+);')
CALL_RE = re.compile(r'GuiTuning\.get(?:Int|Float|Double|Color)\(\s*"([^"]+)"\s*,\s*([^)]+)\)')


def is_color_key(key):
    if key.endswith("_COLOR") or key.endswith("_OUTLINE"):
        return True
    # GuiTheme 的调色板全部是颜色，只有尺寸/开关例外
    return key.startswith("GuiTheme.") and not key.endswith(("RADIUS", "PANEL_RADIUS", "BLUR_ENABLED"))


def resolve_expr(expr, consts):
    text = expr.strip()
    if re.fullmatch(r'-?\d+', text):
        return int(text)
    if re.fullmatch(r'-?\d+\.\d+[fFdD]?', text):
        return float(text.rstrip('fFdD'))
    for name in re.findall(r'[A-Z][A-Z0-9_]*', text):
        if name in consts:
            text = re.sub(rf'\b{name}\b', str(consts[name]), text)
    try:
        return int(eval(text, {"__builtins__": {}}, {}))
    except Exception:
        try:
            return float(eval(text, {"__builtins__": {}}, {}))
        except Exception:
            return None


def main():
    schema = {"version": 2, "groups": [], "items": []}
    for path in sorted(GUI_DIR.glob("*.java")):
        if path.name == "GuiTuning.java":
            continue
        source = path.read_text(encoding="utf-8")
        consts = {}
        for line in source.splitlines():
            m = DECL_RE.match(line)
            if m:
                val = resolve_expr(m.group(3), consts)
                if val is not None:
                    consts[m.group(2)] = val
        calls = CALL_RE.findall(source)
        if not calls:
            continue
        prefix = path.stem
        if prefix not in GROUPS:
            continue
        group = GROUPS[prefix]
        seen_keys = set()
        for key, fallback in calls:
            if key in seen_keys:
                continue
            seen_keys.add(key)
            value = resolve_expr(fallback, consts)
            if value is None:
                continue
            item = {
                "key": key,
                "label": LABELS.get(key, key.rsplit(".", 1)[-1]),
                "type": "color" if is_color_key(key) else ("int" if isinstance(value, int) else "float"),
                "default": value,
                "min": 1 if isinstance(value, int) else 0.1,
                "max": 4096 if isinstance(value, int) else 1000.0,
                "step": 1 if isinstance(value, int) else 0.1,
                "group": group,
            }
            if item["type"] == "color":
                item["min"], item["max"], item["step"] = None, None, None
            schema["items"].append(item)
    schema["groups"] = ["液态玻璃主题", "主窗口与底栏", "通用命令网格", "快捷指令页", "机器开关页", "假人页", "设置页", "滚动条样式"]
    SCHEMA_PATH.write_text(json.dumps(schema, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    if not TUNING_PATH.exists():
        current = {it["key"]: it["default"] for it in schema["items"]}
        TUNING_PATH.write_text(json.dumps(current, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"schema: {len(schema['items'])} items -> {SCHEMA_PATH}")
    print(f"tuning: {TUNING_PATH}")


if __name__ == "__main__":
    main()
