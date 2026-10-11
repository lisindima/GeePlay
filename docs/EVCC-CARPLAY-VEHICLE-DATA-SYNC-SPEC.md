# EVCC 1.2 → CarPlay/iPhone 车辆数据同步 Spec

状态：待实施

日期：2026-10-11

目标分支：`geely/main`

关联文档：

- [EVCC-1.2-CARPLAY-INTEGRATION-ANALYSIS.md](EVCC-1.2-CARPLAY-INTEGRATION-ANALYSIS.md)
- [EVCC-1.2-SIRI-VEHICLE-CONTROL.md](EVCC-1.2-SIRI-VEHICLE-CONTROL.md)
- [GEELY-CORE-FEATURES-SPEC.md](GEELY-CORE-FEATURES-SPEC.md)
- [GEELY-IMPLEMENTATION-PROGRESS.md](GEELY-IMPLEMENTATION-PROGRESS.md)
- [GEELY-VALIDATION.md](GEELY-VALIDATION.md)
- [BYD_NAVIGATION.md](BYD_NAVIGATION.md)
- [SIDE_PANEL.md](SIDE_PANEL.md)

## 1. 摘要

本 Spec 定义一条只读、单向、可验证的数据路径：

```text
Geely 车辆属性
    → EVCC 1.2 Binder
    → :vehicle-geely 中的 EVCC adapter
    → :shared 中的品牌中立车辆模型
    → iAP2 VehicleStatus / LocationInformation
    → CarPlay/iPhone
```

首期只同步当前 GeePlay 已有可靠 iAP2 编码的字段：

- 动力电池 SOC；
- 剩余续航；
- 充电状态；
- 最大续航；
- 可选的当前/最大电池能量；
- 车速；
- P/R/N/D 挡位；
- 继续使用 Android Location 提供 GNSS。

EVCC 1.2 的车辆写入、音量控制、变量、Shell、Siri 和 CarPlay Ultra 不属于本 Spec。

## 2. 背景

当前 `:shared` 已实现两条车辆到 iPhone 的 iAP2 数据路径：

1. `VehicleStatusUpdate 0xA101`：电池、续航和充电状态；
2. `LocationInformation 0xFFFB`：GGA/RMC 定位和 `$PASCD` 轮速/挡位。

这些传输实现已经同时接入有线 CarPlay 和无线 runtime tunnel，但 `CarPlayHostActivity` 仍直接选择 BYD ADB provider。GeePlay 的 `:vehicle-geely` 尚未向该数据面提供 Geely/EVCC adapter。

当前结构中的主要问题：

- 通用 `:common` 直接引用 `BydOutputSettings` 和 `BydNavigationOutputs`；
- `VehicleIntegrationProvider` 能注入导航、媒体和输入行为，但不能注入车辆状态；
- EVCC `replaceSubscriptions()` 是整集合替换，多个消费者分别调用会互相覆盖；
- iAP2 Identification 在 CarPlay 会话开始前完成，EVCC 数据却需要异步绑定和初始快照；
- 当前 `VehicleStatusSnapshot` 默认假定核心字段完整，不适合把部分可用的 EVCC 目录直接透传。

## 3. 目标

### 3.1 功能目标

1. 使用官方 EVCC 1.2 Contract 和 Client SDK 读取 Geely 车辆属性。
2. 在 CarPlay Identification 前确定本次会话可以诚实声明的车辆能力。
3. 在 iPhone 请求后，通过当前 iAP2 通道发送车辆状态、车速和挡位。
4. 同时支持有线 CarPlay 和无线 runtime tunnel。
5. EVCC 不存在、未授权、版本不兼容或来源不可用时，CarPlay 主链路仍可工作。
6. 保持普通 DiPlay/BYD 当前行为不变。
7. 为后续增加外部温度、雨刷、Alerts、乘客座椅等字段保留 typed extension seam。

### 3.2 架构目标

1. `:shared` 不依赖 EVCC、Geely SDK 或 EVCC Parcelable。
2. `:common` 不再决定使用 BYD 还是 EVCC 数据源。
3. EVCC 的连接、授权、版本、订阅、转换和缓存集中在一个深 Module 内。
4. iAP2 编码只消费品牌中立模型，不读取 Binder、不访问车辆厂商接口。
5. 以同一 Interface 支持至少两个 Adapter：BYD ADB 和 Geely EVCC。
6. 后续增加车型映射时只修改 `:vehicle-geely`，增加 iAP2 字段时只修改 `:shared` 编码与模型。

## 4. 非目标

