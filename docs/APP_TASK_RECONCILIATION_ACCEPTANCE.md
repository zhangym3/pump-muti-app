# APP 任务结果对账锁修复验收记录

日期：2026-10-22。

当前结论：本方案验收测试全部通过。APP 已更新安装至 Samsung SM_F9660；124 项 JVM 单元测试、3 项真机页面测试、1 项真实 BLE 连接与重连测试通过，构建通过。现场确认完整历史恢复后可进入新任务确认，主机水泵输出关闭；只读验收前后手机控制账本与任务历史逐字节一致。未发送启动或试泵命令，未进行实际出水验收。

## 已实施行为

- 重连使用主机 BLE 地址以及 client、seq、epoch、job、mask、水量、时限和逐路参数匹配历史，恢复 R、V、FLOW 与当次槽位地址映射。
- 保留账本的任务状态。仅 FINISHED 且 R/V/FLOW/映射完整时解除对账锁；PENDING、ACCEPTED、缺项及未知结果继续拦截。
- 完整结果仍需通过身份、主机当前状态、存储、从机清单等既有启动门槛。
- UNKNOWN 或其他非 OK 查询响应保留本机已有证据，不把不完整任务判为已结束。
- 已验证的停止回执按原任务 seq 归一化后再更新 UI 和保存历史，避免停止请求使用新序号导致再次锁定。
- 恢复的 FLOW 到达时间置零并清空实时进度，旧记录不会变成实时流量。
- 当前从机清单不再为旧任务推断地址映射，避免同一槽位更换设备后误用结果。
- 历史 Properties 文件仍为 version=1，未改变格式、清空记录或更换客户端身份。

核心文件：`MasterControlViewModel.kt`、`MasterHistory.kt`、`MasterTaskReconciliation.kt`、`PumpScreens.kt`。

## 自动化验收

| 检查 | 结果与证据 |
| --- | --- |
| 全部 JVM 单元测试 | 124 项通过，失败 0、错误 0、跳过 0 |
| 原现场任务重连恢复回归 | job=15:6，client=d82ef12190ff6359，seq=1，目标500 mL；恢复502 mL、2957脉冲、TARGET及槽位1→地址3；允许进入新任务确认 |
| 跨主机/任务身份 | 主机、client、seq、epoch、job、mask、水量、时限、逐路参数不匹配均拒绝复用 |
| 分别缺 R/V/FLOW/映射 | 保持启动拦截，并显示对应缺项 |
| 非终态/unknown/未计量 | 保持拦截；额外 FLOW、错地址、错 job、无效或重复映射也不解锁 |
| UNKNOWN 查询 | 完整历史维持完整；缺 FLOW 和无历史维持拦截；不篡改账本状态 |
| PENDING/ACCEPTED | 不因恢复历史或 UNKNOWN 响应变为已完成 |
| 停止回执 | 经 stopSequence、client、job、mask 验证后归一化原 seq；错任务拒绝；保存后可兼容读取 |
| 多槽位 | 所选槽位均需独立冻结地址及终态计量；缺任意一路仍拦截，支持槽位32 |
| 历史兼容 | 原 Properties 读写、缺旧字段、损坏/重复字段拒绝以及账本往返测试通过 |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` 已生成 |
| Android 测试 | 源码编译及测试 APK 构建通过；SM_F9660 上页面3项、真实BLE连接与重连1项通过，均无失败或跳过 |
| 真机完整历史 | 当前 job=24:22，恢复2 mL、12脉冲，`taskResultsComplete=true`；重连后仍完整且 `canStart=true` |
| 主机输出 | 真实BLE重连后确认 `pump_command=0`、`testActive=false`、`phase=0`、`epoch=24` |
| 手机数据保留 | 只读验收前后两文件 SHA-256 相同；最初备份中的3条完整历史均保留，包含原502 mL、2957脉冲记录 |
| 差异检查 | `git diff --check` 通过；原有 LF/CRLF 提示不影响检查结果 |

主要测试：`MasterTaskReconciliationTest`、`MasterUiStateTest`、`MasterHistoryTest`，并运行现有完整 JVM 测试集。

本机构建使用 Gradle 9.6.0 / Adoptium JDK 25。此前失败发生在 JDK 的 AF_UNIX 本机管道连接阶段；本次构建进程设置 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:\pump-muti` 后成功。未改全局 Java、网络或仓库 Gradle 配置。

验证命令：

```powershell
$env:JAVA_HOME='C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=D:\pump-muti'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --console=plain
```

完整构建日志：`D:\pump-muti\artifacts\reconciliation-20261022\gradle-verification.log`。JUnit XML 位于 `app/build/test-results/testDebugUnitTest/`，HTML 报告位于 `app/build/reports/tests/testDebugUnitTest/index.html`。

