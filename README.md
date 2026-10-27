# 泵阀控制 Android APP

本工程是 `pump-muti` 多节点固件的独立 Android 客户端，包名 / applicationId 保持 `com.cczb.app`。打开后进入四个固定标签的 Compose 控制界面，日常出水在控制页完成。原 `D:\esp32\pump` Android 工程及原单机固件工程保持原样。

## 当前功能

2026-10-25 基础候选；当前配套 ESP32-S3 主机 / ESP32-C3 从机，最多一主五从、最多五路同时定量。当前启动的 CAPS 决定并发（旧固件无声明时回退 1），产品容量 5；同任务统一每路目标，各路独立计量和关阀。基础版隐藏新的逐路不同目标、交互式标定和独立试泵，保留旧会话停止/查询。维护许可未退出或状态未同步时禁止新任务。协议 revision 6 / RequiredFeatures `0x15FF`，需配套主从固件；本轮无实机测试条件。以下兼容功能以设备实际能力为准。

- 扫描并直连 `PUMP_MASTER_XXXX`，等待系统蓝牙配对后建立加密通知通道。主机源码默认使用六位 PIN 的认证配对；已安装旧开发镜像的设备仍可能使用 Just Works，以设备实际镜像为准。
- 1.2.2 设备页支持单台删除从机，核对名称与地址后二次确认；支持离线和待配置节点。需同步更新主机固件，旧固件按钮禁用并提示升级。
- 从 Master 完整清单选择在线且已启用阀门，每路 50–2000 mL（步进 50 mL）、最长 1–240 秒；基础候选按同一 JobKey 同时供水，任一故障整批停止，不静默分批。
- 启动、查询原任务、停止整个任务；显示已接受状态、阶段、异常 / 未知槽位，以及累计水量的数据覆盖范围。
- 1.2.1 固定控制、设备、记录、设置四个底部入口；控制页原位选阀门和显示任务结果，记录详情与水泵测试均在原页展开；切标签保留草稿、表单和滚动位置，系统返回只处理当前可见内容。记录页保存最近 100 项手机本地任务观察。
- 基础版设备页显示运行模式、维护锁和配置恢复动作；旧固件或在途试泵会话保留测试停止/查询界面。
- ESP32-S3 N16R8 主机 GPIO4 接驱动 PWM，三档默认 65% / 80% / 100%，停止 0%，20 kHz。空闲选档不启泵，任务中禁止改档，需核对驱动规格。
- 设置页支持每个从节点低/中/高档霍尔计量校准，可手动编辑脉冲/mL，或用总脉冲数和量杯实测 mL 计算，主机确认保存后下次任务生效。使用步骤与协议见 [三档流量校准](docs/FLOW_CALIBRATION.md)，需同步更新主从机固件。
- 配套主机上电输出关闭，不强制 BOOT_LOCK；必须完成系统蓝牙配对、状态同步、旧任务对账及全员维护互锁证明后显式启动。USB `pump-reset` 是故障维护操作，不是日常必经步骤。
- 启动前原子保存客户端身份、递增序号和完整请求。Activity 重建或进程重启后，重新连接相同 Master 时查询原任务，不自动再次启动。
- 请求未确认时可查询或重试完全相同请求；若用户已点击停止，先持久保存停止意图，查询取得原 JobKey 后自动停止，禁止再次启动。APP 进程重启保留此意图，未获设备停止确认不会显示已停止。断连不取消已接受任务。
- 调度状态超过约 15 秒未更新时禁止新任务；仍可对已知任务提交停止。停止命令优先于待发查询，停止结果仍需主机确认。

设备清单来自 Master 完整同步，槽位是 Master 保存的稳定登记号；实际注册、在线与系数匹配仍由 Master 核验。当前只直连 Master，不提供从机转发或其他手机任务自动发现。FLOW 的地址不等同于槽位，页面明确使用地址标注。历史记录是本手机保存的最近 100 项观察，不保证补回换手机前的数据。

当前交互路径和返回规则见 [docs/UI_UX_DESIGN.md](docs/UI_UX_DESIGN.md)。

## 首次使用

