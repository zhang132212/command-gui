# main 性能与逻辑优化验收（2026-09-23）

基线：[zhang132212/command-gui main，208fae03e72c1339c642a99fb734f457602c7cd8](https://github.com/zhang132212/command-gui/tree/208fae03e72c1339c642a99fb734f457602c7cd8)。结束前再次通过 `git ls-remote` 确认 main 未变。

优化代码在独立分支 `codex/main-performance-e2e-20260923`，原有工作目录未提交内容未被覆盖。未推送或部署。

## 修复和性能改进

|模块|原问题|本次结果|
|---|---|---|
|管理权限|离线命令源没有玩家实体，被误当控制台；普通玩家可通过 machineadmin/cgtest 修改白名单或冒充管理员删除机器|统一检查命令源的实际权限集；离线访客回归验证两条路径均被拒绝|
|调度取消|执行命令同步停止自身运行项时，遍历 Map 发生 ConcurrentModificationException|迭代本 tick 的运行项快照，执行前后检查是否已取消/替换|
|时间线|末尾连续延时被丢弃；纯等待关机不生效|保留尾等待步骤，覆盖循环间隔、最终完成与纯等待流程|
|模式切换|空停止流程忽略间隔/完成反馈；主开关可打断正在进行的模式流程|空流程也完成状态机与间隔，主开关和模式任务互斥|
|区块检测|卸载时优先读旧磁盘，覆盖新内存状态；自定义维度误回退主世界；超范围 Y 可截断别名到已有区段|直接快照待卸载区块内存；按正确维度目录查找；区段计算保留 int|
|无效配置|无效维度可保存并在 tick 中崩溃；模式检测配置漏校验|保存时拒绝无效标识符/检测配置；已有坏维度返回异常状态，不抛到服务器 tick|
|配置载入|候选配置还未完整迁移就替换有效状态|归一化、迁移、结构校验完成后发布；损坏结构保留当前有效配置|
|会话生命周期|切换集成世界残留运行项、编辑锁、订阅、缓存，以及客户端定时任务|服务端生命周期和客户端断线统一清理；真实客户端同 JVM 两世界验证|
|同步性能|每位玩家重复构建机器 JSON 树和检测状态|一次广播复用公共快照，逐玩家设置权限并生成独立响应；权限隔离断言通过|
|假人状态性能|每次读取反复获取 actionPack/遍历动作，客户端重复状态也刷新 UI|每位假人每次快照获取一次 actionPack、扫描一次动作；相同客户端快照不递增刷新版本|
|规则查询性能|逐规则/分类重复查找反射方法；被拒绝的频繁请求延长冷却|每次目录读取只解析一次各 API 方法；限频时间只记录接纳的查询|
|命令校验|主线程最多等待补全 500ms；用补全列表推断有效值；重定向和缺参判断不准|直接使用 Brigadier 完整解析和最终执行节点，不调用异步建议提供器|
|占位符/输入|player_fake 别名未替换；$ 和反斜杠破坏正则替换；旧坐标模板和重复坐标弹窗有误|按字面替换用户输入，兼容别名与 x/y/z，坐标只询问一次|
|长计时/本地数据|int tick 溢出、不同系统语言生成逗号坐标、失效目标分类可能丢命令、Reader 未关闭|long tick、Locale.ROOT、先验证分类再移出命令、try-with-resources|
|坏网络数据|解析中途清空已有机器/权限或假人状态|完整候选解析成功才整体发布，失败保留有效快照|
|测试设施|进程非零退出/阶段不完整仍可能产出全绿 JUnit；Windows 临时读锁影响报告发布|严格校验报告、记录 runner error、超时保留已完成用例；原子发布使用有上限的重试|

性能结论针对上述工作量减少和阻塞消除。本次未测量 TPS/FPS 提升百分比，也不将不同启动时间当作性能基准。

## 实测结果

环境：Windows 11、Microsoft JDK 25.0.3、Minecraft 26.2、Fabric Loader 0.19.3、Fabric API 0.156.0+26.2。
有 Carpet 的测试使用 `fabric-carpet-26.2+v260616.jar` 与 `carpet-org-addition-mc26.2.x-v1.45.1-2606231614.jar`；每份报告记录依赖 SHA-256。

|验证|结果|报告/标记|
|---|---|---|
|真实专用服务端 suite|143/143 用例通过，903 次断言|`build/backend-tests/20260923-233033-c53e4c9a/report.json`|
|同一测试存档跨进程重启|1/1 用例通过，6 次断言|同上，restart 阶段|
|真实客户端 + Carpet + 集成服务器|22/22 阶段通过，76 次断言|`build/client-e2e/20260923-232907-with-carpet-9c9caf6a/report.json`|
|真实客户端，无 Carpet|22/22 阶段通过，74 次断言|`build/client-e2e/20260923-232543-without-carpet-b672e6ef/report.json`|
|报告/JUnit 失败语义|112 次断言通过|`testing/backend/test-runner-reports.ps1`|
|布局和交互|三种逻辑尺寸 720×450、480×300、320×240；18 次控件边界检查；25 张截图；筛选组合、草稿保持、设置切换、复选框通过|`build/editor-layout-qa/runner.log` 中 `EDITOR_LAYOUT_QA_COMPLETE`|
|最终发布构建|成功，跳过自动版本递增；发布 JAR 含 main/client/server，未混入 QA 类|`build/final-build.log`|

两个客户端运行都在标题画面执行同一组 46 项客户端检查；上表是各次运行的断言数量，不代表去重后的分支覆盖率。

真实 E2E 经 GUI hit testing 发出选择与确认事件，再经过实际 Fabric 连接、服务端命令执行、世界状态变更和客户端同步。测试创建独立的 E2E-A、E2E-B 世界，不使用已有玩家存档。

### 原始 main 对照

另建 `command-gui-regression-baseline` 工作区，只放入相同测试设施，产品源码保持原始 `208fae0`：143 个后端用例完整执行，122 通过、21 失败，退出码 1；优化版同套件 143 通过、0 失败。

21 项中有 20 项行为/状态断言失败；另 1 项是旧版没有新增生命周期清理方法，反射测试报告 NoSuchMethodException。实际断线/换世界的行为另由真实客户端 E2E 验证。首次未隔离非法配置夹具的对照运行，还复现了非法维度导致服务器 tick 崩溃；最终用例在 finally 中移除坏夹具，保留原失败断言，让其他缺陷能继续独立验证。

对照报告：`command-gui-regression-baseline/build/backend-tests/20260923-233033-4d24f01b/report.json`。这不是跳过失败项的基线白名单，优化版仍要求所有回归通过。

## 复现

```powershell
# 无需启动 Minecraft 的报告自检（CI 已加入此步骤）
./testing/backend/test-runner-reports.ps1

# 独立专用服务器，包含完整 suite 与 restart
./testing/run-backend-tests.ps1 -ModsDirectory 'D:\测试依赖' -EulaFile 'D:\已接受协议的服务端\eula.txt' -Offline

# 桌面真实客户端，有/无 Carpet 两种环境
./testing/run-client-e2e.ps1 -ModsDirectory 'D:\测试依赖' -Offline
./testing/run-client-e2e.ps1 -WithoutCarpet -Offline

# 发布构建；构建本身不等同于已运行上述集成测试
./gradlew.bat build -x bumpVersion --offline
```

首次运行未缓存依赖时移除 `-Offline`。后端脚本复用已有已接受 EULA 文件，所有服务器只使用脚本新建的测试目录。

## 产物与范围

JAR：`build/libs/command-gui-0.3.0-beta1.jar`（607,590 字节）。

SHA-256：`31459A65F61CEF836C926CC620AF73ED34C0C7BBEAC39753BA3024D7B590FE01`。

测试覆盖本轮缺陷、既有后端模块与上述真实客户端流程；未声称穷尽所有代码分支。尚未验证两个远程真人客户端的 TCP 竞争/丢包、公网认证故障、磁盘写满/断电、任意第三方 Carpet 扩展及 ReGlass/其他渲染模组的所有组合。可选依赖缺失、实际断线、单人世界作弊权限撤销和同 JVM 世界切换已验证。

详细测试维护说明：[后端](../testing/backend/README.md)、[客户端 E2E](../tests/e2e/README.md)。
