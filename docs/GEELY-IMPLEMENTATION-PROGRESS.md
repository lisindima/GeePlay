# GeePlay 吉利功能实施进展

依据：[GEELY-CORE-FEATURES-SPEC.md](GEELY-CORE-FEATURES-SPEC.md)

分支：`geely/main`

起始基线：`4314bbe133599b21f40d23d3bf3de3f0d45353b4`

本文记录实现、审计、自动化和外部证据边界。软件完成不等于车型确认；只有在 [GEELY-VALIDATION.md](GEELY-VALIDATION.md) 中记录完整实车条件后，厂商能力才能标记为 `VEHICLE_CONFIRMED`。

## 总体状态

| Phase | 内容 | 当前状态 |
| --- | --- | --- |
| 0 | 产品与架构骨架 | 软件与自动化完成 |
| 1 | RouteGuidance 中立化 | 软件与自动化完成 |
| 2 | Geely 基础导航 | 软件完成，待厂商权限与实车 |
| 3 | Lane / junction | 合成车道 builder/flush 时序已在 G636 验证；持久化真实 CarPlay 全链路诊断已安装，待通勤采集 |
| 4 | 媒体 owner 和产品身份 | 导航音频已在 G636 验证；媒体 owner/A2DP 仲裁仍待完整实车矩阵 |
| 5 | OneOS/Flyme 输入 | 软件完成，待目标固件实车 |
| 6 | 吉利物理仪表 | fail-closed 骨架完成，validated profile 为空 |
| 产品边界 | AutoDisplay / 外部 MapEmbed | 已完整移除，不属于产品范围 |

## 2026-10-10：实施启动

### 基线事实

- `CarPlayController` 原先直接调用 `BydNavigationOutputs`，wired、Bluetooth bootstrap 和 type-130 tunnel 共用无 source 参数的 route callback。
- `BydHudRouteState` 只解析 `0x5201/0x5202`；`Iap2Endpoint` 只有 `0x5204` 名称，未形成完整 lane 数据面。
- 旧“原车蓝牙音频”通过 `disableAudioOutput` 移除全部音频能力，不等价于只由原车蓝牙拥有音乐。
- 通用 DiPlay 已有 stream `111`、多 Surface、turn card 和 launcher `MapEmbedService` 基础。
- `docs/GEELY-APK-0.2.13-ANALYSIS.md` 是样本 APK 事实记录，实施期间保持独立，不把反编译实现复制进仓库。

### 架构决定

- 迁移顺序为“接口 → 品牌中立实现 → 厂商实现 → 调用方 → 测试”。
- 每条 iAP2 control channel 分配不可复用 source token；tunnel ready 后成为 preferred，旧 bootstrap 不得回抢。
- Apple parser/store 进入 `:shared`；ECARX reflection、OneOS Binder、Bitmap 和车型 profile 进入 `:vehicle-geely`。
- `:geely` 只做产品组合；`:mobile`、`:automotive` 和通用 DiPlay 不依赖 Geely 模块。
- 保留 BYD 行为和测试，不批量改包名。

### 启动前基线

```text
BUILD SUCCESSFUL in 10m 24s
247 actionable tasks: 11 executed, 236 up-to-date
```

## 2026-10-11：Phase 0–3

### 产品与生命周期骨架

- 新增 `:vehicle-geely` 和独立 `:geely` application module。
- 新增 `VehicleSessionHooks`、`VehicleHostControls`、`VehicleIntegrationProvider` 和品牌中立 settings descriptor。
- `CarPlayController` 不再直接依赖 BYD 导航单例；BYD Bluetooth suspend/recovery 也迁入 `BydVehicleSessionHooks`。
- `GeePlayApplication` 提供 Geely hooks、媒体策略、设置、display policy、产品身份、更新地址和诊断。

### RouteGuidance

- wired、Bluetooth bootstrap 和 type-130 tunnel 使用独立 source token。
- 新增 immutable snapshot、`AppleRouteGuidanceParser`、`AppleRouteGuidanceStore` 和 `RouteGuidanceCoordinator`。
- `BydHudRouteState` 成为品牌中立 store 的兼容包装，旧 BYD output/UI 回归保留。
- session replacement/end 清空 route/lane/junction cache并推进 generation；旧 worker 和旧 source 无法写入新 session。
- 文本使用严格 UTF-8；道路名、lane description、lane/angle/junction 数量均有边界。

### Lane / junction

