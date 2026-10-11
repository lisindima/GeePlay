# EVCC 1.2 与 Siri 语音车控方案分析

状态：方案调研完成，尚未实施

分析日期：2026-10-11

关联文档：[EVCC-1.2-CARPLAY-INTEGRATION-ANALYSIS.md](EVCC-1.2-CARPLAY-INTEGRATION-ANALYSIS.md)

## 1. 结论

只做 Siri 语音车控是可行的，但不能只修改 GeePlay Android 端。

当前 GeePlay 只能向 iPhone 发送“唤起 Siri”的输入事件，无法收到 Siri 识别后的“打开空调”“车窗降一半”等语义命令。要让 Siri 最终调用 EVCC 1.2，必须增加 iPhone 侧入口，把语音意图转换为经过鉴权的结构化命令，再传给 GeePlay。

推荐链路：

```text
“Hey Siri，让 GeePlay 把空调调到 22 度”
                    │
                    ▼
iOS App Intent / App Shortcut
                    │
            结构化并签名的命令
                    │
                    ▼
GeePlay CompanionCommandGateway
                    │
         车型、速度、挡位、安全校验
                    │
                    ▼
EVCC 1.2 validateOnly
                    │
               用户确认策略
                    │
                    ▼
EVCC 1.2 writeProperty / volume API
                    │
                    ▼
              read-back 验证
                    │
                    ▼
        Siri：“空调已设置为 22 度”
```

如果只是个人使用或功能验证，最快方案是“iPhone 快捷指令/App Intent + 本地 HTTPS + GeePlay + EVCC”。如果需要公开发布、原生 CarPlay 车辆设置页面和完整 Siri 车控，则需要 Automaker App 或 CarPlay Ultra/OEM 合作。

## 2. 当前 GeePlay 为什么不能直接接收 Siri 命令

当前 Siri 路径位于：

```text
方向盘语音键
→ GeePlay
→ CarPlayController.requestSiri()
→ AirPlaySession.invokeSiri()
→ iPhone 打开 Siri
```

相关实现：

- `shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt`
- `shared/src/main/java/com/shilapi/xcertplay/vehicle/VehicleSessionHooks.kt`
- `vehicle-geely/src/main/java/com/lllhys/geeplay/vehicle/geely/GeelyVehicleInput.kt`

这条链路只把方向盘按键转换成 CarPlay HID/Siri 唤起操作。Siri 的语音识别、意图匹配和执行发生在 iPhone 上，当前 iAP2 控制通道不会把解析后的语义命令返回给 GeePlay。

当前 `VehicleHostControls` 只提供：

```kotlin
fun setClusterUiShown(shown: Boolean)
fun sendMediaButton(index: Int): Boolean
fun requestSiri(): Boolean
fun isCommunicationActive(): Boolean
```

不存在 `onSiriIntent()`、`setClimate()`、`openWindow()` 或通用车辆属性写入入口。

## 3. 当前 CarPlay VehicleStatus 不是车控协议

当前项目的 VehicleStatus 消息方向是：

```text
iPhone -> GeePlay: 0xA100 StartVehicleStatusUpdates
GeePlay -> iPhone: 0xA101 VehicleStatusUpdate
iPhone -> GeePlay: 0xA102 StopVehicleStatusUpdates
```

`0xA100` 和 `0xA102` 只是订阅管理，不是车辆控制。当前消息目录中没有以下通用命令：

```text
SetVehicleProperty
SetClimate
OpenWindow
SetSeatHeating
```

因此 EVCC 的 `writeProperty()` 和 `writeProperties()` 不能直接挂到当前 VehicleStatus 通道上。

## 4. Apple 官方车控能力边界

### 4.1 传统 CarPlay Automaker App

Apple 允许车厂发布 Automaker App，让驾驶员在 CarPlay 内查看和操作车辆专属功能。

官方资料：

- <https://developer.apple.com/carplay/>
- <https://developer.apple.com/videos/play/wwdc2017/719/>

这条路径通常需要：

- 一个安装在 iPhone 上的 iOS Automaker App；
- Apple 授予专门的 CarPlay entitlement；
- 车辆和 iOS App 之间使用匹配的 External Accessory protocol；
- 车型能力与应用可见性匹配；
- 车厂身份或正式车厂合作。

GeePlay 当前在 iAP2 Identification 中声明了 `com.shilapi.xcertplay` External Accessory protocol，但尚未实现完整的 EA session 数据通道，也没有配套 iOS App 和 Apple entitlement，因此不能直接利用 Automaker App 车控。

### 4.2 CarPlay Ultra

CarPlay Ultra/下一代 CarPlay 原生支持更完整的车辆状态和控制，包括：

- 空调开关；
- 分区温度；
- 风量和出风方向；
- 内外循环；
- 座椅加热和通风；
- 仪表、胎压和驾驶状态；
- 自定义车辆设置菜单；
- 用户操作后的 requested state change。

官方架构说明：<https://developer.apple.com/videos/play/wwdc2024/10111/>

