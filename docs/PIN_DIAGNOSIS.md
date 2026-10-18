# 手机连接主机未弹 PIN 的现场排查

2026-10-16，三星 SM-F9660（Android 16）连接 PUMP_MASTER_02CE，APP 报 GATT=147。

## 已确认的根因

初次日志中手机保留旧绑定（bond=BONDED）；忘记设备后变为 bond=NONE，仍然失败，因此旧绑定不足以解释故障。Android 的 147（0x93）为 GATT_CONNECTION_TIMEOUT，底层链路建立约 5 秒后以 reason=0x08 断开。

用户确认主机为 COM5 并关闭串口监视器后，同步复现捕获到了主机崩溃：

```text
BT_OSI: osi_alarm_new alarm_cbs exhausted
assert failed: hash_map_set hash_map.c:129 (data != NULL)
rst:0xc (SW_CPU_RESET)
```

已核对设备与本地 ELF 哈希，调用链为 GATT Service Changed indication -> 确认定时器申请 -> BTU hash_map_set 断言。Mesh 与 Bluedroid 共用的 50 个 OSI alarm 槽位耗尽，连接时主机重启，手机无法进入正常配对流程。这是本次没有 PIN 窗口的直接原因。

## 修复及现场操作

固件公共默认配置与主从实际 sdkconfig 均将 CONFIG_BT_ALARM_MAX_NUM 提高至 128。APP 配对逻辑无需针对该崩溃改动。更详细的容量分析、固件验证和独立 Mesh 角色问题见固件工程 docs/GATT147_DIAGNOSIS.md。

排查中读取了 Android 蓝牙日志，通过 COM5 发送诊断查询，重启主机收集启动日志；用户清除了手机绑定。用户卸载 APP 后，已重新安装工程现有的 debug APK 并启动。主从固件均编译通过，生成配置均确认容量为 128，已重新打包；COM5 主机应用镜像已更新并通过写入哈希验证，保留设备 NVS。更新后查询确认泵输出为 0、BOOT_LOCK、test_active=0。未发送泵阀输出指令。

## 修复后实测

用户确认已弹系统 PIN，输入后连接成功。2026-10-16 北京时间 21:32:42—21:32:52 的 build/gatt147-fixed-app.log 确认：

```text
BLE 连接回调：GATT=0 state=2 bond=BONDING
BLE 配对状态：11 → 12，实际=BONDED
BLE 服务发现回调：GATT=0
BLE 加密 CCCD 回调：GATT=0 bond=BONDED
BLE MTU 回调：mtu=185 GATT=0
BLE 控制通道就绪，MTU=185
```

配对后串口查询主机 epoch 仍为 59，未因本次连接重启；输出为 0、BOOT_LOCK、test_active=0。本次验证覆盖首次正确 PIN 连接及控制通道建立；错 PIN、取消、反复重连、Mesh 组网和水路仍未验收。

已有有效绑定时不重复弹 PIN 属于预期行为；首次配对需要系统 PIN 输入，不能绕过认证。现场证据位于 APP build/gatt147-bluetooth-diagnostic.txt、build/pin-app-diagnostic.log 与固件 .pio/gatt147-master-connect.log。
