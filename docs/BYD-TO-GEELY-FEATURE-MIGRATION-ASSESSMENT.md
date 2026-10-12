# BYD 功能迁移到 Geely / GeePlay 的可行性与架构评估

状态：代码盘点与方案评估完成；未对齐 Spec 的品牌中立接口、EVCC 读/写/系统动作、设置和证据门禁已完成软件接线；无 G636 descriptor 或 Geely OEM API 的能力仍 fail-closed，本文不代表完成实车验证

评估日期：2026-10-12（基线于 2026-10-12 更新到 `geely/main` 合并后）

评估分支：`geely/main`（源自 `feat/evcc-carplay-vehicle-data`，已合并）

代码基线：`f16878e9`（merge: integrate EVCC CarPlay vehicle data；两个父提交为 `d566c2b0 feat: add menu block` 和 `c0b25edc feat: expand Geely vehicle and CarPlay integration`）

初始评估基线：`d1311cc1ca91`，结论未因合并发生实质变更；增量仅为 `d566c2b0` 的上游 BYD 菜单屏蔽（见 3.1）

目标车机：当前连接的 Geely G636

关联文档：

- [未对齐能力迁移到 Geely 的实施 Spec](BYD-TO-GEELY-UNALIGNED-CAPABILITIES-MIGRATION-SPEC.md)
- [EVCC 1.2 与 GeePlay CarPlay 车辆数据联动分析](EVCC-1.2-CARPLAY-INTEGRATION-ANALYSIS.md)
- [EVCC 1.2 → CarPlay/iPhone 车辆数据同步 Spec](EVCC-CARPLAY-VEHICLE-DATA-SYNC-SPEC.md)
- [EVCC 1.2 Siri 车辆控制分析](EVCC-1.2-SIRI-VEHICLE-CONTROL.md)
- [Geely 核心功能 Spec](GEELY-CORE-FEATURES-SPEC.md)
- [Geely 实施进度](GEELY-IMPLEMENTATION-PROGRESS.md)

官方 EVCC 依据：