- Identify capability 加入参数 `7/8`，接收白名单和 endpoint catalog 加入完整 `0x5204` 路径。
- parser 支持事件缓存、current/showing、u8/u16 position、status、多 angle、highlight sentinel、容量限制和原子拒绝。
- junction angle 使用有界解析；本地 Canvas 可绘制简化支路和高亮出口，默认实验关闭。

### ECARX output

- Driver 支持 watched/direct 连接、精确方法签名检查、动态 navigation/lane proxy 和 typed empty array。
- publish 使用单线程有界合并，失败隔离；每次 OEM 写入前持久化 cleanup journal。
- route clear 会清理 lane、route、presentation、callback/watcher/connection；进程重启优先恢复清理。
- backend 或导航开关关闭会立即撤销活动输出，并保留下次连接设置语义。

## 2026-10-11：Phase 4–6

### 媒体 owner 与产品身份

- 新增不可变 `LocalMediaAudioPolicy` 和 `MediaOutputOwner`。
- GeePlay 默认 `OEM_BLUETOOTH`：只抑制 MEDIA，本地保留 NAVIGATION、ASSISTANT 和 PHONE。
- 非 GeePlay owner 时不创建 media session、不请求音乐 audio focus；无线 Bluetooth control 可按策略保留。
- GeePlay 使用 `com.lllhys.geeplay`、独立版本/更新地址和自绘 app/CarPlay 图标。
- 未自定义图标时，遗留默认 `BYD` label 在 GeePlay 中迁移为 `GEELY`。
- 构建任务拒绝意外打包 MFi/private key/keystore 等凭据。

### 方向盘与语音输入

- OneOS 完成 service type 8、transaction 2/3/4、精确 descriptor、8 秒 bind timeout、2 秒重连和 death recipient。
- epoch gate 阻止旧 listener 回调；注销失败进入 Binder quarantine。
- 输入 runtime 按活动 session、owner、profile 和通话状态 attach/detach。
- Flyme 两个 action 只处理 action type 0，250 ms 去重；不可信 sender 路径默认关闭并带风险确认。
- XUI/OneOS 2 维持 probe-only。

### G636 Opus 导航音频回归修复

- 真机抓取确认 iPhone 正常建立 `type=101`、`audioType=default`、`audioFormat=0x10000000` 的 Opus 导航流，UDP 接收与解密无丢包。
- 修复前 G636 的 `MediaCodec.createDecoderByType("audio/opus")` 返回 `0xfffffffe`；AudioTrack 虽已建立，但没有 PCM 可写，因此导航静音。
- 对照验证过的 DiPlay 0.2.13 发布产物发现其额外包含 `libdiplay_opus_jni.so`；该发布期打包输入不在 Git 历史中，因此新建 GeePlay application module 时未被继承。
- GeePlay 恢复同一 bundled libopus JNI 后端，并以已随源码 vendored 的 Concentus decoder 作为缺失 ABI/加载失败时的 fallback。
- 修复包在 G636 上使用 `bundled-libopus`，连续输出 48 kHz mono PCM；采样窗口内 `inputDropped=0`、`decoderUnavailablePackets=0`、`underruns=0`，用户确认导航声音恢复。

### G636 车道信息真实链路与发布边界

