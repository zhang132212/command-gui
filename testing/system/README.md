# 可复现的双端系统测试程序

统一入口为 `testing/run-system-tests.ps1`。程序串行执行各个独立阶段，前面失败仍继续后面的阶段；所有游戏实例使用本次结果目录内的新配置和新存档。

需要 PowerShell 7、JDK 25、可运行项目的 Gradle 依赖缓存/网络，以及包含对应版本 `fabric-carpet-*.jar`、`carpet-org-addition-*.jar` 各一份的目录。Minecraft 服务端 EULA 必须由使用者预先接受，程序只复制传入文件。

```powershell
pwsh -NoProfile -File testing/run-system-tests.ps1 `
  -JavaHome 'C:\path\to\jdk-25' `
  -ModsDirectory 'C:\path\to\test-mods' `
  -EulaFile 'C:\path\to\accepted\eula.txt' `
  -TimeoutSeconds 600 -Seed 132212 -Offline
```

不使用 `-Offline` 时允许 Gradle 下载依赖。默认输出 `build/system-tests/<时间-随机编号>/`，也可提供一个不存在或空的 `-OutputRoot`。拒绝复用非空目录，以免旧报告、旧截图造成假通过。实际 Minecraft 窗口会在客户端测试阶段出现。

| 阶段 | 验证内容 | 通过依据 |
| --- | --- | --- |
| backend | 专用服务端逻辑、权限、命令调度、数据存储、跨进程重启 | 必須同时有 suite 和 restart 完整报告，汇总与用例计数一致 |
| integrated-carpet | 真实客户端、集成服务端、有 Carpet | 完整流程报告、最低用例/断言守卫、正确依赖环境 |
| integrated-vanilla | 真实客户端、集成服务端、无 Carpet | 同上，且实际环境必须无 Carpet |
| dedicated | 独立服务端与两名真实客户端，通过 TCP 交互 | 独立运行器的完整双客户端/服务端报告及成功退出 |
| layout | 3 种窗口/GUI 尺寸、编辑器边界、草稿保留、设置与筛选行为 | 精确完成标记、18 次边界标记及 25 张非空截图 |
| runner-selftest | 测试系统本身是否会假绿 | 畸形/缺失/截断报告、计数冲突、超时、异常退出、编码、HTML/JUnit 等自检 |

`BUILD SUCCESSFUL` 本身不算测试通过。任何超时、启动失败、非零退出、缺少报告、错误数据类型、重复用例名、不完整流程或统计不符，都会使阶段失败。单阶段执行与跳过阶段始终明确标注 `PARTIAL`。

```powershell
# 不启动 Minecraft/Gradle，单独验证报告系统和子进程异常处理。
pwsh -NoProfile -File testing/system/test-reporting.ps1

# 用统一入口运行报告自检；其他阶段显示未运行，退出码为 2。
pwsh -NoProfile -File testing/run-system-tests.ps1 -Phases runner-selftest

# 在 PowerShell 内筛选阶段；仍不计作全量通过。
./testing/run-system-tests.ps1 -Phases backend,dedicated -ModsDirectory ... -EulaFile ... -JavaHome ...
```

## 结果与日志

- `report.html`：离线可用，可筛选失败阶段、搜索用例/错误、查看阶段耗时及步骤，并打开完整日志与截图。
- `report.json`：稳定的 `schemaVersion: 1` 汇总；记录环境、Git commit 与未提交状态、输入路径、依赖 SHA-256、种子、阶段退出码、超时、时间、用例和错误。
- `source-manifest.json`：记录已提交与未提交的测试/生产源码 SHA-256，定位实际测试版本。
- `junit.xml`：CI 可读。真实断言失败、运行器错误与未执行阶段分别写入 `failure`、`error`、`skipped`。
- `<阶段>/runner/command.json`、`stdout.log`、`stderr.log`：精确命令、参数、工作目录、时间预算与原始字节流；stdout/stderr 分开保留。
- `<阶段>/artifacts/`：子运行器的报告、Minecraft 日志、测试事件及截图，保留详细上下文。
- `report.json` 的 `evidence`：相对路径、字节数和 SHA-256。完整结果目录应一起复制或打包，不能只拿 HTML。

退出码：`0=所有声明阶段通过`，`1=至少一阶段失败/错误`，`2=仅运行部分阶段`。`passed` 仅在全量通过时为 `true`。操作系统或外层 shell 可能统一映射子程序非零退出；以 `report.json` 与阶段实际 `processExitCode` 为准。