- 不实现 CarPlay/iPhone → EVCC 的车辆控制。
- 不实现 Siri 语音控制。
- 不暴露 EVCC `writeProperty()`、`writeProperties()`、Shell 或变量写入。
- 不接入 EVCC 1.2 音量组；音量不是经典 CarPlay VehicleStatus 字段。
- 不实现 CarPlay Ultra Vehicle State Protocol。
- 不把 EVCC 5,000+ 属性作为动态 key-value 透传给 CarPlay。
- 不在缺少 MFi 字段定义时猜测新的 iAP2 参数 ID、类型或单位。
- 不把 TPMS 胎压映射成 `BarometricPressure`。
- 不承诺 iPhone 一定展示其已经接收的所有 VehicleStatus 字段。

## 5. 术语与证据等级

### 5.1 术语

- **Vehicle data Module**：向 CarPlay 提供车辆状态和运动数据的完整能力。
- **Interface**：调用方需要知道的车辆数据类型、生命周期、失效语义和线程要求。
- **Seam**：`:common`/`:shared` 与厂商 Adapter 之间可以替换实现的位置。
- **Adapter**：BYD ADB 或 Geely EVCC 对品牌中立 Interface 的实现。
- **Session profile**：在一次 iAP2 Identification 前冻结的能力声明。
- **Telemetry cache**：只保存已验证、已转换且带来源代次的最近车辆值。
- **Demand**：当前 CarPlay 会话实际需要的 EVCC 逻辑信号集合。

### 5.2 证据等级

| 等级 | 含义 |
| --- | --- |
| `CATALOG_CANDIDATE` | 只在 EVCC 静态目录或运行时目录发现 |
| `RESOLVED` | EVCC 运行时可解析，类型、访问级别和区域符合预期 |
| `OBSERVED` | 目标车上读取到有效、随车辆变化的数据 |
| `IAP2_DELIVERED` | iPhone 已订阅且 GeePlay 成功发送对应消息 |
| `VEHICLE_CONFIRMED` | 指定车型、固件、EVCC、iPhone/iOS 和连接方式完成实车验证 |

只有 `VEHICLE_CONFIRMED` 能用于对外声明支持。

## 6. 协议范围

### 6.1 EVCC 版本

最低目标：

```text
apiMajor == 1
apiMinor >= 2
contractHash 记录为诊断信息，不作为授权或唯一兼容判断
最低完整功能 EVCC 版本：3.2.17
```

客户端接受未来 `1.x` 次版本，并通过 `queryCapabilities()` 判断实际能力。主版本不是 1 时停止车辆数据接入，但不得影响 CarPlay 核心连接。

### 6.2 EVCC 权限

本 Spec 只需要：

```text
property.directory
property.read
property.listen
```

不请求：

```text
property.write
property.full_scan
variable.*
action.*
volume.*
shell.*
```

Android Manifest：

```xml
<uses-permission android:name="com.kooo.evcc.permission.ACCESS_EVCC_API" />

<queries>
    <package android:name="com.kooo.evcc" />
</queries>
```

### 6.3 iAP2 VehicleInformation

当前支持的静态字段：

| 参数 ID | 字段 | 要求 |
| ---: | --- | --- |
| 0 | `Identifier` | 每个组件稳定且在会话内唯一 |
| 1 | `Name` | 非空、无 NUL |
| 2 | `EngineType` | 首期为 Electric |
| 6 | `DisplayName` | GeePlay/车型展示名 |
| 8 | `MapsDisplayName` | Apple Maps 车辆名称 |
| 11 | `SupportedChargingConnectors` | 来自显式车型配置，不从不可靠状态猜测 |

### 6.4 iAP2 VehicleStatusUpdate

iPhone 通过 `0xA100 StartVehicleStatusUpdates` 开始订阅，通过 `0xA102` 停止；GeePlay 使用 `0xA101` 发送状态。

首期字段：

| 参数 ID | 字段 | 必需性 | 数据规则 |
| ---: | --- | --- | --- |
| 3 | `Range` | 必需 | km，限制在 `0..65535` |
| 6 | `RangeWarning` | 必需 | 由 SOC 阈值计算 |
| 11 | `RangeElectric` | 必需 | 与首期 `Range` 一致 |
| 15 | `RangeWarningElectric` | 必需 | 与首期 `RangeWarning` 一致 |
| 21 | `MinBatteryCharge` | 可选组成员 | 仅在可靠能量数据存在时发送 |
| 22 | `CurrentBatteryCharge` | 可选 | Wh，未知则省略 |
| 23 | `MaxBatteryCharge` | 可选 | Wh，未知则省略 |
| 24 | `DisplayedBatteryPercentage` | 必需 | `SOC × 1000`，SOC 限制在 `0..100` |
| 25 | `isCharging` | 必需 | 必须使用已验证枚举映射 |
| 30 | `MaxRangeElectric` | 必需 | 可靠额定值或有界估算 |

