# 未对齐能力迁移到 Geely 的实施 Spec（EVCC 优先）

状态：软件方案已实施；已验证 selector 继续可用，胎压/仪表布局/氛围灯/OEM 仪表输出及 A2DP 自动避让仍按运行时证据 fail-closed，尚未完成 G636 端到端实车验收

日期：2026-10-12

分支：`geely/main`

代码基线：`f16878e9`（merge: integrate EVCC CarPlay vehicle data）

目标车机：Geely G636（EVCC 目标 1.2 / EVCC 3.2.17+）

父文档：[BYD 功能迁移到 Geely / GeePlay 的可行性与架构评估](BYD-TO-GEELY-FEATURE-MIGRATION-ASSESSMENT.md)

相关文档：

- [EVCC 1.2 → CarPlay/iPhone 车辆数据同步 Spec](EVCC-CARPLAY-VEHICLE-DATA-SYNC-SPEC.md)
- [EVCC 1.2 Siri 车辆控制分析](EVCC-1.2-SIRI-VEHICLE-CONTROL.md)
- [EVCC 1.2 与 GeePlay CarPlay 车辆数据联动分析](EVCC-1.2-CARPLAY-INTEGRATION-ANALYSIS.md)

EVCC 契约证据（仓库内）：

- `third_party/evccapi/evccapi-contract/src/main/aidl/com/kooo/evcc/evccapi/IEvccApiSession.aidl`
- `third_party/evccapi/evccapi-contract/src/main/java/com/kooo/evcc/evccapi/EvccApiContract.kt`
- `third_party/evccapi/evccapi-client/src/main/java/com/kooo/evcc/evccapi/client/EvccApiClient.kt`

## 1. 范围与目标

本 Spec 覆盖父评估矩阵中所有尚未端到端对齐的 BYD 能力（可行性非“已支持”，或仅“部分已支持”）：

| 能力 | 父矩阵编号 | 父矩阵可行性 |
| --- | ---: | --- |
| 挡风 HUD 导航输出 | 2 | 中 |
| 独立 HUD 广播输出 | 3 | 不迁移 |
| DiLink3 仪表模式/投屏准备 | 4 | 不迁移 |
| CarPlay 仪表地图/私有 Display 路由 | 5 | 中 |
| 仪表地图流随导航模式暂停 | 6 | 中 |
| 仅驻车允许车内视频 | 10 | 高 |
| 胎压/胎温侧边栏 | 11 | 高 |
| 仪表歌曲卡片 | 12 | 中偏低 |
| 仪表来电卡片 | 13 | 低 |
| 方向盘接听/挂断 | 14 | 高 |
| 方向盘地图缩放/旋钮/摇杆 | 15 | 部分已支持 |
| 车机蓝牙与 CarPlay 音频抢占仲裁 | 16 | 中（G636 已复现，升级为需支持） |
| 音乐氛围灯 | 17 | 中 |
| 隐藏原生来电弹窗 | 18 | 不迁移 |
| 自动热点与权限授权 | 19 | 中 |
| 开机自启修复 | 20 | 低 |

另含一项前置工程项：把现有 EVCC 连接核心提取为进程级 `EvccGateway`（父文档 6.2 / Phase 1）。它是本 Spec 多数能力的共同依赖，必须先做。

目标：

1. **EVCC 优先**：每个能力都先尝试用 EVCC 能力来源实现，依次为 typed property（read/listen/write）→ `executeAction` → `executeShell`（含 `shell.system_write`），只有 EVCC 契约在该能力上不可用时才回退 Geely OEM API 或品牌中立 host 命令。
2. 保持上游可合并：`:shared`/`:common` 只增稳定 Interface 和品牌中立模型，EVCC/Geely/固件细节只在 `:vehicle-geely`。
3. 每个写/系统类能力都 fail-closed、可审计、可恢复，不默认开启。

## 2. EVCC 能力来源与优先级

### 2.1 EVCC 会话方法（证据）

`IEvccApiSession` 可用方法（节选，均为同步 Binder，须在 worker 调用）：

| 方法 | 用途 | 所需权限 |
| --- | --- | --- |
| `queryCapabilities()` | 判定动作/能力是否可用 | 会话级 |
| `queryPropertyDirectory(EvccPageRequest)` | 目录发现（`DIRECTORY`） | `property.directory` |
| `resolveProperty` / `readProperty` / `readProperties` | 解析与读取 | `property.read` |
| `replaceSubscriptions(List<EvccPropertySelector>)` | 整集合替换订阅 | `property.listen`(+`read`) |
| `writeProperty` / `writeProperties(EvccWriteRequest)` | typed 写（支持 `validateOnly`） | `property.write` |
| `executeAction(EvccActionRequest)` | 稳定动作（`validateOnly` 可选） | `action.safe` / `action.privileged` |
| `executeShell(EvccShellRequest)` | 受限 Shell，异步回调 `onShellCompleted` | `shell.exec`(+`shell.system_write`) |
| `cancel(String requestId)` | 取消待确认/执行中任务 | 会话级 |
| `queryVolumeGroups` / `setVolumeGroup` / `adjustVolumeGroup` | 音量组（apiMinor≥2） | `volume.read` / `volume.control` |

权限常量见 `EvccApiContract.Permission`；写值上限 `MAX_WRITE_VALUE_CHARS = 16KiB`；订阅上限 `MAX_PROPERTY_SUBSCRIPTIONS = 256`；Binder 负载上限 `MAX_BINDER_PAYLOAD_BYTES = 512KiB`。

### 2.2 EVCC-first 决策树（每个能力都按此判定）

```text
1. 能否表达为稳定 typed 车辆属性？
   读/监听 → property.read + property.listen
   写      → property.write（validateOnly → write → read-back → restore）
2. 否则，能否映射到稳定 Action？
   executeAction(app.launch/home.show/toast.show/automation.trigger)
3. 否则，是否“系统设置/属性/受支持的应用或服务状态/进程控制”且可接受车机端逐次确认？
   executeShell（+ shell.system_write，车机 ~30s 确认，PENDING→onShellCompleted）
4. 否则（渲染/私有显示/长驻守护/被永久黑名单拒绝的构造）：
   回退 Geely OEM API 或品牌中立 host 命令；EVCC 不作为该能力来源
```

