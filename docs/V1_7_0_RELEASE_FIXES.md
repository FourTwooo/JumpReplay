# 1.7.0 发布问题修复与验收

日期：2026-11-14。正式版本为 **1.7.0 / 10700**，本轮最终调试候选为 **1.7.0-dev.8**。桌面保留第一版 StackView 卡片滑动样式；轮转原型没有接入。没有修改 IntentCapture / ActivityCaptureHooks 等系统框架 Hook。

## 修复内容

1. **Android 11+ 包可见性。** 主应用通过已有配置 Provider 的只读 URI 授权，建立接收应用对 JumpReplay 的包可见性；无需修改第三方 Manifest、Root 或改签第三方 APK。主进程启动、开机、主应用更新和目标应用安装 / 更新时补齐授权。只授予配置 URI 的读取权限，没有新增写权限；开放配置 / Messenger 通信和自定义参数编辑端点原有身份校验均保留。系统文档见 [自动可见的应用：URI 授权](https://developer.android.com/training/package-visibility/automatic)。
2. **临时草稿回收。** 动作编辑使用独立 `files/action_drafts`，同一编辑会话复用一个 ID，以 AtomicFile 写入。正常保存 / 取消后删除；旋转重建保留；超过 7 天的废弃草稿在打开编辑器时清理，活跃会话受保护。永久 `shortcut_payloads` 不参与扫描或删除，旧入口仍可读取。旧版本混存在永久目录的历史文件不能可靠区分是否被快捷方式引用，本轮不冒险删除。
3. **新页面本地化。** 动作导入、操作和图标菜单、参数提示、校验错误、备份提示、组件配置、无障碍说明、捕获监视计数等改用中 / 英 / 日资源。修复英文、日文“Shizuku 系统助手”显示文本被当成持久化启动方式的问题，已有本地化值兼容归一化，内部协议值保持不变。
4. **验收中发现的组件恢复问题。** 一加桌面在应用反复强制停止后可持有失效 RemoteViews 服务连接。主进程冷启动刷新已存在组件，每个进程会话使用新的适配器标识；进程内更新复用同一标识。没有修改卡片布局、动画、动作选择或永久载荷。

## 验证结果

- Gradle Debug / Release、AndroidTest 构建成功；单元测试共 192 项，190 通过、2 项跳过，无失败；Lint 0 错误、187 项警告。Windows 下 Robolectric 的现代 AtomicFile 替换不能等同 Android 的 POSIX 行为，草稿单元测试使用 API 28，API 29 / 33 真机环境另验实际覆盖写入及重建。
- 主验收：OnePlus 6 / Android 10，`f9b4ccf9`。dev.8 的草稿重复重建恢复、英文 / 日文界面及语言恢复、自定义对象编辑重放三项原生测试全部通过。语言测试结束后恢复原 `zh-CN` 记录；组件选择不变。
- 真机实际桌面验证：卡片滑动、点击进入系统设置、强制停止后打开应用恢复全部卡片；捕获关闭时后台没有 CaptureSessionService。已有微信主进程 / push 进程仍能绑定 Messenger。
- Android 13 / API 33 补测：`emulator-5584`，仅用于不同系统兼容性。dev.8 同样三项原生测试全部通过。实际 Activity 重放使用系统 Binder 预算内载荷；不宣称突破系统事务大小限制。
- 独立 targetSdk 33 普通探针没有 `queries`、`QUERY_ALL_PACKAGES` 或 Xposed 配置。修复前包不可见、Provider 无行、绑定失败；修复后配置读取、Messenger 注册及真实状态回包均成功。另一个新安装包、目标更新和冷启动后重试均通过；冷启动检查在打开主 Activity 前完成。没有用给探针补 `queries` 来绕过问题。
- 数据核对：原 1286 条 incoming、4 条动作、4 条动作载荷的完整原始行保留；收藏、已归档捕获、组件选择和原有永久载荷没有清空。测试仅操作自有临时草稿及既有 QA 动作。

本轮覆盖上述设备和场景，不能据此保证所有厂商 / 所有 Android 版本均没有问题。原有自定义参数的来源环境和不可持久化对象限制仍适用。

## 证据与回退

- 源码修改前回退标签：`pre-v170-release-fixes-20261114`，提交 `9812013`。另有 `first-slide-card-retained-20261114` 保留第一版组件。
- 修复完成候选标签：`v1.7.0-rc-release-fixes-20261114`。交付目录 `C:/Users/42/Desktop/Tool/Code/HookIntent-backups/v170-release-fixes-delivery-20261114/` 保存 APK、源码 Git bundle、测试记录及 SHA-256；具体源码提交见该目录的 `validation.json`。
- 每次安装前备份原 APK 和完整私有数据，逐文件读取 TAR 校验；未清空应用数据。原始 dev.3 真机备份：`C:/Users/42/Desktop/Tool/Code/HookIntent-backups/v170-release-fixes-phone-final-before-20261114/`。dev.8 更新前两台设备、主包及测试包备份：`C:/Users/42/Desktop/Tool/Code/HookIntent-backups/v170-release-fixes-dev8-before-20261114/`。
- 构建 / Lint：`audit-artifacts/v170-release-fixes-dev8-final.log`；原生测试：`v170-release-fixes-dev8-phone.txt`、`v170-release-fixes-dev8-api33.txt`；真机组件：`v170-release-fixes-dev7-widget.txt` 和 `v170-release-fixes-phone/`；可见性对照：`v170-release-fixes-api33/`；数据对照：`v170-release-fixes-data-check/`。
- 调试包仅供验收。Release APK 未签名，正式发布必须使用项目原发布密钥；本轮没有发布线上版本。

## 更新说明补充

- 修复 Android 11+ 部分应用无法读取配置、连接捕获通信服务的问题。
- 改善动作编辑草稿回收与恢复，避免长期积累临时文件。
- 补齐动作、桌面卡片及捕获监视相关英文 / 日文文案，修复本地化启动方式兼容性。
- 修复应用重启后部分桌面卡片停留在加载状态的问题。
