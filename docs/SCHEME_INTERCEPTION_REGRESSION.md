# Scheme 系统拦截回归修复：Android 10 实机验证

验证版本：`1.6.5-schemefix.1`。设备为已连接的 OnePlus 6，Android 10 / API 29；LSPosed 已勾选系统框架。本次安装后通过 ADB 重启手机，在真正的 system_server 中验证，没有用显式指定 JumpReplay Activity 的方式代替拦截。

## 实机根因

测试前设备安装的是 `1.6.5-extras.3`。LSPosed 启动日志显示模块已进入 system_server，随后 SettingsProvider 在同一进程加载，模块却执行了普通应用捕获初始化：

```
com.android.providers.settings: xposed挂载成功 attachBaseContext
JumpReplay capture storage/transport: ... No data directory found for package android
```

没有出现系统 PackageManager 查询 Hook 安装成功日志。该次启动共有 757 条捕获存储/传输错误。应用包回调参数不能代替所在进程的身份；系统 Application 的 attach 也可能早于模块系统回调。

## 修改

- 撤回上轮 `SystemQuerySpec`、`SchemeResolver` 和单一查询重载选择方案。
- 恢复原有 `queryIntentActivitiesInternal` Hook、手工构造 ResolveInfo 并添加 JumpReplay 候选的逻辑，保留禁用规则、原始匹配属性和原有 URI 过滤条件。
- 收到系统框架回调时记录系统进程身份并直接安装系统 Hook，使用回调提供的系统服务类加载器。
- 同一 system_server 后续加载 SettingsProvider 等包时直接返回，禁止进入普通应用捕获、存储、悬浮窗和 IPC 初始化。
- 配置缓存初始化延后到系统主消息循环；未准备好时保持系统原结果。
- 保留 Android 12 内部 ComputerEngine 的探测，但本次没有 Android 11+ 实机验证，不能据此宣称新版兼容已经完成。

本次没有添加任何 orpheus 专用分支或 Manifest scheme 声明，没有将 JumpReplay 设为默认处理程序。

## 使用用户给出的完整 URI 验证

```
orpheus://playlist/2312165875?evokeChain=os_other.client_qqbrowser.sessionId_0bf4a102-46c8-48f1-b070-1f7881c1a477.id_2312165875.page_mMobilePlaylist.start.ac_downloadBtn.type_scheme.actionId_downloadBtn-2
```

1. 修复前，以隐式 ACTION_VIEW 启动以上 URI，直接进入网易云 LoadingActivity，没有候选名单。
2. 备份设备当前 APK 和应用数据；保留应用数据安装修复包，并执行 ADB reboot。
3. 重启完成后，日志确认 PackageManagerService 查询 Hook 已安装，未再出现错误的 android 应用捕获初始化错误。
4. 系统查询返回网易云 RedirectActivity 和本应用 IntentIntercept 两个匹配项。
5. 再次以隐式 ACTION_VIEW 启动同一完整 URI，进入系统 ResolverActivity，真实显示 JumpReplay / 意图拦截器候选。
6. 点击该候选进入本应用编辑页；从实际输入框读取值，确认与原 URI 完全一致，共 204 个字符。
7. 实际向输入框插入测试文字，读取确认可编辑，再删除测试文字，确认恢复完整原 URI。手机最后停留在这个编辑页，没有点击发送意图。

截图、UI XML、安装前数据备份及系统日志保存于本地忽略目录 `audit-artifacts/scheme-regression`；其中 `chooser.png` 为真实系统候选名单，`verified-editor.png` 为选中后且恢复原 URI 的实际编辑页。`device-result.json` 保存验证结果。

构建成功；单元测试 174 项通过、2 项不适用跳过；Lint 0 错误、170 项警告。功能恢复的依据是上述手机实际重启与完整操作结果，不是这些静态检查。