首期只有在 SOC、剩余续航和充电状态都有效时，才生成完整 `VehicleStatusSnapshot`。能量字段可单独缺失。

### 6.5 iAP2 LocationInformation

现有 Android Location 继续提供：

- `$GPGGA`：经纬度、UTC 时间、定位质量、HDOP、海拔；
- `$GPRMC`：经纬度、UTC 日期时间、地速、方向。

EVCC adapter 提供：

- `$PASCD`：P/R/N/D 和轮速样本。

iPhone 只有在 `0xFFFA StartLocationInformation` 中请求 VehicleSpeedData 时才获得 `$PASCD`。即使没有 GPS fix，只要车速和挡位有效，仍允许发送 `$PASCD`。

无线模式只在长期存在的 Wi-Fi runtime tunnel 声明 Location/Vehicle 数据；短生命周期 Bluetooth bootstrap 不得声明或发送。

## 7. 后续可扩展字段

以下经典 VehicleStatus 字段已经有项目实车实验依据，但不进入首期：

| 字段 | Identification | iPhone 订阅 | 可见效果 | 当前决策 |
| --- | --- | --- | --- | --- |
| `OutsideTemperature` | 接受 | 是 | 未观察到 | 预留 typed 字段 |
| `InsideTemperature` | 接受 | 否 | 未观察到 | 暂不实现 |
| `WiperStatus` | 接受 | 是 | 未观察到 | 预留 typed 字段 |
| `BarometricPressure` | 接受 | 是 | 未观察到 | 仅真实大气压 |
| `Alerts` | 接受 | 是 | 未观察到 | 需正式 schema |
| `PassengerSeatStatus` | 接受 | 是 | 未观察到 | 需正式枚举 |

以下运动数据有协议线索，但当前没有可信 payload 布局：

- `$PAGCD`：陀螺仪/角速度；
- `$PAACD`：加速度。

在取得当前 MFi iAP2 规格、合法字段 ID/单位/枚举和实车证据前不得实现。

CarPlay Ultra 的 Audio settings、Charging、Climate control、Closures、Drive state、Driver assistance、Electric engine、Fuel、High voltage battery、Seat、Tire、Trip computer、Vehicle motion 等属于另一套 Vehicle State Protocol，不得塞入经典 `0xA101`。

## 8. 模块与依赖

目标依赖关系：

```text
:geely
   ├──> :common ───────────────> :shared
   ├──> :vehicle-geely ────────> :shared
   └──> :vehicle-geely ────────> :evccapi-client ──> :evccapi-contract

:mobile / :automotive / :home / :maphost
   └──> 现有 common/shared 路径，不感知 EVCC
```

约束：

- EVCC 官方 module 只接入 `:vehicle-geely`/`:geely` 产品图。
- `:shared` 和 `:common` 不 import `com.kooo.evcc.*`。
- `:mobile` 的 minSdk 25 不被 EVCC SDK 的 minSdk 28 抬高。
- `:geely` 和 `:vehicle-geely` 当前 minSdk 28，可以满足 EVCC SDK。
- 官方 SDK 源码按固定版本 vendor，并记录来源 URL、版本、SHA-256 和 Apache-2.0 license。
- 不复制 EVCC 主 APK 的反编译实现；只使用官方公开 Contract/Client。

## 9. 品牌中立 Seam

### 9.1 新增模型

建议在 `:shared` 中新增：

```kotlin
enum class CarPlayVehicleField {
    RANGE,
    RANGE_WARNING,
    RANGE_ELECTRIC,
    RANGE_WARNING_ELECTRIC,
    ELECTRIC_CHARGE,
    DISPLAYED_BATTERY_PERCENTAGE,
    CHARGING,
    MAX_RANGE_ELECTRIC,
    VEHICLE_SPEED,
    GEAR,
    OUTSIDE_TEMPERATURE,
    WIPER_STATUS,
    BAROMETRIC_PRESSURE,
    ALERTS,
    PASSENGER_SEAT_STATUS,
}

data class CarPlayVehicleCapabilities(
    val fields: Set<CarPlayVehicleField>,
    val chargingConnectors: EvChargingConnectors,
)

class CarPlayVehicleDataSession(
    val capabilities: CarPlayVehicleCapabilities,
    val statusProvider: VehicleStatusProvider?,
    val speedSource: VehicleSpeedSource?,
    private val release: () -> Unit,
) : Closeable {
    override fun close() = release()
}

interface CarPlayVehicleDataAdapter : Closeable {
    fun prepare()
    fun createSession(): CarPlayVehicleDataSession
    fun diagnostics(): VehicleDataDiagnostics
}
```

