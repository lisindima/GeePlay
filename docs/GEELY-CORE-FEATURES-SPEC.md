# GeePlay 吉利车机核心功能复刻与扩展 Spec

状态：实施中，软件基线已完成，厂商接口和物理车辆能力仍按证据门禁开放

基线分支：`geely/main`

起始提交：`4314bbe133599b21f40d23d3bf3de3f0d45353b4`

参考资料：

- 样本 APK 的事实分析：[GEELY-APK-0.2.13-ANALYSIS.md](GEELY-APK-0.2.13-ANALYSIS.md)
- 实施记录：[GEELY-IMPLEMENTATION-PROGRESS.md](GEELY-IMPLEMENTATION-PROGRESS.md)
- 验证矩阵：[GEELY-VALIDATION.md](GEELY-VALIDATION.md)

## 1. 背景与产品决定

GeePlay 基于当前仓库的 DiPlay CarPlay receiver 架构，为吉利/ECARX 车机提供独立产品组合。目标不是逐类复制样本 APK，而是复刻其可观察的核心行为，并把实现建立在品牌中立的 RouteGuidance、会话生命周期和媒体策略边界之上。

样本 APK 的分析包含 AutoDisplay 协作行为，但该行为只作为历史事实保留。GeePlay 的产品决定是：

- 不依赖、查询、绑定、广播给或接收来自 AutoDisplay 的数据；
- 不包含 AutoDisplay 专用模块、设置、权限、action 或协议；
- 不暴露继承自 `:common` 的通用 `MapEmbedService`，因此外部 launcher 和 AutoDisplay 都不能从 GeePlay 嵌入 stream `111`；
- 通用 DiPlay 产品自身已有的 launcher MapEmbed 能力不删除，仍由 `:common` 和 `docs/LAUNCHER_INTEGRATION.md` 维护。

该决定优先于样本 APK 的行为一致性。

## 2. 目标、范围与非目标

### 2.1 产品目标

1. 保持现有有线、无线 CarPlay 主链路和 BYD 产品行为可回归。
2. 将 iAP2 RouteGuidance `0x5201/0x5202/0x5204` 抽象为品牌中立、可测试、带来源代际的数据面。
3. 在合法厂商权限存在时，通过 ECARX 导航接口输出道路、距离、转向、车道和结束清理。
4. 允许用户选择 GeePlay 或原车蓝牙拥有音乐；原车蓝牙模式仍保留导航、Siri 和电话本地音频。
5. 在明确设置、会话和通话门禁下支持已验证的 OneOS/Flyme 方向盘或语音入口。
6. 支持 stream `111` 的通用解码、本地 turn card 和经白名单车型验证的吉利物理仪表目标。
7. 提供独立 application ID、品牌身份、更新通道、诊断、设置和安全发布约束。

### 2.2 功能范围

| 能力 | 当前仓库基础 | GeePlay 目标 | 发布门禁 |
| --- | --- | --- | --- |
| 有线/无线 CarPlay | 已有 | 保持兼容 | 自动化 + 烟测 |
| `0x5201/0x5202` | BYD 状态与输出 | 品牌中立 parser/store/output | 自动化 |
| `0x5204` lane | 仅 endpoint 名称 | capability、解析、缓存、ECARX 输出 | 自动化 + 真实帧 + 实车 |
| ECARX 导航 | 无 | watched/direct 连接、发布、清理、恢复 | fake SDK + 厂商权限 + 实车 |
| 原车蓝牙音乐 | 全音频关闭式开关 | 逐媒体类别 owner policy | 自动化 + 实车音频 |
| OneOS/Flyme 输入 | 无 | 生命周期接线、超时、重连、风险门禁 | 自动化 + 固件实车 |
| stream `111` | 已解码和镜像 | 本地展示；吉利物理屏按 profile 开放 | 自动化 + 车型白名单 |
| 路口语义图 | 静态 maneuver icon | 有界 junction angle 简化图 | 自动化 + 真实帧后实验开放 |
| 产品身份 | DiPlay | GeePlay/GEELY、独立更新和签名 | 构建与 manifest 检查 |
| 设置与诊断 | 通用 | 吉利卡片、状态、恢复证据 | locale/layout/截图 |

### 2.3 明确不做

