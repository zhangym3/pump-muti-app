# Master 手机控制验证记录

验证日期：2026-10-16。工程：`C:\Users\Administrator\AndroidStudioProjects\pumpmutiapp`，包名 `com.cczb.app`。当前 APK 为手机限时测试版 1.1.4（versionCode 7），按钮统一为“启动测试 / 停止测试”，默认低档 3 秒快速测试，另提供三档通水测试；保留系统 PIN 配对和任务优先四页 UI。

## 验证结果

| 项目 | 结果 | 范围 |
| --- | --- | --- |
| Debug APK 构建 | 通过 | `:app:assembleDebug` |
| JVM 单元测试 | 43 项通过，0 失败、0 错误 | 当前 XML 报告汇总；含测试能力声明、BOOT_LOCK 测试例外、定量任务仍锁定、快速测试边界及旧存储兼容 |
| Android 模拟器界面测试 | 10 项通过，0 失败，16.115 秒 | MasterControlScreenTest 与 PumpTestScreenTest；含统一按钮、默认快速方式、切换通水方式、短时运行确认、快速方式限档和待确认时停止 |
| Android Lint | 0 错误、18 警告 | 依赖版本、旧模板颜色资源和 3 项 SharedPreferences KTX 建议 |
| GATT=133 处理 | 已实现并通过构建/Lint | 旧 GATT 关闭后等待 500 ms；错误卡片对同一固定主机直接重试；保留 GATT=133 专项提示 |
| 首次 PIN 配对链路 | 代码修复、6 项门控测试及构建/Lint 通过；待实机验证 | 等待系统配对完成后发现服务，受保护 CCCD 订阅成功后请求 MTU，最后开放业务命令 |
| 页面人工检查 | 此前四页界面通过；本次 PWM 以自动 UI 测试验证 | Android 11，320dp 宽度；历史首页截图不代表新三档页面 |

单元测试覆盖通知任意字节边界重组、超长行和断连清理、32 槽位掩码、身份/序号/任务/epoch 匹配、原请求恢复、未知结果保留未确认状态、逐节点阶段循环、序号不回绕，以及状态过期、未配网、输出未配置、BOOT_LOCK 和存储失败时的启动限制。

模拟器为现有 `VTF` AVD 的只读临时会话（`emulator-5580`），Android 11/API 30、480×960 px、density 240，即 320dp 宽；关闭时不保存快照。界面测试及截图均未连接蓝牙硬件，也未发送业务控制命令。实体手机保持未安装、未控制状态。

## 构建与测试命令

Gradle JDK：`C:\Program Files\Java\jdk-21.0.10`。Android SDK：`C:\Users\Administrator\AppData\Local\Android\Sdk`。

当前机器使用以下进程级参数绕过 JDK 本地套接字故障，`afunix-disabled` 路径保持不存在。无需更改系统环境。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
$env:JAVA_TOOL_OPTIONS = ($env:JAVA_TOOL_OPTIONS + ' -Djdk.net.unixdomain.tmpdir=C:\Users\Administrator\AndroidStudioProjects\pumpmutiapp\build\afunix-disabled').Trim()
```

在工程根目录执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --no-daemon --console=plain --max-workers=2
```