Interface 约束：

- `prepare()` 异步启动，不阻塞主线程。
- `createSession()` 只读取已发布缓存，不执行 Binder、ADB 或网络 I/O。
- 返回的 capabilities 在一次 CarPlay 会话内不可变。
- provider 的 `snapshot()` 和 `drain()` 必须非阻塞。
- session close 后旧 provider 不得继续向新会话发布。
- Adapter close 必须取消订阅、断开 EVCC 并释放 worker。

### 9.2 扩展 VehicleIntegrationProvider

```kotlin
interface VehicleIntegrationProvider {
    fun createVehicleSessionHooks(context: Context): VehicleSessionHooks
    fun carPlayVehicleData(context: Context): CarPlayVehicleDataAdapter? = null
    // 保留现有 media/settings/display/product 方法。
}
```

`GeePlayApplication` 返回进程级 `EvccVehicleDataAdapter`。普通 DiPlay 使用 `BydVehicleDataAdapter`，从而形成两个真实 Adapter，而不是在 `CarPlayHostActivity` 中继续增加品牌判断。

### 9.3 CarPlayHostActivity 改造

当前以下逻辑需要改为从 `CarPlayVehicleDataAdapter` 获取：

- `vehicleStatusEnabled`；
- `chargingConnectors`；
- `vehicleSpeedEnabled`；
- `VehicleStatusProvider`；
- `VehicleSpeedSource`。

`CarPlayController` 已经接收 `VehicleStatusProvider` 和 `Iap2LocationProvider`，不应再感知 EVCC。

## 10. EVCC Adapter 内部设计

### 10.1 文件建议

```text
vehicle-geely/src/main/java/com/lllhys/geeplay/vehicle/geely/evcc/
  EvccVehicleDataAdapter.kt
  EvccVehicleGateway.kt
  EvccClientPort.kt
  EvccPropertyBinding.kt
  EvccPropertyResolver.kt
  EvccTelemetryCache.kt
  EvccVehicleStatusMapper.kt
  EvccVehicleSpeedSource.kt
  EvccVehicleDiagnostics.kt
```

### 10.2 内部 Interface

官方 `EvccApiClient` 外包一层内部 seam，避免测试直接依赖 Binder：

```kotlin
internal interface EvccClientPort : Closeable {
    fun connect()
    fun disconnect()
    fun apiInfo(): EvccApiInfo?
    fun sessionInfo(): EvccSessionInfo?
    fun queryCapabilities(): List<EvccCapability>
    fun resolveProperty(selector: EvccPropertySelector): EvccPropertyRecord
    fun readProperty(selector: EvccPropertySelector, forceRefresh: Boolean): EvccPropertyRecord
    fun replaceSubscriptions(selectors: List<EvccPropertySelector>): EvccOperationResult
    fun querySourceStates(): List<EvccSourceState>
}
```

生产 Adapter 委托官方 SDK；测试 Adapter 使用内存 fake。Interface 不暴露写入、Shell、变量或音量方法，从类型层面限制本 Module 为只读。

### 10.3 状态机

```text
DISABLED
   │ 用户开启
   ▼
CONNECTING ───────────────→ AUTHORIZATION_REQUIRED
   │ Binder connected                 │ 用户授权并重试
   ▼                                  └──────────────┐
CHECKING_VERSION                                      │
   ├─ major != 1 ─────────→ INCOMPATIBLE             │
   ├─ minor < 2 ──────────→ INCOMPATIBLE             │
   ▼                                                  │
DISCOVERING_SOURCES                                   │
   ├─ 无 GEELY source ─────→ UNAVAILABLE              │
   ▼                                                  │
RESOLVING_BINDINGS                                    │
   ├─ 核心字段不完整 ──────→ DEGRADED                 │
   ▼                                                  │
SUBSCRIBING                                           │
   ▼                                                  │
READY ◄───────────────────────────────────────────────┘
   │ Binder death/source generation/change gap
   ▼
RESYNCING ────────────────→ READY / DEGRADED / AUTHORIZATION_REQUIRED
```