- [开放 API](https://evcc.coauto.cc/docs/open-api/)
- [权限与授权](https://evcc.coauto.cc/docs/permissions/)
- [属性模型](https://evcc.coauto.cc/docs/properties/)
- [变量与动作](https://evcc.coauto.cc/docs/variables-actions/)
- [Shell 安全边界](https://evcc.coauto.cc/docs/shell/)

## 1. 结论摘要

BYD 功能不能按“把 ADB 命令换成 EVCC Shell”整体迁移。应按能力性质拆成四组：

1. **车辆属性读取**：电量、续航、充电、车速、挡位、驻车状态、胎压和胎温，适合由 EVCC typed property `read/listen` 替代 BYD `autoservice` ADB 读取。
2. **车辆属性写入**：氛围灯可能适合 EVCC typed property `write`，但前提是目标 G636 上发现精确的可写 descriptor、完成 `validateOnly`、写后回读和恢复测试；不能使用 EVCC Shell 代替。
3. **CarPlay 输入与厂商输出**：导航、方向盘键、仪表地图、歌曲和来电卡片依赖 CarPlay host 控制或 Geely OEM API，不属于 EVCC 车辆属性。导航、媒体键和 Siri 已有 Geely 原生路径；歌曲和来电卡片尚缺 Geely OEM 输出接口。
4. **系统集成**：蓝牙开关、权限授权、开机自启修复、包启停、私有 Display 路由和弹窗守护不是车辆属性。EVCC Shell 的永久黑名单、逐次确认和执行限制决定了它不能成为这些后台能力的可靠运行时。

当前最明确的迁移顺序是：

```text
已完成：导航元数据 → Geely DIM；电池/车速/挡位 → EVCC → CarPlay
下一批：EVCC Gateway 深化 → 驻车安全状态 → 胎压/胎温侧边栏
随后：Geely 输入扩展 → 通话键、地图缩放、旋钮/摇杆
条件能力：验证 Geely Presentation Display；验证 EVCC 氛围灯 typed write
暂缓：仪表歌曲、仪表来电卡片、挡风 HUD 专用输出，等待 Geely OEM API 证据
不迁移：BYD AutoContainer、autoservice 地址探测、BYD 包禁用、BYD 弹窗守护和演示工具
```

关键架构判断：现有 `CarPlayVehicleDataAdapter`、`NavigationOutput`、`VehicleSessionHooks`、`VehicleSettingsController` 和 `VehicleClusterDisplayPolicy` 已形成可复用 Seam。后续不应继续给 `VehicleSessionHooks` 塞入所有能力，也不应创建多个各自管理订阅的 EVCC client。应在 `:vehicle-geely` 内形成一个进程级、Deep 的 `EvccGateway` Module，再通过多个窄 Interface 向上提供只读遥测、驻车状态、胎压和经审核的写控制。

## 2. 评估口径

### 2.1 可行性等级

| 等级 | 含义 |
| --- | --- |
| 已支持 | 当前 GeePlay 已存在对应 Geely 实现；仍需看“实车验证”列确认是否完成端到端验证 |
| 高 | 已有通用 CarPlay/EVCC 能力，主要工作是增加品牌中立 Interface、映射和测试 |
| 中 | 产品目标可行，但必须发现并验证 Geely OEM API、EVCC writable property 或 Display 路由 |
| 低 | 没有可靠 Geely 接口，或能力需要高风险系统权限；不应承诺交付时间 |
| 不迁移 | BYD 实现或问题本身是车型特有的；仅保留产品目标，不复制实现 |

“已支持”表示代码路径存在，不等于在 G636、目标 iPhone/iOS、EVCC 3.2.17+ 和有线/无线两种连接上都已验收。

### 2.2 EVCC 可替代性的判断规则

- **可以**：目标是稳定、typed、可读/可监听的车辆属性；或经过车型 allowlist 的 typed property 写入。
- **条件可以**：必须先从运行时目录确认 source、property ID、area、类型、单位、访问级别和 source generation，并完成实车闭环。
- **不可以**：目标是 Android 系统设置、进程/包管理、Display 路由、厂商 Binder/广播渲染、长驻 helper 或任意命令执行。
- EVCC `executeAction()` 的稳定动作仅包括 `app.launch`、`home.show`、`toast.show` 和 `automation.trigger` 等公开动作，不是任意广播或厂商服务代理。
- EVCC Shell 永久拒绝 `app_process`/Java 等解释器入口、`nohup`、`setsid`、后台 `&`、控制流、命令替换、环境注入、任意重定向、`rm`/`cp`/`mv` 等通用文件操作。系统写入还要求 `shell.exec + shell.system_write`，并由车机端在约 30 秒内逐次确认。因此它不适合自动启动、会话生命周期和故障恢复。

## 3. 全量迁移矩阵

| # | BYD 功能 | 当前实现与依赖 | BYD 路径使用 ADB | EVCC 能否替代 | Geely 迁移路径 | 可行性 | 必须完成的验证 | 推荐 Module / Seam / Adapter |
| ---: | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 导航箭头、道路、距离、车道和路口信息到仪表 | `BydNavigationOutputs`、`BydHudRouteState`、`BydClusterBridge`、`BydFactoryNavigationOutput`、`BydAmapAdapter`；消费 iAP2 RouteGuidance，再调用 BYD 广播/SDK | 路由元数据本身否；部分显示准备另见 #4 | 否；EVCC 不负责 CarPlay 导航输出 | 已由 `GeelyVehicleSessionHooks` → `RouteGuidanceCoordinator` → `GeelyNavigationOutput`/`GeelyDimNavigationAdapter` 写入 ECARX DIM；不要移植 BYD AMap/SOME-IP 协议 | 已支持 | G636 上验证开始、更新、重算、车道、路口、结束、断连清理和 OEM 导航恢复 | 复用 `NavigationOutput` Seam；Geely DIM Adapter 留在 `:vehicle-geely` |
| 2 | 挡风玻璃 HUD 导航输出 | `BydHudBridge` 绑定 `SomeIpServerService`，发送 BYD HUD payload | 否 | 否 | 优先验证 Geely DIM 导航更新是否由 OEM 同步分发到 HUD；若不能，再寻找 Geely HUD 专用公开服务并实现新的 `NavigationOutput` Adapter | 中 | 有 HUD 的目标车型实测；确认清除、过期、重连和不残留旧箭头 | `NavigationOutput` + `GeelyHudNavigationOutput`；禁止复制 BYD SOME/IP 类 |
| 3 | 独立 HUD 广播输出 | `BydStandaloneHudOutput`/`BydStandaloneNavigationBridge` 向 `com.byd.clusterdebug` 显式广播 | 否 | 否 | BYD 广播没有 Geely 迁移价值；产品目标由 #1/#2 的 Geely OEM 输出覆盖 | 不迁移 | 仅当发现 Geely 官方等价广播并有车型文档时重新立项 | 不新增通用抽象；有证据后作为独立 `NavigationOutput` Adapter |
| 4 | DiLink 3 仪表模式/投屏 Display 准备 | `BydDiLink3ClusterOutput`、`BydAmapAdapter`，通过 `service call AutoContainer` 切模式和建 Display，并带恢复 journal | 是 | 否；Shell 不能成为自动模式切换方案 | 不迁移 AutoContainer。Geely 走 ECARX DIM 结构化导航，或走 #5 的已验证 Presentation Display | 不迁移 | 确认 G636 是否根本需要模式预切换；如果需要，必须找到 Geely 官方服务和对称清理 API | `VehicleClusterDisplayPolicy` 只负责选择 Display；OEM 模式控制应是单独 lease-based Adapter |
| 5 | CarPlay 仪表地图/私有 Display 路由 | `ClusterMapPresentation`、`AdbClusterRouter`、`AdbClusterActivity`；读取 `dumpsys display`，`am start-activity --display`，并检查 task 所在 Display；公开 Presentation 路径本身可不依赖 ADB | 混合 | 否 | 当前有 `VehicleClusterDisplayPolicy`。评估时工作树增加了显式 debug Display 选择，但 `GeelyClusterDisplayPolicy.VALIDATED_PROFILES` 仍为空，故只能算实验诊断，不是生产支持 | 中 | 记录 G636 Display 名称/尺寸/flags、alt stream 111 可见性、前后台、休眠、断连和进程死亡清理；验证不会覆盖安全仪表 UI | 复用 `VehicleClusterDisplayPolicy`；profile 必须按固件 allowlist，debug ID 不进入默认路径 |
| 6 | 根据方向盘导航模式暂停/恢复 iPhone 仪表地图流 | `BydClusterMapPause` 每秒 ADB 读取 `BydClusterNaviMode` 的 autoservice 值，再调用 `setClusterUiShown` | 是 | 条件可以；仅当 EVCC 暴露准确的“仪表地图当前可见/导航布局模式”只读属性 | Geely DIM 路径不需要控制视频流；只有启用 #5 时才需要。优先寻找 Display visibility/OEM callback，其次验证 EVCC typed property，未知状态必须保持流开启 | 中 | Small/Full/Off/仅箭头各模式；未知/过期数据；切换频率和重连 | 新增窄 `ClusterMapVisibilitySource`，由 Display/OEM/EVCC Adapter 实现；不要放进通用遥测 snapshot |
| 7 | 保持/禁用原厂地图，避免抢占仪表 | `BydOemClusterNavi`/`BydOemClusterHold` 用 `pm enable/disable/default-state` 管理 BYD 组件或包，并用 lease/journal 恢复 | 是 | 否；EVCC action 不能禁用包，Shell 系统写入需逐次确认 | 不复制包禁用。Geely DIM Driver 已通过 `notifyTurnByTurnStarted/Stopped` 和 cleanup journal 表达导航所有权；如仍冲突，应修正 OEM 导航生命周期而不是禁包 | 已支持（等价目标） | CarPlay 开始/结束、崩溃恢复、OEM 导航同时运行、服务死亡；确保 OEM 地图可恢复 | `GeelyNavigationDriver` 内保持 ownership lease；禁止 common 层直接管理 Geely 包 |
| 8 | 电池 SOC、续航、充电状态上送 iPhone | `BydBattery`/`BydBatteryStatus` 经 ADB `autoservice`，再生成 iAP2 `VehicleStatusUpdate` | 是 | 可以，typed read/listen | 已由 `EvccVehicleDataAdapter` 实现。G636 已验证 SOC `ADAPT_API:1051136`、续航 `ADAPT_API:1054976`、非充电值 `ADAPT_API:605225728=0` | 已支持 | EVCC 3.2.17+/API 1.2；真实充电值；低电量阈值；EVCC 重启/撤权；iPhone/Apple Maps 实际展示 | 现有 `CarPlayVehicleDataAdapter`；底层逐步提取为进程级 `EvccGateway` |
| 9 | 轮速和 P/R/N/D 挡位上送 iPhone | `BydWheelSpeedSource` 经 ADB `autoservice`，输出 LocationInformation 中 `$PASCD` | 是 | 可以，typed read/listen | 已由 EVCC 实现：车速 `ADAPT_API:1048832`，fallback `1055232`；挡位 `CAPI:289408000`，已验证 `4=P` | 已支持 | 行驶中确认 km/h→m/s；P/R/N/D 全状态；事件静默时主动 refresh；过期数据不得继续发送；iPhone dead reckoning | 现有 `CarPlayVehicleDataAdapter` + `VehicleSpeedSource`；共享同一 `EvccGateway` demand registry |
| 10 | 仅驻车时允许车内视频 | `BydParkedState` ADB 读取 gear；`CarPlayVideo` 当前仍直接引用 BYD 输出 | 是 | 可以，用已验证的 EVCC gear | 增加产品中立、fail-closed 的 `VehicleParkStateSource`，从同一 EVCC gear cache 读取；未知、过期、切源或撤权一律视为非驻车 | 高 | P→R/D 立即停止、源断开、EVCC 重启、缓存过期、应用恢复；同时评估法规和产品开关 | 独立 `VehicleParkStateSource` Seam；复用 `EvccGateway`，不要让 `CarPlayVideo` 直接依赖 EVCC 或 BYD |
| 11 | 胎压/胎温侧边栏 | `BydTyreStatus` 轮询 autoservice 的 tyre/instrument device；`CarPlayHostActivity` 直接读取 BYD singleton | 是 | 条件可以，typed read/listen 是正确路径 | 在 EVCC 1.2 运行时目录发现四轮 pressure/temperature descriptor，建立 area→FL/FR/RL/RR 和单位转换；侧边栏消费品牌中立 snapshot | 高（属性存在时） | 四轮位置、kPa/bar/℃、无效值、冷/热胎变化、备胎/缺失轮位、刷新与 staleness、撤权恢复 | 新增 `TyreTelemetrySource`；同一 `EvccGateway` 聚合订阅；UI 不接触 descriptor |
| 12 | CarPlay 歌曲显示到仪表 | `BydClusterSong` 消费 iAP2 Now Playing，再通过 ADB `CLASSPATH=<apk> app_process` 调 BYD instrument SDK | 是 | 不可以；EVCC music API 是音乐状态/控制能力，不等于向 OEM 仪表写卡片 | 保留通用 Now Playing 模型，寻找 Geely instrument/media card API；未发现前不做。中心屏已有 CarPlay 媒体显示不等于仪表支持 | 中偏低 | OEM API 的文本长度、来源图标、播放状态、清除、切源、睡眠和崩溃恢复 | 新增 `InstrumentMediaOutput` Interface；`GeelyInstrumentMediaOutput` Adapter；禁止复用 EVCC Shell |
| 13 | CarPlay 来电卡片显示到仪表/HUD | `BydCarPlayCall` 消费 call-state frame，用后台 `app_process` watcher 写 BYD instrument SDK | 是 | 不可以 | 需要 Geely telephony/instrument projection API；没有该 API 时只能保留 CarPlay 屏内来电 UI | 低 | 来电、接通、多路通话、挂断、联系人隐私、断连清除、系统电话与 CarPlay 电话冲突 | 新增 `InstrumentCallOutput`，与 call-state parser 分离；Geely Adapter 仅在有官方接口后实现 |
| 14 | 方向盘接听/挂断 CarPlay 电话 | `CarPlayCallKeys`、`WheelKeyService`、BYD keycode/挂断广播；启用 Accessibility 可能通过 ADB 恢复 secure setting | 混合 | 否；这是输入事件到 CarPlay telephony command | `CarPlayController.answerCall/endCall` 已存在。扩展 Geely OneOS/Flyme 输入 Adapter 识别通话键，并通过 host command Seam 调用；通话状态未知时不得吞键 | 高（能收到按键时） | G636 原始 keycode/gesture、来电/通话/非通话、短按/长按、重复事件、OEM 电话共存 | 扩展 `VehicleHostControls` 或提取 `CarPlayHostCommandPort`；车型 key map 留在 `:vehicle-geely` |
| 15 | 方向盘地图缩放、旋钮/摇杆、Siri、媒体键 | BYD Accessibility `WheelKeyService`；部分服务授权恢复使用 `settings put secure`；运行时调用 CarPlay knob/zoom/Siri/media API | 混合 | 否 | Geely Siri、上一首、下一首、播放/暂停已由 OneOS/Flyme Adapter 支持。缩放和 knob 需要扩展 `GeelyInputAction` 及 host command，复用 CarPlayController 现有 `sendKnob`/`zoomDashboardMap` | 部分已支持；其余高 | G636 所有按键 raw code、事件去重、长按、音量不被误吞、通话优先级、无 CarPlay 时透传 | `GeelyInputAdapter` → `CarPlayHostCommandPort`；设置由 `VehicleSettingsController` 注入，不复用 BYD Accessibility 配置 |
| 16 | CarPlay 会话期间暂停/恢复车机蓝牙 | `BydBluetoothSuspend` 用 ADB `svc bluetooth enable/disable`，带 owner lease 和恢复 journal | 是 | 否；A2DP/audio-focus 仲裁不是车辆属性，EVCC 非来源 | G636 已复现：手机同连车机蓝牙+CarPlay 时 CarPlay 音乐一播即停；仅应用内切 owner 不够，必须禁用车机 A2DP 媒体。升级为需支持，不照搬 ADB 关 radio：手动 `MediaOutputOwner` 音频源 + 切到 `CarPlay 播放` 时确认并自动打开“只断 A2DP media profile”避让（等价用户手动“禁用蓝牙媒体”，见未对齐 Spec §11A） | 需支持（手动+自动） | 切到 CarPlay 弹确认→自动开避让+请求蓝牙权限→点击播放不暂停；取消则保持车机蓝牙；owner=车机蓝牙（默认）不避让；拒权/失败不失声；HFP 通话不被误断；结束/撤权/重启后 A2DP 恢复；不改整机 radio | 复用 `MediaOutputOwner`/`LocalMediaAudioPolicy`；新增 `BluetoothAudioConflictPolicy`（默认 NONE，Geely 实现只断 A2DP）；经 `VehicleSessionHooks` 接线 |
| 17 | 随音乐变化的车内氛围灯 | `AmbientMusicController` + `BydAmbientLightClient/Worker`，ADB `app_process` 调 BYD setting API，并用 lease 保存/恢复 | 是 | 条件可以；只能走明确 allowlist 的 EVCC typed property write | 从 EVCC 目录确认前/后区颜色、亮度、区域和开关的 RW descriptor；单独申请 `property.write`，支持 validate、read-before-write、write、read-back、restore | 中 | 静止/行驶安全策略、所有区域映射、值域、写频率、限流、断连、撤权、崩溃后恢复、用户手动调灯冲突 | 新增业务级 `AmbientLightingPort` + 单独 `EvccVehicleControlPort`；不得给现有只读 `EvccClientPort` 加任意写能力 |
| 18 | 隐藏 BYD 原生电话弹窗 | `BydCallPopupGuard`/Java helper 使用 `nohup`、`setsid`、`app_process`、后台 `&`、临时状态和进程守护 | 是 | 明确不可以；这些构造多数被 EVCC Shell 永久拒绝 | BYD 特有问题不迁移。若 G636 实测有同类冲突，必须找 Geely 官方通信 UI policy 或让用户在 OEM 设置中选择 | 不迁移 | 只有复现后才评估；不得以杀进程、后台 helper 或覆盖系统电话 UI 作为默认方案 | 不建通用 catch-all；必要时增加 Geely-only `CommunicationUiPolicy` Adapter |
| 19 | 自动车机热点及权限授权 | `CarHotspotSetup` 用 Android hotspot 逻辑；BYD 上通过 ADB `appops set` 授予 `WRITE_SETTINGS`/overlay 等能力 | 是（授权部分） | 不可以；不是车辆属性，Shell 系统写入需要逐次确认 | 保留通用 hotspot 状态机；Geely 侧优先标准 Android Settings 授权、用户操作或 OEM Wi-Fi API。不要用 EVCC 替应用提权 | 中 | G636 hotspot API、权限页、开机后状态、CarPlay P2P 共存、Wi-Fi scan 恢复、失败回滚 | `HotspotController` 保持通用；授权策略由产品 Adapter 注入；Geely 不调用 `CarHotspotSetup.isBydHeadUnit` |
| 20 | 开机自启修复、doze/appops 白名单 | `BydBootStartRepair` 用 ADB `appops set`、`cmd deviceidle whitelist` 并检查结果 | 是 | 不可以作为自动能力；EVCC Shell 会进入逐次系统写确认 | 先验证 G636 是否丢 BOOT_COMPLETED/后台启动。优先 OEM 自启动设置页、标准电池优化豁免引导或签名 allowlist；不默认修改系统状态 | 低 | 冷启动、休眠唤醒、强停、OTA、用户撤销；设置必须可见且可逆 | 独立 `BootStartSupport` 诊断/引导 Module；不进入 CarPlay session hot path |
| 21 | ADB 能力探测与固件地址发现 | `BydVehicleCapabilityProbe`、`Byd13CatalogProbeMain`、`BydVehicleFields`；ADB `app_process` 枚举 BYD SDK，再试读 autoservice 地址 | 是 | 可以替代“车辆属性发现”，不能替代任意厂商服务逆向 | Geely 使用 EVCC `queryPropertyDirectory/resolve/read/subscribe` 和 source generation；`property.full_scan` 只放诊断工具，不作为每次启动流程。生产仍使用实车确认的 profile allowlist | 已支持（能力发现） | 新版 EVCC 1.2 目录、车型/固件变化、source generation、候选冲突、错误单位和错误语义 | 进程级 `EvccGateway` + `VehiclePropertyProfile`；诊断 discovery 与生产 profile 分离 |
| 22 | BYD-only 调试/演示工具 | `BydStarterBridge`、HUD demo/standalone packet demo、`Byd13CatalogProbeMain` 等为 BYD 协议开发、演示或探测入口 | 混合 | 否；也没有产品迁移价值 | 不进入 GeePlay 产品图。需要 Geely 调试时，重新基于 Geely Interface 写可删除的诊断工具，不引用 BYD packet/SDK | 不迁移 | 确认 release variant 不暴露危险入口；诊断输出不包含授权/隐私数据 | debug source set 或独立 tooling Module；禁止成为生产 fallback |

### 3.1 其余 BYD 假设与辅助路径

下面这些代码也包含 BYD 判断或命名，但不是上述 22 项之外的新车辆能力。它们仍需要纳入解耦计划，否则上游 merge 时会继续制造冲突。

> 基线更新（`d566c2b0 feat: add menu block`）：主分支已落地上游 BYD 菜单屏蔽机制，与本文“按能力位隐藏上游菜单、不删源码”的建议一致：
> - `LegacyBydSettingsPolicy.sections` 固定为 `BYD_ADB`、`BYD_NAVIGATION`、`CLUSTER_MAP`、`NAVIGATION_WHEEL`、`AMBIENT_LIGHTING`；
> - `GeelyProductSettings.blockLegacyBydMenus`（`geeplay_product` SharedPreferences，默认 `true`）控制是否隐藏；
> - `GeePlayApplication.legacyBydSettingsAvailable()` 返回 `!blockLegacyBydMenus`，`DiPlayActivity.filteredSection()` 据此跳过这些分区；
> - 这是“UI 可见性门”，不等于底层解耦。6.4 节列出的 common/shared 直接引用 BYD singleton 的问题仍然存在，仍需按本文方案逐步替换。

| 路径 | 当前 BYD 假设 | Geely 处理 |
| --- | --- | --- |
| `DiLink51ClusterLayout` / `DiLink51ClusterMonitor` | 按 DiLink 5.1 fingerprint、窗口和 Usage Access 调整中心屏/仪表布局 | 不迁移 fingerprint；Geely 尺寸和 Display 选择只通过 product display policy/profile 注入 |
| `HomeScreenMonitor` | 内置 BYD Launcher/MyCar/map-home package 列表 | 若 GeePlay 需要“主页在前”语义，新增 `VehicleHomeSurfacePolicy` 提供 Geely package/component；否则保持禁用 |
| `UsbAutoConfirmService` / setup guide | BYD 设置应用不响应标准 Android intent，需要私有设置页 fallback | Geely 只保留标准 intent；实车证明缺失后再增加 Geely-only settings route |
| `WifiScanPause` | 通用 P2P 恢复逻辑直接复用 `BydAdbShell` 执行系统命令 | 先验证 G636 是否需要；若需要则拆成 product `WifiConflictPolicy`，不能把 BYD shell client 当通用基础设施 |
| `MapEmbedService` / external map embedding | 上游为 BYD launcher/第三方 launcher 暴露集群地图 Surface | `GeePlayApplication.externalMapEmbeddingAvailable=false`；没有 Geely launcher consumer 时不迁移 |
| `VoiceFilter` 与部分 audio copy | 以 DiLink 音响特征解释低频过滤、stream 选择 | 算法可复用，但设置和默认值由 Geely `LocalMediaAudioPolicy`/实车结果决定，不能继承 BYD 默认 |
| OEM label/icon 与 setup 文案 | `BYD` 是上游默认 OEM 名称和车辆图标 | GeePlay 已通过 `VehicleIntegrationProvider` 替换为 `GEELY` 和独立资源；继续保持在 product composition |
| 上游 BYD 设置分区可见性 | 上游默认把 `BYD_ADB` 等 5 个分区直接展示 | 已由 `LegacyBydSettingsPolicy` + `GeelyProductSettings.blockLegacyBydMenus` 默认隐藏；底层仍需按 6.4 节解耦 |
| `DiPlaySessionService` / Activity cleanup | common 仍直接调用 `BydNavigationOutputs.endNow()` | 将 session cleanup 纳入已注入的 `VehicleSessionHooks.close/sessionEnded`，逐步移除 common 对 BYD singleton 的直接调用 |

## 4. 分组分析

### 4.1 导航与显示：迁移“语义”，不迁移 BYD 传输协议

BYD 与 Geely 真正可复用的是 Apple RouteGuidance 的语义模型，而不是输出协议：

```text
iAP2 0x5201 / 0x5202 / lane guidance
        ↓
AppleRouteGuidanceParser / RouteGuidanceCoordinator
        ↓
NavigationOutput
        ├── BYD AMap / SOME-IP / standalone broadcast
        └── Geely ECARX DIM
```

当前 Geely 路径已经采用正确方向：`GeelyVehicleSessionHooks` 把源选择、过期刷新和 session 生命周期交给品牌中立的 `RouteGuidanceCoordinator`，再由 `GeelyNavigationOutput` 管理 OEM Driver、清理和 recovery journal。这比把 `BydNavigationOutputs` 整体复制到 `:vehicle-geely` 更 Deep，且对上游改动更稳定。

后续应注意：

- BYD `BydHudRouteState`、maneuver code 和 packet 格式不能成为 shared 的通用模型。
- Geely DIM、HUD 和未来其他输出都实现 `NavigationOutput`，不要在 `CarPlayController` 中判断品牌。
- 仪表地图视频流与结构化导航数据是两条独立链路。G636 即使 DIM 箭头可用，也不代表 alt stream 111 已经能路由到物理仪表。
- 当前 `GeelyClusterDisplayPolicy.VALIDATED_PROFILES` 为空；显式 debug Display ID 只能用于人工验证，不能自动选择未知 Display。
- 任何会切换 OEM 模式的 Adapter 都要使用 owner token、对称 `clear/close` 和持久化 recovery journal，不能只“启动成功”而没有“恢复成功”。

### 4.2 车辆遥测：EVCC 是正确替代层

现有 `CarPlayVehicleDataAdapter` 已把上层 iAP2 数据面从 BYD ADB 解耦：

```text
CarPlayHostActivity / CarPlayController
        ↓ createSession()
CarPlayVehicleDataAdapter
        ├── BydVehicleDataAdapter → ADB autoservice
        └── EvccVehicleDataAdapter → EVCC Binder
```

现有设计中正确且必须保留的约束：

- Binder 工作只在 worker 上运行；iAP2 loop 只读内存 cache。
- `replaceSubscriptions()` 是整集合替换，所有 demand 必须统一聚合。
- source generation 变化、序列 gap、撤权和 EVCC 重启时清除旧数据。
- Identification 使用的 capability 在一个 CarPlay session 内冻结；中途 ready 留到下次连接，不动态改协议声明。
- provider/session close 只释放自己的 demand，不终止其他消费者。
- 缓存有 staleness 语义，不能把最后一次值永久当真。

要接驻车和胎压时，不应再创建 `EvccParkAdapter`、`EvccTyreAdapter` 各自持有一个 `EvccApiClient`。正确演进是先把 `EvccVehicleDataAdapter` 内的连接、授权、source state、resolve/read/subscribe、generation 和 demand registry 下沉到一个进程级 `EvccGateway`，再提供多种 typed consumer。

推荐的逻辑 Interface：

```kotlin
interface VehicleTelemetryGateway {
    fun acquire(owner: Any, signals: Set<VehicleSignal>): Closeable
    fun snapshot(signal: VehicleSignal): VehicleObservation?
}

interface VehicleParkStateSource {
    fun parked(maxAgeMillis: Long): Boolean?
}

interface TyreTelemetrySource {
    fun snapshot(maxAgeMillis: Long): TyreTelemetrySnapshot?
}
```

这里的 `VehicleObservation` 是品牌中立 typed value + unit + area + observedAt + sourceGeneration，不是 EVCC `Parcelable` 的透传。common/shared 不应依赖 EVCC Contract。

### 4.3 车辆写入：与只读 CarPlay 数据面彻底分离

氛围灯是当前唯一比较合理的 EVCC write 候选，但它不能顺手加进现有 `EvccClientPort`。原因：

- 当前端口刻意只读，权限只需要 `property.directory/read/listen`。
- 写入需要独立 `property.write` 授权、车型 allowlist、安全策略和审计。
- timeout 后结果不确定，不能盲目重试。
- 用户手动改灯与音乐控制可能竞争，需要 lease 和版本/回读判断。
- session 或进程异常后必须恢复用户原状态，而不是写一个默认值。

推荐分层：

```text
AmbientMusicController
        ↓ 业务颜色/亮度意图
AmbientLightingPort
        ↓ allowlist + rate limit + lease + restore
EvccVehicleControlPort
        ↓ validateOnly / write / read-back
Official EVCC 1.2 client
```

`EvccVehicleControlPort` 不向 UI 暴露任意 property ID，也不接受来自网络、CarPlay metadata 或 Siri 文本直接拼装 selector。只有经过 G636/固件验证的 `VehicleCommandCatalog` 可以创建写请求。

### 4.4 输入：复用 CarPlay host 命令，不依赖 EVCC

Geely 当前输入链路已支持媒体键和 Siri：

```text
OneOS / Flyme event
    → GeelyInputAdapter
    → GeelyInputAction
    → VehicleHostControls
    → CarPlayController
```

`CarPlayController` 已经有 `sendKnob()`、`zoomDashboardMap()`、`answerCall()` 和 `endCall()`，但当前 `VehicleHostControls` 只暴露媒体键、Siri、通信状态和 cluster UI。因此通话键、地图缩放和 knob 的主体工作不是协议实现，而是把这些已有命令通过品牌中立 Seam 暴露给 Geely input。

建议两种低冲突方案，优先第一种：

1. 新建 `CarPlayHostCommandPort`，由 `VehicleSessionHooks.attachHostControls()` 一次注入；按媒体、导航、通话三个 capability 子接口组织。
2. 若希望最小 diff，则给 `VehicleHostControls` 增加有默认实现的 `answerCall/endCall/sendKnob/zoomDashboardMap` 方法，避免破坏上游实现。

按键 raw code、gesture、去重和车型 profile 必须留在 `:vehicle-geely`。common 只理解业务动作，不能出现 G636 keycode，也不能引用 BYD `CarPlayCallKeyPolicy`。

### 4.5 OEM 仪表卡片：EVCC 不能代替输出协议

仪表歌曲和来电卡片的 BYD 实现看似是“车辆写入”，实际是“向 BYD 仪表渲染模型写字段”。EVCC 的 music API 即使可以读取歌曲或控制播放，也没有证据表明它会把 CarPlay Now Playing 渲染到 Geely 仪表。

因此应拆成：

- shared：解析并保存 CarPlay `NowPlayingSnapshot` / `CallSnapshot`；
- common：只管理与 CarPlay session 一致的生命周期；
- vehicle-geely：如果找到官方 OEM API，实现 `InstrumentMediaOutput` / `InstrumentCallOutput`；
- 没有 OEM API 时返回 `NONE`，不启用设置项，不使用 EVCC Shell 或反射猜测写入。

### 4.6 系统集成：只在 Geely 实际复现问题后立项

BYD 的蓝牙暂停、开机修复、原厂地图禁用和电话弹窗抑制都是对特定 DiLink 行为的补丁。直接迁移会给 G636 引入不存在的问题和不可恢复的系统副作用。

处理顺序应是：

1. 在 G636 复现具体问题并记录系统版本、触发条件和恢复方式。
2. 优先使用标准 Android API、用户可见设置或 Geely 官方服务。
3. 把策略放在 Geely-only Adapter，默认实现为 no-op。
4. 所有改变系统状态的能力都要有 owner lease、read-before-write、写后确认和 crash recovery。
5. EVCC Shell 只可作为人工诊断工具候选，不可成为自动产品链路。

## 5. ADB 依赖与 EVCC 替代清单

### 5.1 应被 EVCC typed property 替代

| BYD ADB 读取 | 当前命令/机制 | Geely 替代 |
| --- | --- | --- |
| SOC/续航/充电 | `service call autoservice ...` + `getprop ro.car.protocol/sys.car.protocol` | 已实现 EVCC property read/listen |
| 车速/挡位 | `autoservice` device `1013/1011` | 已实现 EVCC property read/listen |
| 驻车状态 | 挡位 `P` 的同步 ADB 读取 | 复用 EVCC gear cache，增加 safety-specific Interface |
| 胎压/胎温 | `autoservice` device `1016/1007` | EVCC runtime directory + typed read/listen，待发现 selector |
| 仪表布局模式 | `autoservice` navi-mode field | 仅在 EVCC 有精确只读属性时可用，否则改用 OEM/Display callback |

### 5.2 不能由 EVCC 替代

| BYD ADB 操作 | 原因 | Geely 策略 |
| --- | --- | --- |
| `service call AutoContainer` | BYD 私有显示容器，不是车辆属性 | 不迁移；使用 Geely DIM 或经验证的 Presentation Display |
| `dumpsys display` + `am start --display` | Android Display/task 路由 | 使用 `VehicleClusterDisplayPolicy` 和公开 Display；生产 profile allowlist |
| `pm enable/disable/default-state` | 包/组件管理 | 不禁用 OEM 包；用 OEM navigation ownership API |
| `svc bluetooth` | 系统 radio 状态 | 不迁移此做法；#16 已在 G636 复现，改用媒体 owner + 只断 A2DP profile 的仲裁（Spec §11A），不关整机 radio |
| `appops set`、`deviceidle whitelist`、`settings put secure` | 系统授权和安全设置 | 用户可见设置、标准授权或 OEM allowlist |
| `app_process` 仪表歌曲/来电/氛围灯 | BYD 私有 SDK + shell identity | 歌曲/来电找 Geely OEM output；氛围灯只考虑 typed EVCC write |
| `nohup`、`setsid`、后台 `&` helper | 长驻进程/守护 | 不迁移；EVCC Shell 明确拒绝这些构造 |
| BYD catalog/feature ID 反射 | BYD 固件内部地址 | EVCC 目录用于 Geely 属性发现；生产使用已验证 profile |

## 6. 推荐目标架构

### 6.1 Module 与依赖方向

```text
:shared
  CarPlay protocol + product-neutral models/interfaces
      ↑
:common
  UI/session composition; only consumes interfaces
      ↑
:vehicle-geely
  Geely DIM/Input/Display adapters
  EvccGateway + Geely property profiles
      ↑
:geely
  GeePlayApplication composition root + Geely settings/resources

EVCC official contract/client ───────> :vehicle-geely only
Geely OEM/Binder/reflection details ─> :vehicle-geely only
BYD autoservice/app_process details ─> existing BYD implementation only
```

这一依赖方向具备 Locality：品牌变化集中在对应 vehicle Module；CarPlay 协议变化集中在 shared；设置与产品装配集中在 product Module。上游新增 CarPlay 能力时，Geely 只需实现新增 Interface 或继续使用默认 no-op，不需要反复解决散落的 `if (isGeely)` 冲突。

### 6.2 Process-level `EvccGateway`

`EvccGateway` 应是 Deep Module：接口小，内部吸收复杂性。它至少负责：

- 单个官方 `EvccApiClient` 生命周期、Binder death 和重连退避；
- API 1.2、contract、service version 和授权校验；
- directory/resolve/read/replace-all subscription；
- 所有消费者 demand 的并集与 owner release；
- event sequence、resync、source state/source generation；
- typed conversion、单位/area 映射、staleness 和 invalidation；
- sanitized diagnostics；
- 只读权限与写权限的物理分离。

推荐拆成：

```text
EvccConnectionCore
  ├── EvccReadGateway       property.directory/read/listen
  │     ├── CarPlayVehicleDataAdapter
  │     ├── VehicleParkStateSource
  │     └── TyreTelemetrySource
  └── EvccControlGateway    property.write，默认不创建
        └── AmbientLightingPort
```

不要让每个业务 Adapter 直接拿官方 client，也不要让多个 Adapter 分别调用 `replaceSubscriptions()`。

### 6.3 现有 Seam 的保留与深化

| 现有 Seam | 评价 | 建议 |
| --- | --- | --- |
| `CarPlayVehicleDataAdapter` | Deep、边界清晰，已经有 BYD/EVCC 两个 Implementation | 保留只读；驻车/胎压不要强塞进 `CarPlayVehicleDataSession` |
| `NavigationOutput` | 可复用度和 Leverage 高 | 所有 Geely HUD/DIM 导航输出都实现它 |
| `VehicleSessionHooks` | 生命周期有用，但能力正在变宽 | 只保留 session/source 生命周期；新业务通过组合的专用 ports 注入 |
| `VehicleHostControls` | 已有有效 host command Seam，但命令不全 | 提取/扩展通话、knob、zoom capability |
| `VehicleClusterDisplayPolicy` | 选择策略清晰 | 保持 fail-closed；profile 与 debug override 分离 |
| `VehicleSettingsController` | 产品设置 Locality 良好 | Geely 设置只由 `:vehicle-geely` 贡献；实验项进入 Advanced 并标注下次连接生效 |

### 6.4 需要清除的 BYD 直接耦合

当前 common/shared 仍有多处直接引用 BYD singleton，这是后续上游合并的主要摩擦点：

- `CarPlayHostActivity` 直接访问 BYD 胎压、cluster navi mode、turn overlay、call popup guard；
- `CarPlayVideo` 直接调用 `BydNavigationOutputs.parked()`；
- `CarPlayCallKeys`/`WheelKeyService` 直接读取 `BydOutputSettings` 和 BYD call state；
- `AdbClusterRouter`/`AdbClusterActivity` 直接管理 `BydOemClusterNavi`；
- `AmbientMusicController` 直接构造 `BydAmbientLightClient`；
- `AndroidMediaSink` 直接调用 `BydBluetoothSuspend`；
- `CarPlayGlance`/`CarPlayNavigationOverlay` 仍引用以 `Byd` 命名的通用 route/frame 类型。

建议采用“先建 Interface，再移动 composition”的小步改造：

1. 在 shared 定义真正品牌中立的 model/port。
2. 保留 BYD Implementation，不做大规模重写。
3. 在 DiPlay 默认 composition 中装配 BYD Adapter，在 `GeePlayApplication` 中装配 Geely Adapter。
4. common 只取 composition，不再做 `provider ?: BydSingleton` 的多处分支。
5. 每移除一个直接耦合，补一个 default/no-op 和 contract test，保证上游未适配时仍可编译运行。

不要只把 `BydNavigationOutputs` 改名为通用类而保留内部 BYD SDK；那只是 Shallow wrapper，会隐藏而不是消除耦合。

## 7. 上游可持续性策略

### 7.1 改动原则

- shared/common 的改动只增加稳定 Interface、品牌中立模型和 composition point。
- 所有 EVCC、Geely package、Binder descriptor、property ID、keycode 和固件 fingerprint 留在 `:vehicle-geely`。
- 所有 Geely 设置卡片通过 `VehicleSettingsController` 注入，不修改 BYD 设置卡内容。
- 不在 `CarPlayController`、`CarPlayHostActivity`、iAP2 encoder 中散落 `isGeely`、包名判断或 property ID。
- 对上游新方法优先提供 default no-op，避免每次 merge 同时修改全部产品 Adapter。
- 使用 capability presence 决定 UI 和 runtime，不以“安装了 EVCC”直接推断某项能力可用。

### 7.2 生命周期契约

每个 Adapter 都应满足：

- `start/attach` 幂等；
- callback 快速返回，vendor I/O 在自己的 worker；
- `close` 幂等且反向释放；
- old generation 的 late callback 不得更新新 session；
- 改变 OEM 状态前保存原值，成功后再写 journal；
- 异常、进程重启和 source generation 变化时可恢复；
- 不因某个附加功能失败而阻断 CarPlay 主连接。

### 7.3 测试契约

建议为每个 Interface 建 contract test，而不是只测具体实现：

- telemetry：订阅并集、owner release、过期、source change、sequence gap；
- parked：unknown/stale 必须 fail closed；
- tyres：area/单位映射、部分轮位和 staleness；
- input：按键去重、通话优先级、无 session 透传；
- navigation output：publish/clear/close 顺序和 source takeover；
- display policy：未知固件/多个匹配/默认 Display 一律拒绝；
- write control：allowlist、validate、read-back、timeout 不重试、lease restore；
- settings：Geely 卡片只出现在正确分类，实验项翻译齐全并正确提示重连。

## 8. 分阶段实施建议

### Phase 0：保持当前已完成链路稳定

- 在 EVCC 3.2.17+/API 1.2 上重新验证当前 G636 profile。
- 完成 SOC/续航/充电、车速/P/R/N/D 到 iPhone 的端到端验收。
- 保持 `EvccClientPort` 只读，不在此阶段引入 write/Shell。

### Phase 1：提取进程级 EVCC Gateway

- 从 `EvccVehicleDataAdapter` 抽出连接、权限、source generation、resolve、subscription 和 demand registry。
- 保持现有 `CarPlayVehicleDataAdapter` 行为与设置不变。
- 增加多 consumer 测试，证明 battery、motion、park、tyres 不会互相覆盖订阅。

### Phase 2：驻车状态与胎压

- 新增 `VehicleParkStateSource`，改造 `CarPlayVideo` 去除 BYD 直接依赖。
- 发现 G636 胎压/胎温 descriptor，建立车型 profile、单位与 wheel area 映射。
- 新增 `TyreTelemetrySource`，改造侧边栏只消费 typed snapshot。
- 两项都在设置页标记实验性并提供诊断，不自动开启。

### Phase 3：Geely 方向盘完整控制

- 通过现有诊断 profile 记录 G636 通话、方向键、确认/返回等 raw event。
- 扩展 host command Seam，接入 answer/end、knob、zoom。
- 无 CarPlay session、通话状态不匹配或 action 未启用时，按键必须留给 OEM。

### Phase 4：仪表地图 Display 实车验证

- 使用当前显式 debug Display 选择器验证，不建立自动 profile。
- 完成前后台、休眠、重连、异常清理和安全 UI 不被覆盖的记录后，才把 fingerprint/display tuple 加入 `VALIDATED_PROFILES`。
- 无法证明安全和清理时，继续只用 DIM 结构化导航。

### Phase 5：氛围灯 typed write PoC

- 只做 G636 单车型实验，先目录发现和只读观察，再 validate-only，再单次写/回读/恢复。
- 新建独立写端口、权限和设置；不复用 EVCC Shell。
- 通过限频、lease 和用户手动操作冲突测试后，才接音乐节奏。

### Phase 6：按证据增加 OEM 输出

- 只有找到 Geely 官方 instrument/HUD API 后才实现歌曲、来电卡片或专用 HUD。
- 每种输出各自实现窄 Interface，不建立一个包含导航、歌曲、电话、灯光的 `GeelyEverythingBridge`。
- 蓝牙、开机修复、弹窗抑制继续按实际问题单独立项。

## 9. G636 实车验证清单

### EVCC 遥测

- EVCC 3.2.17+ 返回 API 1.2，授权包含 `property.directory/read/listen`。
- 当前 5 个 selector 的 source、type、area、unit 和 access 与 profile 一致。
- 充电插入/拔出；SOC 和续航变化；车速 0/低速/高速；P/R/N/D 全挡位。
- EVCC 强停、升级、撤权、重新授权、source generation 变化和事件 sequence gap。
- 有线/无线 CarPlay 下 iPhone 实际请求 VehicleStatus/LocationInformation 并消费数据。

### 导航与仪表

- 路线开始、连续 maneuver、车道、路口图、道路名截断、重算、短暂无 maneuver、路线结束。
- DIM 服务死亡/重连；CarPlay 被替换；应用被划掉；车机休眠后恢复。
- debug Presentation Display 的 id/name/size/flags、stream 111、触摸隔离、主屏不受影响。
- 未知 Display、默认 Display 和多个候选时 fail closed。

### 输入

- OneOS/Flyme 每个按键的 down/up/short/long/double 事件和重复次数。
- 音量键、媒体键、Siri、接听、挂断、方向/确认/返回在有/无 CarPlay 和通话中行为。
- OEM 电话、CarPlay 电话和普通媒体场景不互相吞键。

### 驻车与胎压

- 驻车状态过期、EVCC 离线或 selector 异常时视频不可播放。
- 从 P 切出立即关闭视频，不等待下一次 30 秒轮询。
- 四轮胎压/胎温位置与实车一致，单位转换正确，缺失/故障轮位不会显示旧值。

### 写控制

- writable descriptor 与具体车型/固件绑定，不使用名称模糊匹配或 `AUTO` source。
- 写前读取、validate-only、写入、回读、用户手动改值和 restore 全部有记录。
- timeout/断连不盲重试；撤权后立即停止；进程死亡后能恢复或明确报告 cleanup pending。

## 10. 明确不做的事

- 不把 EVCC Shell 当成 BYD ADB 的通用替代层。
- 不把 EVCC 的 W/RW 目录自动生成 GeePlay 或 CarPlay 控件。
- 不把 BYD `autoservice` device/feature ID、AutoContainer、SOME/IP 或广播协议带入 Geely Module。
- 不因安装了 EVCC 就申请 `shell.exec`、`shell.system_write` 或 `property.full_scan`。
- 不让 tyres、park、ambient light 各自创建独立 EVCC client。
- 不把车辆写入加入当前只读的 CarPlay vehicle-data port。
- 不在未知固件上自动选择仪表 Display，也不以 debug Display ID 作为生产 profile。
- 不默认关闭蓝牙、禁用 OEM 包、修改 appops/secure settings 或启动后台 helper。
- 不把 BYD demo/probe 代码作为 Geely 运行时 fallback。

## 11. 最终建议

短期应把精力集中在三件可验证、高 Leverage 的工作：

1. 在新版 EVCC 1.2 上完成当前电池/车速/挡位端到端验收。
2. 把现有 EVCC 连接核心深化为单实例 `EvccGateway`，随后接入 fail-closed 驻车状态和胎压。
3. 扩展品牌中立 host command Seam，让 Geely OneOS/Flyme 输入直接复用现有 CarPlay answer/end/knob/zoom 能力。

集群视频、HUD、歌曲和来电卡片应采用“先拿到 Geely OEM 接口证据，再写 Adapter”的策略。系统补丁类功能则采用“先在 G636 复现问题，再决定是否需要产品能力”的策略。这样既能保留当前 GeePlay 的快速演进，也能把与上游的冲突压缩在少量稳定 composition point 上。

未对齐能力（本文矩阵中“高/中/低/条件”各项）的逐项落地方案，见 [BYD-TO-GEELY-UNALIGNED-CAPABILITIES-MIGRATION-SPEC.md](BYD-TO-GEELY-UNALIGNED-CAPABILITIES-MIGRATION-SPEC.md)。该 Spec 以 EVCC 作为首选能力来源（车辆属性读/写、Shell、Action、音量组），只有 EVCC 契约不支持时才回退到 Geely OEM API 或品牌中立 host 命令。

## 12. 主要代码证据索引

品牌中立边界：

- [`CarPlayVehicleData.kt`](../shared/src/main/java/com/shilapi/xcertplay/vehicle/CarPlayVehicleData.kt)
- [`VehicleSessionHooks.kt`](../shared/src/main/java/com/shilapi/xcertplay/vehicle/VehicleSessionHooks.kt)
- [`VehicleSettings.kt`](../shared/src/main/java/com/shilapi/xcertplay/vehicle/VehicleSettings.kt)
- [`VehicleClusterDisplay.kt`](../shared/src/main/java/com/shilapi/xcertplay/vehicle/VehicleClusterDisplay.kt)
- [`RouteGuidanceCoordinator.kt`](../shared/src/main/java/com/shilapi/xcertplay/navigation/runtime/RouteGuidanceCoordinator.kt)

Geely 实现：

- [`GeePlayApplication.kt`](../geely/src/main/java/com/lllhys/geeplay/GeePlayApplication.kt)
- [`GeelyVehicleSessionHooks.kt`](../vehicle-geely/src/main/java/com/lllhys/geeplay/vehicle/geely/GeelyVehicleSessionHooks.kt)
- [`GeelyNavigationOutput.kt`](../vehicle-geely/src/main/java/com/lllhys/geeplay/vehicle/geely/GeelyNavigationOutput.kt)
- [`GeelyDimNavigationAdapter.kt`](../vehicle-geely/src/main/java/com/lllhys/geeplay/vehicle/geely/GeelyDimNavigationAdapter.kt)
- [`GeelyVehicleInput.kt`](../vehicle-geely/src/main/java/com/lllhys/geeplay/vehicle/geely/GeelyVehicleInput.kt)
- [`GeelyClusterDisplayPolicy.kt`](../vehicle-geely/src/main/java/com/lllhys/geeplay/vehicle/geely/GeelyClusterDisplayPolicy.kt)
- [`EvccVehicleDataAdapter.kt`](../vehicle-geely/src/main/java/com/lllhys/geeplay/vehicle/geely/evcc/EvccVehicleDataAdapter.kt)
- [`EvccVehicleTelemetry.kt`](../vehicle-geely/src/main/java/com/lllhys/geeplay/vehicle/geely/evcc/EvccVehicleTelemetry.kt)
- [`EvccClientPort.kt`](../vehicle-geely/src/main/java/com/lllhys/geeplay/vehicle/geely/evcc/EvccClientPort.kt)
- [`EvccDemandRegistry.kt`](../vehicle-geely/src/main/java/com/lllhys/geeplay/vehicle/geely/evcc/EvccDemandRegistry.kt)

BYD 车辆属性与系统补丁：

- [`BydVehicleDataAdapter.kt`](../shared/src/main/java/com/shilapi/xcertplay/hud/BydVehicleDataAdapter.kt)
- [`BydBattery.kt`](../shared/src/main/java/com/shilapi/xcertplay/hud/BydBattery.kt)
- [`BydWheelSpeed.kt`](../shared/src/main/java/com/shilapi/xcertplay/hud/BydWheelSpeed.kt)
- [`BydParkedState.kt`](../shared/src/main/java/com/shilapi/xcertplay/hud/BydParkedState.kt)
- [`BydTyres.kt`](../shared/src/main/java/com/shilapi/xcertplay/hud/BydTyres.kt)
- [`BydBluetoothSuspend.kt`](../shared/src/main/java/com/shilapi/xcertplay/hud/BydBluetoothSuspend.kt)
- [`BydBootStartRepair.kt`](../shared/src/main/java/com/shilapi/xcertplay/hud/BydBootStartRepair.kt)
- [`BydCallPopupGuard.kt`](../shared/src/main/java/com/shilapi/xcertplay/hud/BydCallPopupGuard.kt)

BYD 导航、仪表和灯光：

- [`BydNavigationOutputs.kt`](../shared/src/main/java/com/shilapi/xcertplay/hud/BydNavigationOutputs.kt)
- [`BydVehicleSessionHooks.kt`](../shared/src/main/java/com/shilapi/xcertplay/hud/BydVehicleSessionHooks.kt)
- [`BydHudBridge.kt`](../shared/src/main/java/com/shilapi/xcertplay/hud/BydHudBridge.kt)
- [`BydClusterBridge.kt`](../shared/src/main/java/com/shilapi/xcertplay/hud/BydClusterBridge.kt)
- [`BydStandaloneHudOutput.kt`](../shared/src/main/java/com/shilapi/xcertplay/hud/BydStandaloneHudOutput.kt)
- [`BydDiLink3ClusterOutput.kt`](../shared/src/main/java/com/shilapi/xcertplay/hud/BydDiLink3ClusterOutput.kt)
- [`BydOemClusterNavi.kt`](../shared/src/main/java/com/shilapi/xcertplay/hud/BydOemClusterNavi.kt)
- [`BydClusterMapPause.kt`](../shared/src/main/java/com/shilapi/xcertplay/hud/BydClusterMapPause.kt)
- [`BydClusterSong.kt`](../shared/src/main/java/com/shilapi/xcertplay/hud/BydClusterSong.kt)
- [`BydCarPlayCall.kt`](../shared/src/main/java/com/shilapi/xcertplay/hud/BydCarPlayCall.kt)
- [`BydAmbientLightClient.kt`](../shared/src/main/java/com/shilapi/xcertplay/hud/BydAmbientLightClient.kt)
- [`BydAmbientLightWorker.kt`](../shared/src/main/java/com/shilapi/xcertplay/hud/BydAmbientLightWorker.kt)

common 层耦合入口：

- [`CarPlayHostActivity.kt`](../common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt)
- [`CarPlayVideo.kt`](../common/src/main/java/com/shilapi/xcertplay/CarPlayVideo.kt)
- [`CarPlayCallKeys.kt`](../common/src/main/java/com/shilapi/xcertplay/CarPlayCallKeys.kt)
- [`WheelKeyService.kt`](../common/src/main/java/com/shilapi/xcertplay/WheelKeyService.kt)
- [`AdbClusterRouter.kt`](../common/src/main/java/com/shilapi/xcertplay/AdbClusterRouter.kt)
- [`CarHotspotSetup.kt`](../common/src/main/java/com/shilapi/xcertplay/CarHotspotSetup.kt)