- 打开工厂地图隐藏的模拟导航后，抓到真实 `RspLanesInfo`。已确认 `laneIndex` 从 0 开始，样本包括 `[0,16, 1,0, 2,4]`、`[0,0, 1,0, 2,15, 3,15, 4,0, 5,0]` 和 `[0,1, 1,1, 2,15, 3,15, 4,3]`。
- 工厂含义已校准：`0=直行`、`1=左转`、`3=右转`、`4=直行或右转`、`8=右掉头`、`15=推荐直行`、`16=推荐左转`。`GeelyLaneIconMapper` 将 CarPlay angle/highlight 转为该 OEM 编号，生产默认保持 0 基 lane number。
- GeePlay 通过旧 XUI `updateLaneInfo()` 发送相同数组时，`DIMNaviInteractioncService` 明确记录了数组，但用户多轮确认仪表没有车道。精确固件代码和真车日志最终证明：该 setter 只更新共享 protobuf builder，不立即调用 `sendNaviMessgae()`。
- G636 的 `updateNavigationInfo()` 会在处理 ETA 时触发一次完整导航帧；旧顺序是 `updateNavigationInfo/ETA flush → updateLaneInfo → 相同 ETA 被 OnceExecute 去重`，所以车道永远落在唯一一次 flush 之后。`EcarxNaviInteractionImpl.updateNaviStatus()` 为空操作不是这次 lane 丢失的主因。
- 真车已运行旧版 Debug-only EAS 探针。NaviService 将 GeePlay 的实际包身份注册为 vendor `-100`，收到 `drive_way_size=3` 和 `16/0/4`，但日志始终为 `mapForWidget:9`，因此事件 `2009` 未转成 `RspLanesInfo`，仪表无车道；EAS `43` 后延时 500 ms 再走 XUI lane 的混合路径也失败。
- 离线反编译精确确认：`43/44` 是 `ENLARGE_VIEW_OF_CROSSROAD_ON/OFF`，不改变 guiding source；`16/33` 才设置 guiding bit，`17/35` 才清除。`MapStateMachine` 只在 lane vendor 等于 `getMapForWidget()` 时发布事件 `1009`，再转换成协议 `3418 RspLanesInfo`。
- `MapInstallMonitor` 对七个内置地图使用固定 vendor；普通第三方若声明启用的 `ecarx.intent.action.MAP_SERVICE`，会获得 `packageName.hashCode()` 动态 vendor。GeePlay 当前未声明该 provider service，不能把客户端请求的 `9` 当成身份；生产接入前还需验证完整 service contract，不能仅为取 vendor 而添加空壳 service。
- Debug-only `eas-lane` 探针已删除 `setMapVendor(9)`，改为服务器分配身份，并按 `16 → 43 → lanes → clear → 44 → 17` 发布和清理。Robolectric 锁定 guide start 必须先于 lane、guide stop 必须晚于 clear。
- 真车以显式 Debug override 运行修正 EAS 序列后，`mapForWidget` 切到 `-100`，`3418 RspLanesInfo` 和 `[0,16, 1,0, 2,4]` 均到达 DIM；EAS-only 仍不显示，但随后由独立 Activity 发送完整 XUI route frame 后，用户确认仪表车道可见。这证明问题不是 vendor 拒绝，而是缺少 builder flush。
- 生产修复不接入 EAS 私有 SDK：`GeelyNavigationOutput` 在 lane 开启时启用已有独立 XUI lane refresh，`GeelyDimNavigationAdapter` 将 lane/empty clear 提前到 `updateNavigationInfo()` 之前，使同一帧的 ETA flush 携带车道。回归测试锁定发布和清理的 `lanes → info` 顺序。
- 修复包 SHA-256 为 `689f10c8a25d18d77d98ef27dc49af906a0dddf6bd8f7324ac088dede20265b2`；G636 日志确认合成 `[0,1, 1,15, 2,3]` 在 `updateETA(600)` 前进入 DIM，`published/cleared=true`、`cleanupPending=false`，用户确认该合成车道可见。此结果仅验证输出端，不代表真实 CarPlay lane 已闭环。
- 真实 CarPlay 日志纠正：`22:17:29` 的会话一次收到 8 帧 `0x5204`，但当时只持久化了 message ID，没有保存 event/lane geometry、store 选择和 DIM 写入时序；后续静止路线明确为 `LaneGuidanceTotalCount=0`，不能用于判断 lane 输出。
- 新增持久化 `RGDIAG`：记录脱敏后的 5201/5202/5204 字段、source/session owner、store publish/clear、Apple lane 到 G636 icon 映射，以及关键 DIM 方法与结果。道路名、目的地、坐标、lane description 正文和原始 payload 均不落盘；使用现有 `diplay.log + previous{,-2..-7}.log` 轮转。
- 2026-10-14 通勤取回完整轮转日志后，确认 `0x5204=0`、非空 lane=0、`LaneGuidanceShowing=false` 持续出现；同期系统 `NaviService` 却独立发布多组动态 `RspLanesInfo`。根因是此前测试遗留的 `flyme_auto_map_mock_guidance=1`：方向拓扑可能与同一路段的 CarPlay 相似，但推荐态和展示时机属于原车模拟路线，不能当作 CarPlay 数据。
- 已删除该 setting 并重启 `com.flyme.auto.map`；最后一帧原车模拟 lane 后不再出现新 `3418/updateLaneInfo`。原车地图结束事件曾把 DIM 导航整体置为停止，GeePlay 重连后普通道路/距离恢复；清理后的高德会话持续发送 `0x5201` 且 `LaneGuidanceShowing=0`，因此 CarPlay 本身不显示车道时 HUD 无车道是正确行为。
- 防护改为每个新 DIM driver 首帧即写一次 typed empty lane array，清除 OEM 跨进程残留；活动导航每 5 秒重申 `notifyNavigationStatus(1)`，避免原车地图迟到的 stop 事件长期关闭 HUD。两项行为均有可注入时钟的单测；2026-10-14 重连真车日志确认两个新 driver 都在首个 route frame 前写入 empty lane，稳定 runtime driver 在 5.38 秒后重申 status=1。
- 增加仅存在于 Debug APK 的 `route-replay`：在真实 CarPlay session 为 idle 时，按生产顺序重放合成但格式真实的 `0x5202 → 0x5204 → 0x5201`，逐阶段等待 DIM `ACTIVE` 回执，再演示两套推荐车道、lane hidden、恢复和 route end 清理。它覆盖 parser/store/icon mapper/XUI flush 全链路，不冒充 iPhone/高德源端证据。
- `route-replay` 真车写入依次为 `[0,1, 1,16, 2,15, 3,3] → [0,0, 1,30, 2,18, 3,8] → [] → [0,1, 1,16, 2,15, 3,3] → []`，每次 lane 后均有 `updateNavigationInfo` flush，报告为 `COMPLETE phases=4/4`。当前安装包 SHA-256 为 `8dcf3bd665b5b86eabaa03968485770d0b019e899a1a85d2b39e8918c4fadab6`。
- 同日 `18:29` 真实驾驶出现决定性反例：用户明确确认高德 CarPlay 主页小组件已显示车道，但 `18:29:15–18:31:10` 的 125 帧 `0x5201` 全部为 `LaneGuidanceShowing=false`，只有 2 帧 `0x5202`、没有 `0x5204`；store、OEM lane write 和 DIM 非空 lane 均为 0。当前连接 identification 已接受，component 42 的 RouteGuidance 订阅包含 source name / route guidance / exit info 请求；源码与测试同时锁定 MaxLaneGuidance 参数 7/8 及 `0x5204` 接收白名单。可靠边界是“高德 lane 已进入 CarPlay 系统，但没有经当前 accessory RouteGuidance component 到达 GeePlay”，不能简化为高德没有推；后续需比较所有控制消息和 Apple Maps/高德的组件路由差异。
- 安装推荐态修复包后切回高德，以每分钟 heartbeat 持续抓取所有 iAP2 RX 控制消息而非只过滤 RouteGuidance。用户在 `19:16` 明确确认 CarPlay 主页小组件正显示 lane；对应 `19:15:00–19:17:05` 窗口为 `5201=221`、`5202=5`、`5204=0`、`showing true=0/false=221`、store/OEM 非空 lane=0。全部额外消息只有 `0x4158 CommunicationsUpdate`，与 lane 无关。Apple Maps 会通过标准 `0x5204` 输出相同类别数据，而高德主页组件 lane 没有被 iOS 桥接到 accessory Control Session；现有接收端没有另一条可解析的 iAP2 lane 消息可补接。
- 用户在 `19:18:48` 再次确认高德主页 lane 仍在；延伸到 `19:18:49` 的新分片继续出现 58 帧 `0x5201`，全部 `showing=false`，没有 `0x5204`、store 或 OEM lane。持续数分钟的 UI/metadata 分离排除了单帧竞争和上报延迟。
- 外部资料与运行证据给出机制解释。Apple WWDC23《Optimize CarPlay for vehicle systems》明确：主屏/地图小组件/仪表地图使用 CarPlay navigation UI stream，HUD 的原生 turn-by-turn 使用 iAP2 metadata。WWDC25《Turbocharge your app for CarPlay》进一步明确第三方导航 App 必须返回 `mapTemplateShouldProvideNavigationMetadata=true`，并调用 `cpNavigationSession.add(maneuvers)`/`add(laneGuidance)`；`CPLaneGuidance` 从 iOS 17.4 提供。高德公开的 2024 仪表盘导航也要求 iPhone 12+、iOS 17.4+ 和支持 CarPlay 仪表投屏的车机。
- 同一高德窗口内 GeePlay 的 CarPlay stream 111 持续接收约 20–30 fps、约 0.3–1.5 Mbps 的 navigation UI 视频，而 `0x5204` 始终为 0。这说明高德的仪表/主页投屏路径实际已工作；缺失的是 App→CarPlay→accessory 的结构化 `CPLaneGuidance` 桥接。GeePlay 可以把 stream 111 直接显示到经过验证的仪表视频目标，但它不能直接生成 G636 原生 HUD lane ID；若高德不补 `add(laneGuidance)`，原生 HUD 只能另行评估固定 UI 的视频模板识别，不应伪装成普通协议修复。
- 切换 Apple Maps 后形成正向对照：`18:32:26` 一次预发 3 个真实 `0x5204`（event 0/1/2），其后 `0x5201` 保持 `total=3/showing=false`；`18:40:20` 距下一引导约 2.07 km 时切为 `index=1/showing=true`，store 选中 3 条 lane，OEM/DIM 按 `updateLaneInfo → updateNavigationInfo` 连续输出。这证明订阅、parser、缓存选择、显示时机和 G636 flush 链路均有效，高德结果是导航 App 兼容性差异。
- Apple Maps event 1 的第三条原始值为 `status=PREFERRED, LaneAngles=[90], LaneAngleHighlight=0`。用户确认视觉语义是“直行+右转都可”，说明 highlighted direction 仍是该车道允许方向，只是 Apple Maps 没在 `LaneAngles` 重复发送。旧 parser 只保留 `[90]`，mapper 因而输出普通右转 `3`；现已在通用 parser 中将非重复 highlight 合并进归一化 angles，同时保留 highlight 作为推荐分支，目标图标变为复合推荐直行 `32`，整组从 `[15,15,3]` 修正为 `[15,15,32]`。
- 新增真实 Apple Maps 三车道 parser 回归和 Geely icon 回归；测试先以 `expected [90,0] but was [90]` 失败，修复后通过。Debug replay 增加第五阶段 `captured-apple-maps-preferred-composite`，预期 `[15,15,32]`。完整 AGENTS.md CI 439 tasks 通过；停车后安装 SHA-256 `303e8c4c6367d27689133f99df6ab069a9cf1ddc9d74c0e5956678a6f4bbd329`，5/5 replay 报告 `COMPLETE` 且用户确认复合推荐视觉正确，无崩溃、mock setting 仍为 `null`。

