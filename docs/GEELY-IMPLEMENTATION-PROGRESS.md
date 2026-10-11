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
| 3 | Lane / junction | 软件完成，待真实帧与实车 |
| 4 | 媒体 owner 和产品身份 | 软件完成，待实车音频 |
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

### 物理仪表和诊断

- `GeelyClusterDisplayPolicy.VALIDATED_PROFILES` 故意为空。
- 无精确 fingerprint/display/恢复证据时设置 disabled、`requiresClusterStream=false`，且不回退到 BYD display 规则。
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

当前可用设备是 Google `sdk_gphone64_arm64` 模拟器，不是吉利车机；它不能验证：

- `com.permission.adnaviservice` 的实际授予和 ECARX 物理仪表输出；
- 真实 `0x5204`/junction TLV、laneNumber/Bitmap/iconId 语义；
- OneOS 注册/注销/death 和方向盘一次一动作；
- Flyme 广播 sender；
- 物理 display profile、OEM enter/restore 和强停恢复；
- 原车蓝牙音乐 owner 的可听结果。

这些门禁保持 `VEHICLE_PENDING`，不会由模拟器或类探测升级为已支持。
