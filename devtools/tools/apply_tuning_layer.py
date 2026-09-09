#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GuiTuning 调优层维护脚本。

当前项目源码已经接入调优层。本脚本提供：
  python apply_tuning_layer.py --verify   检查接入状态并输出参数数量
  python apply_tuning_layer.py --schema   重新扫描源码生成 tuning-schema.json
  python apply_tuning_layer.py --restore  从 devtools/backup/pre-tuning-layer 恢复
                                          接入调优层之前的 GUI 源码（慎用！）
"""
import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GUI_DIR = ROOT / "src" / "client" / "java" / "com" / "remrin" / "client" / "gui"
BACKUP_DIR = ROOT / "devtools" / "backup" / "pre-tuning-layer" / "gui"
SCHEMA_PATH = ROOT / "devtools" / "tuning-schema.json"
TUNING_PATH = ROOT / "devtools" / "gui-tuning.json"
GUI_TUNING_JAVA = GUI_DIR / "GuiTuning.java"


def verify():
    if not GUI_TUNING_JAVA.exists():
        print("❌ 未找到 GuiTuning.java，调优层未接入。")
        return 1
    count = 0
    for path in GUI_DIR.glob("*.java"):
        if path.name == "GuiTuning.java":
            continue
        count += len(re.findall(r'GuiTuning\.get(?:Int|Float|Double|Color)', path.read_text(encoding="utf-8")))
    print(f"✅ GuiTuning.java 存在")
    print(f"✅ 源码中调优点数量: {count}")
    if SCHEMA_PATH.exists():
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        print(f"✅ tuning-schema.json 参数数量: {len(schema.get('items', []))}")
    if TUNING_PATH.exists():
        values = json.loads(TUNING_PATH.read_text(encoding="utf-8"))
        print(f"✅ 当前参数文件 gui-tuning.json 数量: {len(values)}")
    return 0


def regenerate_schema():
    subprocess.run([sys.executable, str(ROOT / "devtools" / "tools" / "generate_schema.py")], check=False)


def restore_original():
    if not BACKUP_DIR.exists():
        print("❌ 没有备份目录。")
        return 1
    print("即将删除当前 GUI 源码并用 pre-tuning-layer 备份覆盖（GuiTuning 调优层也会移除）。")
    print("如果只是想恢复参数，请使用网页里的“恢复默认”，不要执行本操作。")
    answer = input("确认输入 RESTORE 继续: ").strip()
    if answer != "RESTORE":
        print("已取消。")
        return 0
    shutil.rmtree(GUI_DIR)
    shutil.copytree(BACKUP_DIR, GUI_DIR)
    print("已恢复接入前的 GUI 源码。")
    return 0


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--verify", action="store_true")
    ap.add_argument("--schema", action="store_true")
    ap.add_argument("--restore", action="store_true")
    args = ap.parse_args()

    if args.restore:
        return restore_original()
    if args.schema:
        regenerate_schema()
        return verify()
    if args.verify or not any([args.schema, args.restore]):
        return verify()
    return 0


if __name__ == "__main__":
    sys.exit(main())
