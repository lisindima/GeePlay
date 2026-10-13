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
| 音频媒体/导航流 | ✅ 已对齐 | android-std + bundled libopus | 媒体 owner 由 `GeelyMediaSettings`/`LocalMediaAudioPolicy` 决定；G636 已验证 AAC 媒体流和 Opus 导航流。GeePlay 恢复 DiPlay 发布包中的 `libdiplay_opus_jni.so`，native 不可用时回退已随源码提供的 Concentus 解码器 |
| 设置备份/恢复 | ⬛ 继承 | n/a | `SettingsBackupServer`/`SettingsBackupCodec`；仅在 About 的备份页存活，导入使用 allowlist，覆盖后提示下次重连 |
| 设置 IA、搜索与多语言 | ⬛ 继承 | n/a | About 已纳入设置层级，搜索监听清理与日语/阿拉伯语补全已继承；GeePlay compact/full/RTL 大字体截图由 `GeePlaySettingsScreenshotsTest` 生成 |
| 产品身份（名称/图标/OEM label/更新源） | ✅ 已对齐 | n/a | `GeePlayApplication` 覆写 `VehicleIntegrationProvider`（`GEELY` 标签、独立更新源）；本次随上游 0.2.18 更新为 `0.2.18-geely.4` |
| 上游厂商设置边界 | ✅ 已对齐 | n/a | `LegacyBydSettingsPolicy` + `GeelyProductSettings.blockLegacyBydMenus` 默认隐藏 BYD/FCE/cluster 项；产品 capability gate 另行禁用 BYD Telecom、Harman 全屏和上游内嵌 Geely 输入，不删上游源码 |

## B. 导航与仪表显示

| # | 能力 | 状态 | Geely 实现方式 | 说明/证据 |
| ---: | --- | --- | --- | --- |
| 1 | 导航箭头/道路/距离/车道/路口到仪表 | 🟡 部分对齐 | DIM→EAS OEM-API | 基础 DIM 字段已接线；G636 实车确认旧 XUI 可把 0 基 `laneIndex/laneIconId` 送入 DIM，但仪表不渲染，且固件 `updateNaviStatus()` 为 no-op。离线已证明 `16/33` 才建立 guiding vendor、`43/44` 仅控制路口放大图；但 GeePlay 当前仍被 NaviService 标记为未知 vendor `-100`，现有离线包不含 G636 仪表最终消费者的 vendor 接受规则，因此生产与下一轮真车投放都保持关闭 |
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
| 9 | 车速 + P/R/N/D 挡位 | ✅ 已对齐 | EVCC-read | 车速 `ADAPT_API:1048832`（fallback `1055232`），G636 实测单位为 m/s；挡位主来源 `CAPI:557849089`（`0=P, 1=N, 2=R, 3=D`），fallback `ADAPT_API:2097664` |
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
| 16 | 车机蓝牙与 CarPlay 音频抢占仲裁 | 🟡 部分对齐 | host-cmd（媒体 owner + A2DP 避让） | 已实现确认联动、A2DP-only worker/lease/journal/recovery 和诊断；待 G636 隐藏 API/权限实测（Spec §11A） |
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
