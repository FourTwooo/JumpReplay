# 系统 Scheme 拦截与 Intent 漏抓审查（2026-11-13）

本次分成两份提交：先修复应用进程捕获，再修改系统 Scheme 拦截。回退基线为 `framework-capture-baseline-20261113`（`fed6a78`）。系统版本是待真机验证的候选实现，不能把下面的源码核对、Robolectric 测试当成 LSPosed 在真实 system_server 中的启动测试。

## 1. 系统拦截

### 查到的问题

1. 不能把 Android 11 的问题概括为方法消失：AOSP Android 11 中 `PackageManagerService.queryIntentActivitiesInternal` 仍存在。
2. 现有代码在 `handleLoadPackage` 中等待 `Application.attach` 后才进入系统分支。LSPosed 的系统模块回调在 `SystemServer.startBootstrapServices` 前发生；AOSP 此时已经执行 `createSystemContext → ActivityThread.systemMain → attach(true) → makeApplication`。等待下一次 Application.attach 会错过系统初始化。
3. 查找系统服务类使用了 Context 的加载器，没有使用模块回调提供的 system_server 类加载器。两者不应假定相同。
4. Android 12 的查询实现存在于 `PackageManagerService$ComputerEngine`；13 起存在独立 `ComputerEngine`。只在 13 起更换类名没有覆盖 12 的内部实现。
5. 向列表末尾添加拦截器并不总能显示选择器：`chooseBestActivity` 先比较前两个候选的优先级、preferredOrder 和 isDefault。首项和第二项不同便可能直接选择首项，根本看不到末尾新增候选。
6. 原实现 Hook 所有查询重载，并在每次 URI 查询时输出候选列表。前者可能在包装方法和实现方法各处理一次，后者会增加系统查询路径的日志负担。

### 候选修改

- 系统包回调直接安装 Hook，使用回调中的系统服务类加载器；普通应用初始化流程保留。
- 按独立 ComputerEngine、PMS 内部 ComputerEngine、PMS 的顺序探测真实类，选择已知完整签名的一处实现。支持旧版 4/7 参数、11/12 的 8 参数 int flags、13/14 的 8 参数 long flags、15/16 的 9 参数形式。未知签名不修改系统结果，并记录诊断。
- 缓存初始化推迟到系统主消息循环；配置查询、接收器注册、正则编译不放在系统查询回调中。注册失败不会从缓存构造过程直接抛到系统启动栈，同一错误不持续刷日志。
- 只处理无 component、无 package 的隐式 URI 请求；正确检查 selector，避免误改 selector 中指定组件或包名的请求。
- 新候选紧跟最高优先级候选，复制其排序属性。保留原 List 和已有 ResolveInfo，拦截器 ActivityInfo/ApplicationInfo 另建副本。
- 禁用规则仍先于“列表已有拦截器”的判断，保留 HTTP/HTTPS 已匹配本应用时的禁用行为。
- 异常、缓存未准备好、未知返回类型时保留原结果；不递归查询候选、不改写启动 Intent 或其 extras。

### 边界与尚未验证的部分

- 仍是“将意图拦截器加入系统候选”的行为，不是绕过所有系统安全校验强行跳转。显式组件和指定 package 的请求保持原来的不处理规则。
- 缓存来自系统 Context 所属用户。不会把这一用户的 ActivityInfo 伪装成其他 Android 用户的组件；工作资料和多用户需要另做各用户组件缓存与实际验证。
- 原有正则使用 Android 的 ICU 实现，仍在匹配阶段执行。不能用 JVM 的可中断 CharSequence 手法声称限制住 Android 的原生回溯；本次未加入这种无效保护。复杂正则的系统线程耗时仍属于待验证风险。
- 系统启动、厂商 Resolver、默认应用、跨用户、锁屏启动等尚未完成原生 Hook 验证。当前不自动安装或重启手机。

## 2. Intent 漏抓

### 调用链结论

普通 Activity 启动通常为 `Activity.startActivity → startActivityForResult → Instrumentation.execStartActivity → Binder`。原来的前两处 Hook 会重复覆盖同一条链；ContextWrapper 的正常启动也会汇合到 Instrumentation。

独立路径包括：

| 路径 | 选用入口 | 理由 |
| --- | --- | --- |
| 普通 Activity/Context 启动 | Instrumentation.execStartActivity | 替换上层重复包装 Hook |
| 批量启动 | Instrumentation.execStartActivitiesAsUser | execStartActivities 委托到这里，传入 Intent[] |
| 以调用者身份启动 | Instrumentation.execStartActivityAsCaller | 单独发起 Binder 启动 |
| AppTask 启动 | Instrumentation.execStartActivityFromAppTask | 单独调用 AppTask 的 Binder 接口 |
| ContextImpl 指定用户启动 | ContextImpl.startActivityAsUser 的完整重载 | 直接发出 Binder 请求，绕过 Instrumentation |
| 需要时启动/下一个匹配目标 | Activity.startActivityIfNeeded、startNextMatchingActivity 的完整重载 | 直接发出 Binder 请求，短重载委托到这里 |
| Activity 首次创建 | Activity.performCreate | 从 Activity.getIntent 获取本次创建的 Intent，不再把每次 resume 当作新的 Intent |
| 已有 Activity 收到新 Intent | ActivityThread.deliverNewIntents | 读取实际收到的每个 Intent；不依赖应用是否调用 super.onNewIntent、是否 setIntent |