### 2.3 EVCC Shell 的硬性边界（来自 `EvccShellRequest`/官方 Shell 文档）

把 Shell 作为能力来源时必须接受：

- `executeShell` 先返回 `ACCEPTED`/`PENDING`，最终结果走 `EvccApiClient.Listener.onShellCompleted(EvccShellResult)`；不能当同步调用。
- 命令 ≤4096 字符，超时 1–30s（默认 10s），同一 UID 同时只允许一个待确认/执行中 Shell，重复提交 `RATE_LIMITED`。
- 系统写入须同时持 `shell.exec` + `shell.system_write`，并由用户在车机端约 30s 内逐次确认；超时/拒绝不执行；授权应用不能替用户确认。
- 永久拒绝（任何授权都不放行）：`app_process`/Java 等解释器入口、`nohup`/`setsid`/`busybox`/`toybox`、后台 `&`、`$()`/反引号、变量展开与环境注入、`eval`/`sh -c`/`su -c`、重定向、`cp`/`mv`/`rm`/`ln`/`tee`/`dd`/归档/下载器、here-doc、子 shell、命令分组、`&&`/`||`/管道隐藏第二段命令、可写目录中的可执行文件。
- 结论：Shell 适合“一次性、可被确认的系统设置/状态读写”，不适合自动启动、长驻守护、会话级连续控制或任何渲染。因此本 Spec 中 Shell 只用于少量系统集成能力的**人工确认动作**，不进入 CarPlay 会话热路径。

### 2.4 回退顺序

当 EVCC 不是合适来源时，回退优先级：

1. 品牌中立 CarPlay host 命令（输入类，如接听/挂断/缩放/旋钮）。
2. Geely OEM 官方 API（渲染类，如仪表卡片/HUD/私有 Display）。
3. 标准 Android API + 用户可见设置（系统类，如权限、热点、自启）。

## 3. 前置工程：进程级 `EvccGateway`

### 3.1 现状

`EvccVehicleDataAdapter`（`:vehicle-geely/.../evcc/`）已经独立拥有：单个 `EvccApiClient`、授权校验（`REQUIRED_PERMISSIONS`）、`EvccDemandRegistry`（replace-all 聚合）、`EvccVehicleTelemetryStore`（typed 转换/缓存/staleness/source generation）、`EvccClientPort`（刻意只读）。这是正确基础，但目前这些能力只服务电池/车速/挡位，且连接核心与业务消费耦合在一个类里。

### 3.2 目标结构

```text
EvccConnectionCore              (单 EvccApiClient、重连、授权、apiInfo、source state)
  ├── EvccReadGateway           property.directory/read/listen；持 EvccDemandRegistry
  │     ├── CarPlayVehicleDataAdapter   (电池/车速/挡位，已存在)
  │     ├── VehicleParkStateSource      (#10)
  │     ├── TyreTelemetrySource         (#11)
  │     └── ClusterLayoutSource         (#6，若 EVCC 有只读属性)
  └── EvccControlGateway        property.write / executeAction / executeShell，默认不创建
        ├── AmbientLightingPort         (#17)
        └── EvccSystemActionPort        (#19/#20 的 Shell 确认动作，若采用)
```

### 3.3 约束

- 全进程仅一个 `EvccApiClient`；所有消费者通过 `EvccDemandRegistry` 请求逻辑信号，禁止各自调用 `replaceSubscriptions()`。
- `EvccReadGateway` 与 `EvccControlGateway` 物理分离：只读消费者拿不到任何写/Shell 入口。
- `EvccControlGateway` 懒创建，仅在用户显式开启写能力并授权 `property.write`（或 `shell.exec`+`shell.system_write`）后实例化。
- Gateway 由 `GeePlayApplication` 持有（已持有 `EvccVehicleDataAdapter`），不随每个 `VehicleSessionHooks`/CarPlay session 重建。
- 回调快速返回；typed 转换、缓存发布在 worker；绝不在 EVCC callback 线程写 iAP2 channel。

### 3.4 工作量

- 从 `EvccVehicleDataAdapter` 抽出 `EvccConnectionCore` + `EvccReadGateway`，`EvccVehicleDataAdapter` 变成 `EvccReadGateway` 的一个消费者。
- `EvccClientPort` 保持只读；写路径走新增、独立的 `EvccControlPort`（见 §12）。
- 补多消费者测试：battery/motion/park/tyre 并存时订阅为并集、owner release 不互相清除。

## 4. 能力 #10 仅驻车允许车内视频（EVCC 属性，高）

### 4.1 EVCC-first 判定

命中决策树第 1 步：驻车=挡位 `P`，已验证 EVCC 可读属性 `GEAR = CAPI:289408000`（`4=PARK`）。无需新属性，复用已订阅的 gear 信号。

### 4.2 Interface（`:shared`）

```kotlin
interface VehicleParkStateSource {
    /** true=驻车；false=非驻车；null=未知。调用方须 fail-closed 处理 null。 */
    fun parked(maxAgeMillis: Long): Boolean?
}
```

### 4.3 实现（`:vehicle-geely`）

- `EvccReadGateway` 增加 `GEAR` 为可被 park 消费者请求的信号（已在 `MOTION_SIGNALS`，park 作为新 owner demand `GEAR`）。
- `EvccParkStateSource` 读取 `EvccVehicleTelemetryStore` 的 gear observation：
  - gear == `PARK` 且 `now - observedAt <= maxAgeMillis` → `true`；
  - gear 为其他有效值 → `false`；
  - gear 缺失/过期/source generation 不一致/撤权 → `null`。

### 4.4 接线（去 BYD 耦合）

- `common/.../CarPlayVideo.kt` 当前直接 `BydNavigationOutputs.parked()`。改为通过 `VehicleIntegrationProvider` 取 `VehicleParkStateSource`，DiPlay 默认用 BYD 适配，GeePlay 用 EVCC 适配。
- `null`（未知）必须按“非驻车”处理，禁止播放。

