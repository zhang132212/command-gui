# Command-GUI

> 基于 Fabric 26.2 的命令面板、假人管理与机器开关模组。本文以旧版仓库 [`xgenya/command-gui`](https://github.com/xgenya/command-gui) 为基线，说明当前源码包相对旧版的变化。

## 版本信息

| 项目 | 当前源码 |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3+ |
| Fabric API | 0.156.0+26.2 |
| Java | 25+ |
| 模组版本 | `0.3.0-beta1`（本地构建成功后自动递增 beta 序号；CI 以 `-x bumpVersion` 跳过） |
| 许可证 | GPL-3.0 |
| 产物 | `command-gui-<version>.jar`，客户端与服务端通用 |

项目现在是**单个 jar**（客户端与服务端合一），由运行环境决定执行哪一侧：

- 客户端入口（`CommandGUIClient`）：命令面板、自定义指令、预设指令、假人管理和机器开关 GUI。
- 服务端分支（`CommandGUI` → `MachineMod`）：机器、模式、检测、时间线调度和权限系统。专用服务端只跑这一侧；客户端的单人/局域网集成服务端也会跑它，因此单人模式不再需要额外安装服务端 mod。

## 相对旧版的主要变化

### 1. GUI 外观参数支持运行时配置

当前版新增 `GuiTuning` 调优层：

- GUI 的尺寸、间距、颜色等参数集中从 `config/command-gui/gui-tuning.json` 读取。
- 默认值仍保留在 Java 源码中；没有调优文件时，行为回退到源码默认值。
- GUI 调优通常只需部署 JSON 并重新打开界面，不必每次修改 Java、重新打包或替换 jar。

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

当前版把客户端与服务端合并进同一个 jar，并完善了两侧的数据同步和编辑流程：

- 机器支持开机/关机时间线、多个假人、循环次数和每步延迟。
- 模式支持单选/多选、独立开关检测、冷却间隔和模式编排。
- 检测状态按区块内存、卸载快照和 `.mca` 文件逐级读取，区块未加载时仍可查询。
- 指令按触发玩家身份执行；离线/自动触发时不会继承管理员权限。
- 开机前进行首条指令权限预检，失败会反馈并中止流程。
- 服务端增加编辑锁、配置 revision 和网络同步，减少多人同时编辑造成的覆盖。
- 增加机器分类、分类管理、编辑白名单和未保存退出确认。

### 5. 构建与资源组织调整

- 客户端与服务端共用根目录版本号，且只产出一个 jar：`server/` 只是被编进同一 jar 的源码目录，不再有独立服务端产物。
- `build.gradle` 增加成功构建后的 beta 版本自动递增任务（同时兼容 `0.3.0-beta1` 与 `0.2.0-beta.95` 两种写法）；如不希望自动修改 `gradle.properties`，请在构建前移除或禁用 `build.finalizedBy bumpVersion`。
- 语言文件继续提供 `en_us` 与 `zh_cn`；预设指令位于 `src/main/resources/assets/command-gui/presets/`。

## 功能概览

### 客户端

- 按 `C` 打开命令面板（键位以当前客户端配置为准）。
- 自定义指令分类、描述、多指令链和占位符：`{player}`、`{player_all}`、`{player_fake}`、`{name}`、`{number}`、`{time}`、`{coords}` 等。
- 原版和 Carpet 预设指令。
- 「Carpet 规则」标签：查询 Carpet 及其扩展注册的真实规则，支持批量确认设置值、`setDefault`、`removeDefault`（权限、分包与校验细节见 [`docs/carpet-rules.md`](docs/carpet-rules.md)）。
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
2. 客户端实例和服务端实例都放入同一个产物：`command-gui-<version>.jar`。
   - 客户端：命令面板、假人管理与机器开关 GUI；单人/局域网主机的服务端逻辑由同一个 jar 内置执行。
   - 专用服务端：只执行服务端分支（机器、模式、检测、时间线、权限）。
3. 需要假人功能时安装与 Minecraft 26.2 匹配的 Carpet 版本。
4. 启动服务端和客户端；首次启动会创建相关配置文件。

> **升级提示**：旧版需要额外安装独立服务端 mod `command-gui-server`。升级时请从 `mods/` 移除该 jar——两个 jar 都带同一套 `com.remrin.server.*` 类，同时安装会出现重复类与版本错配（模组会在日志里输出告警）。

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
```

单个 jar 同时用于客户端与服务端。`server/` 只是服务端源码目录（由根项目 `build.gradle` 的 `srcDir` 编进同一个 jar），不再是独立 Gradle 子项目，也不再产出单独的 server jar。

构建任务会自动递增 `gradle.properties` 中 `mod_version` 的 beta 序号（如 `0.3.0-beta1` → `0.3.0-beta2`），请提交前确认版本号是否符合预期；CI 构建带 `-x bumpVersion`，不会改动仓库里的版本号。

## 配置与开发

| 内容 | 位置 |
|---|---|
| 自定义指令 | `config/command-gui/presets/custom.json` |
| 客户端设置 | `config/command-gui/settings.json` |
| GUI 调优 | `config/command-gui/gui-tuning.json` |
| 服务端机器 | `config/command-gui-server/machines.json` |
| 中英文文案 | `src/main/resources/assets/command-gui/lang/` |
| 预设指令 | `src/main/resources/assets/command-gui/presets/` |
| 开发文档 | `docs/`（Carpet 规则、界面整理、标签点击修复等） |
| 测试脚手架 | `testing/`（后端 QA：`run-backend-tests.ps1`、`smoke-embedded-server.mjs`）、`tests/carpet-rules/`（规则 QA） |
| 网页调优工具 | `devtools/`（实验性，不参与构建） |

GUI 参数调整后重新打开 GUI 即可验证；语言资源包调整后可使用 `F3+T` 重载资源。Java 逻辑或资源正式发布前仍需重新构建。

## 架构

```text
客户端入口（客户端实例）                     服务端分支（专用服务端 / 集成服务端）
CommandGUIScreen                            MachineMod
├─ CustomCommandTab                         ├─ MachineConfig
├─ FakePlayerTab                 网络同步   ├─ MachineManager
├─ PresetCommandTab              <───────>  ├─ MachineScheduler
├─ CarpetRulesTab                          ├─ MachineModeChain
└─ MachineSwitchTab                        ├─ MachineDetector
   ├─ MachineEditorScreen                   ├─ MachineBlockCache
   ├─ MachineModesScreen                    ├─ FakePlayerStateTracker
   └─ MachineNetworkManager                 └─ MachineAdminCommand
```

两侧共用 `com.remrin.rules`（Carpet 规则的目录、查询与执行）与 `MachinePayloads` 网络包定义；`/cgtest` 供自动化测试驱动，`CarpetRulesTab` 只在客户端显示。

## 仓库状态

本仓库（`zhang132212/command-gui`）为 [xgenya/command-gui](https://github.com/xgenya/command-gui) 的 fork，内容包括：
- 单个 mod `command-gui`：客户端与服务端合一（公共入口 `src/main/java/`、客户端代码 `src/client/`、服务端代码 `server/src/main/java/`）
- 构建配置（Gradle / GitHub Actions）、开发文档（`docs/`）与测试脚手架（`testing/`、`tests/`）
- `devtools/` 网页调优框架（含 `backup/pre-tuning-layer/` 旧版 GUI 备份、调优脚本与贴图）：实验性工具，不参与构建，也不影响产物

`devtools/` 与测试脚手架都会随仓库一起发布；`.gitignore` 只排除 Gradle/IDE/运行目录（`.gradle/`、`build/`、`run/` 等）。

## 许可证

本项目使用 [GPL-3.0](LICENSE) 许可证。
