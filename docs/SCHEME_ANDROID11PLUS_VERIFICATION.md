# 系统 Scheme 拦截：Android 11 / 12 / 13 原生验证

日期：2026-11-13。验证代码为 `bed622d95e5fae166cd2619d550b701df335c49a`，安装包为已通过 Android 10 真机验证的 `1.6.5-schemefix.1`。后续 1.7.0 的版本配置和功能规划不代表新功能已经完成；本报告对应下述固定 APK。

## 验收结果

三套 Google APIs x86_64 原生模拟器，均安装真实 Magisk 25.2 与 LSPosed 1.9.2 (7024) Zygisk，每套完成两次不同 kernel boot ID 下的完整流程。LSPosed 作用域只勾选 System Framework；测试发起应用和网易云均不勾选。

| Android | API | 实际安装的查询 Hook 所属类 | 两次冷启动流程 |
| --- | --- | --- | --- |
| 11 | 30 | `com.android.server.pm.PackageManagerService` | 通过 |
| 12 | 31 | `com.android.server.pm.PackageManagerService$ComputerEngine` | 通过 |
| 13 | 33 | `com.android.server.pm.ComputerEngine` | 通过 |

实际流程为：普通 UID 的测试应用点击按钮，发起未指定目标的 ACTION_VIEW → Android 系统选择器出现 JumpReplay 和网易云 → 选择 JumpReplay / Just once → 本应用 Intent Interceptor 编辑页 → 实际启用的 URI 输入框与原 URI 逐字一致。没有使用 Intent.createChooser、Root 重放、显式指定 JumpReplay 组件或打包拦截器冒充系统拦截。

首次选择后，系统记忆上次选择，后续可能显示 “Open with JumpReplay / Just once / Always / Use a different app” 的紧凑候选布局；网易云仍在候选中。测试不点击 Always，不将 JumpReplay 设为默认处理程序。

三套环境中，完成操作前后 system_server PID 均相同，本次启动 crash buffer 未发现 `FATAL EXCEPTION IN SYSTEM PROCESS` 或 `>>> system_server <<<`，模块日志没有旧问题中的 `No data directory found for package android`。这只描述本次测试窗口，不是长期稳定性或性能承诺。

## 固定输入和安装包

用户提供的完整 URI（204 字符）：

```
orpheus://playlist/2312165875?evokeChain=os_other.client_qqbrowser.sessionId_0bf4a102-46c8-48f1-b070-1f7881c1a477.id_2312165875.page_mMobilePlaylist.start.ac_downloadBtn.type_scheme.actionId_downloadBtn-2
```

- JumpReplay APK SHA-256：`9c083ae6825b1f7c8fff8aa19f2528f667a67b63b001cf6cc6bdd9db77132580`。与 Android 10 真机通过的安装包一致；最终从三台模拟器各自实际安装的 base.apk 核对。
- 网易云 APK 9.2.48 从用户真机只读导出，SHA-256：`f21f10fe8af19d819029a045ca515f810a67c0f27f24fe6c579b1d93e23c76dc`。实际安装并注册 `com.netease.cloudmusic/.activity.RedirectActivity`，没有用假的 Scheme 接收器替代。
- 网易云受保护的原生初始化在模拟器中可能报错；按用户要求，不测试其登录、播放和运行完整性。验收只依赖真实安装的 Scheme 匹配信息及进入 JumpReplay 的链路。
- 普通测试发起 APK 为 `com.fourtwo.hookintent.probe`，源码随证据保存；按钮仅执行 `startActivity(new Intent(ACTION_VIEW, Uri.parse(uri)).addCategory(CATEGORY_BROWSABLE))`，不是一个 orpheus 接收器。
- 三台模拟器 SELinux 均为 Enforcing。

## 两轮启动凭据

| API | 第一轮 kernel boot ID | 第二轮 kernel boot ID |
| --- | --- | --- |
| 30 | `6a3111c3-b7f7-4954-be2c-dac3c1ea0010` | `81d6c349-9921-44f2-ae95-78352956823b` |
| 31 | `7a254115-88e0-48e4-b6f4-c148183bfa5d` | `11836c99-8da7-478a-98ab-c14c144fb1a3` |
| 33 | `04f071f1-394d-41f7-a8ba-5fdfb9d9143a` | `5c72c181-b750-4ab5-8599-0abbc82cb948` |

仅重启应用或替换磁盘 APK 不算系统 Hook 重新生效。测试实际关机并重新启动不加载快照的模拟器，再核对 boot ID 和系统模块安装日志。未成功上线的启动尝试不计入通过记录。

完整 fingerprint、安装包哈希、候选查询、实际编辑框内容、system_server PID、Hook 日志保存在 `audit-artifacts/android11plus/final-results.json` 和各 API 目录。第一轮在 `round1/apiXX`，第二轮在 `apiXX`；每轮均保留录屏。

最终哈希复核曾发现 API 31 存在同版本名、不同哈希的调试构建（`e7120475...`）。它的旧结果已移到实验室 `retired-api31-build-variant`，不计入上表。重新安装固定 `9c083ae6...` 包，关机再启动，重新完成上表两轮；没有把同版本名当成同一份安装包。

## 本地证据和复测环境

项目忽略目录：`audit-artifacts/android11plus`，包含三版两轮的 `chooser.png`、`editor.png`、`scheme-flow.mp4`、UI XML、日志、结果 JSON、测试 APK 源码和复测脚本。该目录不提交 Git，以免把调试视频与设备日志放入源码仓库。

额外副本：`C:/Users/42/Desktop/Tool/Code/HookIntent-backups/scheme-android11plus-verified-20261113`。

原生实验室保留于 `D:/CodexLabs/HookIntent-android11plus-20261113`，AVD：`JumpReplay_API30x64` / `JumpReplay_API31` / `JumpReplay_API33`，端口分别为 5580 / 5582 / 5584。SDK 镜像和 root 补丁均在隔离目录，没有修改用户原 SDK 镜像。所有测试命令指定模拟器序列号，未因模拟器测试重新安装或重启真机。

## 边界

这次验证覆盖 AOSP 系列 Android 11、12、13 的实际系统 Hook 链路，加上先前 Android 10 OnePlus 真机结果。不包括 Android 12L / 14 及以后、各 OEM 定制 PackageManager、多用户 / 工作资料、已有默认处理程序和所有禁用规则组合；不能表述成所有 Android 11+ 厂商系统已验证。系统 Hook 后续若再修改，必须重启对应环境并重跑原生链路。