这套能力基于 Vehicle State Protocol、Local UI、Remote UI、Automaker App 和车型资源配置，需要 Apple MFi 技术资料及 OEM 集成。GeePlay 当前具备 MFi 鉴权和传统 CarPlay 接收能力，不代表已经具备 CarPlay Ultra 的协议授权、车型资源和 entitlement。

### 4.3 SiriKit 车辆 Intent

Apple 曾提供以下 CarPlay SiriKit Intent：

- `INSetClimateSettingsInCarIntent`
- `INSetDefrosterSettingsInCarIntent`
- `INSetSeatSettingsInCarIntent`
- `INSetProfileInCarIntent`
- `INSetAudioSourceInCarIntent`

这些 Intent 已在 iOS 15 后废弃，不能作为新的 GeePlay Siri 车控基础：

- <https://developer.apple.com/support/deprecated-sirikit-intent-domains>
- <https://developer.apple.com/documentation/intents/insetclimatesettingsincarintent>

当前 SiriKit Car Commands 仍包含查询车辆状态、锁车和解锁等能力，但 Apple 将其定位为 automotive vendor 的 iOS App：

- <https://developer.apple.com/documentation/sirikit/car-commands>

它不提供通用的空调、车窗或任意 EVCC 属性写入接口。

## 5. 可选实施路径

### 5.1 方案 A：快捷指令/App Intent + 本地 HTTPS

适合个人使用、内测和快速验证。

iPhone 上的快捷指令或轻量 iOS App 发起结构化请求：

```http
POST /v1/vehicle/climate/power
Content-Type: application/json

{"enabled":true}
```

```http
POST /v1/vehicle/climate/temperature
Content-Type: application/json

{"zone":"driver","celsius":22.0}
```

```http
POST /v1/vehicle/window
Content-Type: application/json

{"window":"front_left","positionPercent":20}
```

优点：

- 开发快；
- 不需要扩展现有 CarPlay VehicleStatus 消息；
- 可以先验证 EVCC 属性、车型和 Siri 交互。

限制：

- 需要确认有线和无线 CarPlay 下 iPhone 到 GeePlay 的本地网络可达性；
- iOS 本地网络权限、锁屏和驾驶状态可能影响执行；
- Shortcut 的错误处理、设备选择和安全能力有限；
- 它不是 Apple 原生 CarPlay 车辆设置能力。

### 5.2 方案 B：iOS App + App Intents + External Accessory

适合长期维护的产品方案。

iOS App 使用 App Intents 定义业务命令，并通过 External Accessory 数据通道发送给 GeePlay。需要补齐：

- iOS App 的 `UISupportedExternalAccessoryProtocols`；
- 与 GeePlay Identification 一致的协议标识；
- iAP2 EA open/close/status 和数据 session；
- 请求、响应、版本、超时和幂等协议；
- Apple entitlement/MFi 合规确认；
- 后台和驾驶模式实机验证。

优点是可以绑定具体手机 App、保留完整结果并形成稳定协议；缺点是开发、签名和审核门槛较高。

### 5.3 方案 C：Automaker App / CarPlay Ultra

这是获得原生 CarPlay 车辆设置页面和完整车辆状态交互的正式路线，但依赖 Apple 和 OEM 合作，不适合作为当前 GeePlay 的近期实现前提。

## 6. 推荐的 Siri Intent

第一期建议只开放低风险、容易读回确认的命令：

| Intent | 示例短语 | EVCC 目标 |
| --- | --- | --- |
| `SetClimatePower` | “让 GeePlay 打开空调” | HVAC power/AC allowlist |
| `SetCabinTemperature` | “让 GeePlay 把驾驶位调到 22 度” | 分区温度 |
| `AdjustFanSpeed` | “让 GeePlay 把风量调高一级” | 风量绝对值或增量 |
| `SetAirCirculation` | “让 GeePlay 打开内循环” | 循环模式 |
| `SetSeatHeating` | “让 GeePlay 打开驾驶位座椅加热” | 座椅加热等级 |
| `SetSeatVentilation` | “让 GeePlay 打开驾驶位座椅通风” | 座椅通风等级 |
| `SetVehicleVolume` | “让 GeePlay 把媒体音量调到 10” | EVCC 1.2 volume group |

建议语音短语包含“GeePlay”，避免和 HomeKit、iPhone 自身或其他车辆 App 的命令冲突。

第二阶段才考虑：

- 车窗；
- 天窗；
- 门锁；
- 尾门；
- 驾驶模式。

以下能力不应开放给 Siri：

- 挡位；
- 转向；
- ADAS 开关；
- 自动泊车；
- 制动或动力系统；
- 任意 EVCC property ID；
- 任意 Shell 命令。

## 7. GeePlay 命令协议

Siri/iOS 侧只能发送业务命令，不能发送 EVCC 原始属性：

推荐：

```json
{
  "requestId": "UUID",
  "command": "climate.set_temperature",
  "parameters": {
    "zone": "driver",
    "celsius": 22.0
  },
  "issuedAt": 1791680000000,
  "nonce": "random-value"
}
```

禁止：

```json
{
  "source": "GEELY_CAPI",
  "propertyId": 268503552,
  "value": 22
}
```

