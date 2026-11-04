# 页面读取对照程序

此目录仅是临时诊断输入，不参与常规 APK 构建。`scripts/prepare-page-read-probe.py` 在 `.tools/a11y-probe/project` 建立隔离副本，不修改正式工程，不操作手机。

副本保留 `PageCaptureService` 原有的 `getRootInActiveWindow()`、后台线程、节点遍历、范围上限、密码/编辑框退出与有限重试。唯一的采集范围扩展是自己的固定测试页，`isAccessibilityTool` 保持 false。页面读取出口替换成计数报告，不调用流水队列或自动入账。

四项对照：原生 TextView 固定标记、本地 WebView 相同标记、微信非支付设置页面、已有转账详情。若原生测试也失败，优先查服务/读取实现；原生通过而 WebView 失败，查 WebView 兼容；两者通过而微信设置和详情失败，查微信整体开放情况；微信设置通过而详情失败，则更接近支付页面限制。单项结果不能证明具体厂商保护机制。

只记录节点/文字数量、根节点可见性/子节点数/敏感标记、固定标记是否匹配以及时间；不记录页面原文、联系人、交易单号或截图。每次显式开始诊断后有效 90 秒，结果最多保留 24 条。

```powershell
python -X utf8 scripts/prepare-page-read-probe.py
# 仅构建，不安装：
.\.tools\a11y-probe\project\gradlew.bat -p .tools/a11y-probe/project :app:assembleDebug :app:lintDebug
```

默认模式保持正式版的节点读取方法。`--windows` 单独加入公开的 `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` 对照：只检查允许来源的窗口元数据；仅在原根节点为空且同来源的聚焦应用窗口确有可见子节点时选择后者。`--fresh-cache` 单独在 Android 13 以上调用 `clearCache()` 后重读节点。两项实验应分别构建，不叠加，避免同时改变两个变量；均不参与正式 APK。

只有用户方便操作手机后，才安装该诊断 APK、重新确认原服务绑定状态并切换页面。该变体与正式版使用同一包名，覆盖安装保留数据；切勿卸载、清空数据或开启另一个无障碍服务来冒充同等权限。原有通知/手动记账功能仍在，诊断页面不会调用它们。

启动测试页的场景参数是 `native`、`web`、`wechat` 或 `stop`：

```powershell
adb shell am start -n cn.yubai.ledger/.PageReadProbeActivity --es scenario native
```

微信场景只在用户指定页面上运行。每次结果必须同时核对手机亮屏解锁、前台页面和服务已绑定，不能拿旧的偏好记录当新结果。原转账的真实入账测试须恢复正式 APK 后单独进行，诊断变体不用于证明自动扣款。

测试结束后覆盖安装原正式 APK，核对账本、权限与 APK 哈希恢复；删除设备上的诊断结果。未安装、未运行的检查必须标明待执行。

2026-11-02 已在 PLK110 实测四项对照、窗口选择和缓存刷新，已恢复 0.2.9。结果见 `docs/PAGE-READ-COMPARISON.md` 与 `delivery/page-read-comparison.json`。原生和本地 WebView 标记可读，微信设置与转账详情不可读；该测试没有修复微信采集问题。