### 物理仪表和诊断

- `GeelyClusterDisplayPolicy.VALIDATED_PROFILES` 故意为空。
- 无精确 fingerprint/display/恢复证据时设置 disabled、`requiresClusterStream=false`，且不回退到 BYD display 规则。
- Advanced 增加默认关闭的调试目标屏选择：只列出非默认、有效且带 `FLAG_PRESENTATION` 的显示屏；
  用户确认后可临时以 `debug-display-{id}` 绕过白名单，并在下次 CarPlay 连接请求 stream 111。
- 调试 displayId 消失、变为默认屏或失去 Presentation flag 时立即 fail-closed，不回退主屏或 BYD 规则；
  这一能力只用于采集实车证据，不会写入 `VALIDATED_PROFILES`。
- 诊断包括 OEM 方法/结果、route/frame 计数、OneOS lifecycle、display profile 选择、mirror/decoder 数量和 cleanup journal。
- 日志与导出不包含道路名、lane description、SSID、蓝牙地址、Binder token 或原始帧。

### 审计修复

- Android receiver 使用 `ContextCompat.registerReceiver` 和明确 exported flag。
- output retry 只保留一个 pending 定时任务；8 秒 watched-connect 窗口结束后停止写 OEM并保留 journal。
- local turn card 在 source replacement 无新快照时 fail-closed 清空。
- preferred lane 缺少 highlight angle 时不再错误高亮所有支路。
- 通用 Activity 不同时注册 BYD 与品牌中立 overlay listener。
- `BydVehicleSessionHooks` 使用 `stillActive` 防止重复 active/close 竞态。
- Robolectric 的空 service binder 被安全拒绝，不再造成测试崩溃。

