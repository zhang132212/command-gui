# Command-GUI

> 基于 Fabric 26.2 的命令面板、假人管理与机器开关模组。本文以旧版仓库 [`xgenya/command-gui`](https://github.com/xgenya/command-gui) 为基线，说明当前源码包相对旧版的变化。

## 版本信息

| 项目 | 当前源码 |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3+ |
| Fabric API | 0.156.0+26.2 |
| Java | 25+ |
| 模组版本 | `0.2.0-beta.95-perf3`（构建成功后自动递增 beta 序号） |
| 许可证 | GPL-3.0 |

项目包含两个 Fabric mod：

- `command-gui`：客户端命令面板、自定义指令、预设指令、假人管理和机器开关 GUI。
- `command-gui-server`：服务端机器、模式、检测、时间线调度和权限系统。

## 相对旧版的主要变化

### 1. GUI 调优从“改源码”变为可视化配置

当前版新增 `GuiTuning` 调优层和 `devtools/` DevStudio：

- GUI 的尺寸、间距、颜色等参数集中从 `config/command-gui/gui-tuning.json` 读取。
- 默认值仍保留在 Java 源码中；没有调优文件时，行为回退到源码默认值。
- DevStudio 提供浏览器预览、参数编辑、文案编辑、预设编辑以及构建部署功能。
- GUI 调优通常只需部署 JSON 并重新打开界面，不必每次修改 Java、重新打包或替换 jar。
- `devtools/backup/pre-tuning-layer/` 保存了接入调优层前的 GUI 源码，便于回退和对照。

启动 DevStudio：

```bat
devtools\start-devstudio.bat
```

或：

```bash
python devtools/server.py
```

浏览器打开 `http://127.0.0.1:8765`。详细说明见 [`devtools/README.md`](devtools/README.md)。

### 2. GUI 布局与交互全面整理

相较旧版，当前版补充或重构了以下 GUI 基础能力：

- 统一的父子页面导航、返回确认和未保存草稿处理。
- 自定义指令支持快捷入口、分类选择/编辑/移动和分类侧栏。
- 指令编辑器增加占位符处理、数字输入、步骤宿主和更明确的保存/取消流程。
- 新增统一的按钮、复选框、标记控件、滚动条和图标控件。
- 主界面、命令网格、机器编辑器、模式编辑器、时间线、多模式配置、检测页面和假人页面适配窗口/全屏切换。
- 滚动列表支持滚轮和拖拽；内容不足时仍保持一致的滚动条表现。
- 命令建议 mixin 与占位符建议 mixin 分离，避免影响聊天栏和命令方块的原版补全行为。

### 3. 假人管理状态同步更完整

当前版新增服务端 `FakePlayerStateTracker`，在 Carpet 可用时读取假人的动作包状态，并同步：

- 攻击、使用、跳跃的持续动作；
- 攻击/使用间隔动作及间隔 tick；
- 潜行和疾跑状态。

该功能通过运行时反射兼容 Carpet：未安装 Carpet 或 Carpet API 不匹配时自动降级，不会阻止服务端启动。客户端仍可使用批量生成、定时生成/移除和动作控制页面。

### 4. 原有机器开关系统继续扩展并与 GUI 对齐

当前版保留旧版的双 mod 架构，并完善了客户端与服务端的数据同步和编辑流程：

- 机器支持开机/关机时间线、多个假人、循环次数和每步延迟。
- 模式支持单选/多选、独立开关检测、冷却间隔和模式编排。
- 检测状态按区块内存、卸载快照和 `.mca` 文件逐级读取，区块未加载时仍可查询。
- 指令按触发玩家身份执行；离线/自动触发时不会继承管理员权限。
- 开机前进行首条指令权限预检，失败会反馈并中止流程。
- 服务端增加编辑锁、配置 revision 和网络同步，减少多人同时编辑造成的覆盖。
- 增加机器分类、分类管理、编辑白名单和未保存退出确认。

### 5. 构建与资源组织调整

- 当前客户端和服务端共用根目录版本号，服务端产物仍为独立 jar。
- `build.gradle` 增加成功构建后的 beta 版本自动递增任务；如不希望自动修改 `gradle.properties`，请在构建前移除或禁用 `build.finalizedBy bumpVersion`。
- 语言文件继续提供 `en_us` 与 `zh_cn`；预设指令位于 `src/main/resources/assets/command-gui/presets/`。
- 开发工具、调优 schema、网页预览资源和调优前备份均纳入源码包。

## 功能概览

### 客户端

- 按 `C` 打开命令面板（键位以当前客户端配置为准）。
- 自定义指令分类、描述、多指令链和占位符：`{player}`、`{player_all}`、`{player_fake}`、`{name}`、`{number}`、`{time}`、`{coords}` 等。
- 原版和 Carpet 预设指令。
- 假人批量生成、定时任务、攻击/使用/潜行/骑乘/停止等动作控制。
- 机器开关、模式、检测、时间线和多模式配置页面。

### 服务端

- 机器与模式配置持久化到 `config/command-gui-server/machines.json`。
- 每 tick 推进时间线，并按队列执行指令。
- 检测拉杆、红石灯等方块状态，显示开、关或异常锁定状态。
- 机器权限等级、允许玩家列表、编辑白名单和 `/machineadmin` 管理命令。
- 开关冷却、防重复点击、流程错误中止和客户端状态同步。

## 安装

1. 安装 Minecraft 26.2、Fabric Loader 0.19.3+、Fabric API 和 Java 25+。
2. 将客户端产物放入客户端实例的 `mods/`：`command-gui-<version>.jar`。
3. 将服务端产物放入服务端实例的 `mods/`：`command-gui-server-<version>.jar`。
4. 需要假人功能时安装与 Minecraft 26.2 匹配的 Carpet 版本。
5. 启动服务端和客户端；首次启动会创建相关配置文件。

## 从源码构建

Windows：

```bat
gradlew.bat build
```

Linux/macOS：

```bash
./gradlew build
```

产物位于：

```text
build/libs/command-gui-<version>.jar
server/build/libs/server-<version>.jar
```

建议使用 DevStudio 的“构建并安装到 mods”完成构建、备份旧 jar 和部署。构建任务会自动递增 `gradle.properties` 中的 beta 序号，请提交前确认版本号是否符合预期。

## 配置与开发

| 内容 | 位置 |
|---|---|
| 自定义指令 | `config/command-gui/presets/custom.json` |
| 客户端设置 | `config/command-gui/settings.json` |
| GUI 调优 | `config/command-gui/gui-tuning.json` |
| 服务端机器 | `config/command-gui-server/machines.json` |
| GUI 参数 schema | `devtools/tuning-schema.json` |
| 中英文文案 | `src/main/resources/assets/command-gui/lang/` |
| 预设指令 | `src/main/resources/assets/command-gui/presets/` |

GUI 参数调整后重新打开 GUI 即可验证；语言资源包调整后可使用 `F3+T` 重载资源。Java 逻辑或资源正式发布前仍需重新构建。

## 架构

```text
客户端 command-gui                         服务端 command-gui-server
CommandGUIScreen                            MachineMod
├─ CustomCommandTab                         ├─ MachineConfig
├─ FakePlayerTab                 网络同步   ├─ MachineManager
├─ PresetCommandTab              <───────>  ├─ MachineScheduler
└─ MachineSwitchTab                        ├─ MachineModeChain
   ├─ MachineEditorScreen                   ├─ MachineDetector
   ├─ MachineModesScreen                    ├─ MachineBlockCache
   └─ MachineNetworkManager                 ├─ FakePlayerStateTracker
                                            └─ MachineAdminCommand
```

## 对照范围与已知限制

本 README 的差异基线是 GitHub 仓库当前可获取的旧版快照，而不是某个未发布的 PR 分支。当前源码目录本身没有 Git remote，因此无法在本地直接创建并推送到 `xgenya/command-gui`；提交 PR 需要先将本目录放入 Git 仓库、配置 fork/remote 和 GitHub 凭据。

## 许可证

本项目使用 [GPL-3.0](LICENSE) 许可证。