状态变化只更新缓存和诊断，不直接中断运行中的 CarPlay。能力集合在下一次 CarPlay Identification 时重新评估。

## 11. 属性解析和车型 Profile

### 11.1 候选属性

旧 3.2.14 APK 的 Geely catalog 提供以下初始候选；新版 1.2 运行时必须重新验证：

| 逻辑信号 | 优先候选 | ID | 来源 | 类型 |
| --- | --- | ---: | --- | --- |
| `BATTERY_SOC` | `TYPE_EV_BATTERY_PERCENTAGE` | `4195840` | `GEELY_CAPI` | Float |
| `RANGE_EV_KM` | `SENSOR_TYPE_ENDURANCE_MILEAGE_EV` | `1049088` | `GEELY_CAPI` | Float |
| `VEHICLE_SPEED` | `SENSOR_TYPE_CAR_SPEED` | `1048832` | `GEELY_CAPI` | Float |
| `VEHICLE_SPEED` fallback | `SENSOR_TYPE_CAR_SPEED_FROM_IPK` | `1070336` | `GEELY_CAPI` | Float |
| `GEAR` | `SENSOR_TYPE_GEAR` | `2097664` | `GEELY_CAPI` | Int32 |
| `GEAR` fallback | `SENSOR_TYPE_GEARMOV_STS` | `1068800` | `GEELY_CAPI` | Int32 |
| `CHARGING_AC` | `SENSOR_TYPE_ONBD_CHARGING_STATUS` | `1064448` | `GEELY_CAPI` | Int32 |
| `CHARGING_DC` | `SENSOR_TYPE_DC_CHARGING_STATUS` | `1064704` | `GEELY_CAPI` | Int32 |

### 11.2 解析规则

1. 首选显式 `source + id + areaId`，不以 `AUTO` 作为生产映射。
2. 先查询 `querySourceStates()`，只使用启用且可用的来源。
3. 使用 `resolveProperty()` 校验实际来源、descriptor、access 和区域。
4. 核心读取只接受 `R` 或 `RW`，拒绝 write-only 条目。
5. 数字类型统一经过有限值检查、单位换算和范围检查。
6. 挡位、充电等枚举由车型 Profile 显式映射，不通过数值顺序猜测。
7. 同一逻辑信号的 fallback 必须逐一验证；多个候选同时有效时由 Profile 指定，不自动选第一个。
8. Profile 至少绑定车型/固件特征和 EVCC source，不以 Android API 版本代替车型识别。
9. 运行时发现与已验证 Profile 不一致时进入 `DEGRADED`，不发送猜测值。

### 11.3 转换规则

#### SOC

- 输入必须是有限数；
- 接受范围 `0.0..100.0`；
- iAP2 编码为 `round(SOC × 1000)`；
- 越界值使本次字段无效，不做静默裁剪后继续发布。

#### 续航

- 统一为 km；
- 输入单位必须由 descriptor/Profile 证明；
- 有效范围 `0..65535 km`；
- 小数按明确策略舍入；
- 负值、NaN 和无穷值无效。

#### 车速

- 内部统一为 m/s；
- EVCC 原始单位由 Profile 指定；
- 有效范围 `0..100 m/s`；
- `$PASCD` 每个样本使用 `SystemClock.elapsedRealtime()`；
- 不直接使用墙上时钟或跨重启时间戳。

#### 挡位

- Profile 将原始枚举映射为 `PARK/REVERSE/NEUTRAL/DRIVE`；
- 手动、运动和低速前进挡可以映射为 `DRIVE`，但必须有实车证据；
- 未知枚举使 `$PASCD` 暂停，不默认映射为 P 或 D。

#### 充电状态

- AC、DC 任一路明确处于充电态时为 `true`；
- 两路都明确不充电时为 `false`；
- 任一路未知且无法得出确定结果时，核心快照不 ready；
- 不把充电枪连接、预约充电或放电状态自动解释为正在充电。

#### 最大续航

- 优先使用经过验证的额定满电续航；
- 无额定值且 SOC 大于安全阈值时，可使用 `rangeKm / (SOC / 100)` 估算；
- 低 SOC 下保留最近一次高 SOC 的稳定估算；
- 估算结果必须有上限，并在诊断中标明 derived。

## 12. 订阅聚合

EVCC `replaceSubscriptions()` 是整集合替换。所有消费者必须通过同一个 demand registry 请求逻辑信号：

```text
effectiveDemand = identificationPrewarm
                ∪ activeVehicleStatus
                ∪ activeVehicleSpeed
                ∪ futureOptionalFields
                ∪ diagnostics
```

