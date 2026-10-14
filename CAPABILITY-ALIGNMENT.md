# GeePlay 能力对齐总表（CAPABILITY ALIGNMENT）

本文件是 GeePlay 相对 DiPlay 上游的**能力对齐单一事实来源**：记录每项项目能力的对齐状态、Geely 的实现方式与证据，以及负责维护它的详细文档。

> 维护规则：每次从 DiPlay 上游合并（merge/rebase/cherry-pick）到 GeePlay 分支，都必须按 `AGENTS.md`“Upstream merge review for GeePlay”在本表更新受影响能力的状态；本表与 `docs/upstream-merge-checklists/<date>-<sha>.md` 配套，不可只写 checklist 而不更新本表。

- 维护分支：`geely/main`
- 当前上游基线：`d9881b4a`（DiPlay `main`，2026-10-12 合并；见本次 upstream checklist）
- 最近更新：2026-10-13

## 相关文档

- 可行性与架构评估：[docs/BYD-TO-GEELY-FEATURE-MIGRATION-ASSESSMENT.md](docs/BYD-TO-GEELY-FEATURE-MIGRATION-ASSESSMENT.md)
- 未对齐能力实施 Spec（EVCC 优先）：[docs/BYD-TO-GEELY-UNALIGNED-CAPABILITIES-MIGRATION-SPEC.md](docs/BYD-TO-GEELY-UNALIGNED-CAPABILITIES-MIGRATION-SPEC.md)
- EVCC 车辆数据同步 Spec：[docs/EVCC-CARPLAY-VEHICLE-DATA-SYNC-SPEC.md](docs/EVCC-CARPLAY-VEHICLE-DATA-SYNC-SPEC.md)
- EVCC Siri 车控分析：[docs/EVCC-1.2-SIRI-VEHICLE-CONTROL.md](docs/EVCC-1.2-SIRI-VEHICLE-CONTROL.md)
- Geely 核心功能 Spec：[docs/GEELY-CORE-FEATURES-SPEC.md](docs/GEELY-CORE-FEATURES-SPEC.md)
- Geely 实施进展：[docs/GEELY-IMPLEMENTATION-PROGRESS.md](docs/GEELY-IMPLEMENTATION-PROGRESS.md)
- 实车验证矩阵：[docs/GEELY-VALIDATION.md](docs/GEELY-VALIDATION.md)

## 状态图例

| 状态 | 含义 |
| --- | --- |
| ✅ 已对齐 | GeePlay 已有 Geely 实现并接线；软件完成（实车验证以 `GEELY-VALIDATION.md` 为准） |
| 🟡 部分对齐 | 主路径已有 Geely 实现，仍有子能力缺失或待扩展 |
| 📝 待实现 | 已在 Spec 定义方案，尚未实现 |
| 🧪 条件开放 | 需固件/厂商接口/实车证据，默认 fail-closed 或禁用 |
| 🚫 不迁移 | BYD/DiLink 车型特有；GeePlay 仅保留产品目标，不复制实现 |
| ⬛ 继承 | 品牌中立上游能力，GeePlay 原样继承（`INHERIT`） |

## 实现方式图例

| 实现方式 | 说明 |
| --- | --- |
| EVCC-read | EVCC `property.read/listen` typed 属性读取 |
| EVCC-write | EVCC `property.write`（validate→write→read-back→restore） |
| EVCC-shell | EVCC `executeShell`(+`shell.system_write`)，车机逐次确认，仅一次性系统动作 |
| DIM | Geely/ECARX `DimInteraction` 结构化导航输出 |
| OneOS-input | Geely OneOS/Flyme 输入适配 → CarPlay host 命令 |
| host-cmd | 品牌中立 CarPlay host 命令（`CarPlayController`/`VehicleHostControls`） |
| OEM-API | Geely 官方仪表/HUD/电话 projection API（多数待确认） |
| android-std | 标准 Android API + 用户可见设置 |
| n/a | 不适用（不迁移或继承） |