## 2026-10-11：移除 AutoDisplay 与 GeePlay 外部 MapEmbed

用户明确 GeePlay 不与 AutoDisplay 交互。已完成以下收口：

### 构建与模块

- 从 `settings.gradle.kts` 删除 `:integration-autodisplay`。
- 从 `:geely` 删除对应 dependency。
- 删除整个 `integration-autodisplay/` 模块及其资源和测试。
- 删除 AutoDisplay 协议文档及专用 `ExternalMapIntegration`/render-target lease。

### Runtime 与设置

- `GeePlayApplication` 不再注入导航 snapshot output、access policy、设置 controller 或 AutoDisplay 诊断。
- `requiresClusterStream()` 只由经验证的 Geely 物理 display 设置决定。
- `VehicleIntegrationProvider` 使用产品级 `externalMapEmbeddingAvailable()` seam；GeePlay 固定返回 `false`。
- GeePlay 设置页不显示 launcher map sharing；普通 DiPlay 保持原设置与服务。
- `CarPlayHostActivity` 删除外部客户端 canvas 协商，只保留本地 virtual fallback 和产品物理 display policy。

### Manifest 与协议

- 删除 `com.lllhys.geeplay.permission.NAVIGATION`。
- 删除 GeePlay 专用 `EMBED_MAP` action。
- GeePlay manifest 用 `tools:node="remove"` 删除继承自 `:common` 的 `MapEmbedService`。
- `MapEmbedService` 恢复为通用 DiPlay launcher 协议；保留增强后的 death-recipient 清理，不保留 AutoDisplay 鉴权、协议版本或 render-target 扩展。

