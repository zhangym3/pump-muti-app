# APP 验证记录

## 当前测试范围（2026-10-27）

按用户最新说明“此次任务 后续不做真实测试”，后续不再执行真实设备测试或要求接入手机/开发板；代码审查、构建、静态检查和软件单元测试可以继续。下文构建与测试均按各自原有证据记录，未实测项目保留未验，不能以软件检查补为通过。恢复重配继续保持 `recovery_enabled=0`，完整流程尚未实现，G0 与最终验收未通过。

## 2026-10-27：G0 连接诊断与负向测试证据收紧

恢复 GATT 现在区分连接、服务发现、身份读取和业务读写阶段，并区分连接回调状态与 ATT 写回调状态。只有写回调明确返回 ATT `0x08`（Insufficient Authorization）才可作为错误证明/重放被拒绝的证据；超时、GATT 133/147、意外断线、其他 ATT 错误和本地解析失败均判定为证据不足。负向测试先验证设备身份与密码证明，再验证拒绝及合法凭证重新连接。

设备探测使用每次运行独立的 `probeRun`。验收脚本同时要求三项 instrumentation 按顺序实际开始/成功结束、runner 完整结束、本轮全部认证次数、最大帧/重复读取、三项明确拒绝及合法重连日志、独立前后快照和全程软件输出监测。旧轮次日志、缺失/重复轮次或测试 APK 过旧不能补足证据。

本轮 JVM 170 项通过（新增 4 项错误分类测试）；探测证据校验器 14 项通过。Debug APK、测试 APK 和 Lint 构建通过，Lint 0 错误、20 警告。构建日志为任务交付目录中的 `app-gatt-evidence-build-final.log`，证据校验日志为 `auth-probe-evidence-strict-tests.log`。

本轮仍未安装或运行设备测试：ADB 列表、mDNS 和串口列表均为空。日志仅记录阶段、回调类别、数字状态和耗时，不记录认证报文、凭证或 PIN。没有改变认证规则或启用恢复清网，`recovery_enabled=0`；此前 GATT=147 的根因及完整恢复验收仍未解决。

## 2026-10-27：恢复基础与 OOB 承诺阶段候选

这是开发阶段验证记录，完整重新组网、续办及待启用流程尚未实现，不能视为恢复功能已验收。恢复能力继续保持 `recovery_enabled=0`。

| 检查 | 本轮结果 |
| --- | --- |
| JVM 单元测试 | 166 项，0 失败、0 错误、0 跳过；根据本轮 XML 汇总，含 4 项一次性 OOB 承诺测试 |
| Debug APK / Android 测试 APK | `assembleDebug`、`assembleDebugAndroidTest` 成功 |
| Lint | 0 错误、20 警告 |
| 本轮实机安装 / instrumentation | 未执行；ADB 未上线，自动发现为空 |
| 主机 / 从机 | 最终 ESP32-S3 / ESP32-C3 构建成功；主机静态 RAM 61,724 B、Flash 1,084,924 B；从机静态 RAM 39,752 B、Flash 1,026,530 B |
| 共享恢复基础与真实 SDK host harness | 通过；模拟存储故障、真实 mbedTLS、SDK 强制 OOB 分支及 AppKey / 模型容量验证，均不代表空口和真实掉电通过 |