### 4.5 验证

P→R/D 立即停止（≤1 个 iAP2 周期，不等 30s 轮询）、EVCC 离线/撤权/重启=null=禁止、缓存过期=禁止、法规与产品开关评估。

## 5. 能力 #11 胎压/胎温侧边栏（EVCC 属性，高）

### 5.1 EVCC-first 判定

命中第 1 步，但 selector 未知：需先用 `queryPropertyDirectory`（`property.directory`）在 G636 运行时目录发现四轮 pressure/temperature descriptor。父评估的 BYD device `1016/1007` 不可移植。

### 5.2 发现与 profile

- 诊断期用 `queryPropertyDirectory(DIRECTORY)` 枚举，按 `EvccPropertyDescriptor.areaType/areaId/name/dataType/access` 识别 FL/FR/RL/RR 的压力与温度。
- 生产用固定 `VehiclePropertyProfile`（source + canonicalId + areaId + 单位 + scale），不在启动流程做 `full_scan`。
- 单位：压力归一 kPa（兼容 bar/psi 源），温度归一 ℃；由 profile 指定换算。

### 5.3 Interface（`:shared`）

```kotlin
enum class WheelPosition { FRONT_LEFT, FRONT_RIGHT, REAR_LEFT, REAR_RIGHT }

data class TyreReading(val pressureKpa: Double?, val temperatureC: Double?, val observedAtElapsed: Long)
data class TyreTelemetrySnapshot(val wheels: Map<WheelPosition, TyreReading>)

interface TyreTelemetrySource {
    fun snapshot(maxAgeMillis: Long): TyreTelemetrySnapshot?
}
```

### 5.4 实现与接线

- 新增逻辑信号 `TYRE_PRESSURE_*` / `TYRE_TEMP_*`（或按轮位聚合）进 `EvccReadGateway` demand。
- `EvccTyreTelemetrySource` 从 telemetry store 读取并转 typed snapshot。
- `common/.../CarPlayHostActivity.kt` 当前直接 `BydTyreStatus`；改为经 provider 取 `TyreTelemetrySource`，side panel 只消费品牌中立 snapshot，不接触 descriptor。

### 5.5 验证

四轮位置与实车一致、kPa/bar/psi→kPa、无效/缺失/备胎轮位不显示旧值、冷热胎变化、staleness、撤权恢复。

## 6. 能力 #6 仪表地图流随导航模式暂停（EVCC 属性优先，中）

### 6.1 EVCC-first 判定

仅当启用 #5 私有 Display 投屏时才需要。目标是“仪表当前是否显示地图/导航布局模式”。

- 第 1 步：若 EVCC 目录存在稳定只读属性表达“仪表导航布局/地图可见”（`property.read/listen`），用它。
- 否则回退 Display 可见性或 Geely OEM callback。BYD 的 `autoservice` navi-mode 不可移植。

### 6.2 Interface（`:shared`）

```kotlin
interface ClusterMapVisibilitySource {
    /** true=仪表在显示地图；false=未显示；null=未知（须保持地图流开启）。 */
    fun mapShownOnCluster(): Boolean?
}
```

### 6.3 实现

- 优先 `EvccClusterLayoutSource`（EVCC 只读属性）；否则 `GeelyDisplayVisibilitySource`（Display 可见性/OEM callback）。
- 未知一律保持流开启（与 BYD 行为一致）。
- 由 `VehicleSessionHooks`/host 侧消费，调用现有 `VehicleHostControls.setClusterUiShown()`，不新增通用 snapshot 字段。

### 6.4 验证

Small/Full/Off/仅箭头各模式、未知/过期保持开启、切换频率、重连。

## 7. 能力 #17 音乐氛围灯（EVCC typed write，中）

### 7.1 EVCC-first 判定

命中第 1 步的“写”：目标是车内氛围灯颜色/亮度/区域/开关。只能走 EVCC `property.write`（经 allowlist），严禁 EVCC Shell、严禁复制 BYD `app_process`。

### 7.2 写闭环（强约束）

```text
目录发现可写 descriptor（access 含写）
  → readProperty 保存原值
  → writeProperty(validateOnly=true)
  → writeProperty 实写
  → readProperty 回读校验
  → 会话结束/异常/撤权时 restore 原值
```

- 单独申请 `property.write`，与只读会话隔离（见 §12 `EvccControlGateway`）。
- `EvccWriteRequest(selector, EvccValue.ofLong/ofDouble/ofBoolean, validateOnly)`；值 ≤16KiB。
- `TIMEOUT`/Binder 异常=结果不确定，不盲重试；写用唯一 `requestId`，必要时 `cancel`。
- 行驶中安全策略、写频率限流、lease、与用户手动调灯冲突处理。

### 7.3 Interface（`:shared` 业务层 / `:vehicle-geely` 实现）

```kotlin
interface AmbientLightingPort {
    fun apply(intent: AmbientLightingIntent): AmbientLightingResult   // 节奏/颜色意图
    fun restore()                                                     // 回到用户原状态
}
```

- `AmbientMusicController`（`:shared`）当前直接 `BydAmbientLightClient`；改为依赖 `AmbientLightingPort`，GeePlay 注入 `EvccAmbientLightingPort`，DiPlay 注入 BYD 实现。
- `EvccAmbientLightingPort` 内部用 `EvccControlPort`（§12），维护 allowlist（source+id+area+值域）、read-before-write、read-back、lease、restore。

### 7.4 验证

全区域映射、值域、静止/行驶策略、限流、断连、撤权、崩溃恢复、用户手动改灯优先。

## 8. 能力 #14/#15 方向盘输入（CarPlay host 命令，非 EVCC）

### 8.1 EVCC-first 判定

命中决策树第 4 步：这是“输入事件 → CarPlay telephony/knob/zoom/Siri 命令”，不是车辆属性，EVCC 不是来源。复用已有 CarPlay host 能力。

