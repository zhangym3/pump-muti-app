# 泵阀控制 Android APP

本工程是 `pump-muti` 多节点固件的独立 Android 客户端，包名 / applicationId 保持 `com.cczb.app`。打开后进入四个固定标签的 Compose 控制界面，日常出水在控制页完成。原 `D:\esp32\pump` Android 工程及原单机固件工程保持原样。

## 当前功能

- 扫描并直连 `PUMP_MASTER_XXXX`，支持 Android 系统六位 PIN 配对和加密通知订阅。
- 从 Master 真实清单选择已配对且在线的阀门，设置每路 50–2000 mL（步进 50 mL）、单路最长 1–240 秒，一次确认框列出逐路与总目标；默认由主机逐路执行。
- 启动、查询原任务、停止整个任务；显示已接受状态、阶段、异常 / 未知槽位，以及累计水量的数据覆盖范围。
- 1.2.1 固定控制、设备、记录、设置四个底部入口；控制页原位选阀门和显示任务结果，记录详情与水泵测试均在原页展开；切标签保留草稿、表单和滚动位置，系统返回只处理当前可见内容。记录页保存最近 100 项手机本地任务观察。
- 设备页直接展开通水测试，直接选择50% / 75% / 100%和3 / 5 / 10秒，点击启动测试，无需准备确认勾选。停止测试及主机到时自动停止保留。
- GPIO25 单线接驱动独立 PWM 输入，三档默认 50% / 75% / 100%，停止为 0%。通水测试选档等待主机确认，空闲选档不启泵，测试中切档不延长时限。定量任务中禁止改档。频率暂设 20 kHz，须核对驱动规格。
- 设置页支持每个从节点低/中/高档霍尔计量校准，可手动编辑脉冲/mL，或用总脉冲数和量杯实测 mL 计算，主机确认保存后下次任务生效。使用步骤与协议见 [三档流量校准](docs/FLOW_CALIBRATION.md)，需同步更新主从机固件。
- 配套新版 Master 已启用 GPIO25 水泵输出；独立测试不要求 Mesh 配网或 USB `pump-reset`。APP 必须先收到主机本次启动的测试能力与新鲜状态，完成系统 PIN 配对、停机间隔并确认无其他任务或真实故障。测试从 BOOT_LOCK 开始时，结束后恢复该锁，定量任务仍需现场复位。
- 启动前原子保存客户端身份、递增序号和完整请求。Activity 重建或进程重启后，重新连接相同 Master 时查询原任务，不自动再次启动。
- 请求未确认时只允许查询或经用户确认重试完全相同的请求；不以新序号绕过去重。断连不取消设备已接受任务。
- 调度状态超过约 15 秒未更新时禁止新任务；仍可对已知任务提交停止。停止命令优先于待发查询，停止结果仍需主机确认。

设备清单来自 Master 完整同步，槽位是 Master 保存的稳定登记号；实际注册、在线与系数匹配仍由 Master 核验。当前只直连 Master，不提供从机转发或其他手机任务自动发现。FLOW 的地址不等同于槽位，页面明确使用地址标注。历史记录是本手机保存的最近 100 项观察，不保证补回换手机前的数据。

当前交互路径和返回规则见 [docs/UI_UX_DESIGN.md](docs/UI_UX_DESIGN.md)。

## 首次使用

1. Master 烧录 `pump-muti` 的 `pump_master` 固件。未配网 Master 作为 Provisioner，在本 APP 设备页扫描从机、选择连接组网，自动完成密钥绑定、群组订阅及真实清单同步；独立试泵不要求 Mesh 配网。
2. 确认 GPIO25 驱动接线与参数。配套新版 Master 的水泵档案为 `commissioned=true`，Slave 阀门仍为 `false`；APP 不修改硬件档案。仅定量出水还需通过 USB 登记从节点、设定霍尔系数并启用对应阀门。
3. Master 必须配置六位 PIN，以设备本次启动的串口 `current PIN=......` 为准。未设置时经 USB 使用 `pin 六位数字` 后重启。独立测试不需要 USB 解锁；定量出水仍须核实现场状态后执行 `pump-reset`。
4. 安装 debug APK，允许附近设备权限，在 APP 内扫描并连接 `PUMP_MASTER_XXXX`。Master 是 BLE GATT 设备，部分手机系统蓝牙列表可能不显示；首次连接在 Android 系统弹窗中输入 PIN，未弹窗时查看通知栏。Android 11 还需要位置权限和系统位置服务。
5. 主机身份、Mesh、硬件输出配置和故障状态均就绪后，选现场已登记槽位并创建任务。例如选 1、2 路，每路 500 mL，总目标为 1,000 mL。

