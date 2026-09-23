# 真实客户端端到端回归

在独立 `build/client-e2e/<时间-模式-随机编号>/run` 启动 Minecraft 26.2 客户端，创建两个全新集成世界。
测试模组通过 `Screen.mouseClicked` 点击机器选择/确认与规则确认按钮，经过产品客户端、Fabric 实际连接、服务端处理、实际指令、方块检测，再等待客户端同步。
不会替换网络发送或握手，不读取用户存档，不把 QA 代码打进发布 JAR。

要求 Windows、PowerShell 7、JDK 25 和可渲染 OpenGL 的桌面。运行时会出现自动操作的 Minecraft 窗口。

```powershell
# Carpet 与 Carpet Org Addition（各一个匹配 Minecraft 26.2 的 JAR）
./testing/run-client-e2e.ps1 -ModsDirectory 'D:\测试依赖' -Offline
# 无 Carpet：验证可选依赖缺失时能启动、拒绝不支持的开机指令并正常切换世界
./testing/run-client-e2e.ps1 -WithoutCarpet -Offline
```

`-Offline` 只禁止 Gradle 下载；首次依赖未缓存时去掉它。JDK 可通过 `-JavaHome` 指定。
每次都使用新配置、存档、测试账户；超时只终止此脚本创建的进程树，保留结果以便诊断。

覆盖：

- 标题画面先执行 `ClientRegressionChecks`：四种系统语言下的坐标、特殊字符替换、旧占位符、重定向指令、长计时、延迟队列、分类移动、坏包原子性、断线事件。
- 真正的 Fabric 握手、机器新增、编辑锁、保存、过期修订号、无效 JSON 和未知动作。
- GUI 选择不立即执行，点击确认才启动；真实 Carpet 假人生成、检测方块开关和客户端状态同步。
- 规则分页查询、GUI 确认提交、真实 Carpet 值变化、恢复原值、撤销单人世界作弊权限。
- 带未完成任务断线后，同 JVM 打开第二世界，验证静态任务、服务端引用和运行状态不泄漏，配置按既有设计在该客户端实例内保留。
- 第二世界删除机器并验证服务器确认。

输出 `report.json`、`client.log`、`run/e2e-report.json` 与 `run/screenshots/e2e-machine.png`。只有退出码为 0、完整 22 个阶段成功且断言达到最低覆盖守卫才算通过；`BUILD SUCCESSFUL` 不代表测试通过。

边界：这是单人集成服务器实际连接，独立服务端覆盖见 `testing/backend/README.md`。不等同于两个远程玩家通过 TCP 同时操作，不覆盖网络丢包、断电/磁盘写满、任意第三方扩展、所有渲染器。窗口尺寸/编辑器布局有独立的 `tests/editor-layout` 和 `tests/gui-theme` 工具。