## 手机现场情况与只读验收

初次只读检查可连接 Samsung SM_F9660，APP 版本1.2.2，蓝牙权限已授予。初次备份时控制账本任务为 seq=3 / epoch=22 / job=22:9，历史有3条。恢复无线调试并更新安装后，读取的控制账本任务已为 seq=16 / epoch=24 / job=24:22，历史有4条，最新记录时间为15:25:31，早于本次续跑的安装和只读测试。最新记录为2 mL、12脉冲、NO_FLOW终态。原 job=15:6 的502 mL、2957脉冲以及另外两条完整记录的全部字段均保留。本次只验证完整对账，不把 NO_FLOW 记录描述成成功出水。

安装前已只读备份两文件至 `D:\pump-muti\artifacts\reconciliation-20261022\`，未覆盖手机文件：

| 文件 | 备份 SHA-256 |
| --- | --- |
| master-control.properties | 5a0c1b0bb0a54c82043d56c626f691e89e5a9ebe42a8feaf6918e31c42cb6b92 |
| master-history.properties | 058f01ac4e4082a562b5b99f0f316f83da7f5c4fbadb44af98df8fed793230f0 |

此前 `adb install -r` 期间无线调试中断，未取得安装确认。用户恢复手机连接并解锁后，APP 与测试包的 `install -r` 均返回 `Success`，未卸载或清除应用数据。本次继续完成以下验收：

1. `TaskReconciliationScreenTest`：3项通过。完整历史可点击“检查并启动”并触发空确认回调，详情保留502 mL、2957脉冲、TARGET和历史快照；缺FLOW与错job数据继续禁用。使用内存fixture，不访问蓝牙或用户文件。
2. 首轮页面测试有1项断言定位歧义：页面的阀门卡片和详细结果均包含“2957 脉冲”。将断言改为两处完整文本的精确匹配后，3项全部通过；未为此改变产品逻辑。原失败输出保留在 `screen-instrumentation-initial-failure.log`。
3. `TaskReconciliationDeviceTest`：1项通过。显式指定主机地址 `DC:DA:0C:6B:BB:C6`，恢复手机现有任务，再完成真实BLE连接、断开、重连。两次同步均确认任务未变、结果完整、`canStart=true`、`pumpCommand=false`、`testActive=false`。不调用start/retry/试泵接口。
4. 15:31:22 的验收日志记录 `reconnected job=24:22 complete=true canStart=true pump_command=0 phase=0 epoch=24`。测试结束清理连接；后续页面测试仅使用内存fixture，不控制设备。
5. 只读验收前后再次备份两文件，逐字节一致；最初3条历史与当前4条历史按记录全部字段比较，确认原记录全部保留。

只读验收前后文件哈希（文件名前缀分别为 `before-readonly-`、`after-readonly-`）：

| 文件 | 前后相同的 SHA-256 |
| --- | --- |
| master-control.properties | 733677b8e94297bf02f1469e0c04e42b98004767de78447035e441c237770111 |
| master-history.properties | 233185180fb46939ac8512dcd055a10ef2f4fbf46503a758b11777e295361ce2 |

续跑日志均在 `D:\pump-muti\artifacts\reconciliation-20261022\`：`screen-instrumentation.log`（3项通过）、`device-instrumentation.log`（1项通过）、`device-reconciliation-state.log`（现场状态）、`android-test-rebuild.log`（精确断言修改后的构建通过）。

已执行命令（实际执行均使用 `-s adb-RFCY715LVMD-2Lr7C0._adb-tls-connect._tcp` 指定手机）：

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.cczb.app.TaskReconciliationScreenTest com.cczb.app.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class com.cczb.app.TaskReconciliationDeviceTest -e masterAddress DC:DA:0C:6B:BB:C6 com.cczb.app.test/androidx.test.runner.AndroidJUnitRunner
```

本次未发送启动、原请求重试、试泵、固件刷写或设备重启命令，未清空手机历史和控制账本。

## 交付产物

- 修复版APK：`D:\pump-muti\artifacts\reconciliation-20261022\pump-app-reconciliation-debug.apk`，SHA-256：`a57ed99ba0c09eaa77dd9fd3f032ed898a028dd8bb0dccdd64988cccf87b64c0`。
- Android测试APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`，SHA-256：`d5c43638dcbcea83d78e46f6be9c0eed54ecb48f79e916573bf7dfeab62908b9`。
- 对账修复仅涉及APP恢复逻辑及相关测试、文档，保留三个工程已有修改。构建产物与手机备份保存在仓库外。