## A. 核心 CarPlay 能力（品牌中立，继承）

| 能力 | 状态 | Geely 实现方式 | 说明/证据 |
| --- | --- | --- | --- |
| 有线/无线连接、配对、USB/热点引导 | ⬛ 继承 | n/a | `CarPlayController`/`CarPlayHostActivity` 上游通用链路；`d9881b4a` 的 USBMUX、VPN scope、无效 RFCOMM、配置选择及恢复修复已继承 |
| 视频解码与多 Surface、触控 | ⬛ 继承 | n/a | 上游 `MediaEngine`/stream 处理；`d9881b4a` 的 safe-area、尺寸、MTK 回退和解码等待修复已继承，Harman 专用全屏在 GeePlay 禁用 |
| 音频媒体/导航流 | ✅ 已对齐 | android-std + bundled libopus | 媒体 owner 由 `GeelyMediaSettings`/`LocalMediaAudioPolicy` 决定；G636 已验证 AAC 媒体流和 Opus 导航流。GeePlay 恢复 DiPlay 发布包中的 `libdiplay_opus_jni.so`，native 不可用时回退已随源码提供的 Concentus 解码器。GeePlay 未保存过设置时使用 100 ms 低延迟媒体缓冲；50/100 ms 档不在 underrun 后再次暂停，并容忍无线 CarPlay 约 400 ms 的正常突发，只裁掉超过低延迟窗口的陈旧压缩包 |
| 设置备份/恢复 | ⬛ 继承 | n/a | `SettingsBackupServer`/`SettingsBackupCodec`；仅在 About 的备份页存活，导入使用 allowlist，覆盖后提示下次重连 |
| 设置 IA、搜索与多语言 | ⬛ 继承 | n/a | About 已纳入设置层级，搜索监听清理与日语/阿拉伯语补全已继承；GeePlay compact/full/RTL 大字体截图由 `GeePlaySettingsScreenshotsTest` 生成 |
| 产品身份（名称/图标/OEM label/更新源） | ✅ 已对齐 | n/a | `GeePlayApplication` 覆写 `VehicleIntegrationProvider`（`GEELY` 标签、独立更新源）；本次随上游 0.2.18 更新为 `0.2.18-geely.4` |
| 上游厂商设置边界 | ✅ 已对齐 | n/a | `LegacyBydSettingsPolicy` + `GeelyProductSettings.blockLegacyBydMenus` 默认隐藏 BYD/FCE/cluster 项；产品 capability gate 另行禁用 BYD Telecom、Harman 全屏和上游内嵌 Geely 输入，不删上游源码 |

## B. 导航与仪表显示