- 不实现 AutoDisplay 互操作，也不保留“未来可能启用”的隐藏协议。
- 不在 GeePlay 中开放通用 launcher MapEmbed service。
- 不提交反编译源码、样本 APK 资源、厂商私有 SDK 包或未经授权的图标。
- 不提交 MFi、Android release keystore、证书、token 或签名摘要。
- 不绕过 `com.permission.adnaviservice` 等 signature/白名单权限。
- 不把类存在、反射调用成功、stream `111` 存在或模拟器结果描述为实车已支持。
- 不在没有成对进入/恢复证据时猜测物理 display ID 或强制替换 OEM 画面。
- XUI/OneOS 2 在缺少可靠注册/注销协议时只做 probe，不提供接管开关。

## 3. 证据模型

所有厂商相关能力统一使用三层结论：

| 等级 | 含义 |
| --- | --- |
| `AVAILABLE` | 类、服务、权限声明、方法签名或显示目标存在 |
| `DELIVERED` | 本地调用返回成功，或目标 Surface 收到并呈现帧 |
| `VEHICLE_CONFIRMED` | 在记录完整车型、固件、iPhone、iOS、地图 App 和连接方式的实车上确认用户可见/可听行为及清理 |

只有 `VEHICLE_CONFIRMED` 可以对外声称“支持该车型/固件”。软件自动化完成使用 `SOFTWARE_VERIFIED`，不能替代实车证据。

## 4. 目标架构

### 4.1 模块依赖

```text
:geely ───────────────> :common ───────────────> :shared
   └──────────────────> :vehicle-geely ────────> :shared

:mobile / :automotive / :home / :maphost
   └──────────────────> existing common/shared paths
```

约束：

- `:shared` 不依赖 Android UI、Geely 或 AutoDisplay 包。
- `:common` 提供通用 Activity、设置 renderer、session host 和通用 DiPlay MapEmbed 实现，但不知道 ECARX/OneOS 类。
- `:vehicle-geely` 独占 Geely reflection、Binder、设置模型、恢复 journal、车型探测和诊断。
- `:geely` 是 composition root，组合 `:common`、`:shared` 和 `:vehicle-geely`，并通过 manifest 移除 `MapEmbedService`。
- 通用产品不得反向依赖 `:vehicle-geely` 或 `:geely`。

### 4.2 `:shared` 品牌中立边界

```text
navigation/model/       immutable route/lane/junction snapshots
navigation/apple/       iAP2 payload parser and limits
navigation/runtime/     source ownership, store, output fan-out
vehicle/                product/session hooks and settings descriptors
media/                  LocalMediaAudioPolicy
```

关键接口：

```kotlin
interface VehicleSessionHooks : Closeable {
    fun attachHostControls(controls: VehicleHostControls)
    fun sourceOpened(source: RouteGuidanceSource)
    fun sourceReady(source: RouteGuidanceSource)
    fun onControlFrame(source: RouteGuidanceSource, frame: Iap2Frame)
    fun carPlaySessionStarted(sessionToken: Any, stillActive: () -> Boolean)
    fun carPlaySessionEnded(sessionToken: Any, replacementExpected: Boolean)
    fun close()
}

interface VehicleIntegrationProvider {
    fun createVehicleSessionHooks(context: Context): VehicleSessionHooks
    fun mediaAudioPolicy(context: Context): LocalMediaAudioPolicy
    fun vehicleSettings(context: Context): VehicleSettingsController?
    fun externalMapEmbeddingAvailable(context: Context): Boolean
    fun clusterDisplayPolicy(context: Context): VehicleClusterDisplayPolicy?
    fun requiresClusterStream(context: Context): Boolean
}
```

`VehicleIntegrationProvider` 只负责组合，不承载数据面业务。每个 Controller generation 创建独立 hooks；hooks 只能持有 application context 和带代际的命令端口，不持有 Activity/View。

GeePlay 的 `externalMapEmbeddingAvailable` 固定为 `false`，设置页不展示 launcher sharing 控件，manifest 同时删除对应 service。UI 隐藏和组件移除必须同时存在，不能只依赖一个开关。

### 4.3 会话数据流

```text
wired control ─────────┐
BT bootstrap ──────────┼─> RouteGuidanceCoordinator ─> immutable snapshot
wireless type-130 ─────┘              │
                                      ├─> BYD compatibility output
                                      ├─> Geely navigation output
                                      └─> local turn/junction UI
```