建议 Interface：

```kotlin
internal interface EvccDemandRegistry {
    fun acquire(owner: Any, signals: Set<EvccLogicalSignal>): Closeable
    fun current(): Set<EvccLogicalSignal>
}
```

要求：

- owner 重复 acquire 不增加重复订阅；
- close 只释放当前 owner 的需求；
- 仅在并集实际变化时调用 `replaceSubscriptions()`；
- 新集合只有收到 `OK/ACCEPTED` 后才成为 committed 集合；
- Binder 重连由 SDK 重放 committed 集合；
- `onResyncRequired` 时冻结旧基线，等待所有必需字段的新 `INITIAL_SNAPSHOT`；
- 单个消费者不得直接访问官方 client 的 `replaceSubscriptions()`。

## 13. 缓存与并发

### 13.1 缓存项

每个逻辑信号至少保存：

```kotlin
data class VehicleSignalValue<T>(
    val value: T,
    val source: String,
    val canonicalId: Long,
    val areaId: Int,
    val sourceGeneration: Long,
    val observedAtMillis: Long,
    val receivedAtElapsedMillis: Long,
    val subscriptionGeneration: Long,
)
```

### 13.2 并发要求

- EVCC Listener 只做校验、转换和缓存发布，不执行文件、网络或 iAP2 I/O。
- `VehicleStatusProvider.snapshot()` 只读取不可变缓存。
- `VehicleSpeedSource.drain()` 只读取并清空有界样本队列。
- EVCC Binder 调用统一运行在单独 worker/coroutine dispatcher。
- 不从 EVCC callback 线程直接向 iAP2 channel 写数据。
- 缓存快照必须原子发布，不能组合不同 source generation 的字段。
- 旧连接 generation 的迟到回调必须丢弃。

### 13.3 新鲜度

- 来源变为 `UNAVAILABLE` 时立即使对应字段无效。
- `sourceGeneration` 变化时清空该来源旧值。
- `onResyncRequired` 后在新初始快照完成前不得使用旧值。
- 车速与挡位必须属于同一有效 generation。
- 静止时速度可能长时间保持 0，不能单纯因为“值未变化”判定过期；应结合来源状态、会话和周期性健康检查。
- SOC/续航可以低频主动读作为健康检查，但不得高频 `forceRefresh`。

## 14. iAP2 会话时序

### 14.1 启动前预热

```text
GeePlay app open / feature enabled
    → EVCC connect
    → verify apiMajor=1, apiMinor>=2
    → read granted permissions
    → query capabilities/source states
    → resolve bindings
    → subscribe core properties
    → receive initial snapshots
    → publish READY capability snapshot
```

所有步骤异步执行，不阻塞主线程。

### 14.2 CarPlay Identification

`CarPlayHostActivity` 创建本次 `CarPlayVehicleDataSession`：

- EVCC adapter 为 `READY` 且核心电池字段完整：声明 VehicleInformation/VehicleStatus；
- 速度与挡位映射都 ready：在 LocationInformation 中声明 VehicleSpeedData；
- 只有 GPS 可用：只声明 GGA/RMC；
- EVCC 未就绪：不声明对应能力，CarPlay 继续连接。

Session profile 一旦用于 Identification 就冻结。EVCC 在会话中途变为 ready，不动态增加本次能力；提示用户下次连接生效。

### 14.3 运行时

```text
iPhone 0xA100
    → Iap2VehicleStatusReporter active
    → 读取内存 snapshot
    → 立即发送 0xA101
    → 保持现有 30 秒周期

iPhone 0xFFFA 且请求 VehicleSpeedData
    → VehicleSpeedSource.start()
    → EVCC speed/gear demand acquire
    → 缓存速度按 250 ms 采样到有界队列
    → 每次 0xFFFB 附带自上次发送后的 $PASCD

iPhone 0xA102 / 0xFFFC / session close
    → 对应 demand release
    → 无消费者后取消 EVCC 订阅
```

首期保留 VehicleStatus 立即首帧 + 30 秒周期，不擅自提高 iAP2 发送频率。若以后希望变化后立即发送，应先取得当前 MFi 规格并验证 iPhone 行为。

## 15. 降级与错误处理

