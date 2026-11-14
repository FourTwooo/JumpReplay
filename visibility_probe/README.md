# Android 13 包可见性探针

仅用于兼容性验证，默认不参与构建，也不打入正式 APK。模块不声明 queries、QUERY_ALL_PACKAGES、权限或 Xposed 元数据，使用自身普通应用 UID 读取主应用配置、绑定真实 MessengerService、注册回调并请求捕获状态；不创建捕获记录。

构建：

```powershell
./gradlew.bat -PvisibilityProbe :visibility_probe:assembleDebug :visibility_probe:assembleVisibilityNew
```

调试包 ID 为 `com.fourtwo.hookintent.visibilityprobe`，新安装对照变体为 `com.fourtwo.hookintent.visibilityprobe.new`。二者 Activity 类都为 `com.fourtwo.hookintent.visibilityprobe.ProbeActivity`。启动后结果显示在界面，同时保存在自身 `shared_prefs/probe.xml`。安装 / 更新前遵守项目备份约定；ADB 命令显式指定目标设备。

比较 `package_visible`、`provider_rows`、`bind_accepted`、`connected`、`registered` 和 `reply`。必须在未给探针增加主应用可见性声明的前提下验证；仅让主应用查询探针，或仅能显式启动 Activity，不能当作通信已通过。开机补授权检查应在打开主应用 Activity 前运行探针。