截图显示实际渲染结果，自动布局断言检查可测量的越界和重叠；仅凭截图存在不等同于全自动视觉判定。日志包含故意注入的失败与拒绝请求，不能仅搜索 `ERROR` 就判断产品有 bug，应对应具体用例结果和事件顺序。

## 独立服务端、双客户端与日志分析

`testing/run-dedicated-e2e.ps1` 先构建一次产品 JAR 与测试专用 JAR，再根据 Gradle 导出的启动参数启动三个独立 JVM。服务端和 ActorA、ActorB 客户端分别在 `server/`、`client-ActorA/`、`client-ActorB/` 中运行，配置、模组目录、日志与截图分开。服务端使用本轮新建的平坦世界，仅监听 `127.0.0.1` 并由操作系统分配空闲端口；两个客户端通过真实 TCP 连接，权限不同，测试编辑冲突、同步、断线重连等双人流程。

每个角色保留 `command.json`、`stdout.log`、`stderr.log`、`report.json`、`events.jsonl`、Minecraft 自带日志及客户端截图。`command.json` 记录展开后的 JVM 参数与进程号。Windows Java 的 `@java.args` 文件采用 `GetACP` 返回的真实系统代码页，代码页也写入报告；JVM 标准输出/错误显式使用 UTF-8，并以原始字节保存。某角色退出并给出失败或不完整报告时，运行器会停止剩余实例，仍收集各角色已完成与失败的用例。清理、进程退出与日志排空都有时间上限，清理异常也保留在最终失败报告中。

`events.jsonl` 记录的是**全部 command-gui 自定义 payload 与测试事件**，不是所有原版 Minecraft 网络包的抓包文件。每行包含 UTC、进程内单调时间、连续序号、角色、线程、事件类型及内容。数据包记录包含方向、payload 类型、对端、是否为内存连接及完整 payload；其他事件包括实际 GUI 点击、输入、断言、状态快照、服务器命令执行和阶段开始/完成/失败。

统一 HTML 中有独立的双端分析面板：

- 按角色、发送/接收方向和 payload 类型统计数量，区分真实 TCP 与内存连接。
- 展示 `stage.fail`、`server.failure`、失败断言及对应原始日志行。
- 校验每行 JSON、序号连续、单调时间和最后一行完整性；截断尾行明确标为问题，不会静默丢弃。
- 按 UTC 展示阶段与命令执行时间线，可搜索内容、筛选角色。摘要有长度与条数上限，报告明确显示省略数量，完整 payload 仍在原始文件中。

已完成的日志可随时独立分析，无需启动 Minecraft 或 Gradle：

```powershell
pwsh -NoProfile -File testing/system/analyze-traces.ps1 `
  -TraceRoot 'build\system-tests\某次运行\dedicated\artifacts'
```

默认生成该目录下的 `trace-analysis.json`；也可以用 `-OutputFile` 指定路径。统一测试中即使业务用例全部通过，日志缺失、损坏或存在失败事件也会使专服阶段标为错误，避免缺证据时显示全绿。

## 子运行器报告约定

新增阶段宜输出以下顶层 JSON；`cases` 必须是数组，状态大小写严格匹配：

```json
{
  "schemaVersion": 1,
  "passed": true,
  "complete": true,
  "expectedCases": 1,
  "assertions": 3,
  "cases": [
    {"group": "network", "name": "request-response", "status": "PASS", "milliseconds": 25, "error": "", "steps": ["connect", "request", "response"]}
  ]
}
```

后端和集成客户端的旧报告由适配器读取，保持原入口兼容。发生失败也应尽量写报告，原始日志始终保留。新的 `expectedCases` 应由独立的预期流程定义给出，不能简单用执行成功数作为预期数。

## 覆盖边界

通过只说明所列自动化场景完成，不代表穷尽任意 mod 状态。真实客户端测试会调用界面处理逻辑、网络收发与服务端处理器，未把每个操作都转换为鼠标键盘输入。未模拟任意丢包、断电、磁盘写满、所有第三方模组、所有渲染器和操作系统。改变 `-Seed` 可以复现/扩展专服中的测试序列；后端随机序列另有记录的固定种子。发布前仍应在实际使用的整合包及环境下运行。