本次 1.1.4 构建、43 项单元测试和 Lint 结果为 `BUILD SUCCESSFUL in 27s`，Lint 为 0 错误、18 警告，记录在 `build/phone-test-validation.log`。修正旧界面测试对默认方式的断言后，测试 APK 再构建成功（21 秒，`build/phone-test-ui-build.log`）；主 APK 未改变。仅对 `emulator-5580` 安装并执行下列测试，未操作实体手机：

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5580 install -r app\build\outputs\apk\debug\app-debug.apk
& $adb -s emulator-5580 install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
& $adb -s emulator-5580 shell am instrument -w -r -e class com.cczb.app.MasterControlScreenTest,com.cczb.app.PumpTestScreenTest com.cczb.app.test/androidx.test.runner.AndroidJUnitRunner
```

本次界面测试结果：`OK (10 tests)`，16.115 秒，记录在 `build/phone-test-ui-validation.log`。汇总为 `build/phone-test-validation.json`。connectedTest HTML 是历史报告；本次以原始 instrumentation 日志为准。

## 手机启动 / 停止测试（1.1.4）

默认“快速测试”只提供低档、最长 3 秒和“已确认设备允许短时运行”；“通水测试”保留三档、3 / 5 / 10 秒及供水通路确认。按钮始终为“启动测试 / 停止测试”，不突出内部模式名称。切换方式清除确认，快速测试启动待确认或执行中禁止升档，停止不依赖状态新鲜度或记录存储正常。

配套 Master GPIO25 输出已配置为启用。APP 仅在当前启动 epoch、PWM 新鲜状态明确声明 `test_manual=1 dry_max_ms=3000` 后，允许从 BOOT_LOCK 启动独立测试；旧固件缺失能力时保留原限制。停止或到时由固件恢复原 BOOT_LOCK，定量任务仍不能启动。真实故障、任务互斥、停机间隔和持久化门控不变；测试无需 USB `pump-reset` 或 Mesh 配网。

内部 `pump-dry-test` 命令限定 1–3000ms，固件强制低档；原有停止/查询协议共用身份、序号和当前 epoch。手机保存方式标志并兼容旧四字段测试记录，恢复只查询，不自动启动。配套固件 24 组主机控制测试、双角色编译及 6 镜像 / 29 源码配置指纹核对通过。三秒软件时限不证明具体水泵允许在当前水路条件下运行。

## 历史：GPIO25 PWM 三档（1.1.3）

水泵测试页显示低 / 中 / 高及主机确认的占空比和频率；默认 30% / 60% / 100%，停止输出为 0%，重启恢复低档。20 kHz 是固件暂设值，尚未确认驱动规格。选档只发送一次 `pump-speed epoch expected_rev level`，匹配当前启动 epoch 和更高修订号才确认修改；设备拒绝或冲突同步实际档位。GATT 写入成功不算接受。

空闲选档不会启动，独立测试中切档不延长设备时限，定量任务或未知结果期间禁止改档。调速待确认时禁止新任务/测试，PWM 快照过期时禁止新测试；原测试仍可停止。超时保留待确认并提示刷新或重连，不自动重发。断连清除档位会话并重新查询，不恢复旧档位命令。

5 项 PWM JVM 测试覆盖字段与命令边界、修订号顺序、拒绝、确认与停止限制；2 项新增 UI 测试覆盖档位回调不触发启动及禁用态保留停止。配套固件 22 组主机测试通过，Master / Slave 均编译并打包成功；这些验证不证明物理 PWM 波形或实际泵速。

## 首次 PIN 配对链路（1.1.1）

固件为 BLE-only。已在本机 ESP-IDF 5.4.0 源码中定位日志：默认密钥分发掩码为 `0x0f`，包含 BR/EDR LinkKey；`smp_calculate_link_key_from_long_term_key` / `smp_derive_link_key_from_long_term_key` 正是由 LE Secure Connections 密钥派生 Classic LinkKey 的路径。Master 显式将发起方与响应方密钥掩码设为 BLE ENC + ID（`0x03`），保留 SC + MITM + Bond、DisplayOnly、静态六位 PIN 以及受保护的 RX/TX/CCCD 权限。配套 Master、Slave 固件均已编译通过，未烧录硬件。

APP 原先在 GATT 连接后立即发现服务并写入需要 MITM 的 CCCD，可能与 Master 发起的系统配对交错。现在在连接前注册当前会话的配对广播监听，连接后等待 `BOND_BONDED`；Master 优先发起加密，1.5 秒仍未进入配对时，APP 才通过公开 `createBond()` 兜底一次。配对完成后依次执行服务发现、受保护 CCCD 订阅和 MTU 请求；通知订阅成功前不开放控制。只读取系统配对广播用于状态和诊断，不注入 PIN、不拦截系统输入框。

六项新增 JVM 测试覆盖首次配对等待、已有绑定、主动配对兜底、配对取消/失败、绑定丢失与状态重置，以及连接回调前已进入配对。测试仅验证纯 Kotlin 门控逻辑，不能证明 Android 蓝牙栈或 ESP32 空口配对成功。GATT=133 是通用连接错误，本次不能据此声称所有 133 均已解决。

以设备本次启动串口显示的当前 PIN 为准；APP 仍由 Android 系统要求用户输入 PIN。只在旧密钥不一致时，针对这个 Master 取消手机端配对并按固件说明清理对应旧绑定；不要擦除整个 NVS 或所有绑定。手机系统蓝牙列表在部分机型上不显示 BLE 外设，应使用 APP 的 BLE 扫描/固定主机直连入口。

APP Logcat 标签为 `MasterBle`，记录连接、绑定、服务发现、CCCD、MTU 和就绪阶段；固件记录安全请求、密钥类型（不输出密钥内容）、认证结果、对端地址和断连原因。实体手机无线 ADB 在取证时中断，未完成新版实际配对；本次没有安装实体手机 APK、烧录固件或发送水泵/阀门控制命令。

## GATT=133 连接失败处理

`MasterBleManager` 在每次固定主机直连前先关闭上一条 `BluetoothGatt`，等待 500 ms 让 Android 蓝牙栈完成清理，再发起新的 LE 连接；旧连接的迟到回调通过连接 epoch 丢弃，避免污染新连接。连接失败卡片会对上一次选择的主机直接重试，不会退回扫描流程。首次连接仍由系统 PIN 配对完成，界面提示为“固定主机直连，首次连接需系统 PIN 配对”。

当系统返回 GATT 状态 133 时，界面显示：`蓝牙连接失败 GATT=133：请关闭其他蓝牙 APP，重新打开手机蓝牙后点击重试；仍失败请重启主机`。这套提示和重试流程已完成代码构建、Lint 及模拟器 UI 回归验证；实体手机上的蓝牙栈、PIN 配对和主机固件仍需现场验证。

## MTU 23 问题

只读检查已连接手机中 1.0 版 APP 的 BluetoothGatt 日志，三次连接均出现 `configureMTU ... mtu: 247`，随后 `onConfigureMTU(..., 23, 0)`。状态 0 表示协商成功，但实际协商值为 23；此时 APP 主动关闭连接。这说明当时的连接上限确实为 23，不能将其归为手机没有发出请求或所有回调都是错误状态。具体烧录版本与外设运行状态仍需固件更新后核实。

1.0.1 将流程调整为发现服务、PIN 配对/加密通知订阅、请求 MTU 185，最后开放控制命令。提交失败或失败回调最多重试一次；成功返回低 MTU 时不循环请求；超时重置连接。失败回调保留真实 GATT 状态码，不再把所有失败统一显示成“当前 23”。诊断保留请求、回调和就绪时的实际值。

配套 `pump-muti` 固件将本地 MTU 185 的设置提前到 Mesh 初始化后、主动启用配网和注册业务服务前，检查 API 返回结果，并在业务 GATT 的 MTU 事件中打印实际协商值。ESP-IDF 5.4.0 的 Mesh 初始化会将共享 ATT 上限设为 23，因此业务设置应放在它之后；旧固件已在较晚的网关初始化中调用设置 API，但未检查返回值。

此次只读取实体手机日志，没有安装新版 APK、烧录或控制硬件。新流程的真实配对、实际 MTU 185 和业务收发仍待更新后验证；22 项单元测试不覆盖 Android 蓝牙栈或 ESP32 空口。

## 报告与截图

以下路径均相对于工程根目录；`build` 下为本次构建产物，清理工程会删除这些文件。

- [首次构建与单元测试日志](../build/master-control-validation.log)
- [1.0 版构建、Lint 与模拟器测试日志](../build/master-ui-validation.log)
- [1.0.1 MTU 修复构建、单元测试与 Lint 日志](../build/mtu-fix-validation.log)
- [GATT=133 修复构建、单元测试与 Lint 日志](../build/gatt133-validation.log)
- [1.1.1 PIN 配对修复构建、28 项单元测试与 Lint 日志](../build/pin-pairing-validation.log)
- [1.1.3 PWM 构建、40 项 JVM 与 Lint 日志](../build/pwm-validation.log)
- [1.1.3 PWM 模拟器 8 项界面测试原始日志](../build/pwm-ui-validation.log)
- [1.1.4 手机测试构建、43 项 JVM 与 Lint 日志](../build/phone-test-validation.log)
- [1.1.4 模拟器 10 项界面测试原始日志](../build/phone-test-ui-validation.log)
- [1.1.4 最终验证汇总](../build/phone-test-validation.json)
- [单元测试报告](../app/build/reports/tests/testDebugUnitTest/index.html)
- [历史 connectedTest 界面报告，非本次 PWM 验证](../app/build/reports/androidTests/connected/debug/index.html)
- [Lint 报告](../app/build/reports/lint-results-debug.html)
- [控制首页截图：320dp 浅色](../build/screenshots/ui-control-320dp-light.png)

## APK

- 文件：`app/build/outputs/apk/debug/app-debug.apk`
- 类型：Debug 签名，最低 Android 11（API 30）
- 版本：1.1.4（versionCode 7）
- SHA-256：`4E23140A491F7D3F4E68D2A2D465C89BC59413E964E88CFB5E292F2663CE015E`

## 待实机验证

尚未验证实体手机 BLE 扫描、系统 PIN 配对、加密通知订阅、实际断连恢复，以及 Master/32 个从节点的水泵阀门联动、到量关阀和异常处理。自动化通过不代表这些硬件行为已通过验收。

本次没有在实体手机安装 APP，没有烧录固件、擦除 NVS 或运行实体水泵。原 `D:\esp32\pump` Android 工程与原单机固件均保留。首次独立测试需同时更新 APP 与 Master 固件、核对 GPIO25 PWM 驱动参数并完成系统 PIN 配对，不要求 USB 解锁或 Mesh 配网；定量任务仍需现场复位、Mesh 配网与节点登记。具体步骤见 [README](../README.md)。