1. 后续实机验证使用配套主从候选；`pump_master_five_candidate` 支持五路，正式默认 `pump_master` 并发 1。未配网主机作为 Provisioner，在设备页扫描、连接组网并同步完整清单。当前不具备实机条件，未烧录或安装本候选。
2. 确认 GPIO4 驱动接线与参数。配套新版 Master 的水泵档案为 `commissioned=true`，Slave 阀门仍为 `false`；APP 不修改硬件档案。定量出水前需核实从节点登记、霍尔系数并在设备页启用对应阀门。
3. 主机源码默认关闭 `CONFIG_PUMP_DEV_NO_PIN`；安装人员须先通过可信 USB 设置有效六位 PIN 并重启，否则新镜像的手机控制服务关闭。APP 按系统提示输入 PIN。已安装设备与旧 dist 产物不随源码自动更新。正常启动不要求 `pump-reset`。
4. 安装 debug APK，允许附近设备权限，在 APP 内扫描并连接 `PUMP_MASTER_XXXX`。Master 是 BLE GATT 设备，部分手机系统蓝牙列表可能不显示；首次连接如出现 Android 系统配对确认，请确认；启用 PIN 的主机才需输入六位 PIN。Android 11 还需要位置权限和系统位置服务。
5. 主机身份、Mesh、硬件输出配置和故障状态均就绪后，选现场已登记槽位并创建任务。例如选 1、2 路，每路 500 mL，总目标为 1,000 mL。

基础版通过多路定量任务控制共享泵，空闲可在出水页选档，等待主机确认。现场按住开阀须在主机 USB 取得单路 60 秒维护许可，期间主机泵禁用；APP 不提供许可授予。设备页显示配置最近剩余预算与隔离恢复动作，`RESTART_REQUIRED` 时须重启主机后刷新清单，反复点击重试无法解锁。

手机不承担毫秒级关阀闭环。每个从机本地计量并到量关阀，主机控制公共水泵与调度。暂不加入缺水保护。页面中的配置状态不代表已测得水泵运行或阀门机械位置。

已有任务换手机无法自动发现；本手机保存的身份也不应复制到其他手机。若主机重启、历史被移除或返回 UNKNOWN，APP 保留未确认状态，需查询并现场核实，不自动补水。

## Android Studio 与构建

使用 Android Studio 打开工程根目录（当前为 `D:\pump\pumpmutiapp`），选择 `app` 配置运行。最低 Android 11（API 30），编译 / 目标 SDK 为 37，Gradle JDK 使用 21。

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

APP 先等待系统蓝牙配对达到 `BOND_BONDED`，再发现服务、验证加密通知订阅，最后请求 MTU 185，最低接受 163；MTU 回调确认达标后才开放命令通道。协商请求提交或回调失败时最多重试一次；超时重置连接，不并行提交新的 GATT 操作。成功协商但低于 163 时明确提示检查 Master 固件。一条命令最多 159 字节，不发送长写或分片命令。TX 按字节缓冲至换行后解析，单行最多 191 字节，超长行丢弃至下一换行。普通写入串行，写入超时 5 秒后重置连接，不自动重试启动。重置会清除残留通知与旧会话回调。约每 2 秒查询未结束任务，每 10–12 秒刷新 hello。成功写入 GATT 不等于业务接受，必须匹配响应身份、序号、任务号、epoch 及所选节点。

### 首次蓝牙配对与重连

Master 在 LE 连接后主动请求加密：开发模式使用 Just Works，PIN 模式要求 MITM 认证；APP 等待 `NONE → BONDING → BONDED`，期间不发现服务、不写 CCCD。1.5 秒后仍为 NONE 时，APP 仅补发一次公开 `createBond()` 请求。开发模式无需 PIN；PIN 模式由 Android 系统窗口收集六位数字，不在 APP 内自动填充。已有绑定通常复用密钥，仍需加密 CCCD 成功才能开放控制。取消或失败会关闭本次连接，断开时注销配对广播监听、丢弃旧会话回调。