只测试主机水泵：同时更新配套 Master 固件和 APP 1.2.1，进入设备 → 展开测试，选择低档50%、中档75%或高档100%，等待档位确认，选择3/5/10秒，点击“启动测试”。页面只保留“通水测试”，不再要求勾选准备或允许短时运行。可以随时停止；测试不自动开阀，时限由 MCU 执行。独立试泵无需 USB pump-reset。

手机不承担毫秒级关阀闭环。每个从机本地计量并到量关阀，主机控制公共水泵与调度。暂不加入缺水保护。页面中的配置状态不代表已测得水泵运行或阀门机械位置。

已有任务换手机无法自动发现；本手机保存的身份也不应复制到其他手机。若主机重启、历史被移除或返回 UNKNOWN，APP 保留未确认状态，需查询并现场核实，不自动补水。

## Android Studio 与构建

使用 Android Studio 打开工程根目录（当前为 `D:\pump-muti\pump-muti-app`），选择 `app` 配置运行。最低 Android 11（API 30），编译 / 目标 SDK 为 37，Gradle JDK 使用 21。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain --max-workers=2
```

本机 JDK 如出现 `Unable to establish loopback connection`，可以仅在当前构建终端设置以下参数再构建；参数中的目录必须保持不存在，不创建它，也不需要更改系统环境：

```powershell
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:\pump-muti\pump-muti-app\build\afunix-disabled'
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。自动化验证结果见 `docs/VALIDATION.md`。

## 协议与代码

| 通道 | UUID |
|---|---|
| Service | `a64d0001-2f9c-4b6e-8db7-a570530e1000` |
| RX 普通响应写入 | `a64d0002-2f9c-4b6e-8db7-a570530e1000` |
| TX 通知 | `a64d0003-2f9c-4b6e-8db7-a570530e1000` |

APP 先等待系统 PIN 配对达到 `BOND_BONDED`，再发现服务、验证加密通知订阅，最后请求 MTU 185，最低接受 163；MTU 回调确认达标后才开放命令通道。协商请求提交或回调失败时最多重试一次；超时重置连接，不并行提交新的 GATT 操作。成功协商但低于 163 时明确提示检查 Master 固件。一条命令最多 159 字节，不发送长写或分片命令。TX 按字节缓冲至换行后解析，单行最多 191 字节，超长行丢弃至下一换行。普通写入串行，写入超时 5 秒后重置连接，不自动重试启动。重置会清除残留通知与旧会话回调。约每 2 秒查询未结束任务，每 10–12 秒刷新 hello。成功写入 GATT 不等于业务接受，必须匹配响应身份、序号、任务号、epoch 及所选节点。

### 首次 PIN 配对与重连（1.1.1）

Master 在 LE 连接后主动请求 MITM 加密；APP 等待 `NONE → BONDING → BONDED`，期间不发现服务、不写 CCCD。1.5 秒后仍为 NONE 时，APP 仅补发一次公开 `createBond()` 请求。PIN 由 Android 系统窗口输入，不在 APP 内自动填充。已有绑定通常复用密钥，仍需加密 CCCD 成功才能开放控制。取消或失败会关闭本次连接，断开时注销配对广播监听、丢弃旧会话回调。

以设备本次启动的 `current PIN=......` 为准；干净 NVS 默认不设 PIN。修复固件显式将双向密钥分发设为 `ENC|ID (0x03)`，避开 BLE-only 模式下的经典蓝牙 LinkKey 派生。`smp_derive_link_key_from_long_term_key failed` 与此路径相关；`GATT=133` 本身不能证明唯一原因。若更新后仍有旧密钥问题，仅在手机系统中忘记 `PUMP_MASTER_XXXX` 后重连，不清空设备 NVS。

APP 诊断与 Logcat `MasterBle` 标签记录连接、配对状态、服务发现、加密 CCCD、MTU。现场验收应覆盖首次正确 PIN、错误/取消 PIN、已绑定重连、连接中取消及更换主机；软件单元测试不能替代实机配对验证。

连接诊断会记录加密订阅、MTU 请求、实际回调值与 GATT 状态码。默认 MTU 23 只允许约 20 字节普通写入，无法容纳完整控制命令。遇到 MTU 问题时先断开 nRF Mesh、nRF Connect 等其他 APP 与该设备的连接，再连接本 APP；如成功回调仍返回 23，需更新 Master 固件。修复后的固件在 Mesh 初始化后设置本地 MTU 185，并在串口打印实际协商结果；修改 APP 的最低阈值不能提升实际 MTU。