### 自动化

- GeePlay test 断言 merged manifest 中不存在 `MapEmbedService` 和旧 signature permission。
- common test 保留 attach/revoke 与客户端死亡释放回归。
- 移除 AutoDisplay contract/publisher/render lease 测试。

首次针对性测试因磁盘只剩 244 MiB，在截图写入时出现 `No space left on device`；只清理仓库内可再生 `build/` 目录后，原命令重跑成功：

```sh
env JAVA_HOME=/Users/bytedance/.gradle/jdks/eclipse_adoptium-25-aarch64-os_x.2/jdk-25.0.3+9/Contents/Home \
  ./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.MapEmbedServiceTest \
  :geely:testDebugUnitTest :geely:processDebugMainManifest
```

```text
BUILD SUCCESSFUL in 30s
93 actionable tasks: 93 executed
```

该磁盘失败不作为代码失败记录；重跑结果为当前有效证据。

## 当前自动化门禁

最终命令（移除 AutoDisplay 后）：

```sh
env JAVA_HOME=/Users/bytedance/.gradle/jdks/eclipse_adoptium-25-aarch64-os_x.2/jdk-25.0.3+9/Contents/Home \
  ./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug \
  :vehicle-geely:testDebugUnitTest :vehicle-geely:lintDebug \
  :geely:testDebugUnitTest :geely:lintDebug :geely:assembleDebug
```

最终结果：

```text
BUILD SUCCESSFUL in 3m 29s
358 actionable tasks: 344 executed, 14 up-to-date
```

测试结果：

| 模块 | tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| `:shared` | 1303 | 0 | 0 | 1 |
| `:common` | 1081 | 0 | 0 | 0 |
| `:home` | 4 | 0 | 0 | 0 |
| `:vehicle-geely` | 44 | 0 | 0 | 0 |
| `:geely` | 5 | 0 | 0 | 0 |
| **合计** | **2437** | **0** | **0** | **1** |

`mobile`、`home`、`maphost`、`vehicle-geely`、`geely` 五个 lint 目标均为 `0 errors`，合计 33 warnings；四个 assemble 目标全部成功。

构建产物：

- `geely/build/outputs/apk/debug/geely-debug.apk`
- size：约 51 MiB
- SHA-256：`fcb4d5f2bb2a1f9fc709390d71a0036ab935e3eeb2cf4b14394d6a802d2f660c`

静态安全检查：

- merged manifest 的 `MapEmbedService`、两个 `EMBED_MAP` action、旧 navigation permission、AutoDisplay package：0 命中；
- APK 的 AutoDisplay 专用 class/package/action/permission 字符串：0 命中；
- APK 的 `offline-mfi`、private key、certificate/keystore 类条目：0 命中；
- 模拟器 package manager 的上述禁止项：0 命中。

大字体截图由本轮 `:geely:testDebugUnitTest` 重新生成，和持久交付物 hash 一致：

- `docs/images/geely-settings/en-600dp-font-1.3.png`
- `docs/images/geely-settings/en-1280dp-font-1.5.png`
- `docs/images/geely-settings/ar-600dp-font-1.3.png`

移除前的 `2452` 测试、六个 lint 目标及旧 APK hash 已失效，不作为当前交付证据。

## 模拟器与实车边界

本轮 APK 已在 Android 模拟器重新验证：

- `adb install -r -t` 成功；
- `DiPlayActivity` cold launch 成功，`am start -W` 报 `Status: ok`、`LaunchState: COLD`、`TotalTime: 520 ms`；
- `com.lllhys.geeplay.debug` 进程保持存活，Activity 为 top resumed；
- 清空 logcat 后未发现该进程的 `FATAL EXCEPTION`；
- 未提供 `DIPLAY_AUTH_ASSETS_DIR` 时只出现预期的 `offline-mfi/identity.pk8` 缺失提示。

