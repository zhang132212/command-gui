# 顶部标签点击命中修复

问题复现于当前工作区（HEAD `5f1abed` 加已有诊断改动）。

## 日志定论

Minecraft 26.2 的 `TabNavigationBar.isMouseOver` 检查内部 `FrameLayout` 的直接子布局矩形，`getRectangle` 同样来自内部布局。自定义标签栏将按钮单独移动、改变宽度后，这个内部横向布局仍保持旧几何；渲染和按钮自身悬停正常，但 Screen 在父容器命中阶段便拦住了点击。

逻辑窗口 640 × 360 时，原父布局为 `(34,6,320,22)`，右边界是 354；机器标签绘制矩形为 `(322,6,100,22)`。

点击机器标签中心 `(372,17)`：

```text
修复前：childHit=true parentHit=false handled=false switched=false
修复后：childHit=true parentHit=true  handled=true  switched=true
```

所以增加按钮 `onClick` 或在标签栏的 `mouseClicked` 中直接切页，无法补救父级命中阶段没有分发事件的问题。

## 改动

- `GuiTabBar` 的命中判断使用实际绘制按钮的边界，排除隐藏、禁用按钮与按钮间隙。
- 容器位置、宽度及导航矩形同步至实际标签布局，不再依赖原生内部布局的旧矩形。
- 点击走原生事件分发与既有按钮回调，移除忽略鼠标键、active/visible 的直接切页兜底。
- 保留已有界面和按钮诊断日志。业务层源码没有修改。

## 回归检查

隔离客户端通过 Screen 的实际鼠标事件入口，分别在 480 × 270、640 × 360、320 × 240 三种逻辑尺寸执行检查。

- 每个标签左、中、右三个点，共 36 个点击位置。修复前其中 8 个失败，修复后全部通过。
- 另验证右键、禁用和隐藏标签、隐藏标签栏、标签间隙、下边界，以及 Ctrl+3 键盘切页。
- 修复后共 90 项检查，失败 0。

当前工作区复现入口为 `build/visual-qa/src/qa/TabHitQa.java`，运行配置为 `build/visual-qa/init.gradle`。原始日志保存在 `build/visual-qa/tab-hit-before.log` 和 `build/visual-qa/tab-hit-after.log`。这些夹具和日志不打入正式 JAR。