| # | 能力 | 状态 | Geely 实现方式 | 说明/证据 |
| ---: | --- | --- | --- | --- |
| 1 | 导航箭头/道路/距离/车道/路口到仪表 | 🟡 部分对齐 | DIM/XUI OEM-API | G636 已验证 lane-before-route flush、首次 empty 清残留和 5 秒 ownership heartbeat。同车 Apple Maps 预发 3 个真实 `0x5204`，`Showing=true` 后完整经过 store/OEM/DIM；真实样本修正 highlighted angle 归一化，停车 replay 已确认第三条“直行+右转、推荐直行”映射为 OEM `32`。高德主页组件 lane 窗口内没有任何 accessory lane metadata，但 CarPlay stream 111 持续收到 20–30 fps navigation UI。Apple 官方明确区分 navigation UI stream 与 HUD/iAP2 metadata；第三方 App 还必须显式调用 `cpNavigationSession.add(laneGuidance)`。因此高德仪表投屏可走 stream 111，而原生 HUD lane 仍缺结构化输入；除高德补 metadata 外，只能另立视频识别方案。继续保持部分对齐 |
| 7 | 保持/不被原厂地图抢占仪表 | ✅ 已对齐（等价目标） | DIM | `GeelyNavigationDriver` 用 `notifyTurnByTurnStarted/Stopped` + cleanup journal 表达所有权，不禁用 OEM 包 |
| 2 | 挡风 HUD 导航输出 | 📝 待实现 | DIM→OEM-API | 先验证 DIM 是否自动分发到 HUD；否则 `GeelyHudNavigationOutput`（Spec §10.1） |
| 5 | CarPlay 仪表地图/私有 Display 路由 | 🧪 条件开放 | OEM-API/Display | `VehicleClusterDisplayPolicy` 已就位，`VALIDATED_PROFILES` 为空，仅 debug 选择；上游新增 cluster speedometer/music overlay 仍由被屏蔽的 `CLUSTER_MAP` 控制，未作为 Geely 生产能力开放（Spec §10.2） |
| 6 | 仪表地图流随导航模式暂停 | 🧪 条件开放 | EVCC-read→OEM-API | 已实现保守目录发现、`ClusterMapVisibilitySource` 与 session 控制；未发现可信属性时保持流开启（Spec §6） |
| 3 | 独立 HUD 广播输出 | 🚫 不迁移 | n/a | BYD `com.byd.clusterdebug` 私有广播；目标由 #1/#2 覆盖 |
| 4 | DiLink3 仪表模式/投屏准备 | 🚫 不迁移 | n/a | BYD `AutoContainer` 私有容器；Geely 走 DIM 或已验证 Display |

## C. 车辆遥测 → iPhone/CarPlay（EVCC 优先）

| # | 能力 | 状态 | Geely 实现方式 | 说明/证据 |
| ---: | --- | --- | --- | --- |
| 8 | 电池 SOC/续航/充电状态 | ✅ 已对齐 | EVCC-read | `EvccVehicleDataAdapter`；G636 已验证 SOC `ADAPT_API:1051136`、续航 `ADAPT_API:1054976`、充电 `ADAPT_API:605225728` |
| 9 | 车速 + P/R/N/D 挡位 | ✅ 已对齐 | EVCC-read | 车速 `ADAPT_API:1048832`（fallback `1055232`），G636 实测单位为 m/s；挡位主来源 `CAPI:557849089`（`0=P, 1=N, 2=R, 3=D`），fallback `ADAPT_API:2097664`；`$PASCD` 缓存采样支持默认 4 Hz 与实验 10 Hz，不增加 forceRefresh 或 iAP2 发包频率 |
| 10 | 仅驻车允许车内视频 | 🟡 部分对齐 | EVCC-read | 已实现 fail-closed `VehicleParkStateSource`、设置与通用 consumer；待 G636 P→D/R 实车（Spec §4） |
| 11 | 胎压/胎温侧边栏 | 🟡 部分对齐 | EVCC-read | 已实现保守目录发现、轮位/单位映射、`VehicleTyreTelemetrySource` 与侧边栏；待 G636 descriptor 实证（Spec §5） |
| 21 | 车辆属性发现 | ✅ 已对齐（发现层） | EVCC-read | `queryPropertyDirectory/resolve/read/subscribe` + source generation；生产用已验证 profile |

## D. 输入（方向盘/语音，非 EVCC）

| # | 能力 | 状态 | Geely 实现方式 | 说明/证据 |
| ---: | --- | --- | --- | --- |
| 15a | 媒体键（播放/暂停/上一首/下一首）+ Siri | ✅ 已对齐 | OneOS-input | `GeelyInputRuntime`/`OneOsWheelAdapter`→`VehicleHostControls`；上游 `GeelyWheelKeys` 直连 singleton 在 GeePlay 由 `embeddedGeelyWheelKeysAvailable=false` 禁用，避免双注册 |
| 14 | 方向盘接听/挂断 CarPlay 电话 | 🧪 条件开放 | OneOS-input + host-cmd | 已扩展 host 命令、OneOS 注册和通话门禁；待 G636 raw keycode 实证（Spec §8） |
| 15b | 方向盘地图缩放、旋钮/摇杆 | 🧪 条件开放 | OneOS-input + host-cmd | 已接 `sendKnob`/`zoomDashboardMap` 和标准 keycode；待 G636 raw keycode 实证（Spec §8） |

