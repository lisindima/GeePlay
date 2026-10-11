# EVCC 1.2 与 GeePlay CarPlay 车辆数据联动分析

状态：以 EVCC 1.2 公开契约为实施基线，尚未实施代码改动，待新版 APK 和目标车机运行时验证

分析日期：2026-10-11

分析基线：`geely/main`，`HEAD 4314bbe1`，包含当时工作区内尚未提交的 GeePlay/Geely 改动

相关资料：

- EVCC 1.2 源码包：<https://evcc.coauto.cc/evccapi/downloads/evccapi-demo-source-1.2.0.zip>
- 历史静态样本：`/Users/bytedance/Downloads/EVCC_v3.2.14.apk`，仅用于补充壳、签名和 Geely catalog 证据
- EVCC 开放 API 文档：<https://evcc.coauto.cc/docs/open-api/>
- GeePlay 产品设计：[GEELY-CORE-FEATURES-SPEC.md](GEELY-CORE-FEATURES-SPEC.md)
- GeePlay 实施进度：[GEELY-IMPLEMENTATION-PROGRESS.md](GEELY-IMPLEMENTATION-PROGRESS.md)
- CarPlay 侧边栏及 VehicleStatus 实车记录：[SIDE_PANEL.md](SIDE_PANEL.md)

## 1. 结论

EVCC 1.2 可以作为 GeePlay 的车辆属性访问层，但需要把目标拆成两个方向：

1. **EVCC 读取车辆状态 → GeePlay → CarPlay/iPhone**：当前项目已有 iAP2 输出通道，可以接入。
2. **GeePlay 用户操作 → EVCC 写车辆属性**：可以通过 EVCC `writeProperty()` 实现，但应放在 GeePlay 自己的侧边栏或设置界面。

当前 CarPlay/iAP2 实现不是通用车辆属性总线。它只允许 GeePlay 向 iPhone 上送一组固定的车辆状态、定位、轮速和挡位；没有可将 EVCC 任意 `W/RW` 属性自动转换为 CarPlay 控件的协议入口。

因此推荐的产品边界是：

```text
车辆遥测：EVCC read/listen -> GeePlay typed cache -> iAP2 -> iPhone
车辆控制：GeePlay UI -> allowlist -> EVCC validateOnly -> write -> read-back
```

不要把 EVCC 的完整属性目录直接暴露给 CarPlay，也不要让来自 iPhone 的普通状态或媒体事件自动触发车辆写入。

## 2. 历史 EVCC 3.2.14 APK 分析

本节结论只针对旧样本 `EVCC_v3.2.14.apk`。它不能替代对新版 EVCC 3.2.17+ APK 的签名、加固和实际属性目录验证；实施时以 EVCC 1.2 公开 Contract、运行时 `EvccApiInfo`、会话权限和 `queryCapabilities()` 为准。

### 2.1 基础信息

| 项目 | 结果 |
| --- | --- |
| 包名 | `com.kooo.evcc` |
| 版本 | `3.2.14` |
| versionCode | `300` |
| minSdk | 28 |
| targetSdk | 30 |
| APK SHA-256 | `3d08371d67aa75129533882d9777d81c186112511ad6d193384ace79b4f8ac76` |
| 签名方案 | APK Signature Scheme v2 |
| 证书 SHA-256 | `ed5bcaec10ea8125afb23fc5c91ea4e729a1941072ed01aaf134f9ea33642a97` |

### 2.2 加固判断

未发现 360、梆梆、爱加密、乐固、SecNeo 等常见整体加固壳，依据如下：

- APK 直接包含约 13.9 MiB 的 `classes.dex` 和约 5.8 MiB 的 `classes2.dex`。
- JADX 可以恢复约 11,023 个源码文件，包括真实的 Application、Activity、服务、授权和属性运行时代码。
- Application 是真实入口 `com.kooo.evcc.EvccApplication`，不是常见壳 Stub Application。
- 未发现常见商业壳类名、native loader 或加密主 DEX 载荷。
- `assets/iplm_ctrl.dex` 是带同名 Java 源码的独立 Root/HIDL 工具，不是加密的主程序。

