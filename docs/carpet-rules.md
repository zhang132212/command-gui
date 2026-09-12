# Carpet 规则查询与批量确认

新增「Carpet 规则」标签，保留原有 Carpet 预设命令。显示开关沿用设置中的 Carpet 命令开关。

- 单人世界：必须开启作弊，并具有指令权限。
- 专用服务端：需要 OP（Minecraft `LEVEL_MODERATORS`）及以上权限。
- 权限在客户端显示、子页面、查询接口和执行接口共同检查，创意模式不是权限依据。掉线或失去权限会清空缓存和待执行项。
- 服务端需要同时安装新版 Command-GUI。单人世界由同一 JAR 的集成服务端提供查询。未安装 Carpet、旧服务端不支持查询等情况会显示原因。

## 数据与操作

通过可选的 Carpet API 适配器调用 `CarpetServer.forEachManager`、`SettingsManager.getCarpetRules`，读取规则的原生分类、名称、说明、类型、候选值、严格取值限制和当前值。Org 注册到 Carpet 管理器中的规则和独立管理器的扩展都走同一路径，不内置固定规则清单。说明及分类翻译采用 Carpet 的当前语言。

点击规则进入编辑器，支持候选值翻页、非严格规则的自定义输入，以及本次设置、`setDefault`、`removeDefault`。点击“加入待执行”不会改变规则。列表右键取消单项，也可清空整批。批量确认页列出所有分类和搜索条件下的待执行项及操作类型。

“内置默认”指规则声明的 `defaultValue`，不冒充当前存档保存的默认值。`setDefault` 与 `removeDefault` 使用原生指令，保留 Org 自己的配置存储方式；移除默认后的即时行为由对应扩展决定。

服务端每批最多接收 64 个不同规则，先重新读取目录、校验选择时的值及锁定状态，再使用玩家自己的 `CommandSourceStack` 调用各管理器原有命令。不会直接写规则字段、绕过 Carpet 权限与验证器，或修改现有机器后端。批次不是事务：值验证失败时原生错误会出现在聊天中，不回滚已经成功的其他项。界面只报告请求已处理，随后重新查询实际值。

查询分包并按请求编号组装。批次去重、非法操作名/规则标识/控制字符拒绝、权限撤销及超时均有处理。超时不自动重试修改。

## 验证

2026-09-12，在隔离存档中使用 Minecraft 26.2、`fabric-carpet-26.2+v260616.jar` 和 `carpet-org-addition-mc26.2.x-v1.45.1-2606231614.jar` 进行真实客户端/集成服务端验证：

- 查询 171 项真实规则及 Org 原生分类，含多个非布尔候选值。
- 布尔规则 `flippinCactus`、数值规则 `pushLimit` 的混合批次执行及恢复。
- Org `commandRuleSearch` 的 setDefault 写入其 `carpetorgaddition/config.json`，removeDefault 删除该项。
- 单人作弊关闭后隐藏标签，并在服务端拒绝伪造修改包；共享权限策略覆盖所有 Minecraft 权限等级及专用服务端分支。
- 规则编辑、批量确认、宽/窄窗口布局、严格值及命令构造校验。

可复用测试位于 `tests/carpet-rules`，不打入正式 JAR。将一个**可丢弃的测试存档副本**放入 `build/carpet-rules-test/saves/CarpetRulesQA`，并将上述 Carpet、Org JAR 放入 `build/carpet-rules-test/mods`；以 JDK 25 运行：

```powershell
.\gradlew.bat :runClient -I tests/carpet-rules/init.gradle -x bumpVersion --console=plain --offline '-Dorg.gradle.jvmargs=-Xmx2G -Dfile.encoding=COMPAT'
```

测试会设置测试存档的作弊/OP、修改并恢复测试规则，最后关闭作弊。成功标记是 `CARPET_QA_COMPLETE`，失败标记是 `CARPET_QA_FAILED`。截图位于测试运行目录的 `screenshots`。

参考源码：[Org RuleSearchCommand](https://github.com/fcsailboat/Carpet-Org-Addition/blob/26.1/src/main/java/boat/carpetorgaddition/command/RuleSearchCommand.java)、[Carpet CarpetRule API](https://github.com/gnembon/fabric-carpet/blob/master/src/main/java/carpet/api/settings/CarpetRule.java)。实际兼容性以以上 26.2 JAR 测试为准。