### G636 实车 ADB 安装与初步探测

2026-10-11 连接到 ECARX `G636` 物理车机并完成以下验证：

- Android 11 / API 30，build fingerprint 为 `qti/ecarx/ecarx:11/RQ3A.211001.001/eng.jenkin.32980801.222706:user/test-keys`；
- 物理显示为 1440×1920、160 dpi，`/data` 剩余约 7.9 GiB；
- 系统存在 `ecarx.naviservice`、`com.ecarx.sdk.openapi`、`com.geely.inputservice`；
- Spec 当前 OneOS Binder target `com.geely.service.oneosapi` 不存在，不能把 `com.geely.inputservice` 直接当成同一协议；
- `com.permission.adnaviservice` 由 `ecarx.naviservice` 定义为 normal permission；GeePlay 安装后显示 `granted=true`；
- 使用禁用 sync compression 的 USB push 后，车机端 APK SHA-256 与本地一致；`pm install -r -t` 成功；
- 已安装版本为 `0.2.17-geely.1-debug`，versionCode 1；
- 通用和 GeePlay 两个 MapEmbed action 都不能解析到 GeePlay service；
- AutoDisplay package 未安装，GeePlay 也没有相关 service/action；
- `DiPlayActivity` cold launch 返回 `Status: ok`、`LaunchState: COLD`、`TotalTime: 2067 ms`。

ADB 后续恢复并补齐了启动后证据：GeePlay PID 15541 持续存活，Activity task 保留，process exit history 为空；TOTAL PSS 约 65.9 MiB、RSS 约 151 MiB。AndroidRuntime 中唯一 fatal 来自车机 `uiautomator` 自身的 Looper 空指针（PID 23440），不是 GeePlay。

实车截图确认主界面、设置 Overview、Navigation 和 Diagnostics 正常渲染。诊断显示 `sdk=true`、`adnaviservicePermission=true`、Driver=`IDLE`、cleanupPending=false、OEM method=`none`、route frames 全部为 0；默认没有写 OEM。显示探测发现 display 0 `HDMI 屏幕` 1440×1920 和 display 1 `HDMI 屏幕` 2880×1080，但 `validatedProfiles=0`、selectedProfile=none、surfaceCreation=not_attempted，因此没有误投物理仪表。

该 G636 证据仍不能验证：

- ECARX Driver connect/publish 和原车仪表实际显示；
- 真实 `0x5204`/junction TLV、laneNumber/Bitmap/iconId 语义；
- OneOS 注册/注销/death 和方向盘一次一动作；
- Flyme 广播 sender；
- 物理 display profile、OEM enter/restore 和强停恢复；
- 原车蓝牙音乐 owner 的可听结果。

这些门禁保持 `VEHICLE_PENDING`，不会由安装成功、permission granted 或类探测升级为已支持。下一步在含认证资产的新包上连接 iPhone，验证路线开始/更新/结束清理。

### 2026-10-11：一期实车反馈修复包

- 版本提升为 `0.2.17-geely.2-debug` / versionCode 2，便于确认覆盖升级。
- 从 G636 已安装的官方 DiPlay 0.2.13 只读提取两项 runtime authentication asset 到仓库外临时目录；PKCS#8/PKCS#7 解析通过。
- Gradle 仅允许 `identity.pk8` 和 `certificate.p7b`，APK 内条目与选定输入逐字节一致；本机构建后已删除临时私钥目录，仓库仍为 0 credential 文件。
- GeePlay 七个 locale 覆盖实车可见品牌文案；设置 Overview、语言、About、认证错误、三指手势和报告目录不再显示 DiPlay。
- `VehicleIntegrationProvider` 增加 legacy BYD 设置和诊断目录 seam；GeePlay 隐藏 BYD unavailable 文案，普通 DiPlay 默认行为不变。
- 诊断报告实际写入 `Downloads/GeePlay`，不再出现文案与落盘路径不一致。

完整门禁最终有效结果：

```text
BUILD SUCCESSFUL in 2s
358 actionable tasks: 22 executed, 336 up-to-date
```

测试合计 2439 项（0 failures、0 errors、1 skipped）；五个 lint 目标均为 0 errors。第一次全量运行中 3 个 `LocalAdb` 时序测试在高并发下失败，专项复跑全部通过，随后完整命令通过。

新 APK：