但 APK 并非没有保护：

- 使用 R8 混淆，内部大量类名和方法名被压缩。
- `libevcc_native.so` 承载许可、签名和部分敏感配置逻辑。
- 存在 `isApkCertificateMismatch()`、`verifySignature()` 和 `onNativeCertificateRejected()`。
- 重签后会触发 APK 签名异常处理和开发者远程协助流程。
- native 字符串包含 `/proc/self/maps`、`TracerPid`、`debugger`、Frida/Gum 等环境检测痕迹；但 EVCC 自身也提供 Frida 注入能力，不能把所有相关字符串都解释为反调试。

准确表述应为：**无传统整体加固壳，有 R8 混淆和自研 native 防篡改/签名保护。**

## 3. EVCC 1.2 开放 API 能力

实现目标契约为：

```text
API_MAJOR     = 1
API_MINOR     = 2
CONTRACT_HASH = evccapi-1.2
最低完整功能 EVCC = 3.2.17
```

客户端必须以运行时 `getApiInfo()` 为准：主版本不是 1 时停止调用；次版本低于 2 时保留 1.0/1.1 能力，但不能发送音量组事务。`contractHash` 只用于诊断，不是授权凭据。

### 3.1 Binder 入口

```text
EVCC package:     com.kooo.evcc
bind action:      com.kooo.evcc.evccapi.BIND
settings action:  com.kooo.evcc.evccapi.OPEN_SETTINGS
manifest 权限:    com.kooo.evcc.permission.ACCESS_EVCC_API
```

调用方 Manifest：

```xml
<uses-permission android:name="com.kooo.evcc.permission.ACCESS_EVCC_API" />

<queries>
    <package android:name="com.kooo.evcc" />
</queries>
```

不要申请 `com.kooo.evcc.permission.BIND_EVCC`。它是 EVCC 私有桥使用的签名级权限，不是开放 API 权限。

EVCC 的公开服务在 Manifest 中默认 `enabled=false`，只有满足以下条件后才会启用：

- EVCC 已激活；
- 不处于安全隔离或功能受限状态；
- 云端没有关闭开放 API；
- 至少存在一条可用的应用授权，或 EVCC 自身调试页面持有本地 lease。

用户必须在 EVCC 的“服务 → 实验功能 → EVCC 开放 API → 开放 API 控制台 → 开放权限的应用”中授权 GeePlay。

授权绑定以下身份：

```text
packageName + UID + APK certificate SHA-256 + grant revision
```

重新签名、Debug/Release 包名变化、UID 变化都需要重新授权；共享 UID 应用会被拒绝。

### 3.2 API 1.2 能力

- 属性目录、解析、单项/批量读取、监听、写入和批量写入；
- 实时全量扫描，需要单独授权；
- 变量查询、读取、监听、写入和删除；
- `app.launch`、`home.show`、`toast.show` 和 `automation.trigger` 动作；
- Shell 提交、取消和异步完成回调，需要独立高风险授权；
- 音乐状态、歌词、封面、BPM、订阅和播放控制；
- 音量组查询、读取、绝对设置和相对增减；
- 属性来源：`VHAL`、`APVP`、`VEHICLE_AIDL`、`CAPI`、`ADAPT_API`、`SOA`、`GEELY_CAPI`、`GEELY_AIDL`。

所有 `IEvccApiSession` 业务方法都是同步 Binder 调用，不能在 Android 主线程执行。官方 `EvccApiClient` 负责 Binder death、退避重连、会话恢复、事件序号检查和订阅恢复。

### 3.3 API 1.2 音量组

API 1.2 新增以下同步方法：

