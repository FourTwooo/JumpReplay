# 桌面轮转样式可行性原型

此目录是独立应用 `com.fourtwo.hookintent.widgetlab`，**不属于 1.7.0 发布功能**，不依赖主应用，不读写用户的捕获记录、收藏、动作或快捷方式。默认不参与工程构建，须显式启用：

```powershell
./gradlew.bat -PwidgetLab :widget_lab:assembleDebug
```

## 两种实现及实际区别

- `RotaryCardsView`：应用内自绘的连续轮转，三个真实绘制的卡片按角度、深度逐帧变换，松手吸附。该效果已由用户在真机确认视觉方向满意。
- `LabWidget` / `NativeCardService`：标准 RemoteViews 桌面组件，只使用系统 StackView、ImageView、Bitmap、PendingIntent，不需要 Root、Xposed 或悬浮窗权限。每个条目将当前 / 相邻动作预览画成一帧，再由系统切换**整帧**。其外观接近原型，但不是逐张卡片跟手轮转；系统还会露出后方整帧的边沿。不能把这一方案描述成与应用内动画相同。

`widget_rotary.xml` 保留自绘控件加载的失败夹具，当前 provider 已改用 `widget_rotary_native.xml`，不会再添加已知无法加载的自绘测试组件。

原型只包含 Wi-Fi / 蓝牙 / 系统设置三个演示动作，点击打开对应设置页，不修改硬件开关。尚未接入真实动作、自定义昵称 / 图标、完整 Intent 载荷、动态参数、参数编辑及备份恢复；接入时必须复用主应用现有执行链路。

## 真机验证（2026-11-14）

设备：OnePlus 6，Android 10，ADB 序列号 `f9b4ccf9`。所有安装前均已备份原 APK / 完整私有数据并逐文件读取校验。主应用 APK 保持 `1.7.0-dev.3`，没有替换主应用或修改任何系统框架 Hook。

1. 自绘原型在应用内可上 / 下拖动、吸附居中，点击打开对应系统设置。
2. 自绘控件实际交给 OnePlus 桌面加载，被拒绝，显示“加载微件时出现问题”；日志为 `AppWidgetHostView: Error inflating RemoteViews ... Error inflating class ... RotaryCardsView`。失败组件已从桌面移除。
3. 标准组件实际放到桌面成功，1×1（203×341 屏幕像素）和 2×3（406×1023 屏幕像素）均检查了显示；也检查了 2×1 的中间尺寸。小尺寸仅显示图标。
4. 标准组件上下滑动：Wi-Fi → 蓝牙 → Wi-Fi；点击前景 Wi-Fi 卡片进入 `com.android.settings/.Settings$WifiSettingsActivity`。
5. 测试包没有 Xposed 元数据、Root 调用或特殊权限。真机本身有 Root，不能把本次结果描述成“已在无 Root 真机验收”；组件实现没有使用这些能力。

截图、UI 树、实际桌面录像、失败日志在 `audit-artifacts/rotary-widget-lab-20261114/`（本地交付，不进 Git）。录像 `native-desktop-prototype.mp4` 是桌面实录；`rotary-app-prototype.mp4` 是较早应用内录制，不可作为桌面效果证明。

## 尺寸约束

用户要求最小 1×1、最大 2×3，允许调整。应用内提供四档尺寸预览；标准组件声明最小可缩放尺寸及 API 31 的最大可缩放 dp 尺寸，并限制位图尺寸。

**尚不能宣称严格完成占格上限。** Android 11 及以下不支持 `maxResizeWidth` / `maxResizeHeight`；新系统的最大值也是 dp，而不是统一的格数。桌面的网格大小和外边距由宿主决定。限制绘制区域不能阻止旧桌面让用户分配更大的空白占位。因此 1×1 到 2×3 的严格全机型占格约束仍有平台限制。

## 后续决策

等待用户判断真实桌面上的系统整帧翻页是否可接受。接受后才接入真实动作并补齐边界；若要求与应用内连续轮转完全相同，当前标准 RemoteViews 路线不满足要求，不能以它冒充完成品。用户已明确排除依赖 Root / Xposed 的桌面注入方案。
