#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Command-GUI DevStudio —— 本地开发网页服务。

不启动游戏客户端即可完成：
  1. GUI 布局/尺寸/颜色微调，并实时在浏览器里预览近似效果；
  2. 中英文文案编辑；
  3. 预设指令编辑；
  4. 一键部署调优配置到游戏 config / resourcepacks；
  5. 一键 Gradle 构建并把 jar 安装到 mods 目录。

用法：
    python devtools/server.py [--host 127.0.0.1] [--port 8765]
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import threading
import time
import webbrowser
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs

ROOT = Path(__file__).resolve().parents[1]
DEVTOOLS = ROOT / "devtools"
SCHEMA_PATH = DEVTOOLS / "tuning-schema.json"
TUNING_PATH = DEVTOOLS / "gui-tuning.json"
DEV_CONFIG_PATH = DEVTOOLS / "dev-config.json"
LANG_DIR = ROOT / "src" / "main" / "resources" / "assets" / "command-gui" / "lang"
PRESET_DIR = ROOT / "src" / "main" / "resources" / "assets" / "command-gui" / "presets"
GUI_TUNING_JAVA = ROOT / "src" / "client" / "java" / "com" / "remrin" / "client" / "gui" / "GuiTuning.java"
CLIENT_BUILD_DIR = ROOT / "build" / "libs"
SERVER_BUILD_DIR = ROOT / "server" / "build" / "libs"

BUILD_LOCK = threading.Lock()


# ---------------------------------------------------------------- utilities

def read_json(path: Path, default=None):
    try:
        if not path.exists():
            return default
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        print(f"[DevStudio] 读取 JSON 失败: {path}: {exc}", file=sys.stderr)
        return default


def write_json(path: Path, data, sort_keys=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def read_text(path: Path, default=""):
    try:
        return path.read_text(encoding="utf-8") if path.exists() else default
    except Exception as exc:
        print(f"[DevStudio] 读取文件失败: {path}: {exc}", file=sys.stderr)
        return default


def write_text(path: Path, text: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(text, encoding="utf-8")
    tmp.replace(path)


def parse_body(handler) -> dict:
    length = int(handler.headers.get("Content-Length") or 0)
    if length <= 0:
        return {}
    raw = handler.rfile.read(length)
    if not raw:
        return {}
    try:
        return json.loads(raw.decode("utf-8"))
    except Exception:
        return {}


def send_json(handler, data, code=200):
    body = json.dumps(data, ensure_ascii=False).encode("utf-8")
    handler.send_response(code)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(body)))
    handler.send_header("Cache-Control", "no-store")
    handler.end_headers()
    handler.wfile.write(body)


def send_text(handler, text, content_type="text/plain; charset=utf-8", code=200):
    body = text.encode("utf-8")
    handler.send_response(code)
    handler.send_header("Content-Type", content_type)
    handler.send_header("Content-Length", str(len(body)))
    handler.send_header("Cache-Control", "no-store")
    handler.end_headers()
    handler.wfile.write(body)


def now_text():
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


# ---------------------------------------------------------------- JDK / Minecraft paths

