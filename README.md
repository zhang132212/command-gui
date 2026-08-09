# Command-GUI（命令面板）

> **版本** 0.2.0-beta.2 · **目标** Minecraft 26.2 · **加载器** Fabric Loader 0.19.3+ · **许可证** GPL-3.0

一个面向 Fabric 26.2 的「机器开关」与命令管理模组集。整个项目由两个独立 mod 组成：

| Mod | 端 | 说明 |
|---|---|---|
| `command-gui` | 客户端 | 命令面板 GUI、自定义指令库、假人管理、**机器开关界面** |
| `command-gui-server` | 服务端 | **机器开关系统**：机器/模式/检测/模式编排/白名单/时间线调度 |

---

## 目录

- [一、功能特性](#一功能特性)
- [二、安装与部署](#二安装与部署)
- [三、快速开始](#三快速开始)
- [四、机器开关系统详解](#四机器开关系统详解)
- [五、权限体系](#五权限体系)
- [六、配置文件](#六配置文件)
- [七、架构总览](#七架构总览)
- [九、构建](#九构建)
- [十、许可证](#十许可证)

---

## 一、功能特性

### 客户端（command-gui）

- **命令面板**：按 `C` 打开，标签页组织（自定义 / 假人管理 / 预设 / 机器开关）
- **自定义指令库**：分类管理、多指令链（按顺序执行）、描述、动态占位符（`{player}` `{player_all}` `{player_fake}` `{name}` `{number}` `{time}` `{coords}`）
- **假人管理**：批量生成、定时生成/移除、动作指令（攻击/使用/潜行/骑乘/停止等）
- **预设指令**：原版 + Carpet 常用指令分组
- **机器开关界面**：
  - 每行：开关按钮（⏸/▶/⚠ 三态）、⛏ 模式选择、🕐 刷新检测、✎ 编辑、✖ 删除
  - 分类侧边栏（约四分之一宽，支持 × 删除分类，仅 OP 可见）
  - 模式选择界面：**单选 = 单选器**（开机亮起不可点、点关机模式切换）、**多选 = 取反**（点一下翻转状态）、检测状态锁定、chip 网格滚动条
- **统一滚动条**：所有可滚动列表（机器列表 / 模式列表 / 开关机流程 / 多模式配置 / 假人列表 / 玩家选择器 / 模式 chip 网格）使用同一款 12px 滚动条（灰色滑块 + 描边），**内容不足时显示全高灰色滑块**，支持滚轮 + 拖拽；多模式配置的启动/停止两列各带独立滚动条
- **命令编辑器占位符补全**：编辑指令时 `{player}` 等占位符**绿色高亮**、Tab 可补全（不影响聊天栏/命令方块的原版行为）
- **全屏/窗口自适应**：任意切换全屏 ↔ 窗口，布局自动重排、无按钮残留（底部工具栏 44px 安全区、右侧 16px 边距）

### 服务端（command-gui-server）

- **机器开关**：每台机器可配置开机/关机流程（时间线）、假人列表、权限、检测方块
- **时间线调度器**：按 tick 推进步骤、指令队列（每 tick 一条）、循环次数（0/一次、-1/永久、N/N 次）
- **模式**：独立于开关的附加流程；**必须配置检测**（方块状态即模式开关）；单选（互斥单选器）/多选（取反）；流程中禁止切换、完成后冷却并提示、中途错误即时报错
- **模式编排**：多模式同时切换时按预设顺序 + 间隔执行；**先走完单选逻辑再走多选逻辑**（各段先停后启）
- **方块检测**：拉杆/红石灯等方块状态作为机器开关状态；**不依赖区块加载**（直读 .mca + 内存快照）
- **开关间隔（锁定窗口）**：开机/关机后 N tick 内禁止再次切换（默认 20t，防误触）
- **编辑白名单**：`/machineadmin` 授权非 OP 玩家编辑机器
- **执行权限模型**：机器/模式的每条指令**以触发玩家的身份执行**（谁点开关/模式按钮就是谁在打指令），**不可能发生权限提升**；触发玩家离线（如检测方块自动关机）时以无权限执行。开机前对第一条指令做权限预检，无权限直接拒绝并提示，而不是静默跑空

---

## 二、安装与部署

1. 安装 Fabric Loader ≥ 0.19.3 与 Fabric API（服务端、客户端都需要）
2. 客户端 mods 放入：`command-gui-0.2.0-beta.2.jar`
3. 服务端 mods 放入：`command-gui-server-0.2.0-beta.2.jar`
4. 如需假人管理请安装 Carpet（本项目在 26.2 下测试用的 carpet 版本为 `fabric-carpet-26.2+v260616.jar`）

> 提示：`/player spawn` 指令来自 Carpet，机器/模式的流程指令依赖 Carpet 的假人系统。

---

## 三、快速开始

1. 在服务端放好 `command-gui-server`，启动后会自动创建 `config/command-gui-server/machines.json`
2. 客户端进服后按 `C` 打开面板，切到「服务器机器开关」标签页
3. OP 点击侧边栏底部 `+` 添加机器：
   - 填机器名称、分类（可选）、Bots（假人名单）
   - 配置「开机流程」与「关机流程」（第一步必须是 `/player {bot} spawn ...`）
   - 可选配置「检测方块」（拉杆/红石灯等）作为开关状态来源
4. 点开关按钮：▶ 开机、⏸ 关机、⚠ 异常锁定（点 🕐 可强制刷新检测）
5. 点 ⛏ 进入模式选择：点选模式（变黑 + 琥珀为待选）→ 确定生效

---

## 四、机器开关系统详解

### 1. 机器（Machine）

- `id`：唯一标识（重名自动加后缀）
- `bots`：该机器管理的假人名单（步骤按下标引用）
- `onTimeline` / `offTimeline`：开机 / 关机流程
  - `loopCount`：0 = 跑一次、-1 = 永久循环、N = 跑 N 次
  - 每个步骤：`delay`（距上一步的 tick 数）、`bot`（假人下标）、`commands`（可多条，按顺序执行）
- `switchInterval`：开关锁定窗口（tick，默认 20）
- `detection`：检测方块配置（详见下文）
- `category`：分类（空 = 默认）

### 2. 开关与检测

开关按钮的 ⏸/▶ 状态**来源于检测方块**（配置了检测时）：

| 检测结果 | 按钮 | 点击行为 |
|---|---|---|
| 方块属性命中 onValues | ⏸（绿） | 执行关机流程 |
| 方块属性命中 offValues | ▶（白） | 执行开机流程 |
| 方块缺失/属性异常 | ⚠（红，锁定） | 不可点击 |

未配置检测时，按钮显示脚本运行状态（运行中 ⏸ / 停止 ▶），点击按实际状态切换。

**检测不依赖区块加载**，三级读取：
1. 区块已加载 → 直接读内存（权威）
2. 区块未加载 → 读「区块卸载快照」（`MachineBlockCache`：区块卸载瞬间把检测方块状态写进内存）
3. 都没有 → 直读 `.mca` 区域文件

服务端每秒对比一次状态签名，检测方块状态在游戏里变化后按钮自动刷新；🕐 按钮可强制立即重读。

### 3. 模式（Mode）

- 独立的开机/关机流程对，与开关互不影响
- **模式必须配置开关检测**（方块状态即模式开关）：方块命中 onValues = 开机、offValues = 关机、异常 = 锁定——保存时强制校验，未配置无法保存
- `singleSelect`（☑ 勾选）：**单选模式（单选器）**—— 开机（方块 ON）的模式在面板上亮起且不可点击；点一个关机模式 → **先按配置的关机顺序/间隔关掉方块 ON 的互斥模式** → **再按配置的启动顺序/间隔开点击的模式**
- 未勾选的模式（多选）：**点击 = 取反**（点一下翻转状态：开变关、关变开），与单选互不影响
- 模式可配 `switchInterval`（0 = 跟随机器）
- 模式流程**不支持循环**（始终跑一次，保证编排链可推进）
- **切换规则**：开机/关机流程执行中禁止再次切换；流程走完后提示「模式已切换」并进入切换冷却（开关间隔 tick）；中途指令错误/无权限立即停止并给执行者报错

### 4. 模式编排（多模式配置）

模式列表 → 左下「多模式配置」：

- **启动顺序/间隔**：多个模式同时开启时，按设定顺序逐个启动，间隔 = 上一个模式**开机流程跑完**后 N tick
- **停止顺序/间隔**：同样规则；可勾选「停止时以启动配置为准」复用启动配置
- 单选替换：新模式启动前，旧单选先走关机流程，再等（启动间隔 + 停止间隔）后开机
- **整体执行顺序**：一次切换同时涉及单选与多选时，**单选整体走完**（单选 stop → 单选 start）**再走多选**（多选 stop → 多选 start）——各段内部严格按配置的顺序与 tick 间隔
- 模式编辑器「**沿用开机流程**」勾选项：勾上自动把机器开机流程导入该模式的开启流程（一次性复制，之后可继续编辑）

### 5. 分类

- 机器可填 `category` 分类；侧边栏按分类过滤
- OP 可点 × 删除分类（该分类下所有机器回到默认分类）
- 非 OP 不可见 ×（服务端配置是共享的，个人标签页无需限制）

### 6. 开关间隔（锁定窗口）

每次开机/关机后，`switchInterval` tick 内禁止再次切换（客户端置灰 + 服务端拒绝并提示剩余 tick）。防连点误操作。

### 7. 测试机器（测试服）

测试服（`test_26.2`）预置多台压力测试机器：

- **滚动压力-开关机**（`scroll_machine`）：开机流程 18 步 / 关机流程 15 步，用于测试时间线滚动条
- **滚动压力-模式**（`scroll_modes`）：25 个模式，用于测试模式列表与多模式配置双滚动条
- 另有：多 bot 互测、多模式编排、循环+锁定+检测、左右键变体、权限测试、模式检测显示等（机器名以 `multi_`/`loop_`/`perm_`/`mode_` 等开头）

> 测试服由 MCDReforged 托管，改 `machines.json` 后需重启生效（`!!restart 5`）。

---

## 五、权限体系

| 角色 | 能力 |
|---|---|
| OP（权限等级 ≥2） | 全部：编辑机器、删除分类、管理白名单、查看权限配置 |
| 白名单（`/machineadmin add <玩家>`） | 创建/编辑/删除机器，但**看不到**权限等级/允许玩家配置行 |
| 普通玩家 | 仅按机器配置的 `permissionLevel` / `allowedPlayers` 执行开关与模式操作 |

- `/machineadmin add|remove|list <玩家>`：管理编辑白名单（仅 OP）
- 机器级权限：`allowedPlayers` 非空时以此为准，否则按 `permissionLevel`（0-4）

### 执行权限（重要设计）

机器/模式流程里的每条指令，**都按触发玩家本人的权限执行**：

- 打开机器的玩家用 `player.createCommandSourceStack()` 作为指令源 —— 脚本能做的事**不超过该玩家手动执行的上限**，服务端不引入任何全局执行权限，天然免疫恶意脚本提权
- 触发玩家离线时（例如检测方块自动关机、定时器触发），指令源降级为 **无权限（`PermissionSet.NO_PERMISSIONS`）**，任何需要权限的指令都会被拒绝
- **开机前预检**：第一条指令（spawn）会先用触发玩家的权限解析一次，解析失败（权限不足/指令不存在）直接拒绝开机并提示原因，不会出现"点了开机但什么都没发生"
- **执行结果语义**：指令被拒绝（`CommandSyntaxException`）＝ 流程立即中止 + 向触发玩家报错；返回 0 但无异常（如 carpet 对不存在假人的 `stop`/`kill`）＝ 正常，不中断流程
- 细节坑：`CommandDispatcher.parse` 不接受前导 `/`（执行前剥掉）；`withMaximumPermission` 是并集（不减权限），替换权限必须用 `withPermission`

---

## 六、配置文件

### 客户端 `config/command-gui/settings.json`

```json
{
  "show_vanilla_commands": true,
  "show_carpet_commands": true,
  "show_fakeplayer_tab": true
}
```

### 服务端 `config/command-gui-server/machines.json`

```json
{
  "machines": [
    {
      "id": "demo",
      "name": "示例机器",
      "category": "",
      "bots": ["bot1"],
      "permissionLevel": 2,
      "allowedPlayers": [],
      "switchInterval": 20,
      "onTimeline": { "loopCount": 0, "steps": [ { "delay": 0, "bot": 0, "commands": ["/player {bot} spawn at ..."] } ] },
      "offTimeline": { "loopCount": 0, "steps": [] },
      "modeOrder": [], "modeInterval": 0,
      "stopModeOrder": [], "stopModeInterval": 0, "stopFollowsStart": false,
      "modes": [
        { "id": "mode_1", "name": "工作", "singleSelect": true, "switchInterval": 0,
          "onTimeline": { "steps": [...] }, "offTimeline": { "steps": [...] },
          "detection": null }
      ],
      "detection": { "enabled": true, "dimension": "minecraft:overworld", "x": 0, "y": 0, "z": 0,
        "blockId": "minecraft:lever", "property": "powered",
        "onValues": ["true"], "offValues": ["false"] },
      "revision": 1
    }
  ],
  "editorWhitelist": []
}
```

---

## 七、架构总览

```
客户端（command-gui）                服务端（command-gui-server）
┌───────────────────────────────┐        ┌──────────────────────────────────┐
│ CommandGUIScreen（标签页）      │        │ MachineMod（入口）               │
│  ├ 自定义 / 假人 / 预设        │  网络   │  ├ MachineConfig（配置持久化）    │
│  └ 机器开关 MachineSwitchTab ──┼─JSON──▶│  ├ MachineManager（动作/权限/同步）│
│       │                        │        │  ├ MachineScheduler（tick 调度） │
│       ├ MachineEditorScreen    │        │  ├ MachineModeChain（模式编排链） │
│       ├ MachineModesScreen     │        │  ├ MachineDetector（方块检测）    │
│       └ MachineNetworkManager  │        │  ├ MachineBlockCache（卸载快照）  │
│                                 │        │  ├ BlockStateFileReader（.mca） │
└───────────────────────────────┘        │  └ MachineAdminCommand（白名单） │
                                          └──────────────────────────────────┘
```

- **协议**：Fabric 自定义网络包（`command-gui-server:machines` / `:action` / `:block-query`），载荷为 JSON 字符串
- **同步**：服务端推送完整机器列表 + 运行时状态（running / detected / editingBy / canEdit / canConfig）；客户端按「结构版本」区分全量刷新与原地状态刷新
- **调度**：服务端每 tick 推进运行中的时间线；每条指令经过「假人 bot 门闩」（同一假人同一 tick 只能执行一条）与「时间线队列」（每条间隔 1 tick）
- **检测**：内存（已加载）→ 卸载快照（`CHUNK_UNLOAD` 事件写入）→ .mca 直读

---

## 九、构建

```bash
# 需要 JDK 25（本项目用 F:\java\temurin25-win 或系统 JDK 25）
./gradlew build
# 产物：
#   build/libs/command-gui-0.2.0-beta.2.jar
#   server/build/libs/command-gui-server-0.2.0-beta.2.jar
```

> 国内网络建议在 `~/.gradle/init.gradle` 配置阿里云镜像（本项目已使用）；首次构建需下载 Gradle 9.5.0 发行版（约 140MB）。

---

## 十、许可证

[GPL-3.0](LICENSE)