| 方法 | 权限 | 说明 |
| --- | --- | --- |
| `queryVolumeGroups()` | `volume.read` | 查询当前车型公开的音量组 |
| `readVolumeGroup(groupId)` | `volume.read` | 读取当前值、范围和步长 |
| `setVolumeGroup(request)` | `volume.control` | 设置绝对音量 |
| `adjustVolumeGroup(request)` | `volume.control` | 按增量调整音量 |

音量组是 EVCC/GeePlay 的本地车辆控制能力，不属于 CarPlay `VehicleStatusUpdate`。接入时需要遵守：

- `apiMinor >= 2` 后才允许调用；
- `volume.read` 和 `volume.control` 独立授权；
- 写入成功返回的是下发目标，当前系统值需要再次调用 `readVolumeGroup()`；
- `TIMEOUT` 或 Binder 异常意味着执行结果不确定，尤其不能盲目重试 `adjustVolumeGroup()`；
- SDK 重连后恢复订阅，但不会重放音量写入；
- CarPlay 媒体音量、Android 音频流和车辆音量组需要显式建立映射，不能按 group ID 或顺序猜测。

当前公开源码包为 `evccapi-demo-source-1.2.0.zip`，发布 SHA-256 为：

```text
d009a806587ba766e2408d00c83acc960e63729c9123507eaf24138350cf6454
```

## 4. 当前 GeePlay 可以向 CarPlay 上送的车辆数据

### 4.1 VehicleStatus

实现位于 `shared/src/main/java/com/shilapi/xcertplay/transport/Iap2VehicleStatus.kt`。

Identification 使用：

- 参数 20：`VehicleInformation`
- 参数 21：`VehicleStatus`

车辆被声明为电动车，并附带充电接口类型。iPhone 发送：

```text
0xA100 StartVehicleStatusUpdates
```

GeePlay 才开始发送：

```text
0xA101 VehicleStatusUpdate
```

iPhone 发送 `0xA102 StopVehicleStatusUpdates` 后停止。当前刷新周期为 30 秒。

当前编码字段：

| 参数 | 字段 | GeePlay 模型 |
| ---: | --- | --- |
| 3 | `Range` | `rangeKm` |
| 6 | `RangeWarning` | `rangeWarning` |
| 11 | `RangeElectric` | `rangeKm` |
| 15 | `RangeWarningElectric` | `rangeWarning` |
| 21 | `MinBatteryCharge` | 有电量数据时发送 0 |
| 22 | `CurrentBatteryCharge` | `currentChargeWh`，可省略 |
| 23 | `MaxBatteryCharge` | `maxChargeWh`，可省略 |
| 24 | `DisplayedBatteryPercentage` | `batteryPercent * 1000` |
| 25 | `isCharging` | `charging` |
| 30 | `MaxRangeElectric` | `maxRangeKm` |

静态 VehicleInformation 还包含：

- 车辆名称和显示名称；
- `EngineType = electric`；
- `CCS2/Type2`、`GB/T` 或 `CCS1/J1772` 充电接口声明。

### 4.2 LocationInformation

实现位于：

- `shared/src/main/java/com/shilapi/xcertplay/transport/Iap2LocationClient.kt`
- `shared/src/main/java/com/shilapi/xcertplay/transport/VehicleSpeedNmea.kt`

iPhone 发送 `0xFFFA StartLocationInformation` 后，GeePlay 才发送 `0xFFFB LocationInformation`，约每秒一次。

当前可以发送：

- `$GPGGA`：经纬度、海拔、定位精度和时间；
- `$GPRMC`：经纬度、GPS 速度、方向和时间；
- `$PASCD`：车轮速度样本和 P/R/N/D 挡位。

`$PASCD` 只在 iPhone 的 `0xFFFA` 请求包含组件 4 时发送。当前实现约每 250 ms 采集一次轮速、每秒读取一次挡位，并把两次 LocationInformation 之间的样本整批发送。

### 4.3 当前不是车辆属性的反向输入