每条 control channel 有不可复用的 `RouteGuidanceSource`。wireless type-130 tunnel ready 后成为 preferred；旧 Bluetooth bootstrap 可以继续承担无线控制，但不能回抢导航状态。

## 5. RouteGuidance 核心

### 5.1 数据模型

快照至少包含：

- session/source generation；
- connected、route active、driving side；
- current/next maneuver、道路名、剩余距离和时间；
- lane array、current index、showing/status、highlight angle；
- junction type、element angles 和 exit angle；
- revision、更新时间和过期状态。

所有集合不可变；parser 不返回厂商对象、Android Bitmap 或 Binder 引用。

### 5.2 所有权和边界

- source token 在打开 channel 时创建，在 channel close、connection restart 或 Controller close 时失效。
- preferred source 之外的帧可以解析用于诊断，但不能覆盖当前输出。
- session replacement 先推进 generation，再清空 route/lane/junction cache。
- 任何异步 publish/retry 在执行前检查 generation 和 `stillActive()`。
- route end、disconnect、source replacement 和 close 必须 fail-closed 清理所有 consumer。

### 5.3 防御性解析

- 文本使用严格 UTF-8；畸形序列原子拒绝，不使用替换字符静默修复。
- 道路名、lane description、lane 数量、junction element 和嵌套 TLV 都有明确上限。
- 支持协议中出现的 u8/u16 position、多个 angle 和 highlight sentinel。
- 单个 frame 解析失败不污染上一份完整 snapshot；错误原因以类别计数，不记录原始路线数据。

## 6. Lane guidance `0x5204`

### 6.1 iAP2 注册

- Identify RouteGuidance capability 声明参数 `7/8`。
- 接收消息白名单加入 `0x5204`。
- endpoint catalog 为 `0x5201/0x5202/0x5204` 提供一致 schema 和方向。
- wired、BT bootstrap、type-130 tunnel 使用同一 parser，但保留独立 source token。

### 6.2 ECARX 输出

每个 lane 转换成动态 `ILaneInfo` proxy；使用反射得到的精确 component type 创建 typed array。发布规则：

- lane Bitmap 由仓库自绘 renderer 生成，不使用样本资源；
- `iconId=0`，除非具体车型 profile 证明 OEM 更偏好 icon ID；
- `laneNumber` 的 0/1 基准来自车型 profile，未确认前保持实验状态；
- route end、lane hidden 或 source close 发布 typed empty array；
- 单 lane 渲染失败只清理 lane 输出，不破坏基础导航字段。

## 7. Geely ECARX 导航输出

### 7.1 Driver 探测

Driver 启动前同时验证：

- 目标 SDK 类存在；
- watched/direct 连接路径之一完整；
- 需要的方法名、参数和返回类型精确匹配；
- `com.permission.adnaviservice` 实际可用；
- 当前 session generation 仍活动；
- 用户已启用实验功能并确认 OEM 覆盖/恢复风险。

任一项不满足时不写 OEM，仅报告结构化不可用原因。

### 7.2 发布与清理顺序

1. 在任何 OEM 写入前持久化 pending cleanup journal。
2. 建立/确认 navigation presentation。
3. 发布基础 route 字段和 maneuver。
4. lane 有效时发布 typed lane array，否则发布 empty array。
5. route end、disable、source close、session end 或 app close 时清理 lane、route 和 presentation。
6. 清理确认后才删除 journal；进程重启时优先恢复清理，未确认前阻止新输出。

Driver 的 publish 使用单线程有界合并队列，只保留最新状态和不可丢弃 clear。异常、false/negative 返回和 callback 超时均隔离为失败类别，不进入无界重试。

### 7.3 Maneuver 与 junction

Apple maneuver 到 ECARX 编号使用显式表和 unknown fallback；不得通过 enum ordinal 偶合。junction 简化图只使用验证过的角度语义，默认关闭，不宣称为地图 App 原始路口大图。

## 8. 媒体 owner

### 8.1 策略

```kotlin
enum class MediaOutputOwner {
    DIPLAY,
    OEM_BLUETOOTH,
    PHONE_OR_EXTERNAL,
}
```

GeePlay 新安装默认 `OEM_BLUETOOTH`；升级时迁移旧 `oem_bluetooth_music_enabled`。连接开始时策略固化为不可变 `LocalMediaAudioPolicy`。

### 8.2 `OEM_BLUETOOTH` 行为