证据：`CarPlayController` 已有 `answerCall()`、`endCall()`、`sendKnob()`、`zoomDashboardMap()`、`requestSiri()`；`VehicleHostControls` 目前只暴露 `setClusterUiShown/sendMediaButton/requestSiri/isCommunicationActive`；Geely 已有 `GeelyInputRuntime`/`OneOsWheelAdapter`（媒体键+Siri）。

### 8.2 扩展 host 命令 Seam（`:shared`）

两方案，优先 A：

- 方案 A：新增 `CarPlayHostCommandPort`，按 capability 分组（media / navigation / telephony），由 `attachHostControls` 一次注入。
- 方案 B（最小 diff）：给 `VehicleHostControls` 增加带默认实现的方法：

```kotlin
interface VehicleHostControls {
    fun setClusterUiShown(shown: Boolean)
    fun sendMediaButton(index: Int): Boolean
    fun requestSiri(): Boolean
    fun isCommunicationActive(): Boolean
    fun answerCall(): Boolean = false
    fun endCall(): Boolean = false
    fun zoomDashboardMap(zoomIn: Boolean): Boolean = false
    fun sendKnob(wheel: Int, select: Boolean, back: Boolean): Boolean = false
}
```

默认实现保证上游其他产品不受影响，`CarPlayController` 的匿名实现委托到已有方法。

### 8.3 Geely 侧（`:vehicle-geely`）

- 扩展 `GeelyInputAction`：`ANSWER`、`END`、`ZOOM_IN`、`ZOOM_OUT`、`KNOB_PREV/NEXT/SELECT/BACK`。
- `GeelyInputMapping` 按 G636 实测 raw code/gesture 映射；车型 key map 留在 `:vehicle-geely`，不复用 BYD `CarPlayCallKeyPolicy`/`WheelKeyService` 的 BYD keycode。
- 门禁：无 CarPlay session、`isCommunicationActive` 与动作不匹配、动作未在设置开启时，按键必须留给 OEM（return false，不吞键）。

### 8.4 验证

G636 每个按键 down/up/short/long/double、来电/通话/非通话、音量不被误吞、OEM 电话与 CarPlay 电话共存、无 session 透传。

## 9. 能力 #12/#13 仪表歌曲 / 来电卡片（Geely OEM 输出，非 EVCC）

### 9.1 EVCC-first 判定

命中决策树第 4 步：目标是“向 Geely 仪表渲染模型写卡片”。EVCC music API 是音乐状态/控制，不等于向仪表写卡片；EVCC 无渲染来源。需 Geely 官方 instrument/telephony projection API；无 API 则不做（返回 `NONE`、不启用设置）。严禁 EVCC Shell 或反射猜测。

### 9.2 Interface（`:shared`，有 API 才实现 Adapter）

```kotlin
interface InstrumentMediaOutput { fun publish(now: NowPlayingSnapshot?); fun clear() }
interface InstrumentCallOutput  { fun publish(call: CallSnapshot?); fun clear() }
```

- `:shared` 负责从 iAP2 解析 `NowPlayingSnapshot`/`CallSnapshot`（品牌中立，复用现有 parser）。
- `:common` 只管理与 CarPlay session 一致的生命周期。
- `:vehicle-geely` 仅在找到官方 API 后实现 `GeelyInstrumentMediaOutput`/`GeelyInstrumentCallOutput`；否则 `GeePlayApplication` 不提供，设置不出现。

### 9.3 验证

文本长度/来源图标/播放状态/清除/切源/睡眠/崩溃恢复（歌曲）；来电/接通/多路/挂断/联系人隐私/断连清除/系统电话冲突（来电）。

## 10. 能力 #2/#5 HUD 与仪表地图投屏（Geely OEM / Display，非 EVCC）

### 10.1 #2 挡风 HUD 导航输出（中）

- EVCC-first 判定：第 4 步，渲染输出，非 EVCC。
- 先验证 Geely DIM 结构化导航是否由 OEM 自动分发到 HUD（已有 `GeelyNavigationOutput`/`GeelyDimNavigationAdapter`）。若不自动，再找 Geely HUD 专用公开服务，实现新的 `NavigationOutput` Adapter（`GeelyHudNavigationOutput`）。禁止复制 BYD SOME/IP 类。
- 验证：有 HUD 车型实测，清除/过期/重连/不残留旧箭头。

### 10.2 #5 CarPlay 仪表地图 / 私有 Display 路由（中）

- EVCC-first 判定：第 4 步，Display 路由，非 EVCC。
- 复用 `VehicleClusterDisplayPolicy`。当前 `GeelyClusterDisplayPolicy.VALIDATED_PROFILES` 为空，仅有 debug Display 选择，属实验诊断。
- 生产 profile 必须按固件 fingerprint + display name/size/flags allowlist；debug ID 不进入默认路径。
- 验证：记录 G636 Display 的 id/name/size/flags、alt stream 111 可见性、前后台、休眠、断连、进程死亡清理、不覆盖安全仪表 UI 后，才把 tuple 加入 `VALIDATED_PROFILES`。

## 11. 能力 #19/#20 系统集成（标准 Android/用户设置优先，EVCC Shell 仅作可选确认动作）

### 11.1 #19 自动热点与权限授权（中）

- EVCC-first 判定：授权（`appops set WRITE_SETTINGS` 等）属“系统设置写入”，理论上命中决策树第 3 步，可由 `executeShell`+`shell.system_write` + 车机逐次确认完成一次性授权；但热点状态机本身不是 EVCC 能力。
- 推荐：`HotspotController` 保持通用；Geely 侧优先标准 Android `Settings.System.canWrite`/`Settings.canDrawOverlays` 的用户授权页；仅当用户选择“用 EVCC 代为授权”时，才用 `EvccSystemActionPort.requestSystemWrite(...)` 发一条可被车机确认的 `appops` 授权命令（单条、无链式、无重定向）。
- 不默认、不静默；Geely 不调用 `CarHotspotSetup.isBydHeadUnit`。
- 验证：G636 hotspot API、权限页、开机后状态、CarPlay P2P 共存、Wi-Fi scan 恢复、失败回滚；Shell 路径验证确认窗口、超时拒绝、`RATE_LIMITED`。

### 11.2 #20 开机自启修复、doze/appops 白名单（低）