GeePlay 内部再执行：

```text
业务命令
→ 当前车型 profile
→ 固定 EVCC source/property/area/type
→ 安全策略
→ validateOnly
→ 实际写入
→ read-back
→ 结构化结果
```

这样 EVCC 升级、车型变化或属性 ID 改变时，不需要修改 Siri Shortcut，也不能通过构造请求绕过车辆命令白名单。

## 8. 安全模型

### 8.1 通信鉴权

本地 HTTP 也必须视为外部控制接口，至少需要：

- iPhone 与 GeePlay 首次面对面配对；
- 每台手机独立密钥；
- TLS 或应用层加密；
- timestamp 和 nonce 防重放；
- 短期 session token；
- 请求 ID 去重；
- 只接受当前已配对设备；
- 限流和失败锁定；
- 日志不记录密钥、完整命令参数或敏感车辆值。

不得开放无认证端口，不得仅依赖 CarPlay Wi-Fi 地址作为身份。

### 8.2 车辆状态门禁

命令执行前需要检查：

- EVCC 1.2 会话仍有效；
- 当前授权包含所需 permission；
- 属性或音量组属于当前车型 allowlist；
- 车辆状态缓存仍新鲜；
- 当前车速、挡位和其他前置状态满足策略；
- 用户确认要求已经完成。

建议策略：

- 温度、风量和媒体音量可以在行驶中执行，但仍需限幅；
- 车窗、天窗和尾门至少检查速度与挡位；
- 门锁需要独立确认和更强身份校验；
- 任何安全状态未知时 fail-closed；
- 不允许用 Siri 执行自由 Shell。

### 8.3 EVCC 写入规则

- 使用明确的 source、property ID、area 和值类型；
- 通用属性先调用 `writeProperty(validateOnly = true)`；
- 实际写入后调用读取接口确认车辆状态；
- `OK` 只表示调用被接受，不自动证明物理动作完成；
- `TIMEOUT` 或 Binder 异常时先读取状态，不直接重放非幂等操作；
- 音量使用 EVCC 1.2 专用 volume API，不伪装为通用属性写入；
- `adjustVolumeGroup()` 结果不确定时禁止盲目重试。

## 9. 推荐代码结构

```text
:ios-companion
  ├─ App Intents / App Shortcuts
  ├─ 配对与凭据存储
  └─ GeePlay command transport

:vehicle-geely
  ├─ EvccVehicleGateway
  ├─ VehicleCommandCatalog
  ├─ VehicleCommandPolicy
  ├─ CompanionCommandGateway
  └─ VehicleCommandAudit

:shared
  └─ 品牌中立的 VehicleCommand / VehicleCommandResult

:geely
  ├─ Manifest 权限与网络配置
  └─ composition root
```

`VehicleCommandCatalog` 只维护经过实车验证的业务命令到 EVCC 属性映射。`CompanionCommandGateway` 不能直接依赖来自网络的 property ID，也不能调用 EVCC Shell。

## 10. 推荐实施顺序

### Phase 1：EVCC 本地控制闭环

1. 完成 EVCC 1.2 授权、能力发现和属性目录验证。
2. 在 GeePlay 内实现空调、温度、风量、座椅和音量的 typed command。
3. 先通过本地调试 UI验证 `validate → write → read-back`。
4. 建立速度、挡位、权限和数据新鲜度门禁。

### Phase 2：iPhone 快捷指令原型

1. 增加带配对认证的本地 command endpoint。
2. 制作少量手工 Shortcut。
3. 分别验证有线和无线 CarPlay 网络。
4. 验证锁屏、Siri、驾驶专注模式和网络切换。

### Phase 3：iOS App Intents

1. 将 Shortcut 请求迁移为正式 iOS App Intent。
2. 提供设备选择、参数解析、确认和结构化错误返回。
3. 验证 Siri 不打开 App 时可完成后台请求。
4. 评估 External Accessory 取代本地 HTTPS 的可行性。

### Phase 4：Apple/OEM 路线评估

如果需要原生 CarPlay 车辆设置页、无需产品名前缀的自然车控短语或 CarPlay Ultra UI，再单独评估 Automaker entitlement、MFi 技术协议和 OEM 合作。

## 11. 验收要求

- Siri 识别错误不能触发高风险车辆操作；
- 请求重复、网络重试和 App Intent 重入不会重复执行增量命令；
- 相同 request ID 只执行一次；
- EVCC 不在线、撤权、来源不可用时返回明确失败；
- 车型不匹配时不显示或执行命令；
- 命令执行成功后能够读取到期望状态；
- 车速、挡位和时间戳未知时拒绝敏感命令；
- 有线、无线、锁屏和驾驶专注模式分别记录结果；
- iPhone 与车机断开后，旧 token 和旧请求不能继续控制车辆；
- 日志默认脱敏且不保存语音文本、密钥或完整车辆标识。

只有在目标 iPhone/iOS、Geely 车型、车机固件和 EVCC 版本上完成实车验证后，才能把 Siri 车控标记为可发布能力。
