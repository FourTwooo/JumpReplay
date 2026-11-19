# JumpReplay

[![GitHub tag (latest by date)](https://img.shields.io/github/v/tag/FourTwooo/JumpReplay?label=version&logo=github)](https://github.com/FourTwooo/JumpReplay/releases)
[![加入QQ群](https://img.shields.io/badge/QQ群-1021904342-blue?logo=qq)](https://qm.qq.com/cgi-bin/qm/qr?k=6JWWosRVV0rtISqQKNVU5QY8KT0sBQP8&jump_from=webapi&authKey=kvD0trmJvJiWSeFVv1+WTUYBpalYGKh+dF3zgfpLDuByEmZF2wT8XXwC8QuT/tzQ)
[![License](https://img.shields.io/badge/license-GPL%203.0-blue)](https://raw.githubusercontent.com/FourTwooo/JumpReplay/refs/heads/master/LICENSE)
![GitHub Downloads (all assets, all releases)](https://img.shields.io/github/downloads/FourTwooo/JumpReplay/total?logo=github)

JumpReplay 用于查看、编辑和重放 Android 应用跳转。普通安装即可手动测试 Intent 或 Scheme，启用 Xposed 模块后还可以捕获其他应用的跳转。支持 Android 7.0 及以上。

## 主要功能

| 功能 | 用来做什么 |
| --- | --- |
| **捕获与收藏** | 记录目标应用的 Intent、Scheme、Fragment 或自定义 Hook。可按标签筛选、用正则搜索或设置字段过滤。详情可查看来源、调用方法、参数和堆栈，并收藏、复制 URI 或 `am` 命令。 |
| **捕获控制** | 主界面和快捷设置磁贴控制全局捕获。可选监视窗在 JumpReplay 退到后台且正在捕获时显示新增数量和最新来源。注入目标应用的悬浮窗只控制当前进程。 |
| **意图拦截器** | 手动输入或载入 Intent URI、Scheme，编辑 Extras（附加参数），查看匹配应用，再选一种可用的方式发送。部分自定义对象参数需要兼容的来源应用或进程。 |
| **Fragment 与重放** | 支持的记录可以改参后在原应用进程重放，也可生成链接来冷启动目标应用。详情会显示无法重放的原因。「重放」页可改参执行或复制链接，支持自定义链接标识和导入导出模板。 |
| **应用活动** | 按应用查看系统声明的 Activity，以及按版本保存的 Intent 参数和样例。支持搜索、筛选全部／系统／用户应用、可选自动缓存和批量清理。条目可载入意图拦截器逐条测试。 |
| **快捷动作与桌面入口** | 手动录入 Intent URI／Scheme，或从捕获详情保存。可设置名称、图标和动态参数，导入导出动作备份，创建桌面快捷方式，或添加上下滑动选择动作的桌面叠卡。 |
| **禁用与打包拦截器** | 「禁用」通过系统框架作用域按 Scheme 或 URI 正则拦截跳转。「打包拦截器」则可从已声明或自定义的 Scheme 生成独立 APK，在系统把链接交给它时决定是否拦截或转发。 |
| **配置、设置与关于** | 「配置」可启停各类捕获方法和标签，添加自定义 Hook 或导入云端 Hook 配置。「设置」可检查权限状态、调整悬浮窗和语言。「关于」可离线查看，历史更新和反馈入口在侧栏。 |

## 不同权限能用什么

表格按单独启用的权限列出。Shizuku、Root 和 Xposed 可以叠加使用。

| 功能 | 普通用户 | Shizuku 用户 | Root 用户 | Xposed／LSPosed 用户 |
| --- | :---: | :---: | :---: | :---: |
| 手动编辑和发送 Intent／Scheme、查看匹配应用 | ✓ | ✓ | ✓ | ✓ |
| 浏览应用活动和已有样例，载入拦截器测试 | ✓ | ✓ | ✓ | ✓ |
| 管理收藏、快捷动作、桌面快捷方式与叠卡 | ✓ | ✓ | ✓ | ✓ |
| 生成并安装独立的打包拦截器 | ✓ | ✓ | ✓ | ✓ |
| 通过 Shizuku 或 Shizuku 系统助手发送 | — | ✓ | — | — |
| 以 Root 身份发送，在应用内执行 `am` 命令 | — | — | ✓ | — |
| 实时捕获、自动收录新 Intent 样例、自定义 Hook | — | — | — | ✓ 目标应用作用域 |
| Fragment 改参后在原应用进程重放 | — | — | — | ✓ 目标应用作用域 |
| 将支持的 Scheme 加入系统拦截候选、应用「禁用」规则 | — | — | — | ✓ 系统框架作用域 |

- **普通发送**受 Android 和目标组件权限限制，桌面入口取决于启动器支持。Shizuku 需运行并授权，系统助手模式取决于设备。Root 模式也需要授权 JumpReplay。
- **应用活动**无需模块就能看到系统声明的入口。参数样例来自实际捕获。开启自动记录后，如果同版本样例能确认唯一入口，还可以从 Fragment 生成目标应用的原生 Intent。
- **分享 Fragment 重放**时请同时导出模板。接收方安装同版本、同签名的目标应用并启用对应作用域。若模板显示「等待匹配本机捕获」，打开对应页面捕获一次即可尝试匹配。

## 快速开始

1. **手动测试跳转** → 打开「应用活动」，找到入口后点「转到意图拦截器」，检查参数再发送。
2. **捕获应用内跳转** → 启用 JumpReplay 模块，把目标应用加入作用域并重新启动它。在「配置」打开需要的捕获项，再用「捕获」页或快捷设置磁贴启动。使用捕获监视窗时，另需给 JumpReplay 悬浮窗权限。
3. **接入系统链接拦截** → 在支持的 Xposed 环境中把「系统框架」加入作用域，按框架提示重启设备。

<details>
<summary>应用截图与演示视频</summary>

<img src="https://github.com/user-attachments/assets/9af1fcd1-542f-404f-bb73-536897863f96" alt="配置" width="200"/>
<img src="https://github.com/user-attachments/assets/1204cc25-facc-42a8-9e6d-0b550898996a" alt="捕获列表" width="200"/>
<img src="https://github.com/user-attachments/assets/b73e8411-abb1-4a06-9dcd-938b148f502e" alt="捕获详情" width="200"/>
<img src="https://github.com/user-attachments/assets/7d1a4ede-337d-4c44-add8-0d739964bc33" alt="意图拦截器" width="200"/>

**抓包(作用域勾选对应应用)**

https://github.com/user-attachments/assets/b7c47a00-f47d-43e9-848e-a00eda47c6db

**拦截(作用域勾选系统框架)**

https://github.com/user-attachments/assets/634184d4-1bf4-4a4f-9502-01e064bdee9b

</details>

## 反馈与许可

问题和建议可提交到 [GitHub Issues](https://github.com/FourTwooo/JumpReplay/issues)，也可以加入 QQ 群 `1021904342`。修改和再分发请遵守 [GPL-3.0 许可证](LICENSE)。

<details>
<summary>法律声明与使用协议</summary>

### 项目性质声明

1. 本工具为技术研究性质的开源项目，仅用于：
   - 安卓系统合法授权范围内的通信机制分析
   - 应用开发调试过程中的合法数据监测
   - 网络安全领域的授权渗透测试
   - 计算机技术教学研究场景

### 禁止性条款

2. 使用者承诺不会用于以下场景：
   - 未经授权的数据抓取（《网络安全法》第27条）
   - 实施网络入侵、破坏系统安全（《刑法》第285、286条）
   - 制作/传播恶意程序（《刑法》第285条第三款）
   - 任何形式的黑灰产、电信诈骗等违法活动
   - 侵犯他人隐私或商业秘密（《民法典》第1032条）

### 责任豁免

3. 开发者声明：
   - 本工具不包含任何诱导/教唆违法使用的设计
   - 不对使用者行为承担连带责任（《民法典》第1195条）
   - 已尽合理注意义务提供技术警示
   - 保留对滥用者的法律追诉权利

### 使用约束

4. 使用者须知：
   - 必须遵守《数据安全法》《个人信息保护法》等法律法规
   - 须获得被监测主体的明确书面授权
   - 禁止将工具用于生产环境监控
   - 需自行承担所有法律后果

### 附加保护措施

5. 开发者已采取的风险防控措施：
   - 项目采用[GPL3]开源协议，禁止商业滥用

</details>