## E. 厂商仪表渲染（需 Geely OEM API）

| # | 能力 | 状态 | Geely 实现方式 | 说明/证据 |
| ---: | --- | --- | --- | --- |
| 12 | CarPlay 歌曲显示到仪表 | 🧪 条件开放 | OEM-API | 已建立 `InstrumentMediaOutput`、通用解析和生命周期；Geely 无已验证 API 时注入 NONE。`d9881b4a` 的 cluster music overlay 依赖未验证的仪表 Display，GeePlay 保持设置不可见（Spec §9） |
| 13 | CarPlay 来电卡片到仪表/HUD | 🧪 条件开放 | OEM-API | 已建立 `InstrumentCallOutput`、通用解析和生命周期；Geely 无已验证 API 时注入 NONE（Spec §9） |

## F. 车内氛围（EVCC 写）

| # | 能力 | 状态 | Geely 实现方式 | 说明/证据 |
| ---: | --- | --- | --- | --- |
| 17 | 随音乐变化的氛围灯 | 🧪 条件开放 | EVCC-write | 已实现独立 control port、通用灯光 port、validate/write/read-back/restore；`VALIDATED_PROFILES` 为空时设置 fail-closed（Spec §7） |

## G. 系统集成（标准 Android 优先，EVCC Shell 仅可选确认动作）

| # | 能力 | 状态 | Geely 实现方式 | 说明/证据 |
| ---: | --- | --- | --- | --- |
| 19 | 自动车机热点与权限授权 | 🟡 部分对齐 | android-std（可选 EVCC-shell） | 通用 hotspot 保留；已实现 allowlist EVCC system-action port、双确认设置入口；待授权实车（Spec §11.1） |
| 20 | 开机自启修复、doze/appops 白名单 | 🧪 条件开放 | android-std（可选 EVCC-shell） | 已实现显式、单命令、车机确认的 background/doze action；仅在 G636 需要时使用（Spec §11.2） |
| 16 | 车机蓝牙与 CarPlay 音频抢占仲裁 | 🟡 部分对齐 | android-std + OEM 媒体源（A2DP_SINK 避让） | G636 已验证 profile 11 自动断开且 profile 16/HFP 保持连接；profile 2 角色错误已修复。普通应用直接恢复受 `BLUETOOTH_PRIVILEGED` 拒绝，改由平台签名的原车蓝牙音乐 receiver 切回 OEM source 并代为 `BluetoothA2dpSink.connect()`；仅信任 G636 system app、连接后才清 journal。禁用/恢复为独立开关，恢复默认关；OEM 恢复路径待下次 G636 实车验收（Spec §11A） |
| 18 | 隐藏原生来电弹窗 | 🚫 不迁移 | OEM-API（问题驱动） | 依赖被 EVCC Shell 永久拒绝的构造；复现后找 Geely 通信 UI policy |

## H. 工具与辅助耦合

| # | 能力/路径 | 状态 | Geely 实现方式 | 说明/证据 |
| ---: | --- | --- | --- | --- |
| 22 | BYD-only 调试/演示工具 | 🚫 不迁移 | n/a | `BydStarterBridge`/HUD demo/`Byd13CatalogProbeMain` 不进产品图 |
| 23 | BYD/DiLink Telecom 电话音频状态 | 🚫 不迁移 | n/a | 上游 `PhoneAudioRoute`/`PhoneAudioConnectionService` 仅解决 DiLink 通话回声；GeePlay 以 capability gate 禁止 attach/显示，并从合并 manifest 移除 service 与 `MANAGE_OWN_CALLS` |
| aux | common/shared 直接引用 BYD 单例 | 🟡 部分对齐 | 解耦改造 | 驻车、胎压、氛围灯已换 product Interface；`CarPlayCallKeys`、旧 cluster 路径等仍待后续上游解耦 |
| aux | DiLink51 / FCE / Harman 布局、HomeScreenMonitor、UsbAutoConfirm、WifiScanPause、MapEmbed | 🚫 不迁移 / 🧪 条件开放 | 按需 product policy | FCE/Harman/MapEmbed 由 section、capability 或 manifest gate 排除；无 Geely 证据前不迁移，见评估 §3.1 |