本轮执行命令：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug --no-daemon --console=plain
```

`C:\Windows\Temp` 用于当前终端的 JDK Unix-domain socket 临时路径；没有修改系统网络或产品认证逻辑。证据在任务交付目录 `outputs/mesh-recovery-implementation-20261027/`：`app-attempt-oob-build-uds.log`、`recovery-resource-final-host-tests.log`、`sdk-appkey-capacity-tests.log` 及两份 `*-final-build.log`。阶段候选的确切 SHA256、配置摘要及源码摘要以同目录候选包的 manifest 为准，旧章节哈希不适用于本次构建。

此前单台、30 轮发现测试观察到发现 30/30、读身份 29/30，其中一次 GATT 133。它是既有镜像的局部证据，未覆盖认证、强制 OOB 或恢复闭环。此前认证探测在 GATT 连接阶段失败，不能记作认证通过，也不能据此认定密码协议失败。凭证库的三项设备测试曾通过；新增主机信任库的 Android Provider / Keystore 测试仍待实机执行。

最终预检 `auth-probe-preflight-resource-final/result.json` 明确记录 `accepted=false`、`instrumentationStarted=false`，COM4、COM7 未枚举、ADB 未在线。本轮未刷写、未清网、未操作泵阀。仍缺独立主机管理员认证、运行时准入锁与协调器、逐绑定 AppKey、V7 业务隔离、秘密退休/重试及完整 APP 恢复流程；G0 和方案 T01–T38 均未作完整通过判定。

## 2026-10-26：开发阶段免 PIN 配对

配套 ESP32-S3 主机的 `CONFIG_PUMP_DEV_NO_PIN=y` 使用加密 Just Works 配对；APP 仍等待系统绑定成功、加密 CCCD 订阅和 MTU 协商，更新了连接提示，同时兼容启用六位 PIN 的主机。

- `testDebugUnitTest`、`assembleDebug`、`lintDebug` 均通过；137 项 JVM 单元测试，0 失败、0 错误、0 跳过，包括 6 项配对状态机测试。
- Lint 为 0 错误、0 Fatal、20 警告；未更改蓝牙配对状态机或业务控制逻辑。
- Debug APK SHA256：`13a8cc32c6c4c483c954b0e1dc0f3c120b86131d3214778a9416f1c35f1dfcb2`。
- 构建日志：`build/dev-no-pin-validation.log`；JVM 报告：`app/build/reports/tests/testDebugUnitTest/index.html`；Lint：`app/build/reports/lint-results-debug.html`。

本轮未安装 APK、未执行设备刷写或实机蓝牙配对。仍需验证免 PIN 首次配对、已绑定重连和取消配对；若旧绑定失效，仅忘记对应 Master 后重试，不清空 Mesh 网络或设备 NVS。

## APP 1.2.2：单台删除从机

2026-10-21，包名 `com.cczb.app`，versionCode 10。新增设备页「删除 → 确认删除」、旧固件能力门控、目标/请求精确匹配、离线结果提示、超时不重发以及任务地址快照保存。

| 检查 | 本轮结果 |
| --- | --- |
| Debug APK | BUILD SUCCESSFUL；1.2.2 / 10 |
| JVM 单元测试 | 106 项，0 失败、0 错误、0 跳过 |
| Lint | 0 错误、0 Fatal、22 警告 |
| Android UI 测试源码 | `compileDebugAndroidTestKotlin` 通过；新增删除确认和过期禁用测试，未执行 instrumentation |
| 主机 | ESP32-S3 N16R8 / GPIO4 构建通过，删除状态机及旧任务地址快照回归、原有核心测试和 Mesh 仿真通过 |
| 从机 | ESP32-C3 构建及 host tests 通过，增加 Node Reset 后关阀并重新待组网 |

回归覆盖离线及待配置节点、严格命令边界、旧固件无能力声明、epoch/operation/地址不匹配拒绝、成功终态不回退、删除/复用槽位后旧任务仍可完成对账、历史绑定重启恢复及损坏数据拒绝。

本轮未安装到手机、未刷写主从机、未执行泵阀操作，未验证真实蓝牙删除、掉电恢复及重加入网。不能据此判断此前新增从机失败已经解决。

证据：[构建日志](../build/deletion-build.log)、[JVM 报告](../app/build/reports/tests/testDebugUnitTest/index.html)、[Lint](../app/build/reports/lint-results-debug.html)。产物：[Debug APK](../app/build/outputs/apk/debug/app-debug.apk)，SHA256 `39ec6b74a5b5eaea578f2fb0976327b7aaa5b8b06cf80617a69d77a4bc39f001`。

## 历史：APP 1.2.1 交互改版验证

2026-10-21，包名 `com.cczb.app`，versionCode 9。

## 重复停止入口与异常结果显示修复

2026-10-21 下午：删除任务卡内重复的停止按钮，保留底部全局停止栏；区分有效计量的 FAULT、人工停止与达量完成，LEASE 文案调整为“租约失效，保护停止”。

| 检查 | 本次结果 |
| --- | --- |
| Debug APK 与测试 APK 构建 | BUILD SUCCESSFUL |
| JVM 单元测试 | 99 项，0 失败、0 错误、0 跳过 |
| 相关 Compose UI 测试 | emulator-5556；LiveFlowScreenTest 与 IndividualModeScreenTest，共 12 项通过，18.604 秒 |

新增回归覆盖 62 mL / 367 脉冲的 LEASE 异常不得显示“已完成”，以及人工停止不得显示“已完成”。同时验证达量结果替换实时数据、快照过期、逐阀目标与启动条件。重复停止入口另经源码检查，主程序只保留一处任务停止按钮。

证据：[UI 测试输出](../build/duplicate-stop-instrumentation.log)、[JVM 测试报告](../app/build/reports/tests/testDebugUnitTest/index.html)。本轮实体手机仅作只读诊断，未安装 APK、未操作泵阀或刷写主从固件；模拟器结果不代表本次 LEASE 通信故障已解决。

产物：[Debug APK](../app/build/outputs/apk/debug/app-debug.apk)，SHA256 `a6a21479c3fecd8b68c2473594e23945d0a0742030b3754ca0a0e53016b29ebf`。下列较早验证记录及产物哈希仅对应各自当时的构建。

## 实时流量单播链路补充验证

2026-10-21，主机侧集成测试覆盖从机 D6 单播到主节点、主节点经加密 GATT 通知转发给 APP；未使用 Mesh 群组或广播地址。

| 检查 | 本轮结果 |
| --- | --- |
| 主机控制器 host tests | 30 组通过 |
| 主从 Mesh host harness | 通过；1、2、5 节点均观察到任务运行期间快照，结束后清除实时状态，最终 RESULT 仍完成结算 |
| APP Debug/JVM 单测/Android 测试 APK | BUILD SUCCESSFUL；JVM 单测 97 项通过 |
| 实时流量 Compose instrumentation | VTF（emulator-5554），2 项通过，0 失败 |
| 差异格式检查 | 主机、从机、APP `git diff --check` 均通过；APP 有一条既有 LF/CRLF 提示 |

实时界面验证包括当前任务的累计出水量、脉冲数和流速、6 秒无新样本后的过期显示，以及最终 FLOW 到达后替换实时快照。主机 host harness 是纯软件模拟；VTF 仅验证 APP 渲染，不代表真实 Mesh 空口、实体手机 BLE 或泵阀/水路验收。

证据：[实时流量单测](../app/build/test-results/testDebugUnitTest/TEST-com.cczb.app.MasterLiveTest.xml)、[VTF instrumentation 结果](../app/build/outputs/androidTest-results/connected/debug/TEST-VTF%28AVD%29%20-%2011.xml)。

| 检查 | 本轮结果 |
| --- | --- |
| Debug APK 与测试 APK 构建 | BUILD SUCCESSFUL |
| JVM 单元测试 | 97 项，0 失败 / 0 错误 / 0 跳过 |
| 横屏模拟器 UI 测试 | OK (28 tests)，114.150 秒，1920×1080 / 240 dpi |
| 竖屏模拟器 UI 测试 | OK (28 tests)，86.182 秒，1080×1920 / 420 dpi |
| Lint | 0 错误 / 21 警告（依赖版本、资源、配置读取与代码建议） |
| 差异格式检查 | git diff --check 通过 |

两轮 UI 测试均仅使用 `emulator-5554`，使用模拟状态或断开蓝牙状态；未连接、刷写或启动现场泵阀。横竖屏是同一组 28 项测试。

交互验证覆盖：四个底部入口始终可用；返回先收起当前可见详情，不处理隐藏标签的面板；控制草稿和全局时限在切标签及 Activity 重建后恢复；关闭连接弹层回到原草稿；调校未保存输入在切标签及状态恢复后保留；历史详情在列表内展开总脉冲，仅显式点击才复制草稿，未结束任务禁止替换；一次确认显示逐路目标、总量、时限和速度，主机状态过期时禁止启动；停止能力在状态过期和组网期间保留。

竖屏人工查看了控制页、设备页、水泵展开区域和连接弹层。截图保存在 `build/navigation-preview/`。交互规则见 [UI_UX_DESIGN.md](UI_UX_DESIGN.md)。

构建使用 JDK 21，当前终端设置 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:/pump-muti/pump-muti-app/build/afunix-disabled`（该目录保持不存在），执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --console=plain
```

证据：[构建与单元测试](../build/navigation-validation.log)、[最终 lint](../build/navigation-final-lint.log)、[横屏 UI](../build/navigation-instrumentation.log)、[竖屏 UI](../build/navigation-portrait-instrumentation.log)、[单元测试报告](../app/build/reports/tests/testDebugUnitTest/index.html)、[Lint 报告](../app/build/reports/lint-results-debug.html)。

产物：[Debug APK](../app/build/outputs/apk/debug/app-debug.apk)，SHA256 `aaa622175d8321c48b9faf0b33df5233c2a0547bc8c60048cb216612f28b1464`。

本轮属于 APP 交互和状态恢复的软件验证；实体手机配对、无线断连恢复和实际出水尚未验收。本轮没有安装到实体手机，也未提交或推送代码。

## 历史：APP 1.2.0 验证

2026-10-16，包名 com.cczb.app，versionCode 8。

| 检查 | 本轮结果 |
| --- | --- |
| Debug APK 与测试 APK 构建 | BUILD SUCCESSFUL |
| JVM 单元测试 | 51项，0失败 / 0错误 / 0跳过 |
| 模拟器 UI 测试 | OK (14 tests)，11.863秒，emulator-5580 |
| Lint | 0错误 / 18警告（依赖版本及原有资源等） |

构建命令：

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-21.0.10'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:/Users/Administrator/AndroidStudioProjects/pumpmutiapp/build/afunix-disabled'
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --no-daemon --console=plain --max-workers=2
```