- EVCC-first 判定：同 #19，属系统写入，`executeShell`+`shell.system_write` 技术上可发 `appops set` / `cmd deviceidle whitelist` 单条确认命令。
- 推荐：先验证 G636 是否真丢 `BOOT_COMPLETED`/后台启动；优先 OEM 自启动设置页、标准电池优化豁免引导或签名 allowlist。
- 若确需，封装为 `BootStartSupport` 诊断/引导 Module，命令经 `EvccSystemActionPort`（单条、用户确认），不进 CarPlay 会话热路径，设置可见可逆。
- 验证：冷启动、休眠唤醒、强停、OTA、用户撤销。

### 11.3 EVCC Shell 作为系统能力来源的实现要求

`EvccSystemActionPort`（`:vehicle-geely`，属 `EvccControlGateway`）：

- 维护固定命令 allowlist（只允许少量 `appops set <pkg> <op> allow`、`cmd deviceidle whitelist +<pkg>` 形式），参数来自本地常量，不拼接外部输入。
- 每条命令必须单段：无 `&&`/`||`/`|`/`;`、无重定向、无后台 `&`、无子 shell。
- `executeShell` 异步：发 `requestId` → 等 `onShellCompleted` → 校验 `exitCode`/`stdout`；`PENDING` 表示等待车机确认。
- 超时/拒绝/`RATE_LIMITED` 都要有明确 UI 反馈；绝不重试系统写入。
- 全部为用户显式触发的一次性动作，禁止在启动或 session 回调里自动调用。

## 11A. 能力 #16 车机蓝牙与 CarPlay 音频抢占仲裁（手动 + 自动）

### 11A.1 问题定性（G636 已复现）

现象：手机同时连「车机经典蓝牙」和「CarPlay」时，CarPlay 音乐一点播放就立即暂停。

G636 实测的两条可用恢复路径：

1. **让车机蓝牙播放**（用户当前采用的解法）：把音频源切到车机蓝牙，由车机原生蓝牙唯一拥有媒体，乒乓消失，但此时放的是车机蓝牙音乐，不是 CarPlay。
2. **让 CarPlay 播放**：仅在 DiPlay/应用里切音频源**不足以**解决；必须额外到车机蓝牙设置里把「蓝牙音乐/媒体（A2DP）」禁用，CarPlay 才能播放。

根因：同一手机的 A2DP 媒体流同时存在两个消费者（车机原生蓝牙音乐 + CarPlay），两边争夺 audio focus / AVRCP 控制权，产生 play/pause 乒乓。

关键结论（决定方案）：**“让 CarPlay 出声”的真正开关是断开/禁用车机侧的 A2DP 媒体 profile，而不是只在应用内切 owner。** 用户手动做的“在蓝牙设置里禁用蓝牙媒体”，正是自动层要替用户完成的动作。

与 BYD 既有机制的关系（证据）：

- `MediaOutputOwner` / `LocalMediaAudioPolicy`（`shared/.../media/LocalMediaAudioPolicy.kt`）：决定音乐由 DiPlay 本地渲染（`DIPLAY`，占 MediaSession）还是交给车机蓝牙（`OEM_BLUETOOTH`，不本地渲染）。它表达“谁来播”的意图，但不负责断开车机 A2DP，因此在 G636 上单独切到 `DIPLAY` 仍会被车机 A2DP 抢占。
- `BydBluetoothSuspend`（`shared/.../hud/BydBluetoothSuspend.kt`）：用 ADB `svc bluetooth disable` 直接关车机蓝牙 radio，`bt_suspend_during_carplay` 文案表明它主要为“让来电只在 CarPlay 响”，是通话路由解法，不是媒体抢占解法。

因此 #16 从父评估的“🚫 不迁移”升级为“需支持”，实现**不照搬** `BydBluetoothSuspend` 的 ADB 关整机 radio，而是精确地**只断 A2DP 媒体 profile**。

### 11A.2 EVCC-first 判定

命中决策树第 4 步：这是 Android A2DP / audio-focus / AVRCP 仲裁，不是车辆属性，EVCC 不是能力来源。采用品牌中立媒体策略 + 标准 Android `BluetoothProfile`，不走 EVCC、不走 ADB radio 开关。

### 11A.3 方案：手动 owner 开关 + 自动仲裁，两层

两层能力都做，自动可被用户关掉退回纯手动。

**第 1 层 手动音频源（已有后端，补 Geely 设置可见性）**

- 复用 `GeelyMediaSettings.loadOwner/saveOwner` + `LocalMediaAudioPolicy`。
- 在 `GeelyVehicleSettingsController` 增加明确的“音乐音频源”选择：
  - `车机蓝牙播放`（= `MediaOutputOwner.OEM_BLUETOOTH`，DiPlay 不渲染、不占 MediaSession，车机蓝牙唯一拥有；对应 G636 恢复路径 1，当前用户用的就是这个，默认值）；
  - `CarPlay 播放`（= `MediaOutputOwner.DIPLAY`，本地渲染并占 MediaSession）。
- G636 已证明：选 `CarPlay 播放` 时，若不释放车机 A2DP，仍会被抢占。因此**选择 `CarPlay 播放` 时弹确认对话框，用户确认后同时自动打开第 2 层“自动避让车机蓝牙媒体”**（见 §11A.3 第 2 层与 §11A.5 流程）。
- 文案须说明该选项解决“蓝牙与 CarPlay 争用导致音乐一播即停”，并标注生效时机（`NEXT_CONNECTION` 或 `REFRESH_SETTINGS`）。
- 这一层不依赖任何权限，是 fail-safe 基线：自动层不可用时，用户永远能手动切到车机蓝牙，或手动去蓝牙设置禁用蓝牙媒体。

**第 2 层 自动仲裁（默认关；切到 `CarPlay 播放` 并确认后自动打开）**

目标：CarPlay 媒体会话取得 owner=`DIPLAY` 时，自动完成用户原本要手动做的“禁用车机蓝牙媒体”——断开车机经典蓝牙的 A2DP 媒体 profile，使 CarPlay 成为唯一媒体渲染者；CarPlay 结束时自动恢复。