## 进程级 EVCC 架构（所有 EVCC 能力的共同依赖）

- 现状：`EvccVehicleDataAdapter` 已独立拥有单 `EvccApiClient`、授权校验、`EvccDemandRegistry`（replace-all 并集）、`EvccVehicleTelemetryStore`（typed/staleness/source generation）、只读 `EvccClientPort`。
- 目标：提取进程级 `EvccGateway` = `EvccConnectionCore` + `EvccReadGateway`（只读消费者）+ 懒创建 `EvccControlGateway`（写/Shell，物理隔离）。详见 Spec §3 与评估 §6.2。
- 硬约束：全进程单 client；消费者只经 demand registry；只读/写端口物理隔离；Gateway 由 `GeePlayApplication` 持有。

## 变更记录

| 日期 | 基线 | 变更 |
| --- | --- | --- |
| 2026-10-12 | `f16878e9` | 初版；记录 22 项核心能力 + 辅助耦合对齐状态；#8/#9 EVCC 已对齐，#1/#7 DIM 已对齐，其余按 Spec 分级 |
| 2026-10-12 | `f16878e9` | #16 从「🚫不迁移」升级为「📝待实现」：G636 复现车机蓝牙/CarPlay 音频抢占；方案改为媒体 owner + 自动断 A2DP（Spec §11A），不迁移 BYD ADB 关 radio |
| 2026-10-12 | `cf4ac2f2` 后工作区 | 完成未对齐 Spec 的软件接线：进程级 EVCC core/read/control、#6/#10/#11、#14/#15、#16、#17、#19/#20，以及 #12/#13 证据门禁；实车/descriptor/OEM API 未验证项保持 🟡/🧪 |
| 2026-10-12 | `d9881b4a` | 合并 DiPlay main：继承连接/USB/VPN/解码/备份/本地化改进；NoRoute handoff 语义迁入 `AppleRouteGuidanceStore`；新增 #23，并对上游内嵌 Geely 输入、BYD Telecom、FCE/cluster 与 Harman 功能保持显式 GeePlay 边界 |
| 2026-10-12 | `d9881b4a` 后工作区 | G636 真机确认导航 Opus 流因系统无 `audio/opus` decoder 而静音；恢复 DiPlay 发布包 bundled libopus，增加 Concentus fallback，真机确认 `type=101` 持续解码为 PCM 且导航声音恢复 |
| 2026-10-13 | `d9881b4a` 后工作区 | #1 调整为「🟡部分对齐」：抓到工厂真实 0 基车道数组和 icon ID；GeePlay XUI 数组已到 DIM 但仪表不渲染；当时按工厂 `43/44` 时序新增 Debug-only EAS 探针，后续离线分析在下一条记录中纠正该时序假设 |
| 2026-10-13 | `ec9d9479` 后工作区 | #1 离线纠正根因：真车已执行的 `43 → lanes → 44` 探针虽到达 NaviService，但 `43/44` 不参与 guiding source 选择，`mapForWidget` 因而仍为工厂 vendor `9`，lane `2009` 被过滤。探针改为不请求 vendor 9，并使用 `16 → 43 → lanes → clear → 44 → 17`；二次审计确认 `-100` 可通过 NaviService 状态机并生成 `RspLanesInfo`，但无法离线证明 G636 仪表消费者接受未知 vendor，故暂不安排真车投放 |
| 2026-10-13 | `c3d0809f` → `a99dab1` | #9 G636 实车纠正：EVCC 车速原值已经是 m/s，不再重复除以 3.6；挡位改用动态验证的 `CAPI:0x21401A01`，并保留 `ADAPT_API:0x200200` fallback。合并时保留稳定 `VEHICLE-DATA` 分层诊断与胎压/胎温扩展。 |
| 2026-10-13 | `320647e8` 后工作区 | #9 增加 Advanced 实验设置：`$PASCD` 包内轮速采样可选 4 Hz/250 ms 或 10 Hz/100 ms；EVCC 缓存健康读取和 iAP2 发包仍约 1 Hz，不启用车辆总线 force refresh。 |
| 2026-10-13 | `320647e8` 后工作区 | #1 G636 真车闭环根因：EAS lane 已到 `3418/updateLaneInfo` 但不会单独发往 QNX；在其后触发完整 XUI route frame 后用户确认车道可见。生产保持 XUI，实现改为 lane 先写、`updateNavigationInfo/ETA` 后 flush，并对发布和清理顺序增加回归测试。 |
| 2026-10-13 | `320647e8` 后工作区 | #1 纠正验证结论：用户看到的是 `navigation` 模式注入的固定合成车道，并非真实 CarPlay lane。日志确认上一会话曾接收 8 帧 `0x5204`，但没有保存其解析/DIM 时序；继续保持「🟡部分对齐」。 |
| 2026-10-13 | `320647e8` 后工作区 | #1 安装通勤诊断包：`RGDIAG` 持久记录脱敏后的 5201/5202/5204、source/session、store publish/clear、lane/icon 映射与关键 DIM 调用；当前日志加 7 个历史分片自动轮转，供下一次真实车道出现后离线分析。 |
| 2026-10-13 | `320647e8` 后工作区 | #16 G636 真车纠正：手机位于车机 `A2DP_SINK` profile 11，不是公开 `A2DP` profile 2；改为只断 profile 11 后原车音乐入口变灰，`HEADSET_CLIENT` profile 16/HFP 全程保持 Connected。普通应用恢复被 `BLUETOOTH_PRIVILEGED` 拒绝，ECARX/PSD 候选均不可用，因此自动禁用与自动恢复拆为独立设置、恢复默认关闭并对失败保留 pending journal；原车蓝牙音乐图标可手动恢复。 |
| 2026-10-14 | `320647e8` 后工作区 | #16 自动恢复补强：进一步审计发现平台签名 `com.flyme.auto.localmusic.bluetooth` 的 exported `CHANGE_BT_SOURCE` receiver；其 source-owner 流在蓝牙源激活时由系统进程调用 profile 11 `connect()`。GeePlay 仅在 model=G636、目标为 system receiver 时发送显式请求，并观察到 A2DP Connected 后才清 journal；直接 connect 和手动原车面板仍为 fallback。代码/单测完成，待下次真车确认 source 切换、恢复耗时及是否续播。 |
| 2026-10-14 | `320647e8` 后工作区 | #1 通勤日志纠正：`flyme_auto_map_mock_guidance` 实际仍为 `1`，原车 `NaviService` 独立 lane 流造成推荐态错位和提前展示；删除 setting 并重启 `com.flyme.auto.map` 后该流停止。干净高德会话普通导航恢复，但持续 `LaneGuidanceShowing=0`、无 `0x5204`，故当前无车道是源端无 lane metadata 的正确结果。新增新 driver 首帧 empty lane 清残留及活动导航每 5 秒 `notifyNavigationStatus(1)`；加固包 `8dcf3bd6…dab6` 已安装到 G636，真车日志确认 empty lane 在 route frame 前写入、5.38 秒后重申 status=1。新增仅 Debug 可用的真实 TLV `route-replay`，停车时可重放 parser→store→DIM 动态车道；真实 CarPlay lane 仍保持「🟡部分对齐」。 |
| 2026-10-14 | `320647e8` 后工作区 | #1 高德真实 UI/metadata 分离证据：用户在 `18:29` 明确确认 CarPlay 主页小组件正显示车道；`18:29:15–18:31:10` 同步日志统计为 RX `5201=125`、`5202=2`、`5204=0`、`laneShowing true=0/false=125`、store/OEM/DIM 非空 lane 均为 0。当前会话 identification 已接受、component 42 的 `0x5200` 订阅已发送，代码/测试锁定 capability 7/8 和接收白名单。结论收窄为：高德已向 CarPlay 系统提供 lane，但该窗口未经现有 accessory RouteGuidance 通道到达 GeePlay；下一轮扩大到所有控制消息/组件差异，不能再表述为“高德没推”。 |
| 2026-10-14 | `320647e8` 后工作区 | #1 Apple Maps 对照闭环：`18:32:26` 预发 event 0/1/2 三帧真实 `0x5204`；`18:40:20` 在约 2.07 km 处切 `index=1/total=3/showing=true`，store、OEM 和 DIM 连续输出 `[0,15, 1,15, 2,3]`。用户纠正第三条实际为直行+右转都可；原始值 `status=PREFERRED, angles=[90], highlight=0` 证明 highlight 也是被省略的允许方向。parser 归一化后目标数组为 `[0,15, 1,15, 2,32]`；新包 `303e8c4c…d329` 已安装，5/5 replay 完整清理，用户确认复合推荐视觉正确。 |
| 2026-10-14 | `320647e8` 后工作区 | #1 高德宽口径复核完成：用户在 `19:16` 明确确认 CarPlay 主页小组件正在显示 lane；`19:15:00–19:17:05` 核心窗口记录 `5201=221`、`5202=5`、`5204=0`、`showing true=0/false=221`，store/OEM 非空 lane=0。用户于 `19:18:48` 再次确认 lane 仍可见，延伸段新增 58 帧 `5201` 仍全部 false、无 `5204`。同期全部额外 RX 控制消息只有 `0x4158 CommunicationsUpdate`，不是导航数据。由此排除短暂延迟、“只过滤错了 5204”及另一 iAP2 Control Session lane message；数据未从 CarPlay 内部主页组件桥接到 accessory RouteGuidance。持续 heartbeat 已停止并删除。 |
| 2026-10-14 | `320647e8` 后工作区 | #1 外部资料复核：Apple WWDC23 要求仪表地图/小组件使用 navigation UI stream，HUD 使用 iAP2 metadata；WWDC25 要求导航 App 返回 `mapTemplateShouldProvideNavigationMetadata=true` 并向 `CPNavigationSession` 添加 maneuver/lane guidance。`CPLaneGuidance` 从 iOS 17.4 可用，高德公开仪表盘导航同样要求 iPhone 12+、iOS 17.4+ 与车辆仪表投屏。高德 lane 窗口中 stream 111 持续 20–30 fps、但无 `0x5204`，说明其投屏路径已工作而 lane metadata 未提供。 |
| 2026-10-14 | `320647e8` 后工作区 | G636 媒体低延迟实车修复：原 300 ms 启播阈值和 500 ms Track 容量明显高于原车 A2DP 约 64 ms；GeePlay 新用户默认改为 100 ms，并提供 50/100/300/500/1000 ms 五档。低延迟档改用小 PCM Track，让压缩队列承接约 400 ms 无线突发；100 ms 档真机 Track=120 ms、稳态 PCM≈80 ms，用户确认比旧版明显改善；50 ms 档受平台下限实际为 start=64 ms、Track=74 ms、稳态 PCM≈58–69 ms，正常窗口 `rebuffers=0`、`dropped=0`。遇到 1.2 秒级长空档时按 450 ms 上限追帧，避免重新积累 1–2 秒延迟。最终 APK `2a170d2c…f15c27` 已安装且车机/本地 SHA-256 一致，当前车机选择 50 ms。 |