- 只抑制 MEDIA 类本地 renderer；导航、Siri、电话继续本地播放。
- 不创建 GeePlay media session，不请求媒体 audio focus，不接管音乐媒体键。
- 无线 tunnel ready 后可以保留 Bluetooth bootstrap，但 source coordinator 仍保证导航单 owner。
- 断连时关闭所有 transport，不泄漏 RFCOMM socket。
- 设置文案明确说明手机是否选择 A2DP 取决于车型/系统，提供恢复为 GeePlay owner 的路径。

owner 修改在下次连接生效，保存后调用 `markReconnectNeeded()`；不得静默断开运行中的 session。

## 9. 方向盘与语音输入

### 9.1 生命周期门禁

输入 adapter 仅在以下条件同时满足时 attach：

- 活动且 generation 有效的 CarPlay session；
- media owner 允许 GeePlay 消费对应媒体命令；
- 用户显式选择已支持 profile；
- 当前不在 communication/call 状态；
- 当前 generation 尚未 attach。

任一条件变化必须对称 detach；callback 只访问 `VehicleHostControls`，不持有裸 Controller。

### 9.2 OneOS Binder

实现已分析协议：

- bind `com.geely.service.oneosapi/.OneOSApiService`；
- service manager descriptor `com.geely.lib.oneosapi.IServiceManager`；
- transaction 2 获取 input service type 8；
- input descriptor `com.geely.lib.oneosapi.input.IInputManager`；
- transaction 3/4 注册/注销 listener；
- raw key `200085/200087/200088` 映射 play-pause/next/previous；
- 默认只消费 short gesture。

必须有 8 秒 bind timeout、2 秒有界重连、death recipient、epoch gate、对称 unregister 和 unregister 失败后的 Binder quarantine。

### 9.3 Flyme 语音入口

两个已知 alias 仅处理 `ECARX_KEY_ACTION_TYPE == 0`，250 ms 内去重。由于 sender 身份证据不足，该入口默认关闭，开启前展示可伪造事件和双语音助手风险。无 session、旧 generation、通话中或短时间重复时不请求 Siri。

## 10. stream `111`、物理仪表和路口

### 10.1 通用链路

stream `111` 仍由现有 AirPlay alt-screen、`MediaCodec` 和 `MapMirrors` 解码。主屏、本地 overlay 和物理 display 目标保持独立 Surface 生命周期；一个镜像失败不得终止主 CarPlay 视频。

### 10.2 GeePlay 产品边界

- GeePlay 不允许外部 app 请求 stream `111` 画布。
- `MapEmbedService` 不出现在 GeePlay merged manifest。
- 不存在 GeePlay/AutoDisplay action、signature permission、广播或 render-target lease。
- stream canvas 只由本地设置或经验证的 `GeelyClusterDisplayPolicy` 决定。

### 10.3 物理 display 策略

`GeelyClusterDisplayPolicy.VALIDATED_PROFILES` 默认空。profile 必须匹配精确 fingerprint/service version/display name/geometry，并定义成对的 enter/restore 行为。未匹配时：

- 设置项显示不可用且默认关闭；
- `requiresClusterStream()` 返回 false；
- 不回退到 BYD display 规则；
- 不猜测 display 1/2 或通过接口存在推断支持。

### 10.4 路口显示

- iPhone 若在 MAP/TURN_CARD/INSTRUMENTS stream 中绘制路口图，GeePlay 原样显示。
- 本地简化图只根据有界 junction angles 绘制支路和高亮出口。
- 缺少真实帧时只允许诊断/实验 gate，不得描述为 AMap 原始大图或完整 junction geometry。

## 11. 产品身份、Manifest 与发布

### 11.1 身份

- application ID：`com.lllhys.geeplay`；debug 增加 `.debug`。
- 默认产品名 `GeePlay`，OEM label `GEELY`。
- 返回车机图标使用项目自绘资源，不从样本 APK 提取。
- 更新 API 指向 GeePlay 自有 release endpoint。
- 遗留默认 `BYD` label 仅在用户未自定义图标/标签时迁移为 `GEELY`。

### 11.2 GeePlay Manifest

允许声明：

```xml
<queries>
    <package android:name="com.geely.service.oneosapi" />
</queries>
<uses-permission android:name="com.permission.adnaviservice" />
```

必须显式移除：

