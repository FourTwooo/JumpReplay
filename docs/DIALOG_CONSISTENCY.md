# 弹窗统一与按钮裁切修复

本轮基于 `175d609`，仅整理应用自身弹窗。原主页面、灰色工具栏、抽屉导航和快捷方式独立进程保持不变；未处理第三方自定义参数重放。

## 改动

- 所有自有 AlertDialog 通过 `AppDialogs.builder()` 使用同一个弹窗主题，统一外框、文字、确认/取消按钮、输入框及日夜配色。
- 快捷方式参数、通用输入、禁用 Scheme、拦截器表单中原先固定为 38dp 的自绘按钮改用标准按钮。自定义 XML 按钮使用 `wrap_content` 和最小 48dp 高度，避免裁切文字及边框；不再强制平分按钮宽度。
- 快捷方式等待连接或授权时，提示显示在表单内，确认按钮保持短标题。手动接管确认点击，保留校验失败不关闭、异步等待不关闭、取消后不再启动的行为。
- 多参数和长表单使用受限高度的 `DialogScrollView`；应用、连接进程、云配置与图标列表保留独立的可用列表空间。
- 统一快捷方式创建、Hook 编辑、禁用规则、拦截器设置、云配置、应用/图标选择、命令复制、语言选择、法律/更新提示、电池优化提示和加载窗口的样式。
- 第三方颜色盘继续使用原控件和颜色回调，通过公开 API 统一其外框、按钮及字体尺寸。

共用组件和资源位于 `utils/AppDialogs.java`、`utils/DialogScrollView.java`、`values/dialog_styles.xml`、`values/dialog_colors.xml` 与 `values-night/dialog_colors.xml`。后续新增弹窗应复用这些组件，避免再复制单独的按钮和窗口造型。

## 验证

- 完整 Debug/Release 构建通过；105 项单元测试通过（主模块 101、拦截器模板 4）；两个模块 Lint 无 Error/Fatal，保留原有等 Warning（主模块 158、模板 30）；instrumentation APK 编译通过。
- 新增 10 项实际控件测试：1/1.5/2 倍字体按钮布局，多行等待提示，输入校验不关闭与原有 trim，快捷方式取消及新 Intent 替换，10 个参数滚动，六种复杂 XML 运行时加载，以及第三方颜色盘取消回调和大字体布局。
- 已覆盖安装 `1.6.1-dialogs.1`，保留原应用 UID 和数据；真机确认透明参数框显示在桌面上，确认/取消按钮完整显示。
- 真机检查集中于参数框；复杂表单和颜色选择器以 Robolectric 运行时测试验证，未宣称所有弹窗流程均已在真机逐项执行。

## 回退

- 修改前提交：`175d609855a7f55e13f11870177b7c680a791aef`。
- 回退分支：`codex/rollback-before-dialogs-20261111-120237`。
- 本轮分支：`codex/dialog-consistency-20261111-120237`。
- 备份目录：`C:/Users/42/Desktop/Tool/Code/HookIntent-backups/dialogs-20261111-120237`，保存修改前源码 bundle、手机原 APK 和应用数据备份。

```powershell
git switch codex/rollback-before-dialogs-20261111-120237
# 返回本轮版本
git switch codex/dialog-consistency-20261111-120237
```

切换前先保存自己的后续修改。修改前 Debug 安装包仍在 `audit-artifacts/JumpReplay-1.6.1-shortcut.1-debug.apk`；新包为 `audit-artifacts/JumpReplay-1.6.1-dialogs.1-debug.apk`。两者使用相同 Debug 签名，可覆盖安装回退，无需清空应用数据。Release 交付包未签名，需要原发布密钥签名。