开关默认值与联动：

- 自动避让开关默认 `关`（因为默认 owner 是 `车机蓝牙播放`，该模式下避让不触发，默认关最省事、不提前要权限）。
- 用户把“音乐音频源”切到 `CarPlay 播放` 并在确认对话框点确认时，`saveOwner(DIPLAY)` 的同时 `saveBluetoothAutoAvoid(true)`，自动打开避让；确认框文案说明将在 CarPlay 期间自动禁用蓝牙媒体、需要蓝牙权限、通话不受影响、CarPlay 结束自动恢复。
- 用户仍可在切到 `CarPlay 播放` 后单独关掉避让（回到“需手动在蓝牙设置禁用蓝牙媒体”）。
- 切回 `车机蓝牙播放` 时不强制改避让开关值，但避让在该 owner 下不生效。

新增品牌中立接缝（`:shared`）：

```kotlin
enum class BluetoothMediaArbitration { NONE, DISCONNECT_A2DP_DURING_CARPLAY }

interface BluetoothAudioConflictPolicy {
    /** CarPlay 媒体会话开始、owner=DIPLAY 且避让开启时调用；返回是否已避让。 */
    fun onCarPlayMediaAcquired(): Boolean
    /** CarPlay 会话结束/owner 变化时调用，恢复原状态。 */
    fun release()
    companion object { val NONE = object : BluetoothAudioConflictPolicy {
        override fun onCarPlayMediaAcquired() = false
        override fun release() {}
    } }
}
```

Geely 实现（`:vehicle-geely`，`GeelyBluetoothAudioConflictPolicy`）：

- 仅断 **A2DP media profile**（等价于用户手动在蓝牙设置里“禁用蓝牙媒体”），不关整机蓝牙 radio、不碰 HFP/通话、不动配对。
  - 用 `BluetoothProfile.A2DP` + `getConnectedDevices()`；对手机设备调用隐藏 `BluetoothA2dp.disconnect(device)`（反射），失败则降级为仅请求 `AudioManager` audio-focus 独占并提示用户手动在蓝牙设置禁用蓝牙媒体。
  - 需要 `BLUETOOTH_CONNECT`（Android 12+）；确认开启避让时请求权限，拒绝授权则自动层不可用，回退第 1 层手动并提示。
- 带 owner lease + journal：记录“曾由我们断开 A2DP 的设备”，CarPlay 结束 / owner 改为 `OEM_BLUETOOTH` / 进程重启 / 撤权时，`release()` 重连 A2DP，恢复用户原状态。
- fail-closed：任何一步失败都不得让用户“既没蓝牙又没 CarPlay 声音”；失败即回到“未避让”并提示手动切音频源或手动禁用蓝牙媒体。
- 仅在 owner=`DIPLAY` 且避让开启时触发；`OEM_BLUETOOTH`（默认）下不避让。

### 11A.4 接线

- `GeePlayApplication` 提供 `BluetoothAudioConflictPolicy`（owner=`DIPLAY` 且避让开启时为 `GeelyBluetoothAudioConflictPolicy`，否则 `NONE`）；DiPlay 默认 `NONE`，不改上游行为。
- 由 `VehicleSessionHooks`（会话生命周期）在 `carPlaySessionStarted`（且 owner=`DIPLAY`、避让开启）调用 `onCarPlayMediaAcquired()`，在 `carPlaySessionEnded`/`close` 调用 `release()`。
- 严禁进入 iAP2 control loop 热路径；蓝牙操作在自己的 worker。

### 11A.5 设置与默认值

- 默认 owner 维持现状 `MediaOutputOwner.OEM_BLUETOOTH`（= G636 当前可用的“车机蓝牙播放”，开箱不抢占）。
- 自动避让开关默认 `关`；由“切到 `CarPlay 播放` 的确认”联动打开（见下）。
- `VehicleSettingsPlacement.AUDIO`：
  - “音乐音频源”选择（第 1 层，始终可见）：`车机蓝牙播放` / `CarPlay 播放`。
    - 选 `CarPlay 播放` 使用 `VehicleSettingRow.Choice` 的 `confirmations`（参考 `geely_cluster_debug_display` 已有用法）弹确认框；确认回调里 `GeelyMediaSettings.saveOwner(DIPLAY)` + `saveBluetoothAutoAvoid(true)` 并请求 `BLUETOOTH_CONNECT`。
    - 确认框文案：将在 CarPlay 连接期间自动禁用车机蓝牙媒体（A2DP），不关整机蓝牙，通话不受影响，CarPlay 结束后自动恢复；需要蓝牙权限。
  - “自动避让车机蓝牙媒体（实验性）”开关（第 2 层，`(experimental)`/`（实验性）` 命名）：显示当前联动结果，允许用户在 `CarPlay 播放` 下单独关闭；仅在 owner=`CarPlay 播放` 时生效。
- 新增持久化 `GeelyMediaSettings.bluetoothAutoAvoid`（`geeplay_geely_media`，默认 false）。
- 字符串写入 `vehicle-geely/src/main/res/values/` 及全部 `values-*`。

### 11A.6 验证（G636）

- 切到 `CarPlay 播放` → 弹确认框 → 确认后避让自动打开并请求蓝牙权限：点击播放不再立即暂停（自动断 A2DP 生效）；CarPlay 结束后车机蓝牙 A2DP 自动恢复，车机蓝牙音乐可用。
- 切到 `CarPlay 播放` → 确认后手动关掉避让 → 复现一播即停，且设置提示用户手动在蓝牙设置禁用蓝牙媒体（与现状手动解法一致）。
- 确认框点取消 → owner 保持 `车机蓝牙播放`，避让不打开。
- owner=`车机蓝牙播放`（默认）：不避让，车机蓝牙音乐正常，CarPlay 不抢。
- 拒绝 `BLUETOOTH_CONNECT` / `disconnect` 反射失败：不崩、不静默失声，提示手动切或手动禁用蓝牙媒体。
- 来电（HFP/SCO）在避让期间仍可用，不被误断（只断 A2DP，不断 HFP、不关 radio）。
- 进程重启、撤权、CarPlay 异常断开后 A2DP 能恢复，或明确报告 pending。
- 不迁移 `BydBluetoothSuspend` 的 ADB `svc bluetooth disable`；确认 Geely 路径不改整机 radio。