```xml
<service
    android:name="com.shilapi.xcertplay.MapEmbedService"
    tools:node="remove" />
```

merged manifest 门禁必须证明不存在：

- `com.shilapi.xcertplay.MapEmbedService`；
- `com.shihab.diplay.action.EMBED_MAP`；
- `com.lllhys.geeplay.action.EMBED_MAP`；
- `com.lllhys.geeplay.permission.NAVIGATION`；
- `com.simplerbit.autodisplay`。

### 11.3 凭据与许可证

- MFi/private signing 资产只能由明确环境变量注入。
- Gradle 构建必须拒绝把 `offline-mfi`、`.pk8`、`.p7b`、`.pem`、`.p12`、keystore 等意外打包。
- 对 APK 的学习只用于行为兼容；实现与测试必须独立编写。
- 新增代码遵守仓库现有许可证和 notices。

## 12. 设置与 UX

按驾驶员目标组织：

| 设置卡 | 分类 |
| --- | --- |
| 吉利原车导航、lane、junction | Navigation |
| 原车蓝牙音乐 | Audio |
| 吉利方向盘/语音入口 | Vehicle |
| 吉利物理仪表地图 | Advanced |
| 吉利接口、权限和恢复状态 | Diagnostics |

规则：

- 不向 Overview 增加普通设置；每个 section 恰好属于一个 category。
- experimental 标题使用各 locale 规定的括号后缀。
- 新 `settings_*` 文案覆盖 `values`、`ar`、`es`、`ru`、`uk`、`zh-rCN`、`zh-rTW`。
- Choice 行使用 `Title · Value` 和 chevron 语义。
- 下一次连接生效的设置调用 `markReconnectNeeded()`；只有“应用并重新连接”按钮可立即 reconnect。
- 物理仪表 profile 未验证时控件 disabled，不能只显示 warning 后仍允许打开。
- 不显示 AutoDisplay 或 launcher map sharing 设置。
- 布局变更提供 compact/full 大字体截图；行对齐变化提供 Arabic RTL 截图。

## 13. 诊断、隐私与恢复

诊断至少包含：

- product/build/version 和选中的 vehicle profile；
- SDK class/method/permission probe；
- session/source generation、preferred source 和各 frame 计数；
- maneuver/lane/junction 数量和拒绝原因；
- Driver 状态、最后 OEM 方法/结果类别；
- pending cleanup journal 和恢复结果；
- display profile 选择原因、stream/mirror/decoder 数量；
- OneOS bind/register/unregister/death/quarantine；
- media owner、实际本地 audio 类别和 Bluetooth retention。

不得记录道路名、lane description、SSID、蓝牙地址、证书、Binder token、原始 frame 或签名材料。诊断导出必须经过 `DiagnosticRedactor`。

## 14. 实施阶段

### Phase 0：产品与架构骨架

新增 `:geely`、`:vehicle-geely`、provider/hooks/settings seam；保持通用产品不依赖 Geely。

### Phase 1：RouteGuidance 中立化

完成 parser/store/source ownership/output fan-out，用兼容 adapter 保持 BYD 行为，移除 Controller 对 BYD 导航单例的直接依赖。

### Phase 2：Geely 基础导航

完成 ECARX Driver、maneuver、发布/清理、journal、恢复、设置和诊断。默认关闭，实车证据前不宣称支持。

### Phase 3：Lane 与 junction

加入 `0x5204` capability/parser/cache、typed lane array、Bitmap renderer、empty clear 和有界 junction geometry。

### Phase 4：媒体 owner 与产品身份

完成逐流音频策略、MediaSession gate、Bluetooth retention、GeePlay identity、更新和凭据拒绝门禁。

### Phase 5：方向盘输入

完成 OneOS lifecycle、Flyme 风险 gate 和 XUI probe-only；补齐 timeout/death/epoch/quarantine 测试。

### Phase 6：吉利物理仪表与实车闭环

维护 fail-closed display policy；只有取得精确 profile、enter/restore API、真实 `0x5204`/junction fixture 和录像后，才向 `VALIDATED_PROFILES` 增加车型。

AutoDisplay 不属于任何实施阶段。

## 15. 测试要求

### 15.1 自动化

至少覆盖：