| 条件 | 行为 |
| --- | --- |
| EVCC 未安装 | Adapter `UNAVAILABLE`，CarPlay 正常启动但不声明车辆状态 |
| 未获 Manifest 权限 | 记录配置错误，不循环绑定 |
| 用户未授权 | `AUTHORIZATION_REQUIRED`，提供打开 EVCC 设置入口 |
| API major 不兼容 | `INCOMPATIBLE`，不调用会话方法 |
| API minor < 2 | 本 Spec 目标下视为不满足基线 |
| `GEELY_CAPI` 不可用 | 尝试 Profile 中已验证 fallback，否则 `DEGRADED` |
| 单个属性 `PENDING` | 等待初始快照，不构造假值 |
| 单个属性 `UNAVAILABLE` | 使依赖它的组合快照失效 |
| Binder death | 由官方 SDK 退避重连，旧 generation 数据失效 |
| `onResyncRequired` | 清除旧增量基线，等待新的初始快照 |
| iAP2 没有订阅 | 不发送，释放相应 demand |
| 编码越界 | 丢弃本次快照并计数，不截断后冒充有效值 |

车辆状态 Adapter 失败不能使 CarPlay Controller 失败；它是可选能力，不是连接前置条件。

## 16. 诊断与隐私

诊断至少包含：

- EVCC 是否安装；
- EVCC service version、API major/minor、contract hash；
- 当前连接状态和最近失败类别；
- 已授予的只读权限；
- 各候选 source 的状态和 generation；
- 每个逻辑信号选择的 source、ID、area、类型和 Profile；
- 初始快照是否完整；
- 最近事件年龄和丢弃原因；
- 当前 iAP2 session 是否声明 VehicleStatus/VehicleSpeed；
- 是否收到 `0xA100`/`0xFFFA`；
- 最近 `0xA101`/`$PASCD` 发送时间；
- resync、Binder death、映射失败和越界计数。

默认日志不得包含：

- 精确 GPS 坐标；
- VIN、设备 ID、签名摘要；
- 原始 Binder payload；
- 未脱敏车辆值历史；
- EVCC 授权或用户隐私数据。

## 17. 设置与用户体验

遵循现有设置按驾驶员目标分组的规则。

### Connection

- `EVCC integration (experimental)`：连接状态和授权入口。

### Navigation

- `EV battery and range for CarPlay (experimental)`：下次连接生效。
- `Vehicle speed for CarPlay positioning (experimental)`：下次连接生效，并依赖位置共享。
- 低电量提醒阈值。
- 充电接口类型。

要求：

- 保存后调用 `markReconnectNeeded()`；
- 不自动中断当前 CarPlay；
- 文案明确数据会发送给已连接的 iPhone；
- 每个新 `settings_*` 字符串覆盖所有 locale；
- 不向 Overview 添加新的普通设置；
- 未授权时开关旁提供“在 EVCC 中授权”动作。

## 18. 实施阶段

### Phase 0：官方依赖与只读权限

- vendor EVCC 1.2 `evccapi-contract` 和 `evccapi-client`。
- 源码固定为 `https://evcc.coauto.cc/evccapi/downloads/evccapi-demo-source-1.2.0.zip`，预期 SHA-256 为 `d009a806587ba766e2408d00c83acc960e63729c9123507eaf24138350cf6454`。
- 记录上游 URL、版本、SHA-256、License 和升级流程。
- 只让 `:vehicle-geely` 依赖 EVCC module。
- 在 `:geely` Manifest 增加权限与 package query。

### Phase 1：品牌中立 Seam

- 新增 `CarPlayVehicleDataAdapter` 和 `CarPlayVehicleDataSession`。
- 将现有 BYD battery/speed 包装为 `BydVehicleDataAdapter`，行为不变。
- `CarPlayHostActivity` 改为从 `VehicleIntegrationProvider` 取得 Adapter。
- 保持 `CarPlayController` 和 iAP2 reporter 不依赖厂商类型。

### Phase 2：EVCC Gateway

- 实现状态机、client port、属性 resolver、车型 Profile、demand registry 和缓存。
- 接入 SOC、续航、充电、车速和挡位。
- 增加设置、诊断和授权入口。

### Phase 3：iAP2 数据接线

- 用 EVCC status provider 生成 `0xA101`。
- 用 EVCC speed source 生成 `$PASCD`。
- 验证有线和无线 runtime tunnel。
- 验证重连、撤权、EVCC 重启和 source generation 变化。

### Phase 4：可选经典 VehicleStatus 扩展

- 取得 MFi 字段定义。
- 逐项验证外部温度、雨刷、Alerts 和乘客座椅。
- 只有观察到明确 iPhone 消费价值后才默认开放。

## 19. 自动化测试

### 19.1 `:shared`

