# Command-GUI DevStudio（开发网页框架）

目标：**在没有启动游戏客户端的情况下**，通过浏览器即可微调 GUI；之后只需要
“保存 → 部署 → 正常打开游戏”，不再走“改源码 → 重新构建 → 切换 jar → 启动客户端”
的长流程。

## 一、启动

```bat
devtools\start-devstudio.bat
```

或：

```bash
python devtools/server.py
```

启动后浏览器会自动打开 `http://127.0.0.1:8765`。

## 二、四个工作区

1. **概览**：显示项目、JDK、游戏目录、jar 产物等检测结果。
2. **界面微调**：75 个 GUI 参数（主窗口、命令网格、快捷指令页、机器开关页、假人页、
   设置页、滚动条样式），右侧是**源码坐标 1:1 高级预览**。
   - 直接使用游戏原版 `button/tab/text_field` 九宫格材质和 mod 滚动条颜色；
   - 支持多层 GUI 栈：点击主界面按钮进入 AddCommand / 机器编辑器 / 模式 / 时间线 /
     检测 / 定时假人 / 批量假人等子界面；
   - 默认按 480×270 逻辑分辨率 @400% 缩放显示（即 1920×1080 物理像素、GUI 缩放 4）；
     这是本次游戏截图所用客户端的实际显示比例，按钮像素位置与截图一致；
   - 控件文字按 Minecraft 字体的 9px 逻辑字号渲染（400% 后为 36px 物理高度），
     与截图中的标签/按钮文字比例一致；
   - 左键执行/切换、右键编辑，滚动条支持拖拽和滚轮；
   - 坐标与尺寸按 `CommandGUIScreen`、`AbstractCommandTab`、`AddCommandScreen`、
     `MachineEditorScreen` 等源码中的常量与公式计算。
3. **文案翻译**：直接编辑 `assets/command-gui/lang/zh_cn.json` / `en_us.json`。
4. **预设指令**：直接编辑 `assets/command-gui/presets/vanilla.json` / `carpet.json`。
5. **构建与部署**：一键离线/联网构建，并把 jar 安装到 mods 目录。

## 三、调优值如何生效（关键机制）

源码已接入一个极薄的 **GuiTuning 调优层**：

- `src/client/java/com/remrin/client/gui/GuiTuning.java` 在 GUI 打开时读取
  `config/command-gui/gui-tuning.json`。
- 所有关键尺寸/颜色使用 `GuiTuning.getInt("类名.参数", 源码默认值)`，没有 JSON 时
  行为与原始 jar 完全一致。
- DevStudio 的“部署到游戏”会写入这个 JSON。**因此后续微调完全不需要重新编译。**

文案则部署成 `resourcepacks/Command-GUI-DevTexts` 资源包。在游戏中启用一次该资源包，
之后每次部署文案都会覆盖 mod 自带的语言文件，同样不需要重新构建。

> 第一次接入 GuiTuning 层时已经需要一次构建：DevStudio 的“构建并安装到 mods”
> 会自动完成 `gradlew build` + 复制 jar + 备份旧 jar。只有修改 Java 逻辑或
> 准备分发时才需要再次构建。

## 四、常用操作

| 操作 | 按钮 | 是否需要重开客户端 |
|---|---|---|
| 调 GUI 尺寸/颜色 | 界面微调 → 部署到游戏 | 是（下一次打开 GUI 生效；也可关闭再打开界面） |
| 调文案 | 文案翻译 → 生成语言资源包并部署 | 游戏内 F3+T 重载资源，或重开客户端 |
| 调预设 | 预设指令 → 部署到游戏 config | 是（资源重载/重开） |
| 构建安装 | 构建并安装到 mods | 是 |

## 五、目录说明

```
devtools/
├── server.py                 # 本地开发服务器（仅标准库）
├── index.html / app.js / preview.js / style.css
├── textures/               # 原版 Minecraft 26.2 按钮/标签页/输入框九宫格材质 + mod 图标
├── start-devstudio.bat       # Windows 一键启动
├── tuning-schema.json        # 75 个可调参数的元数据（由工具生成）
├── gui-tuning.json           # 当前参数值（部署到 config/command-gui/gui-tuning.json）
├── backup/pre-tuning-layer/  # 接入 GuiTuning 之前的 GUI 源码备份
└── tools/
    ├── generate_schema.py    # 从 Java 源码扫描 GuiTuning 调用并生成参数表
    └── apply_tuning_layer.py # 调优层验证/恢复脚本
```

## 六、路径说明与版本隔离

默认自动探测 `.minecraft`。如果使用 PCL2/HMCL 版本隔离，程序会优先选择带
`mods/config/resourcepacks` 的 `versions/<版本>` 目录；也可以在“构建与部署”页
手动填写游戏目录。

资源包的 `pack_format` 默认 99，可在构建部署页修改。若游戏提示资源包版本不兼容，
请改成当前 Minecraft 版本对应的 pack_format。