- RouteGuidance strict parsing、source handoff、stale generation、route end；
- lane 数量/文本/角度上限、current/showing、typed empty clear；
- ECARX watched/direct、签名校验、OEM 拒绝、异常、publish 顺序和恢复 journal；
- output conflation 和不可丢弃 clear；
- media owner 对 MEDIA/NAVIGATION/ASSISTANT/PHONE 的不同处理；
- wireless Bluetooth retention 不回抢 preferred source；
- OneOS bind timeout、death、重连、旧 epoch、unregister quarantine；
- Flyme action type、alias 去重、session/call gate；
- display policy 空 allowlist fail-closed；
- GeePlay merged manifest 无 MapEmbed/AutoDisplay 暴露；
- settings IA、七种 locale、重连语义和 compact/full/RTL 截图；
- 通用 DiPlay `MapEmbedService` attach/resize/detach、sharing revoke 和 client death cleanup 回归。

### 15.2 实车矩阵

每条记录包含车型、车机、fingerprint、Android、ECARX/OneOS 版本、iPhone、iOS、地图 App、连接方式、commit 和 APK SHA-256。

| 场景 | 最低验证 |
| --- | --- |
| 基础导航 | 开始、更新、重规划、到达、取消、断连、重连 |
| 无线来源 | bootstrap 有数据、tunnel 接管、旧 source 不回抢 |
| Lane | 2/3/多车道、组合箭头、preferred、隐藏、路线结束 |
| 音频 | 音乐、导航、Siri、来电、挂断、owner 往返 |
| 方向盘 | 单击、长按、双击、重复事件、通话、service death |
| 物理地图 | MAP/TURN_CARD/INSTRUMENTS、暂停恢复、日夜、重连 |
| 恢复 | 正常退出、强停重开、服务重启、权限撤销 |

## 16. CI 与交付门禁

最终必须运行 AGENTS.md 基线命令，并追加 Geely 模块：

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug \
  :vehicle-geely:testDebugUnitTest :vehicle-geely:lintDebug \
  :geely:testDebugUnitTest :geely:lintDebug :geely:assembleDebug
```

交付前还必须：

1. 检查 `geely` merged manifest 的禁止项。
2. 检查 APK 内无凭据、AutoDisplay class/resource/action。
3. 记录 APK SHA-256、分支、HEAD 和 dirty state。
4. 将生成的 compact/full/Arabic 截图复制到 `docs/images/geely-settings/`。
5. 在模拟器冷启动并确认 GeePlay 进程无自身 `FATAL EXCEPTION`。

## 17. Definition of Done

一个能力只有同时满足以下条件才标记完成：

- 模块边界正确，没有 Controller → 厂商直接依赖；
- 默认值、风险文案、重连语义和 settings IA 正确；
- cleanup、旧 generation、进程恢复和失败隔离有测试；
- 完整 CI、lint、assemble 和 manifest/APK 安全检查通过；
- 诊断不泄漏路线或设备标识；
- 文档记录实际证据等级；
- 声称车型支持时附有完整实车矩阵。

## 18. 风险与待补证据

| 风险 | 处理 |
| --- | --- |
| ECARX 权限是 signature/白名单 | 正常 probe；不绕过；保持实验关闭 |
| SDK 方法随固件变化 | 启动前精确签名校验；按 profile 管理 |
| lane Bitmap/iconId/laneNumber 未确认 | profile 化；以真实两/三车道样本校准 |
| 保留 Bluetooth 影响 tunnel/audio | 单独策略、诊断、超时和回退 |
| Flyme 广播 sender 不可信 | 默认关闭、风险确认、频率和 session gate |
| 物理 display/恢复 API 不公开 | 空 allowlist；无成对恢复就不启用 |
| 真实 junction 数据不足 | 原样 stream 优先；语义图保持实验 |
| 上游热点持续变化 | 小接口、低 churn、定期合并 upstream |

当前仍需外部提供或实车采集：

1. 目标车型、fingerprint、Android、ECARX/OneOS 版本；
2. 普通签名 APK 获得 `com.permission.adnaviservice` 的合法方式；
3. 物理 display name/owner/geometry 和成对 enter/restore API；
4. 脱敏真实 `0x5204`、junction TLV 和 lane number/Bitmap 语义；
5. OneOS transaction 在目标固件上的注册、注销、death 和回调结果；
6. 各音频 owner 在有线/无线连接下的可听结果。

缺少上述证据时，相应能力保持默认关闭、disabled 或 probe-only。