## 12. 能力 #18/#3/#4 不迁移项的处理

这些在 EVCC-first 下仍判定为“非 EVCC 来源且不默认迁移”：

| 能力 | 原因 | Geely 处理 |
| --- | --- | --- |
| #18 隐藏原生来电弹窗 | 依赖 `nohup`/`setsid`/`app_process`/后台守护，被 EVCC Shell 永久拒绝 | 不迁移；若复现冲突，找 Geely 通信 UI policy 或让用户在 OEM 设置选择；`CommunicationUiPolicy` 默认 no-op |
| #3 独立 HUD 广播 | BYD `com.byd.clusterdebug` 私有广播 | 不迁移；目标由 #2 Geely OEM 输出覆盖 |
| #4 DiLink3 仪表模式/投屏 | BYD `AutoContainer` 私有容器 | 不迁移；Geely 走 DIM 结构化导航或 #5 已验证 Display |

> #16（车机蓝牙与 CarPlay 音频抢占）已从“不迁移”升级为需支持，方案见 §11A；不复用 `BydBluetoothSuspend` 的 ADB 关 radio 做法。

## 13. 写/控制端口设计（`EvccControlGateway`）

### 13.1 新增只写端口（与只读 `EvccClientPort` 并列、物理隔离）

```kotlin
internal interface EvccControlPort : Closeable {
    fun connectionState(): EvccApiClient.ConnectionState
    fun queryCapabilities(): List<EvccCapability>
    fun readProperty(selector: EvccPropertySelector, forceRefresh: Boolean): EvccPropertyRecord  // read-before-write
    fun writeProperty(request: EvccWriteRequest): EvccOperationResult
    fun executeAction(request: EvccActionRequest): EvccOperationResult
    fun executeShell(request: EvccShellRequest): EvccOperationResult
    fun cancel(requestId: String): EvccOperationResult
}
```

- 由单独会话/权限集驱动：`property.write`（氛围灯）或 `shell.exec`+`shell.system_write`（系统动作）。
- 懒创建；无写能力需求时不实例化，不申请写权限。
- `onShellCompleted` 回调经 `EvccControlGateway` 分发到对应 `requestId` 的等待者。
- 严禁把这些方法加到现有只读 `EvccClientPort`，也严禁只读消费者可见。

### 13.2 授权与能力门禁

- 启动不申请写/Shell 权限；用户在设置开启对应能力并在 EVCC 控制台授权后，才建立写会话。
- 用 `queryCapabilities()`/`grantedPermissions` 实判，不按预设名推断。
- 任一写能力撤权 → 立即停止该能力并提示“在 EVCC 中授权”。

## 14. 设置与产品接线

- 所有新 Geely 能力设置经 `VehicleSettingsController`（`GeelyVehicleSettingsController`）注入，`VehicleSettingsPlacement.ADVANCED`，默认关闭，实验项按既有 `(experimental)`/`（实验性）` 规则命名。
- 写/系统类能力的开关使用 `enableConfirmation`，并在描述中说明“下次连接生效”（`NEXT_CONNECTION`）或“立即生效”（`IMMEDIATE`/`REFRESH_SETTINGS`）。
- 未授权时在开关旁提供 `VehicleSettingRow.Action`“在 EVCC 中授权”，调用 `EvccApiContract.ACTION_OPEN_SETTINGS`。
- 字符串写入 `vehicle-geely/src/main/res/values/` 及全部 `values-*`（ar/es/ru/uk/zh-rCN/zh-rTW），遵守项目翻译测试。
- Geely 新能力默认受 `GeelyProductSettings.blockLegacyBydMenus` 之外的独立开关控制，不复用 BYD 分区。

## 15. 分阶段实施

| 阶段 | 内容 | 依赖 | 退出标准 |
| --- | --- | --- | --- |
| P0 | 稳定现有 EVCC 电池/车速/挡位；确认 EVCC 1.2/权限 | — | §16 遥测清单通过 |
| P1 | 提取 `EvccGateway`（Connection/Read/Control 骨架，Control 懒创建） | P0 | 多消费者订阅并集/release 测试通过 |
| P2 | #10 驻车 + #11 胎压（纯 EVCC 读） | P1 | fail-closed、四轮映射、staleness 验证 |
| P3 | #14/#15 方向盘（host 命令 Seam 扩展） | — | 按键门禁与透传验证 |
| P3B | #16 蓝牙音频仲裁（手动音频源 + 自动 A2DP 避让） | — | §11A.6 验证通过 |
| P4 | #6 仪表地图可见性 + #5 Display 实车 profile | P1 | Display 清理与安全 UI 验证 |
| P5 | #17 氛围灯（EVCC typed write，`EvccControlGateway`） | P1 | validate/read-back/restore/限流/撤权验证 |
| P6 | #19/#20 系统动作（标准设置优先，EVCC Shell 可选确认） | P1 | 确认窗口/超时/回滚验证 |
| P7 | #2 HUD / #12 歌曲 / #13 来电（有 Geely OEM API 才做） | — | 各自 OEM 输出验证 |

#3/#4/#18 维持不迁移，除非实车复现并找到官方接口。#16 已复现并纳入 P3B。

## 16. 验收要求（汇总）