通知的 PendingIntent 可以由 system_server 的 PendingIntentRecord 直接交给 ATMS/AMS，目标应用不会先执行自己的 startActivity。因此接收路径必须独立覆盖，而不是继续叠加通知 API Hook。

首次创建和收到新 Intent 是不同的交付情形；发送和接收也是不同观测点。新的实现去掉了普通 startActivity 链上的重复包装 Hook，不用内容相同就长期忽略的做法代替调用链分析。接收侧在原始数据的副本上设置类加载器，保留原 Intent 和第三方 Parcelable/Serializable 的重放链路。

### 配置兼容

- 保留旧 Activity.startActivity、Activity.startActivityForResult、ContextWrapper.startActivity 的逻辑开关，通过实际调用栈决定是否允许捕获；不再在这些包装方法上安装 Hook。
- 老配置把以上三个发送入口全部关掉时，新发送入口默认也关闭。用户明确保存过的新入口开关优先。
- 原 Activity.onResume 开关控制首次创建捕获；原 Activity.onNewIntent 开关控制新 Intent 交付捕获。它们保留用于旧配置兼容，并非仍在同名方法上捕获。
- 主应用全局开关和悬浮窗当前进程开关沿用现有实现。自定义 Hook、Scheme 字符串捕获、打包拦截器、重放和参数编辑接口未更改。

### 微信示例的结论

外部存在“按 wxid 打开聊天”的 Intent，不能证明微信内部点击会话时也发送该 Intent。内部可能复用 Activity、切换 Fragment/其他页面容器，或调用不同入口。新版本覆盖了上表缺口，但尚未采集微信该操作的实际发送/接收轨迹，不能声称这一具体操作已经修复，更不能承诺所有页面切换都有 Intent。

## 3. 验证范围

- 应用回调测试运行 Android 7/API24、9/API28、11/API30、13/API33 的 Robolectric 框架，实际安装的 Hook Method 用可观察安装器收集，并调用捕获回调验证行为。没有调用原生 Xposed Hook。
- 系统查询测试对 Android 9、11、12、13 框架类实际反射并选择签名；13 以后的额外签名来自官方源码核对及签名模型测试。
- 另外验证批量顺序与重复项、旧配置关闭状态、实际 incoming Intent、不依赖 super.onNewIntent、显式/selector 请求、不同优先级候选、不改原 List、禁用规则与未知返回结构。
- 完整单元测试、APK 构建、Lint 的最终结果随交付日志保存。

### 本次最终结果

| 版本 | 验证结果 |
| --- | --- |
| 仅捕获修复 `1.6.5-capture.1` / `intent-capture-v1-20261113` | 独立源码工作树构建成功；174 项测试通过，2 项不适用跳过；Lint 0 错误、170 警告 |
| 加入系统拦截候选 `1.6.5-framework.1` / `scheme-candidate-v1-20261113` | Debug/Release 构建成功；主模块 197 项测试通过、2 项不适用跳过，打包拦截器模板 4 项通过；主模块 Lint 0 错误、170 警告 |

两项跳过仅对应 Android 11/13 没有同时安装两个 create 重载时的嵌套重载测试，不是失败后忽略。没有原生 LSPosed 系统进程测试，也没有微信会话跳转实测。没有安装到手机或重启设备。

原版本 APK、两份新 APK、Git 全历史 bundle、源码 ZIP、验证日志和哈希均保存在项目旁的 `HookIntent-backups/framework-capture-20261113`。源码回退基线为 `framework-capture-baseline-20261113`；仅捕获版本另有分支 `codex/intent-capture-only-20261113`。

## 官方源码

- [LSPosed 系统模块回调与系统类加载器](https://github.com/LSPosed/LSPosed/blob/master/core/src/main/java/org/lsposed/lspd/hooker/StartBootstrapServicesHooker.java)
- [Android 11 SystemServer 初始化顺序](https://android.googlesource.com/platform/frameworks/base/+/android11-release/services/java/com/android/server/SystemServer.java)
- [Android 11 PMS 查询与 chooseBestActivity](https://android.googlesource.com/platform/frameworks/base/+/android11-release/services/core/java/com/android/server/pm/PackageManagerService.java)
- [Android 12 PMS 内部 ComputerEngine](https://android.googlesource.com/platform/frameworks/base/+/android12-release/services/core/java/com/android/server/pm/PackageManagerService.java)
- [Android 13 ComputerEngine](https://android.googlesource.com/platform/frameworks/base/+/android13-release/services/core/java/com/android/server/pm/ComputerEngine.java)
- [Android 16 ComputerEngine](https://android.googlesource.com/platform/frameworks/base/+/android16-release/services/core/java/com/android/server/pm/ComputerEngine.java)
- [Android 11 Instrumentation 发送入口](https://android.googlesource.com/platform/frameworks/base/+/android11-release/core/java/android/app/Instrumentation.java)
- [Android 11 ActivityThread 新 Intent 交付](https://android.googlesource.com/platform/frameworks/base/+/android11-release/core/java/android/app/ActivityThread.java)
- [Android 11 PendingIntentRecord 系统发送路径](https://android.googlesource.com/platform/frameworks/base/+/android11-release/services/core/java/com/android/server/am/PendingIntentRecord.java)
- [Android 11 Matcher 转换输入并使用 ICU MatcherNative](https://android.googlesource.com/platform/libcore/+/android11-release/ojluni/src/main/java/java/util/regex/Matcher.java)