```text
hello
start CLIENT_HEX SEQ EPOCH MASK_HEX ML MAX_MS
query CLIENT_HEX START_SEQ
stop CLIENT_HEX STOP_SEQ EPOCH JOB_EPOCH JOB_ID
pump-test CLIENT_HEX SEQ EPOCH DURATION_MS
pump-dry-test CLIENT_HEX SEQ EPOCH DURATION_MS
pump-test-stop CLIENT_HEX START_SEQ EPOCH
pump-test-query CLIENT_HEX START_SEQ EPOCH
pump-speed EPOCH EXPECTED_REV LEVEL
```

`local` 返回 `PUMP_PWM epoch=N rev=N level=N duty=N hz=N output=N reply=NAME test_manual=1 dry_max_ms=3000 dry_active=N`；`LEVEL=1/2/3`。尾部字段用于确认本次启动的限时测试能力，旧固件缺失时不允许绕过 BOOT_LOCK。调速使用当前启动 epoch 与全局修订号，成功才递增修订号。界面选中档位以设备确认值为准；待确认时不允许新测试或定量任务，PWM 状态过期时不允许新测试，已有测试停止入口仍可用。超时不自动重发，断连清除待确认档位并重新查询；主机重启恢复低档和关闭输出。

命令仅允许上述业务命令，不包含旧单机协议或 USB 维护命令。通水测试为 1–10000ms，APP 提供 3/5/10 秒；快速测试协议名称保留 `pump-dry-test`，最长 3000ms，主机强制低档并禁止升档，APP 1.2.0 不再提供该入口，仅兼容旧记录及旧协议。两种模式均使用持久化身份、共用递增序号及当前启动 epoch。收到 `PUMP_TEST ... state=RUNNING/STOPPED` 才更新接受/终态；停止可取消尚未确认的原测试，不自动重发启动。`LOCAL` 提供测试能力、剩余时间和输出状态。32 号槽位对应 bit31，使用 Long 处理 uint32 掩码。定量 stop 使用设备确认的真实任务号，整项停止，页面不把它称为硬件急停。

- `MainActivity.kt` / `PumpApp.kt` / `PumpNavigation.kt`：固定标签、独立页面状态、返回规则、连接弹层、单次任务确认和跨页停止入口。
- `PumpScreens.kt` / `PumpComponents.kt`：控制、设备、水泵测试、记录、设置及结果详情组件。
- `MasterBleManager.kt`：Master 专用 BLE 扫描、连接、通知与串行发送。
- `MasterControlViewModel.kt`：操作状态、节流、轮询、恢复与 AtomicFile 保存。
- `MasterProtocol.kt`：协议解析、分片重组、请求 / 任务匹配。
- `MasterLedger.kt`：保存在 noBackupFilesDir 的客户端身份、序号及每个 Master 的最近请求。数据损坏时禁止新启动，不静默重建身份。

## 手机管理 Master 从机（1.2.0）

控制页显示 Master 返回的真实已配对数量、在线数量及稳定编号，只能选在线且已完成现场配置的从机。没有完整清单时显示待同步，不生成32个占位设备；缺包、断连或过期禁止新任务。

1. 手机完成系统 PIN 配对后，在设备页点击“让 Master 扫描附近从机”。
2. 待组网从机上电并靠近 Master，约15秒后点击对应“连接组网”。
3. 等待配对、密钥绑定、订阅和从机 HELLO 握手；成功后刷新真实清单。已配对但配置未完成时可在列表“重试连接配置”。
4. 从机输出启用及流量标定仍需现场通过 USB 完成。入网成功不等于可执行出水；未配置或离线从机不可选。

Master 本地 Provisioner 上限32台，只接纳本工程的从机 UUID；没有自动加入行为。扫描/组网与出水和试泵互斥，停止入口保留。新版 Master 启动时自动清理既有外部工具留下的 Node 角色并重启，手机无需现场迁移或迁移确认；确认 `capable=1 phase=IDLE` 后即可添加设备。新功能需同步更新 Master 固件（匹配的 ESP32-C3 从机固件使用独立工程 `D:/pump/pump-muti-slave`）。

手机通过现有加密 GATT 发送 inventory、mesh-state、mesh-scan、mesh-join、mesh-retry 和二次确认后的 `mesh-reset CONFIRM`。旧外部 Mesh 角色由 Master 固件启动流程自动清理。清单按 epoch/request、完整边界、计数及唯一地址/槽位核验后原子提交。
