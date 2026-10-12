# GeePlay 能力对齐总表（CAPABILITY ALIGNMENT）

本文件是 GeePlay 相对 DiPlay 上游的**能力对齐单一事实来源**：记录每项项目能力的对齐状态、Geely 的实现方式与证据，以及负责维护它的详细文档。

> 维护规则：每次从 DiPlay 上游合并（merge/rebase/cherry-pick）到 GeePlay 分支，都必须按 `AGENTS.md`“Upstream merge review for GeePlay”在本表更新受影响能力的状态；本表与 `docs/upstream-merge-checklists/<date>-<sha>.md` 配套，不可只写 checklist 而不更新本表。

- 维护分支：`geely/main`
- 当前基线：`f16878e9`（merge: integrate EVCC CarPlay vehicle data）
- 最近更新：2026-10-12

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
| 有线/无线连接、配对、USB/热点引导 | ⬛ 继承 | n/a | `CarPlayController`/`CarPlayHostActivity` 上游通用链路；GeePlay 不改协议 |
| 视频解码与多 Surface、触控 | ⬛ 继承 | n/a | 上游 `MediaEngine`/stream 处理 |
| 音频媒体/导航流 | 🟡 部分对齐 | n/a（产品策略） | 媒体 owner 由 `GeelyMediaSettings`/`LocalMediaAudioPolicy` 决定；默认值待实车音频 |
| 产品身份（名称/图标/OEM label/更新源） | ✅ 已对齐 | n/a | `GeePlayApplication` 覆写 `VehicleIntegrationProvider`（`GEELY` 标签、独立更新源） |
| 上游 BYD 设置分区可见性 | ✅ 已对齐 | n/a | `LegacyBydSettingsPolicy` + `GeelyProductSettings.blockLegacyBydMenus`（默认隐藏，不删源码） |

## B. 导航与仪表显示

| # | 能力 | 状态 | Geely 实现方式 | 说明/证据 |
| ---: | --- | --- | --- | --- |
| 1 | 导航箭头/道路/距离/车道/路口到仪表 | ✅ 已对齐 | DIM | `GeelyVehicleSessionHooks`→`RouteGuidanceCoordinator`→`GeelyNavigationOutput`/`GeelyDimNavigationAdapter`；待实车 |
| 7 | 保持/不被原厂地图抢占仪表 | ✅ 已对齐（等价目标） | DIM | `GeelyNavigationDriver` 用 `notifyTurnByTurnStarted/Stopped` + cleanup journal 表达所有权，不禁用 OEM 包 |
| 2 | 挡风 HUD 导航输出 | 📝 待实现 | DIM→OEM-API | 先验证 DIM 是否自动分发到 HUD；否则 `GeelyHudNavigationOutput`（Spec §10.1） |
| 5 | CarPlay 仪表地图/私有 Display 路由 | 🧪 条件开放 | OEM-API/Display | `VehicleClusterDisplayPolicy` 已就位，`VALIDATED_PROFILES` 为空，仅 debug 选择（Spec §10.2） |
| 6 | 仪表地图流随导航模式暂停 | 🧪 条件开放 | EVCC-read→OEM-API | 已实现保守目录发现、`ClusterMapVisibilitySource` 与 session 控制；未发现可信属性时保持流开启（Spec §6） |
| 3 | 独立 HUD 广播输出 | 🚫 不迁移 | n/a | BYD `com.byd.clusterdebug` 私有广播；目标由 #1/#2 覆盖 |
| 4 | DiLink3 仪表模式/投屏准备 | 🚫 不迁移 | n/a | BYD `AutoContainer` 私有容器；Geely 走 DIM 或已验证 Display |

## C. 车辆遥测 → iPhone/CarPlay（EVCC 优先）

| # | 能力 | 状态 | Geely 实现方式 | 说明/证据 |
| ---: | --- | --- | --- | --- |
| 8 | 电池 SOC/续航/充电状态 | ✅ 已对齐 | EVCC-read | `EvccVehicleDataAdapter`；G636 已验证 SOC `ADAPT_API:1051136`、续航 `ADAPT_API:1054976`、充电 `ADAPT_API:605225728` |
| 9 | 车速 + P/R/N/D 挡位 | ✅ 已对齐 | EVCC-read | 车速 `ADAPT_API:1048832`（fallback `1055232`）、挡位 `CAPI:289408000`（`4=P`） |
| 10 | 仅驻车允许车内视频 | 🟡 部分对齐 | EVCC-read | 已实现 fail-closed `VehicleParkStateSource`、设置与通用 consumer；待 G636 P→D/R 实车（Spec §4） |
| 11 | 胎压/胎温侧边栏 | 🟡 部分对齐 | EVCC-read | 已实现保守目录发现、轮位/单位映射、`VehicleTyreTelemetrySource` 与侧边栏；待 G636 descriptor 实证（Spec §5） |
| 21 | 车辆属性发现 | ✅ 已对齐（发现层） | EVCC-read | `queryPropertyDirectory/resolve/read/subscribe` + source generation；生产用已验证 profile |

## D. 输入（方向盘/语音，非 EVCC）

| # | 能力 | 状态 | Geely 实现方式 | 说明/证据 |
| ---: | --- | --- | --- | --- |
| 15a | 媒体键（播放/暂停/上一首/下一首）+ Siri | ✅ 已对齐 | OneOS-input | `GeelyInputRuntime`/`OneOsWheelAdapter`→`VehicleHostControls` |
| 14 | 方向盘接听/挂断 CarPlay 电话 | 🧪 条件开放 | OneOS-input + host-cmd | 已扩展 host 命令、OneOS 注册和通话门禁；待 G636 raw keycode 实证（Spec §8） |
| 15b | 方向盘地图缩放、旋钮/摇杆 | 🧪 条件开放 | OneOS-input + host-cmd | 已接 `sendKnob`/`zoomDashboardMap` 和标准 keycode；待 G636 raw keycode 实证（Spec §8） |

## E. 厂商仪表渲染（需 Geely OEM API）

| # | 能力 | 状态 | Geely 实现方式 | 说明/证据 |
| ---: | --- | --- | --- | --- |
| 12 | CarPlay 歌曲显示到仪表 | 🧪 条件开放 | OEM-API | 已建立 `InstrumentMediaOutput`、通用解析和生命周期；Geely 无已验证 API 时注入 NONE（Spec §9） |
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
| aux | common/shared 直接引用 BYD 单例 | 🟡 部分对齐 | 解耦改造 | 驻车、胎压、氛围灯已换 product Interface；`CarPlayCallKeys`、旧 cluster 路径等仍待后续上游解耦 |
| aux | DiLink51 布局 / HomeScreenMonitor / UsbAutoConfirm / WifiScanPause / MapEmbed | 🚫 不迁移 / 🧪 条件开放 | 按需 product policy | 见评估 §3.1；无 Geely 证据前不迁移 |

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