GeePlay 已经能将 Geely OneOS/Flyme 输入转给 CarPlay：

- 播放/暂停；
- 下一首；
- 上一首；
- Siri。

这些走 CarPlay HID/媒体控制，不是 VehicleStatus 属性，也不是 EVCC 属性写入。

## 5. 当前 GeePlay 产品的缺口

协议层虽然支持 VehicleStatus 和 `$PASCD`，但 `CarPlayHostActivity` 目前仍硬编码 BYD 数据源：

- 是否声明 VehicleStatus 读取 `BydOutputSettings.batteryToIphoneActive()`；
- 车辆状态使用 `BydNavigationOutputs.batteryStatus()`；
- 轮速和挡位使用 `BydNavigationOutputs.wheelSpeed()`。

`VehicleIntegrationProvider` 目前只提供 session hooks、媒体策略、设置、显示策略和产品身份，没有提供品牌中立的车辆状态/轮速数据面。

因此当前 GeePlay 状态是：

- Android GPS 可以按现有设置上送；
- Geely 方向盘媒体键和 Siri 可以转成 CarPlay 输入；
- EVCC 电池、续航、充电、轮速和挡位尚未接入；
- EVCC 可写车辆属性尚未接入 GeePlay UI；
- Geely 构建不会因为安装了 EVCC 自动获得车辆状态上送能力。

## 6. EVCC 到 CarPlay 的第一批候选映射

旧 EVCC 3.2.14 APK 内置 `assets/geely/catalog.json` 共 5,508 条记录：

- `R`：3,857 条；
- `RW`：350 条；
- `W`：1,301 条。

该目录标注的样本固件为 `qti/e171/e171:12/.../user/test-keys`，只能用于发现候选，不能视为所有 Geely 车型或新版 EVCC 1.2 的稳定协议。正式实现必须通过 1.2 运行时目录重新验证。

以下是旧 APK 静态目录阶段提出的历史候选。2026-10-11 的 G636 运行时验证已经证明，
其中多个 ID 在可用 source 中具有完全不同的语义，不能直接进入生产 Profile：

| CarPlay/GeePlay 字段 | EVCC 候选名称 | ID | 类型/访问 |
| --- | --- | ---: | --- |
| SOC | `TYPE_EV_BATTERY_PERCENTAGE` | `4195840` | Float / R |
| 剩余续航 | `SENSOR_TYPE_ENDURANCE_MILEAGE_EV` | `1049088` | Float / R |
| 车速 | `SENSOR_TYPE_CAR_SPEED` | `1048832` | Float / R |
| 备选车速 | `SENSOR_TYPE_CAR_SPEED_FROM_IPK` | `1070336` | Float / R |
| 挡位 | `SENSOR_TYPE_GEAR` | `2097664` | Int32 / R |
| 车身挡位状态备选 | `SENSOR_TYPE_GEARMOV_STS` | `1068800` | Int32 / R |
| 交流充电状态 | `SENSOR_TYPE_ONBD_CHARGING_STATUS` | `1064448` | Int32 / R |
| 直流充电状态 | `SENSOR_TYPE_DC_CHARGING_STATUS` | `1064704` | Int32 / R |

映射约束：

- 历史方案要求先确认 `GEELY_CAPI` 可用；G636 实测后，生产 Profile 改为显式检查
  `ADAPT_API` 和 `CAPI`。
- 使用普通目录和 `resolveProperty()` 核对 descriptor，不默认执行 `LIVE_FULL_SCAN`。
- 读取时使用显式 `source + id + areaId`，避免不同来源同 ID/名称歧义。
- 挡位和充电状态必须在目标车上确认 enum 值，不能根据名称猜测。
- `currentChargeWh` 和 `maxChargeWh` 没有可靠属性时保持 `null`，让现有编码器省略，不伪造为 0。
- `maxRangeKm` 可以暂时沿用当前 BYD 算法，由当前续航和 SOC 估算；有可靠额定续航字段后再替换。
- `rangeWarning` 继续由本地低电量阈值计算。
- 任何值过期、来源换代、授权失效或事件序号缺口都必须使快照失效，不能继续发送旧车辆状态。