仅对 emulator-5580 安装主APK和测试APK，执行 am instrument -w com.cczb.app.test/androidx.test.runner.AndroidJUnitRunner，已检查输出明确为 OK (14 tests)。未操作实体手机。

新增验证覆盖：真实稀疏槽位1/9/32、离线节点禁选、缺清单不显示虚构0台、Master扫描/选定组网回调；只显示通水测试、无需确认即可启动、三档标签、状态过期和组网期间保留停止。清单单元测试覆盖完整提交、显式空清单、缺包、重复地址/槽位/边界、错误计数、旧epoch/request和slot0未配置设备。原任务、测试、调速、配对门控、持久记录测试保留。

产物：[Debug APK](../app/build/outputs/apk/debug/app-debug.apk)，SHA256 8d776432e16b140dc2de80fd6ad4d86fcfe621aca7c17e51ea21d65c8bd12cda。

证据：[最终构建日志](../build/mesh-final-validation.log)、[模拟器原始输出](../build/mesh-ui-validation.log)、[机器可读结果](../build/mesh-validation.json)、[单元测试报告](../app/build/reports/tests/testDebugUnitTest/index.html)、[Lint报告](../app/build/reports/lint-results-debug.html)。旧版结果见 [1.1.4历史记录](VALIDATION-1.1.4.md)，不代表本轮。

尚待实机验证：系统PIN首次配对/旧绑定恢复、BLE实际通知及断连、PB-ADV发现和配网、重启恢复配对表、配置超时重试、Master与从机同时通信以及GPIO25的50/75/100%物理波形。软件测试不代表无线和水路已经验收；本轮没有烧录、清空NVS或启动实体水泵。