def _version_of(java_bin: Path) -> str:
    try:
        proc = subprocess.run(
            [str(java_bin), "-version"],
            capture_output=True, text=True, timeout=10, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        first = (proc.stderr or proc.stdout).splitlines()[0] if (proc.stderr or proc.stdout) else ""
        return first
    except Exception:
        return ""


def find_jdk25() -> dict:
    """返回 {'java_home': ..., 'bin': ..., 'version': ...} 或错误信息。"""
    env_home = os.environ.get("JAVA_HOME")
    candidates = []
    if env_home:
        candidates.append(Path(env_home))

    known = [
        Path("C:/Program Files/Eclipse Adoptium/jdk-25.0.1.8-hotspot"),
        Path("C:/Program Files/Microsoft/jdk-25.0.3.9-hotspot"),
        Path("C:/Program Files/Java"),
        Path("C:/Program Files/Eclipse Adoptium"),
    ]
    for base in known:
        if not base.exists():
            continue
        if base.name.lower().startswith("jdk-25"):
            candidates.append(base)
        else:
            try:
                for child in sorted(base.iterdir(), reverse=True):
                    if child.is_dir() and "25" in child.name.lower():
                        candidates.append(child)
                        break
            except OSError:
                pass

    which_java = shutil.which("java")
    if which_java:
        java_path = Path(which_java).resolve()
        candidates.append(java_path.parent.parent)

    for cand in candidates:
        java_bin = cand / "bin" / "java.exe"
        if not java_bin.exists():
            java_bin = cand / "bin" / "java"
        if not java_bin.exists():
            continue
        version = _version_of(java_bin)
        if "25" in version:
            return {"java_home": str(cand.resolve()), "java_bin": str(java_bin.resolve()), "version": version.strip()}

    return {"error": "未找到 JDK 25。请安装 JDK 25 或在系统环境变量 JAVA_HOME 中指向 JDK 25。"}


def load_dev_config() -> dict:
    return read_json(DEV_CONFIG_PATH, {}) or {}


def save_dev_config(cfg: dict):
    write_json(DEV_CONFIG_PATH, cfg)


def detect_game_dirs() -> dict:
    cfg = load_dev_config()
    base = None
    custom = cfg.get("minecraft_dir")
    if custom:
        base = Path(custom)
    if base is None or not base.exists():
        appdata = os.environ.get("APPDATA")
        cands = []
        if appdata:
            cands.append(Path(appdata) / ".minecraft")
        cands.append(Path.home() / "AppData" / "Roaming" / ".minecraft")
        cands.append(Path.home() / ".minecraft")
        base = next((p for p in cands if p.exists()), cands[0])

    root = base
    mode = "标准 .minecraft"
    if root.exists():
        has_standard = (root / "mods").exists() or (root / "config").exists() or (root / "resourcepacks").exists()
        if not has_standard and (root / "versions").exists():
            version_dirs = sorted(
                [p for p in (root / "versions").iterdir() if p.is_dir()],
                key=lambda p: p.stat().st_mtime if p.exists() else 0,
                reverse=True,
            )
            for vdir in version_dirs:
                if (vdir / "mods").exists() or (vdir / "config").exists() or (vdir / "resourcepacks").exists():
                    root = vdir
                    mode = f"版本隔离目录: {vdir.name}"
                    break

    return {
        "root": str(root),
        "mode": mode,
        "mods_dir": str(root / "mods"),
        "config_dir": str(root / "config" / "command-gui"),
        "resourcepacks_dir": str(root / "resourcepacks"),
        "base_minecraft_dir": str(base),
    }


# ---------------------------------------------------------------- schema / tuning

def load_schema() -> dict:
    return read_json(SCHEMA_PATH, {"version": 2, "groups": [], "items": []}) or {"version": 2, "groups": [], "items": []}


def default_tuning() -> dict:
    schema = load_schema()
    return {item["key"]: item["default"] for item in schema.get("items", [])}


def current_tuning() -> dict:
    values = default_tuning()
    saved = read_json(TUNING_PATH, {}) or {}
    for key, value in saved.items():
        if key in values:
            values[key] = value
    return values


def save_tuning(values: dict) -> dict:
    schema = load_schema()
    known = {item["key"]: item for item in schema.get("items", [])}
    out = default_tuning()
    for key, value in values.items():
        if key not in known:
            continue
        item = known[key]
        try:
            if item.get("type") == "int":
                value = int(value)
            elif item.get("type") == "float":
                value = float(value)
        except Exception:
            return {"error": f"{key} 不是有效数字: {value!r}"}
        out[key] = value
    write_json(TUNING_PATH, out)
    return {"ok": True, "saved": len(out), "path": str(TUNING_PATH)}


def deploy_tuning(game_dirs: dict) -> list:
    written = []
    cfg_path = Path(game_dirs["config_dir"]) / "gui-tuning.json"
    cfg_path.parent.mkdir(parents=True, exist_ok=True)
    write_json(cfg_path, current_tuning())
    written.append(str(cfg_path))

    lang_pack = Path(game_dirs["resourcepacks_dir"]) / "Command-GUI-DevTexts"
    pack_format = int(load_dev_config().get("resourcepack_pack_format", 99))
    pack_mcmeta = {
        "pack": {
            "pack_format": pack_format,
            "description": "Command-GUI DevStudio 生成的语言覆盖包（开发期微调，可随时删除）",
        }
    }
    write_json(lang_pack / "pack.mcmeta", pack_mcmeta)
    for lang in ("zh_cn", "en_us"):
        src = LANG_DIR / f"{lang}.json"
        if src.exists():
            write_text(lang_pack / "assets" / "command-gui" / "lang" / f"{lang}.json", src.read_text(encoding="utf-8"))
    written.append(str(lang_pack))

    presets_cfg = Path(game_dirs["config_dir"]) / "presets"
    presets_cfg.mkdir(parents=True, exist_ok=True)
    for src in PRESET_DIR.glob("*.json"):
        target = presets_cfg / src.name
        write_text(target, src.read_text(encoding="utf-8"))
        written.append(str(target))
    return written


def build_artifacts() -> list:
    jars = []
    for lib_dir in (CLIENT_BUILD_DIR, SERVER_BUILD_DIR):
        if not lib_dir.exists():
            continue
        for jar in sorted(lib_dir.glob("*.jar")):
            if jar.name.endswith("-sources.jar"):
                continue
            stat = jar.stat()
            jars.append({"name": jar.name, "path": str(jar), "size": stat.st_size, "mtime": stat.st_mtime})
    return jars


def install_jars(game_dirs: dict) -> dict:
    mods = Path(game_dirs["mods_dir"])
    mods.mkdir(parents=True, exist_ok=True)
    artifacts = build_artifacts()
    if not artifacts:
        return {"ok": False, "error": "没有找到构建产物，请先执行构建。"}
    installed = []
    stamp = time.strftime("%Y%m%d-%H%M%S")
    for art in artifacts:
        src = Path(art["path"])
        dst = mods / src.name
        if dst.exists():
            backup = dst.with_name(dst.name + f".devstudio-bak-{stamp}")
            try:
                shutil.copy2(dst, backup)
            except Exception as exc:
                return {"ok": False, "error": f"备份旧 jar 失败: {exc}"}
        shutil.copy2(src, dst)
        installed.append(str(dst))
    return {"ok": True, "installed": installed, "mods_dir": str(mods)}


# ---------------------------------------------------------------- build

def run_build(offline: bool, install_after: bool, game_dirs: dict):
    jdk = find_jdk25()
    if "error" in jdk:
        yield {"type": "error", "message": jdk["error"]}
        yield {"type": "done", "exit": -1, "error": jdk["error"]}
        return

    yield {"type": "info", "message": f"使用 JDK: {jdk['java_home']}"}
    args = ["cmd", "/c", str(ROOT / "gradlew.bat"), "build", "-x", "test", "--console=plain", "--no-daemon"]
    if offline:
        args.append("--offline")
    yield {"type": "info", "message": "执行: " + " ".join(args)}

    env = os.environ.copy()
    env["JAVA_HOME"] = jdk["java_home"]
    env["Path"] = str(Path(jdk["java_home"]) / "bin") + os.pathsep + env.get("Path", "")

    creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    try:
        proc = subprocess.Popen(
            args,
            cwd=str(ROOT),
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
            creationflags=creationflags,
        )
    except Exception as exc:
        yield {"type": "error", "message": f"启动构建进程失败: {exc}"}
        yield {"type": "done", "exit": -1, "error": str(exc)}
        return

    for line in proc.stdout:
        line = line.rstrip()
        if line:
            yield {"type": "log", "message": line}

    exit_code = proc.wait()
    result = {"exit": exit_code, "ok": exit_code == 0}
    if exit_code == 0:
        result["message"] = "BUILD SUCCESSFUL"
        if install_after:
            install_result = install_jars(game_dirs)
            result["install"] = install_result
            yield {"type": "info", "message": json.dumps(install_result, ensure_ascii=False)}
    else:
        result["message"] = "BUILD FAILED"
        result["error"] = f"Gradle 退出码 {exit_code}。可尝试切到“联网构建”，或检查 JAVA_HOME 是否为 JDK 25。"

    yield {"type": "done", **result}


# ---------------------------------------------------------------- HTTP handler

class Handler(BaseHTTPRequestHandler):
    server_version = "CommandGUI-DevStudio/1.0"

    def log_message(self, fmt, *args):
        print(f"[DevStudio] {self.address_string()} {fmt % args}")

    def _send_file(self, rel: Path):
        if not rel.exists():
            self.send_error(404)
            return
        content_type = {
            ".html": "text/html; charset=utf-8",
            ".js": "application/javascript; charset=utf-8",
            ".css": "text/css; charset=utf-8",
            ".json": "application/json; charset=utf-8",
            ".png": "image/png",
        }.get(rel.suffix.lower(), "application/octet-stream")
        body = rel.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _route(self):
        parsed = urlparse(self.path)
        path = parsed.path
        query = parse_qs(parsed.query)

        if path == "/" or path == "/index.html":
            return self._send_file(DEVTOOLS / "index.html")
        if path in ("/app.js", "/style.css", "/preview.js"):
            return self._send_file(DEVTOOLS / path.lstrip("/"))
        if path.startswith("/static/"):
            return self._send_file(DEVTOOLS / path.removeprefix("/static/"))
        if path.startswith("/textures/"):
            return self._send_file(DEVTOOLS / path.removeprefix("/"))

        if path == "/api/state":
            schema = load_schema()
            jdk = find_jdk25()
            game = detect_game_dirs()
            artifacts = build_artifacts()
            client_jar = next((a for a in artifacts if a["name"].startswith("command-gui")), None)
            server_jar = next((a for a in artifacts if a["name"].startswith("server")), None)
            return send_json(self, {
                "ok": True,
                "time": now_text(),
                "project": str(ROOT),
                "tuning_layer": GUI_TUNING_JAVA.exists(),
                "schema": schema,
                "tuning": current_tuning(),
                "defaults": default_tuning(),
                "jdk": jdk,
                "minecraft": game,
                "build": {"client_jar": client_jar, "server_jar": server_jar, "all": artifacts},
                "dev_config": load_dev_config(),
            })

        if path == "/api/tuning" and self.command == "GET":
            return send_json(self, {"ok": True, "tuning": current_tuning(), "schema": load_schema()})

        if path == "/api/tuning" and self.command == "POST":
            body = parse_body(self)
            values = body.get("values") or body
            result = save_tuning(values)
            code = 200 if result.get("ok") else 400
            return send_json(self, result, code)

        if path == "/api/tuning/reset" and self.command == "POST":
            write_json(TUNING_PATH, default_tuning())
            return send_json(self, {"ok": True, "tuning": default_tuning()})

        if path == "/api/deploy" and self.command == "POST":
            game = detect_game_dirs()
            written = deploy_tuning(game)
            return send_json(self, {"ok": True, "written": written, "minecraft": game})

        if path == "/api/lang" and self.command == "GET":
            langs = {}
            for lang in ("zh_cn", "en_us"):
                path_lang = LANG_DIR / f"{lang}.json"
                langs[lang] = read_json(path_lang, {}) or {}
            keys = []
            for lang_data in langs.values():
                for key in lang_data:
                    if key not in keys:
                        keys.append(key)
            return send_json(self, {"ok": True, "langs": langs, "keys": keys, "dir": str(LANG_DIR)})

        if path == "/api/lang" and self.command == "POST":
            body = parse_body(self)
            lang = str(body.get("lang", ""))
            entries = body.get("entries")
            if lang not in ("zh_cn", "en_us") or not isinstance(entries, dict):
                return send_json(self, {"ok": False, "error": "参数错误：需要 lang=zh_cn|en_us 和 entries 对象。"}, 400)
            path_lang = LANG_DIR / f"{lang}.json"
            old = read_json(path_lang, {}) or {}
            old.update(entries)
            write_json(path_lang, old)
            return send_json(self, {"ok": True, "path": str(path_lang), "count": len(old)})

        if path == "/api/presets" and self.command == "GET":
            presets = {}
            for preset_path in sorted(PRESET_DIR.glob("*.json")):
                presets[preset_path.stem] = read_json(preset_path, {}) or {}
            return send_json(self, {"ok": True, "presets": presets, "dir": str(PRESET_DIR)})

        if path == "/api/presets" and self.command == "POST":
            body = parse_body(self)
            preset_id = str(body.get("id", ""))
            data = body.get("data")
            if not preset_id or not isinstance(data, (dict, list)):
                return send_json(self, {"ok": False, "error": "参数错误：需要 id 和 data。"}, 400)
            safe_id = "".join(ch for ch in preset_id if ch.isalnum() or ch in "_-")
            if safe_id != preset_id:
                return send_json(self, {"ok": False, "error": "预设 id 只能包含字母、数字、_、-。"}, 400)
            path_preset = PRESET_DIR / f"{safe_id}.json"
            write_json(path_preset, data)
            return send_json(self, {"ok": True, "path": str(path_preset)})

        if path == "/api/dev-config" and self.command == "POST":
            body = parse_body(self)
            cfg = load_dev_config()
            if "minecraft_dir" in body:
                p = Path(str(body["minecraft_dir"])).expanduser()
                cfg["minecraft_dir"] = str(p)
            if "resourcepack_pack_format" in body:
                try:
                    cfg["resourcepack_pack_format"] = int(body["resourcepack_pack_format"])
                except Exception:
                    pass
            save_dev_config(cfg)
            return send_json(self, {"ok": True, "dev_config": cfg, "minecraft": detect_game_dirs()})

        if path == "/api/install" and self.command == "POST":
            result = install_jars(detect_game_dirs())
            return send_json(self, result, 200 if result.get("ok") else 400)

        if path == "/api/build":
            offline = query.get("offline", ["1"])[0] != "0"
            install_after = query.get("install", ["0"])[0] == "1"
            if not BUILD_LOCK.acquire(blocking=False):
                return send_json(self, {"ok": False, "error": "已有构建任务在运行，请稍候。"}, 409)
            try:
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream; charset=utf-8")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Connection", "close")
                self.end_headers()
                for event in run_build(offline, install_after, detect_game_dirs()):
                    event_type = event.get("type")
                    if event_type == "done":
                        self.wfile.write(f"event: done\ndata: {json.dumps(event, ensure_ascii=False)}\n\n".encode("utf-8"))
                    elif event_type == "error":
                        self.wfile.write(f"event: error\ndata: {json.dumps(event, ensure_ascii=False)}\n\n".encode("utf-8"))
                    else:
                        self.wfile.write(f"data: {json.dumps(event, ensure_ascii=False)}\n\n".encode("utf-8"))
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass
            finally:
                BUILD_LOCK.release()
            return

        if path == "/api/source/restore" and self.command == "POST":
            backup = DEVTOOLS / "backup" / "pre-tuning-layer" / "gui"
            gui_dir = ROOT / "src" / "client" / "java" / "com" / "remrin" / "client" / "gui"
            if not backup.exists():
                return send_json(self, {"ok": False, "error": "没有找到源码备份。"}, 400)
            shutil.rmtree(gui_dir)
            shutil.copytree(backup, gui_dir)
            return send_json(self, {"ok": True, "message": "已恢复原始 GUI 源码（会移除 GuiTuning 调优层，需重新构建）。"})

        return send_json(self, {"ok": False, "error": f"未知接口: {path}"}, 404)

    def do_GET(self):
        self._route()

    def do_POST(self):
        self._route()


def main():
    parser = argparse.ArgumentParser(description="Command-GUI DevStudio")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--no-browser", action="store_true", help="启动时不自动打开浏览器")
    args = parser.parse_args()

    print("=" * 62)
    print("Command-GUI DevStudio")
    print(f"项目: {ROOT}")
    print(f"地址: http://{args.host}:{args.port}")
    print("按 Ctrl+C 退出。")
    print("=" * 62)

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    url = f"http://{args.host}:{args.port}"
    if not args.no_browser:
        threading.Timer(1.0, lambda: webbrowser.open(url)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已退出。")


if __name__ == "__main__":
    main()