### 6.1 G636 运行时验证结果（2026-10-11）

独立只读 Probe 在 EVCC 3.2.14 / EvccApi 1.1 上完成全目录扫描，并对最终候选执行
`resolve/read/subscribe`。12 个目标 selector 全部收到 `INITIAL_SNAPSHOT`：

| 字段 | 已验证 selector | 观测值 |
| --- | --- | --- |
| SOC | `ADAPT_API:1051136 / SENSOR_TYPE_EV_BATTERY_LEVEL` | `94 → 88` |
| SOC 交叉验证 | `VEHICLE_AIDL:561024570 / paegyhvbattsoc` | 与主值一致 |
| EV 续航 | `ADAPT_API:1054976 / SENSOR_TYPE_ENDURANCE_MILEAGE_EV` | `84 → 78 km` |
| 车速 | `ADAPT_API:1048832 / SENSOR_TYPE_CAR_SPEED` | 静止时 `0.0` |
| 车速备选 | `ADAPT_API:1055232 / SENSOR_TYPE_CAR_SPEED_FROM_IPK` | 静止时 `0.0` |
| 挡位 | `CAPI:289408000 / GEAR_SELECTION` | `4=PARK` |
| 充电状态 | `ADAPT_API:605225728 / CHARGE_FUNC_CHARGING_STATE` | 未充电时 `0` |

真机同时证明：

- `ADAPT_API:4195840` 是 `TYPE_JOY_LIMIT_STATE`，不是 SOC；
- `ADAPT_API:1049088` 是 `SENSOR_TYPE_PM25_AMBIENT`，不是续航；
- 车速备选正确 ID 是 `1055232`，不是 `1070336`；
- `GEELY_CAPI`/`GEELY_AIDL` 未启用；可用 source 为 `ADAPT_API`、`CAPI`、
  `VEHICLE_AIDL`；
- `VEHICLE_AIDL:557874322` 返回的续航估算与标准 EV 续航不同，不能作为无条件 fallback；
- 充电口连接只表示插枪，不得替代 `isCharging`。

生产代码仍保持 EvccApi 1.2 门禁；以上 1.1 结果用于修正车型 Profile，安装新版 EVCC 后
必须再次验证 selector、车速单位和充电态正值。

## 7. CarPlay 不能直接承载的 EVCC 写能力

当前 Vehicle 协议只有：

```text
iPhone -> GeePlay: 0xA100 开始订阅
GeePlay -> iPhone: 0xA101 车辆状态
iPhone -> GeePlay: 0xA102 停止订阅
```

没有通用的 `SetVehicleProperty` 或属性写入消息。因此以下 EVCC 能力不能通过当前 CarPlay UI 自动暴露：

- 空调温度、风量、AC、电动出风口；
- 座椅加热、通风和按摩；
- 方向盘加热；
- 门锁、车窗、后视镜和尾门；
- 氛围灯和驾驶模式；
- EVCC 1.2 音量组；
- ADAS、泊车或其他车辆设置；
- EVCC 目录中的任意 W/RW 属性。

这些能力可以在 GeePlay 的 Android 侧边栏中提供经过审核的控件。若希望由 iPhone 上的 CarPlay App 操作车辆，需要另一套 Apple 认可的 App、Entitlement 和车厂协议设计，不属于当前 `VehicleStatusUpdate` 通道。

写入必须遵守以下门禁：