开发模式不依赖 NVS 中是否已有 PIN；恢复 PIN 模式后，使用可信安装时设置的 PIN，不在日志里读取或打印 PIN；干净 NVS 默认不设 PIN。修复固件显式将双向密钥分发设为 `ENC|ID (0x03)`，避开 BLE-only 模式下的经典蓝牙 LinkKey 派生。`smp_derive_link_key_from_long_term_key failed` 与此路径相关；`GATT=133` 本身不能证明唯一原因。若更新后仍有旧密钥问题，仅在手机系统中忘记 `PUMP_MASTER_XXXX` 后重连，不清空设备 NVS。

### 恢复凭证与主机身份

高级设置提供从机恢复凭证的加密备份导入及主机身份文件导入。主机 `.pmmaster` 文件须由可信 USB 安装人员交付，并核对完整指纹；相同 UUID/指纹发生身份冲突时拒绝替换。信任库使用独立 Keystore 包装密钥与 AtomicFile，文件损坏或密钥丢失时不自动重建。公开身份文件不授予管理员权限，也不能替代从机恢复凭证。

目前恢复服务只提供认证及只读状态，完整重新组网、续办与待启用流程尚未完成，不能通过导入材料后直接清除从机 Mesh。默认保持 `recovery_enabled=0`；具体安装和当前验证边界见主机仓库 `docs/RECOVERY_INSTALLATION.md`。

APP 诊断与 Logcat `MasterBle` 标签记录连接、配对状态、服务发现、加密 CCCD、MTU。现场验收应覆盖开发模式免 PIN 配对、已绑定重连、取消配对及更换主机，并在恢复 PIN 模式后验证正确/错误 PIN；软件单元测试不能替代实机配对验证。

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

1. 手机完成系统蓝牙配对后，在设备页点击“让 Master 扫描附近从机”。
2. 待组网从机上电并靠近 Master，约15秒后点击对应“连接组网”。
3. 等待配对、密钥绑定、订阅和从机 HELLO 握手；成功后刷新真实清单。已配对但配置未完成时可在列表“重试连接配置”。
4. 从机输出启用及流量标定仍需现场通过 USB 完成。入网成功不等于可执行出水；未配置或离线从机不可选。

Master 本地 Provisioner 上限32台，只接纳本工程的从机 UUID；没有自动加入行为。扫描/组网与出水和试泵互斥，停止入口保留。新版 Master 启动时自动清理既有外部工具留下的 Node 角色并重启，手机无需现场迁移或迁移确认；确认 `capable=1 phase=IDLE` 后即可添加设备。新功能需同步更新 Master 固件（匹配的 ESP32-C3 从机固件使用独立工程 `D:/pump/pump-muti-slave`）。

手机通过现有加密 GATT 发送 inventory、mesh-state、mesh-scan、mesh-join、mesh-retry 和二次确认后的 `mesh-reset CONFIRM`。旧外部 Mesh 角色由 Master 固件启动流程自动清理。清单按 epoch/request、完整边界、计数及唯一地址/槽位核验后原子提交。

## 单台删除从机（1.2.2）

在「设备」页刷新清单，点击目标行「删除」，核对名称和 Mesh 地址后「确认删除」。任务、试泵、组网或启用进行时，以及状态过期或结果未对账时禁止删除。请求只发送一次，断线或超时应刷新清单核实，不自动重发。

主机先尝试远程 Node Reset，再删除该从机的本地连接、登记与三档标定。确认完成后自动刷新清单，清对应手机别名。任务开始时保存槽位与地址对应关系，删除或复用槽位不改写旧任务所属节点。

「已收到退网确认」表示收到从机回复；配套新版从机会关阀、清除启用状态并重启为 PB-ADV 待组网，重加后需再次启用。「未确认从机退网」只确认主机本地删除；从机可能仍留在旧网络，重加前需本地清 Mesh。普通 Reset/断电不会清 Mesh，当前从机 USB 命令没有本地清 Mesh 入口。

需同步更新 `D:/pump/pump-muti` 主机固件；建议更新 `D:/pump/pump-muti-slave` 以支持远程退网后自动重新待组网。MESH 的 `remove=1` 声明单台删除能力；命令为 `mesh-remove EPOCH REQUEST ADDR_HEX CONFIRM`，结果同时匹配 epoch、operation 和目标地址。详细恢复机制见主机工程 `docs/SLAVE_REMOVAL.md`。