- `geely/build/outputs/apk/debug/geely-debug.apk`
- 首次一期包 SHA-256：`5cc88dcaec6ec1024141a84e643b4d74f9f014c0dc673f85a12d1a2659e01a3f`
- 认证条目：恰好两项，且与选定输入匹配
- merged manifest / APK AutoDisplay 专用项：0 命中
- G636 已用 `pm install -r -t` 从 geely.1 覆盖到 geely.2：firstInstallTime 和五份 shared preferences 的 hash 全部保持，未卸载、未清数据。
- 新包冷启动后认证错误消失，原配对手机仍被识别，证明 runtime authentication asset 已成功加载。
- G636 的 InterfaceSize AUTO 因 `smallestWidthDp=1440` 放大到 200%；按用户反馈改为应用内 150%（等效 240 dpi），不修改车机全局 160 dpi，也不影响 CarPlay 画面。
- 实车截图：`docs/images/geely-validation/g636-geely.2-size150.png`。
- 150% 截图发现首页标题资源仍为 DiPlay；补充 `diplay` 与 interface-size hint 的七 locale 覆盖后，完整 CI 再次通过。
- 最终品牌资源包 SHA-256：`c38bf62853058e5fc94272f8aff6aeb2a97d6036a78a77cba22254011fb6f347`；当前等待 G636 ADB 恢复后再覆盖一次并清理 `/data/local/tmp` 安装文件。

### 2026-10-11：G636 投屏外观修复

- 实车确认顶部 88 px 是 visible `TopCarSystemBar`，并非视频黑边；CarPlayHost 的 window 仍使用 dark theme 初始色，未随日间 CarPlay 切换。
- 新增 `applySystemBarAppearance()`，在创建、焦点恢复、fullscreen 重申和实时日夜切换时同步 host root/status/navigation bar 的颜色与 icon appearance。
- 用户建议用相同背景融合 CarPlay 圆角：host root 不再固定 `Color.BLACK`，改为与 system bar 共用日/夜 palette。
- SurfaceFlinger 显示 app/task corner radius 为 0；`/info` 为全屏矩形 safe/view area。Apple 公开能力只有 corner clipping mask 混合，不提供关闭 CarPlay 自绘圆角的 radius 属性。
- 明确未采用视频裁切、overscan、缩放或未知私有协议字段。
- 版本提升为 `0.2.17-geely.3-debug` / versionCode 3。

最终完整 CI：

```text
BUILD SUCCESSFUL in 3m 41s
358 actionable tasks: 344 executed, 14 up-to-date
```

测试合计 2440 项（0 failures、0 errors、1 skipped），五个 lint 目标均为 0 errors。APK SHA-256 为 `b1d2bc90c4290a459af281e904c1d3200e971c247b68b59a0fad12f9eb3a42f8`；等待 G636 ADB 恢复后覆盖安装并采集同一 CarPlay 日间画面的对比截图。

### 2026-10-11：BYD 设置边界与上游合并治理

- 新增 `GeelyProductSettings.blockLegacyBydMenus`，GeePlay 默认开启屏蔽；Advanced 的“上游兼容性”卡片可立即切换。
- `LegacyBydSettingsPolicy` 集中登记整段 BYD/DiLink-only section：`BYD_ADB`、`BYD_NAVIGATION`、`CLUSTER_MAP`、`NAVIGATION_WHEEL`、`AMBIENT_LIGHTING`。
- 混合页面只屏蔽 BYD 控件：call popup、joystick/map zoom、Advanced vehicle data；Siri 键、外部控制器、通用 CarPlay 设置保留。
- Settings Search 使用同一渲染 gate；默认状态下不会索引或搜索到被屏蔽的 BYD 菜单。
- 七个 locale 已补齐新设置文案，设置 controller、provider、IA policy、导航/Advanced UI 和 Search 均有回归覆盖。
- `AGENTS.md` 新增强制 upstream merge review：每个 merge/rebase/cherry-pick 必须重新分类为 `INHERIT`、`MIGRATE_SHARED`、`ADAPT_GEELY`、`EXCLUDE_GEEPLAY` 或 `EVIDENCE_PENDING`。
- 新增独立模板 `docs/upstream-merge-checklists/TEMPLATE.md`；每次上游合并必须复制为带日期和 upstream SHA 的新文件，不能覆盖历史 checklist。

完整门禁：

```text
BUILD SUCCESSFUL in 3m 3s
358 actionable tasks: 64 executed, 294 up-to-date
```

测试合计 2444 项（shared 1303、common 1084、home 4、vehicle-geely 45、geely 8），0 failures、0 errors、1 skipped；五个 lint 目标均为 0 errors。
