# APP 1.2.0 验证

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