- Identification 按 capabilities 精确声明字段。
- 没有可信快照时不声明 VehicleStatus。
- `0xA100/0xA102` 生命周期。
- `0xA101` 每个字段的 wire ID、宽度、单位和边界。
- 未知能量字段不发送 21/22/23。
- `$PASCD` 的挡位、时间、速度、批量样本和 checksum。
- 无 GPS fix 时仍可只发送 `$PASCD`。
- Bluetooth bootstrap 不声明 Location/Vehicle。

### 19.2 `:vehicle-geely`

- API 1.2 版本门禁。
- 权限和 capability 门禁。
- 候选属性解析和 fallback。
- 类型、单位、SOC/续航边界。
- 挡位和充电枚举 Profile。
- source generation 变化使旧值失效。
- resync 前不发布混合代次快照。
- demand acquire/release 和订阅并集。
- 后调用者不覆盖其他消费者订阅。
- Binder callback 迟到、重复和旧 generation 丢弃。
- Adapter close 后不再发布。

### 19.3 `:common` / `:geely`

- GeePlay 使用 EVCC Adapter，不触发 BYD ADB provider。
- 普通 DiPlay 继续保持 BYD 行为。
- EVCC 缺失或未授权不阻止 CarPlay 主链路。
- 修改车辆数据设置只标记 reconnect，不主动断开。
- merged manifest 包含 `ACCESS_EVCC_API` 和 EVCC package query。
- `:mobile`、`:automotive`、`:home` 和 `:maphost` 不引入 EVCC 类。

## 20. 实车验收

每份证据必须记录：

```text
Geely 车型
车机硬件
车机固件 fingerprint
EVCC 版本和 apiMajor/apiMinor
GeePlay commit/build
iPhone 型号
iOS 版本
有线或无线
EVCC source 和属性 selector
```

场景：

1. 未授权时 GeePlay 正常启动并给出授权入口。
2. 授权后重连 CarPlay，iPhone 发送 `0xA100`。
3. `0xA101` 的 SOC、续航、充电状态与仪表一致。
4. 进入充电和结束充电时状态在允许延迟内更新。
5. 低电量阈值上下变化正确。
6. iPhone 在 `0xFFFA` 中请求 VehicleSpeedData。
7. P/R/N/D 和低速/高速轮速与仪表一致。
8. 隧道中 Apple Maps 定位不退化；是否使用 dead reckoning 单独记录。
9. 无线 Bluetooth bootstrap 不声明车辆数据，Wi-Fi tunnel 正确声明。
10. EVCC 进程重启后重新建立快照且不发送旧 generation。
11. 撤权后停止读取和发送，不影响 CarPlay 音视频。
12. 属性源断开、车型不匹配、异常枚举和超范围值均 fail-closed。

## 21. CI 与完成条件

代码实现后必须先运行仓库要求的完整检查：

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
```

并增加 GeePlay 范围：

```sh
./gradlew :vehicle-geely:testDebugUnitTest :vehicle-geely:lintDebug \
  :geely:testDebugUnitTest :geely:lintDebug :geely:assembleDebug
```

完成定义：

- 全部自动化检查通过；
- 普通 DiPlay/BYD 行为无回归；
- EVCC 缺失、未授权和异常时 CarPlay 主链路可用；
- P0 字段完成 `VEHICLE_CONFIRMED`；
- 文档记录最终 selector、单位、枚举、车型和固件；
- 诊断能够区分 EVCC、映射、缓存、Identification、订阅和 iAP2 发送故障；
- 不包含 EVCC 反编译源码、私有密钥或未授权 Apple/MFi 资料。

## 22. 开放问题

以下问题必须在编码前或实车阶段关闭：

1. 新版 EVCC 1.2 APK 在目标 Geely 固件上的实际 `GEELY_CAPI` 目录是否与旧样本一致？
2. SOC、续航、车速的单位是否在 descriptor 中明确，还是必须由车型 Profile 固定？
3. 挡位和 AC/DC 充电状态的实际枚举值是什么？
4. EVCC 属性事件在匀速和静止时是否持续更新，还是只在数值变化时发送？
5. EVCC 启动到核心初始快照 ready 的时间是否足以赶上 iAP2 Identification？
6. 目标 iPhone/iOS 是否发送 `0xA100` 和 VehicleSpeedData 请求？
7. Apple Maps 是否实际消费 EVCC 转换后的电量、续航和轮速？
8. 是否能从 MFi 资料取得可选 VehicleStatus 字段的正式 ID、类型、单位和枚举？

在这些问题关闭前，P0 字段应保持实验开关和明确的下一次连接生效语义。