1. 只允许经过实车验证的显式 allowlist。
2. 禁止将 EVCC 目录中的 `W/RW` 条目自动生成控制界面。
3. 使用明确的 source、property ID、area 和类型，不用模糊名称或无条件 `AUTO`。
4. 首次调用 `writeProperty(validateOnly = true)`。
5. 由用户明确操作和确认后再执行真实写入。
6. 写入后读取状态确认，不把 Binder 返回 `OK` 等同于车辆物理动作成功。
7. 行驶相关、安全相关、挡位、转向、ADAS 和泊车属性默认禁用。

## 8. 实验性 CarPlay VehicleStatus 字段

项目文档记录过基于 Xcode CarPlay Simulator iAP2 定义和实车的实验：

- `OutsideTemperature`
- `InsideTemperature`
- `WiperStatus`
- `BarometricPressure`
- `Alerts`
- `PassengerSeatStatus`

结果是：

- iPhone 接受这些 Identification 字段；
- iPhone 会订阅外部温度、雨刷、大气压、Alerts 和乘客座椅状态；
- iPhone 没有订阅内部温度；
- 实际发送外温、雨刷和双闪后，CarPlay/Apple Maps 没观察到可见变化；
- 当前生产编码器没有实现这些字段。

因此这些字段不建议进入第一期。尤其不能把 TPMS 胎压映射成 `BarometricPressure`：胎压和环境大气压语义不同。胎压目前只适合显示在 GeePlay 自己的侧边栏。

## 9. 推荐代码结构

```text
EVCC Binder / EvccApi 1.2
          │
          ▼
EvccVehicleGateway (:vehicle-geely)
  ├─ 授权、连接和重连
  ├─ capability / directory 探测
  ├─ 多消费者订阅集合聚合
  ├─ 类型、单位、枚举和来源转换
  ├─ 新鲜度、sourceGeneration 和 resync 管理
  └─ volume group 发现、读取和命令确认
          │
          ├── VehicleStatusProvider cache
          │       └── Iap2VehicleStatusReporter -> 0xA101 -> iPhone
          │
          ├── VehicleSpeedSource sample buffer
          │       └── VehicleSpeedLocationProvider -> $PASCD/0xFFFB -> iPhone
          │
          └── VehicleCommandPort
                  └── GeePlay side panel -> validate -> write -> read-back
```

### 9.1 品牌中立接口

建议在 `:shared` 的 `VehicleIntegrationProvider` 上增加一个可选车辆数据面，例如：

```kotlin
data class VehicleDataPlane(
    val statusProvider: VehicleStatusProvider? = null,
    val speedSource: VehicleSpeedSource? = null,
    val chargingConnectors: EvChargingConnectors = EvChargingConnectors.CCS2_TYPE2,
)

interface VehicleIntegrationProvider {
    fun vehicleData(context: Context): VehicleDataPlane? = null
    // 保留现有 hooks/settings/media/display 方法。
}
```

`CarPlayHostActivity` 应从该接口取得 provider，而不是直接引用 `BydOutputSettings` 和 `BydNavigationOutputs`。普通 DiPlay 可以继续使用 BYD fallback，GeePlay 则由 `GeePlayApplication` 注入 EVCC 实现。

### 9.2 EVCC gateway 生命周期

EVCC gateway 应由 `GeePlayApplication` 或等价的进程级组件持有，不随每个 `VehicleSessionHooks` 重建。

原因：

- VehicleStatus 是否支持在 iAP2 Identification 阶段就已确定；
- `carPlaySessionStarted()` 到 AirPlay Session 活动后才触发，时间太晚，不能用于首次 EVCC 预热；
- 当前 `withVehicleStatusFrom()` 在握手时拿不到快照会整场会话不声明电动车状态；
- EVCC 授权或车辆数据设置变化后应提示“下次连接生效”，不能静默中断正在运行的 CarPlay。

推荐流程：

