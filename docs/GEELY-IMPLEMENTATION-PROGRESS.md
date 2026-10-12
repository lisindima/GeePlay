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
