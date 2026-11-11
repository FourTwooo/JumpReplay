# 快捷方式独立进程试验版

本轮只处理快捷方式透明 Activity 与 Shizuku 多进程支持。第三方自定义 Parcelable/Serializable 参数重放留到下一轮。

## 改动

- `ShortcutActivity` 使用 `:shortcut` 进程和 `${applicationId}.shortcut` 任务栈；新建快捷方式携带 `NEW_TASK`。原来的 Activity 名称、旧快捷方式入参以及文件载荷协议保留。
- 透明窗口禁用启动预览和背景变暗，参数框直接显示在桌面或原应用之上。启动成功后正常 `finish()`，不会删除目标应用的任务栈。
- 在 `Application.attachBaseContext` 中启用 Shizuku 官方多进程模式。主进程持有 Provider，快捷方式进程主动取得 Binder，各进程分别接收授权与断开事件。保留 Sui 初始化路径。
- 快捷方式不再依赖主进程的静态授权状态。冷 Binder、首次授权、拒绝、服务重启、连接超时均有等待或错误反馈；参数确认后会禁用重复点击，失败可再次确认。
- 取消、Activity 销毁和新的快捷方式会撤销旧等待操作。系统助手排队任务另持有独立取消令牌，获得跨进程锁后再次检查，避免取消后延迟启动。
- Root 模式通过未导出的主进程后台服务复用 libsu 连接。libsu 6 的服务端按 UID 跟踪客户端，不能让两个同 UID 的应用进程分别管理同一连接。该中转不启动 MainActivity。
- 系统助手临时替换操作使用应用私有文件锁，覆盖读取、替换、启动和恢复全过程，防止主界面与快捷方式同时使用时互相恢复错配置。

主进程可能因 Shizuku Provider 或 Root 后台服务而启动；这是后台通信需要，MainActivity 无须打开。原有跨应用捕获通道保持开放；悬浮窗仍只控制当前目标进程。

## 验证范围

自动检查已通过：完整 Debug/Release 构建、95 项单元测试（主模块 91、拦截器模板 4）、两个模块 Lint 零 Error/Fatal、两个 instrumentation APK 编译。新增测试覆盖独立进程/任务栈/透明主题、主进程 Provider 与私有 Root 中转，以及 Binder 冷启动、授权/拒绝/重启、取消、重复回调、超时和 Root 等待流程。Lint 仍有主模块 157 条、模板模块 30 条 Warning。

Robolectric 与假后端测试不能证明真实手机上的 Shizuku 授权、ROM 任务栈表现或 Root/Sui 工作正常。本轮未安装 APK、未执行真机授权测试。

## 手机上优先测试

1. 主应用退出到桌面，Shizuku 已运行且已授权，点击带参数快捷方式：直接显示参数框，背景保持桌面；确认能启动目标。
2. 无参数快捷方式：直接启动目标；冷连接时只显示短暂等待提示。
3. 将主应用普通进程终止后重新点击快捷方式：允许后台 Provider 拉起，但不应显示主界面。不要用系统“强行停止”代替普通进程终止来判断桌面快捷方式可用性。
4. 撤销 Shizuku 授权后点击快捷方式：允许后只启动一次，拒绝后提示失败且可重试；授权框覆盖快捷方式时 Activity 不应被销毁。
5. 等待连接或授权时取消、返回、点另一个快捷方式：旧目标不应在稍后收到 Binder 时启动。
6. 重启 Shizuku 再点击，核对重新连接；Shizuku 未启动时应超时提示，不应进入主应用。
7. 使用原有普通、Root、系统助手模式，以及更新前已存在的快捷方式。

## 版本与回退

- 本轮修改前：`00da2ba068162de22bfedec7d8fbc44d31eaf948`。
- 回退分支：`codex/rollback-before-shortcut-20261111-104522`。
- 本轮分支：`codex/shortcut-process-20261111-104522`。
- 备份目录：`C:/Users/42/Desktop/Tool/Code/HookIntent-backups/shortcut-20261111-104522`，内含修改前 Git bundle 与本轮交付文件。

在项目根目录执行以下命令可切换源码版本；先提交或保存自己后续的修改，避免切换时混入其它改动。

```powershell
git switch codex/rollback-before-shortcut-20261111-104522
# 返回本轮版本
git switch codex/shortcut-process-20261111-104522
```

修改前的测试包仍在 `audit-artifacts/JumpReplay-1.6.1-audit.1-debug.apk`。新包为 `audit-artifacts/JumpReplay-1.6.1-shortcut.1-debug.apk`；相同 Debug 签名、相同 versionCode，可覆盖安装并保留数据，也可覆盖装回旧 Debug 包。设备厂商仍可能要求用户确认安装。

Release 交付文件未签名，需要原发布密钥签名。原 Release 与 Debug 签名不同，不能互相覆盖；不要为测试而卸载清空原应用。

## 依据

- [Shizuku 官方 Provider 多进程实现](https://github.com/RikkaApps/Shizuku-API/blob/master/provider/src/main/java/rikka/shizuku/ShizukuProvider.java)
- [Shizuku 官方示例 Application](https://github.com/RikkaApps/Shizuku-API/blob/master/demo/src/main/java/rikka/shizuku/demo/DemoApplication.java)
- [Android 任务栈说明](https://developer.android.com/guide/components/activities/tasks-and-back-stack)
- [libsu 6.0.0 服务端实现](https://github.com/topjohnwu/libsu/blob/6.0.0/service/src/main/java/com/topjohnwu/superuser/internal/RootServiceServer.java)