1. GeePlay 启动且用户启用联动时连接 EVCC。
2. 查询 API 版本、会话权限、能力，以及 Profile 所需的 `ADAPT_API`/`CAPI` 状态。
3. 解析并订阅 SOC、续航、充电、车速和挡位。
4. 等待有界时间获得初始快照。
5. 构建 `Iap2IdentificationConfig`，只声明已经有可信数据的能力。
6. CarPlay 运行期间继续通过订阅更新缓存。
7. 断连、撤权、来源换代或 resync 时先使旧缓存失效，再等待新的 `INITIAL_SNAPSHOT`。

### 9.3 订阅聚合

EVCC 的 `replaceSubscriptions()` 是整集合替换，不是追加。电池、轮速、侧边栏等消费者不能分别调用它，否则后调用者会覆盖前一个消费者。

Gateway 应维护：

```text
effectiveSubscriptions = batteryDemand
                       ∪ speedDemand
                       ∪ sidePanelDemand
                       ∪ diagnosticsDemand
```

只在集合变化时统一提交。`VehicleStatusProvider.snapshot()` 和 `VehicleSpeedSource.drain()` 必须只访问内存缓存，绝不能在 iAP2 control loop 中同步调用 EVCC Binder。

## 10. 分阶段建议

### Phase 1：只读上送

- 引入 `evccapi-contract` 和 `evccapi-client`，依赖限制在 `:vehicle-geely`。
- 在 `:geely` Manifest 声明 `ACCESS_EVCC_API` 和 package query。
- 实现 `EvccVehicleGateway`、缓存和订阅聚合。
- 接入 SOC、续航、充电状态、轮速和 P/R/N/D。
- 查询并缓存 API 1.2 音量组目录与当前值，但不在 CarPlay VehicleStatus 中声明音量。
- 保留现有 Android GPS，不必第一期改为 EVCC GPS。
- 支持有线和无线 runtime tunnel；Bluetooth bootstrap 仍不得声明长生命周期车辆数据。

### Phase 2：GeePlay 车辆控制

- 新增显式的 `VehicleCommandPort`，不向通用模块暴露 EVCC 原始对象。
- 先开放少量、低风险、实车已验证的空调或座椅控制。
- 控件放在 GeePlay 自己的侧边栏/设置中。
- 每个命令执行 validate、用户确认、真实写入和 read-back。
- 音量控制优先使用 1.2 专用 `setVolumeGroup()` / `adjustVolumeGroup()`，不要降级为猜测的通用属性写入。
- `adjustVolumeGroup()` 遇到超时或 Binder 异常时先重新读取，禁止直接重放。

### Phase 3：实验 VehicleStatus 扩展

- 依据 iAP2 定义补充 OutsideTemperature、WiperStatus、Alerts、PassengerSeatStatus 等模型和编码。
- 仅在 iPhone 确实订阅对应字段时读取 EVCC。
- 用目标 iOS、车型和连接方式记录是否真正产生用户可见效果。

## 11. 建议验收矩阵

### 自动化

- EVCC descriptor 到 typed vehicle model 的转换；
- SOC、续航、速度的单位与边界；
- Geely 挡位和充电枚举；
- 数据过期和来源换代 fail-closed；
- `replaceSubscriptions()` 多消费者集合不互相覆盖；
- API 1.2 音量组的读写、权限不足、旧服务 `UNSUPPORTED` 和不确定结果处理；
- 无授权、撤权、Binder death 和 resync；
- iAP2 Identification 只声明有可信初值的字段；
- wired/wireless 输出一致，Bluetooth bootstrap 不声明车辆数据。

### 实车

- EVCC 目录中目标属性在具体车型/固件上可见；
- SOC、续航、车速、挡位和充电状态与仪表一致；
- iPhone 是否发送 `0xA100` 和请求 `$PASCD`；
- Apple Maps 是否使用续航和低电量警告；
- 隧道中是否实际使用轮速进行航位推算；
- EVCC 重启、撤权、升级、车辆源断开后的恢复；
- 写入属性的物理结果、读回结果和断线安全性。

只有完成实车验证后，才能把某个属性或车型标记为 `VEHICLE_CONFIRMED`。