- **EVCC 读**：3.2.17+/API 1.2；selector 的 source/type/area/unit/access 与 profile 一致；撤权/重启/source generation/sequence gap 行为正确；缓存 staleness 生效。
- **驻车**：unknown/stale/撤权=禁止播放；P 切出立即停止。
- **胎压**：四轮位置、单位换算、缺失/故障轮位不显示旧值。
- **输入**：按键去重、通话优先级、无 session 透传、不误吞音量。
- **蓝牙音频仲裁**：切到 `CarPlay 播放` 弹确认、确认后自动打开避让并请求蓝牙权限，点击播放不再立即暂停（自动断 A2DP）；确认后手动关避让则复现并提示手动禁用蓝牙媒体；取消确认保持 `车机蓝牙播放`；owner=`车机蓝牙播放`（默认）不避让；拒权/反射失败不失声且提示手动；通话 HFP 不被误断；CarPlay 结束/撤权/重启后 A2DP 恢复；不改整机 radio。
- **氛围灯写**：writable descriptor 绑定车型/固件；validate→write→read-back→restore 全链路；timeout 不重试；撤权即停；崩溃可恢复或明确 cleanup pending。
- **系统 Shell 动作**：单段命令、allowlist、车机确认、超时/拒绝/`RATE_LIMITED` 有反馈、不自动触发。
- **渲染输出**：HUD/歌曲/来电仅在 Geely OEM API 存在时启用，清除/切源/崩溃恢复可靠。
- **上游可合并**：`:shared`/`:common` 仅新增稳定 Interface 与默认实现；EVCC/Geely/固件细节只在 `:vehicle-geely`；无散落 `if (isGeely)`。
- **CI**：按 `AGENTS.md`/`.github/workflows/android.yml` 命令全绿。

## 17. 明确不做（与父文档一致）

- 不把 EVCC Shell 当 BYD ADB 的通用替代层，不用它做自动启动/长驻/会话级连续控制/渲染。
- 不把 EVCC 的 W/RW 目录自动生成 UI 控件。
- 不把 BYD `autoservice`/`AutoContainer`/SOME-IP/广播协议带入 Geely Module。
- 不让 park/tyre/ambient 各自创建独立 EVCC client。
- 不把写能力加进只读 `EvccClientPort`。
- 不在未知固件自动选择仪表 Display。
- 不默认关蓝牙 radio、禁 OEM 包、改 appops/secure settings 或起后台 helper。#16 的自动避让只断 A2DP media profile，不关整机蓝牙。

## 18. 主要代码与契约证据

EVCC 契约：

- [`IEvccApiSession.aidl`](../third_party/evccapi/evccapi-contract/src/main/aidl/com/kooo/evcc/evccapi/IEvccApiSession.aidl)
- [`EvccApiContract.kt`](../third_party/evccapi/evccapi-contract/src/main/java/com/kooo/evcc/evccapi/EvccApiContract.kt)
- [`EvccShellRequest.kt`](../third_party/evccapi/evccapi-contract/src/main/java/com/kooo/evcc/evccapi/EvccShellRequest.kt)
- [`EvccWriteRequest.kt`](../third_party/evccapi/evccapi-contract/src/main/java/com/kooo/evcc/evccapi/EvccWriteRequest.kt)
- [`EvccActionRequest.kt`](../third_party/evccapi/evccapi-contract/src/main/java/com/kooo/evcc/evccapi/EvccActionRequest.kt)
- [`EvccApiClient.kt`](../third_party/evccapi/evccapi-client/src/main/java/com/kooo/evcc/evccapi/client/EvccApiClient.kt)

现有 EVCC 读链路（复用/深化基础）：

- [`EvccVehicleDataAdapter.kt`](../vehicle-geely/src/main/java/com/lllhys/geeplay/vehicle/geely/evcc/EvccVehicleDataAdapter.kt)
- [`EvccVehicleTelemetry.kt`](../vehicle-geely/src/main/java/com/lllhys/geeplay/vehicle/geely/evcc/EvccVehicleTelemetry.kt)
- [`EvccClientPort.kt`](../vehicle-geely/src/main/java/com/lllhys/geeplay/vehicle/geely/evcc/EvccClientPort.kt)
- [`EvccDemandRegistry.kt`](../vehicle-geely/src/main/java/com/lllhys/geeplay/vehicle/geely/evcc/EvccDemandRegistry.kt)

品牌中立 Seam：

- [`CarPlayVehicleData.kt`](../shared/src/main/java/com/shilapi/xcertplay/vehicle/CarPlayVehicleData.kt)
- [`VehicleSessionHooks.kt`](../shared/src/main/java/com/shilapi/xcertplay/vehicle/VehicleSessionHooks.kt)
- [`VehicleSettings.kt`](../shared/src/main/java/com/shilapi/xcertplay/vehicle/VehicleSettings.kt)
- [`VehicleClusterDisplay.kt`](../shared/src/main/java/com/shilapi/xcertplay/vehicle/VehicleClusterDisplay.kt)

CarPlay host 命令（输入类复用）：

- [`CarPlayController.kt`](../shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt)（`answerCall`/`endCall`/`sendKnob`/`zoomDashboardMap`/`requestSiri`）

接线改造入口（去 BYD 直接耦合）：

- [`CarPlayVideo.kt`](../common/src/main/java/com/shilapi/xcertplay/CarPlayVideo.kt)（#10）
- [`CarPlayHostActivity.kt`](../common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt)（#11 胎压、#6 cluster）
- [`AmbientMusicController.kt`](../shared/src/main/java/com/shilapi/xcertplay/media/AmbientMusicController.kt)（#17）
- [`GeelyVehicleInput.kt`](../vehicle-geely/src/main/java/com/lllhys/geeplay/vehicle/geely/GeelyVehicleInput.kt)（#14/#15）

媒体音频 owner / 蓝牙仲裁（#16）：

- [`LocalMediaAudioPolicy.kt`](../shared/src/main/java/com/shilapi/xcertplay/media/LocalMediaAudioPolicy.kt)（`MediaOutputOwner` DIPLAY/OEM_BLUETOOTH/PHONE_OR_EXTERNAL）
- [`GeelyNavigationSettings.kt`](../vehicle-geely/src/main/java/com/lllhys/geeplay/vehicle/geely/GeelyNavigationSettings.kt)（`GeelyMediaSettings.loadOwner/saveOwner`）
- [`AndroidMediaSink.kt`](../shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt)（媒体渲染/会话占用）
- [`BydBluetoothSuspend.kt`](../shared/src/main/java/com/shilapi/xcertplay/hud/BydBluetoothSuspend.kt)（BYD 对照：ADB 关 radio，不迁移此做法）
