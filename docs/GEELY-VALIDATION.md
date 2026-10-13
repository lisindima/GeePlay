# GeePlay 吉利车机验证记录

本文件是 [GEELY-CORE-FEATURES-SPEC.md](GEELY-CORE-FEATURES-SPEC.md) 的证据台账。类存在、调用成功和车辆实际可见/可听是三个不同结论；没有实车记录的能力不得对外标记为已支持。

## 1. 当前产品身份

- application ID：`com.lllhys.geeplay`
- debug application ID：`com.lllhys.geeplay.debug`
- version：`0.2.17-geely.3` / versionCode `3`
- update API：`https://api.github.com/repos/lllhys/GeePlay/releases?per_page=3`
- ECARX permission declaration：`com.permission.adnaviservice`
- AutoDisplay：不属于产品范围，无模块、查询、权限、action、广播或 service
- external MapEmbed：GeePlay merged manifest 必须移除 `MapEmbedService`
- MFi 和 release signing：仅允许本地环境变量注入，不进入仓库或 APK

## 2. 证据等级

| 等级 | 含义 |
| --- | --- |
| `SOFTWARE_VERIFIED` | 源码、自动化、lint/assemble 或静态产物检查闭环 |
| `AVAILABLE` | 厂商类、服务、permission、方法或 display target 存在 |
| `DELIVERED` | 本地调用成功，或目标 Surface 收到并呈现数据 |
| `VEHICLE_CONFIRMED` | 指定车型/固件/iPhone/iOS/地图 App 上确认了用户可见或可听行为及清理 |
| `VEHICLE_PENDING` | 软件就绪，但当前没有满足条件的车辆证据 |

`SOFTWARE_VERIFIED`、`AVAILABLE` 和 `DELIVERED` 均不能自动升级为 `VEHICLE_CONFIRMED`。

## 3. 自动化覆盖

| 能力 | 自动化覆盖 | 当前结果 |
| --- | --- | --- |
| RouteGuidance `0x5201/0x5202` | strict TLV、缓存、empty grace、stale、route end | 通过 |
| Lane `0x5204` | capability、nested TLV、数量/文本/角度边界、current/showing、畸形拒绝 | 通过 |
| source ownership | wired/bootstrap/tunnel、promotion、旧 source/旧 session gate | 通过 |
| BYD 兼容 | state wrapper、cluster frame、glance/overlay、Bluetooth lifecycle | 通过 |
| ECARX Driver | watched/direct、方法签名、dynamic proxy、typed lane array、publish/clear、OEM 拒绝 | fake SDK 通过 |
| cleanup journal | 写前标记、清理确认、启动恢复、未确认时阻止新输出 | 通过 |
| lane / junction renderer | Bitmap、工厂 icon ID 映射、0 基 lane、empty array、junction angle seam | fake SDK / Robolectric 通过 |
| EAS lane Debug probe | 服务器分配 vendor、`-100` fail-closed、`16 → 43 → lanes → clear → 44 → 17`、unregister/release | Robolectric 通过；旧序列真车失败，修正序列暂不投放（未知 vendor 消费规则待明确） |
| media owner | MEDIA 抑制、非 MEDIA 保留、MediaSession gate、Bluetooth retention | 通过 |
| OneOS input | descriptor、transaction、key、owner/call/session gate、timeout、death、epoch、quarantine | 通过 |
| Flyme voice | 两个 action、action type、250 ms 去重、风险确认 | 通过 |
| display policy | 产品 policy 优先、空 allowlist fail-closed、无 BYD fallback | 通过 |
| settings | 唯一分类、七 locale、重连语义、compact/full/Arabic 截图 | 通过 |
| GeePlay 产品隔离 | 无 AutoDisplay 模块；无 external MapEmbed 设置/service/action/permission | 单元 + merged manifest 门禁 |
| 通用 DiPlay MapEmbed | sharing revoke、aspect 更新、client death cleanup | common 回归通过 |

## 4. Spec 追踪矩阵

| 需求 | 软件证据 | 当前等级 |
| --- | --- | --- |
| 模块边界 | `:geely -> :common/:vehicle-geely -> :shared`；通用产品不依赖 Geely | `SOFTWARE_VERIFIED` |
| Controller 品牌中立 | `VehicleSessionHooks`；无 Controller → ECARX/OneOS/BYD navigation singleton 依赖 | `SOFTWARE_VERIFIED` |
| source generation | 独立 source token、preferred promotion、stale callback/session gate | `SOFTWARE_VERIFIED` |
| strict RouteGuidance | strict UTF-8、容量边界、原子拒绝、route end | `SOFTWARE_VERIFIED` |
| Lane capability | Identify 7/8、`0x5204` catalog/whitelist/parser | `SOFTWARE_VERIFIED`; `VEHICLE_PENDING` |
| ECARX basic navigation | fake SDK publish/clear/recovery | `SOFTWARE_VERIFIED`; `AVAILABLE/DELIVERED/VEHICLE_CONFIRMED` 待实车 |
| ECARX lane | 0 基 typed array、工厂 icon ID、empty clear；旧 XUI 与 EAS 差分 | `SOFTWARE_VERIFIED`; XUI `DELIVERED`; EAS/仪表 `VEHICLE_PENDING` |
| media owner | immutable policy、renderer/MediaSession/Bluetooth gates | `SOFTWARE_VERIFIED`; `VEHICLE_PENDING` |
| OneOS/Flyme input | lifecycle、timeout/death/epoch/quarantine、risk gate | `SOFTWARE_VERIFIED`; `VEHICLE_PENDING` |
| stream `111` local path | virtual config、decoder/mirror、local UI | `SOFTWARE_VERIFIED` |
| Geely physical display | empty validated profile、disabled setting、no BYD fallback | `SOFTWARE_VERIFIED`; `VEHICLE_PENDING` |
| junction simplified view | bounded parser/renderer、default off | `SOFTWARE_VERIFIED`; `VEHICLE_PENDING` |
| identity/release | application ID、label/icon、update API、credential rejection | `SOFTWARE_VERIFIED` |
| AutoDisplay excluded | module/dependency/contract removed；无交互入口 | `SOFTWARE_VERIFIED` |
| external MapEmbed excluded | provider returns false；setting hidden；manifest removes service | `SOFTWARE_VERIFIED` |

## 5. AutoDisplay 与 MapEmbed 排除门禁

### 5.1 允许保留的内容

- `docs/GEELY-APK-0.2.13-ANALYSIS.md` 可以记录样本 APK 曾观察到的 AutoDisplay 行为。这是历史研究事实，不是 GeePlay 产品能力。
- `:common` 的 `MapEmbedService`、样例 launcher 和 `docs/LAUNCHER_INTEGRATION.md` 继续服务通用 DiPlay 产品。
- `MapEmbedServiceTest` 可继续验证通用 DiPlay 的资源回收。

### 5.2 GeePlay 必须不存在

源码和构建图：

```text
:integration-autodisplay
com.lllhys.geeplay.integration.autodisplay.*
ExternalMapAccessPolicy
ExternalMapRenderRequest
ExternalMapRenderTargets
```

merged manifest / APK：

```text
com.shilapi.xcertplay.MapEmbedService
com.shihab.diplay.action.EMBED_MAP
com.lllhys.geeplay.action.EMBED_MAP
com.lllhys.geeplay.permission.NAVIGATION
com.simplerbit.autodisplay
```

验证命令在最终构建后执行：

```sh
rg -n -i 'autodisplay|ExternalMapAccessPolicy|ExternalMapRenderTargets|ExternalMapRenderRequest' \
  settings.gradle.kts geely common/src shared/src vehicle-geely --glob '!**/build/**'

rg -n 'MapEmbedService|EMBED_MAP|permission.NAVIGATION|simplerbit.autodisplay' \
  geely/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml
```

预期：第一条最多只命中“必须不存在”的测试说明；第二条 0 命中。

## 6. 自动化执行记录

### 6.1 AutoDisplay 移除后的专项回归

```sh
env JAVA_HOME=/Users/bytedance/.gradle/jdks/eclipse_adoptium-25-aarch64-os_x.2/jdk-25.0.3+9/Contents/Home \
  ./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.MapEmbedServiceTest \
  :geely:testDebugUnitTest :geely:processDebugMainManifest
```

有效结果：

```text
BUILD SUCCESSFUL in 30s
93 actionable tasks: 93 executed
```

首次运行曾因磁盘只剩 244 MiB，在截图写入时出现 `No space left on device`。仅删除仓库内可再生的 `build/` 产物后同命令通过，因此该环境失败不计入产品缺陷。

### 6.2 最终完整 CI

```sh
env JAVA_HOME=/Users/bytedance/.gradle/jdks/eclipse_adoptium-25-aarch64-os_x.2/jdk-25.0.3+9/Contents/Home \
  ./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug \
  :vehicle-geely:testDebugUnitTest :vehicle-geely:lintDebug \
  :geely:testDebugUnitTest :geely:lintDebug :geely:assembleDebug
```

结果：

```text
BUILD SUCCESSFUL in 3m 29s
358 actionable tasks: 344 executed, 14 up-to-date
```

| 模块 | tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| `:shared` | 1303 | 0 | 0 | 1 |
| `:common` | 1081 | 0 | 0 | 0 |
| `:home` | 4 | 0 | 0 | 0 |
| `:vehicle-geely` | 44 | 0 | 0 | 0 |
| `:geely` | 5 | 0 | 0 | 0 |
| **合计** | **2437** | **0** | **0** | **1** |

lint：

| 目标 | errors | warnings |
| --- | ---: | ---: |
| `:mobile` | 0 | 18 |
| `:home` | 0 | 5 |
| `:maphost` | 0 | 2 |
| `:vehicle-geely` | 0 | 3 |
| `:geely` | 0 | 5 |

所有 assemble 目标通过。AutoDisplay 移除前的任务数、测试数、lint 数和 APK hash 已废弃。

## 7. APK 与模拟器门禁

最终构建后记录：

```text
branch=geely/main
HEAD=4314bbe133599b21f40d23d3bf3de3f0d45353b4
dirty=42 porcelain entries; expected implementation/doc worktree, no commit created
APK=geely/build/outputs/apk/debug/geely-debug.apk
APK size=about 51 MiB
SHA-256=fcb4d5f2bb2a1f9fc709390d71a0036ab935e3eeb2cf4b14394d6a802d2f660c
merged manifest AutoDisplay/MapEmbed forbidden hits=0
installed package-manager forbidden hits=0
AutoDisplay-specific APK string hits=0
credential-like APK entries=0
```

模拟器烟测结果（Google `sdk_gphone64_arm64`）：

1. `adb install -r -t geely-debug.apk`：成功。
2. cold launch：`Status: ok`、`LaunchState: COLD`、`TotalTime: 520 ms`。
3. `com.lllhys.geeplay.debug` 进程存活，`DiPlayActivity` 为 top resumed。
4. 清空 logcat 后，该 package 的 `FATAL EXCEPTION`：0。
5. 出现 `offline-mfi/identity.pk8` 缺失：预期；凭据未注入，不是启动崩溃。

设置截图：

| 文件 | SHA-256 |
| --- | --- |
| `docs/images/geely-settings/en-600dp-font-1.3.png` | `b0d90c19b70ceb07bb2004649813f823883625dc260991c4fd751c3801eb707a` |
| `docs/images/geely-settings/en-1280dp-font-1.5.png` | `9bb33c13288b7b66ca51b6eecf3984471e6f253e6618f3270b2a069fbd6a429d` |
| `docs/images/geely-settings/ar-600dp-font-1.3.png` | `48e23d549fa29f5d789de004756f9d8f437060236e465c5d2fa533e670362b26` |

## 8. 实车验证模板

### 8.1 环境

```text
Date:
Tester:
Vehicle model/year:
Head-unit model:
Build fingerprint:
Android version:
ECARX navigation service package/version:
OneOS service package/version:
Granted permissions:
iPhone model:
iOS version:
Navigation app/version:
Connection: wired / wireless
GeePlay branch/commit:
APK SHA-256:
```

### 8.2 ECARX 基础导航

```text
SDK class/method probe: AVAILABLE / UNAVAILABLE
Permission granted: yes / no
Driver connect: success / failure
Start presentation call: success / failure
Road/distance/maneuver visible: yes / no
Reroute update visible: yes / no
Arrival/cancel clear visible: yes / no
Disconnect clear visible: yes / no
Restart recovery clear: success / failure
Evidence link:
Final level: AVAILABLE / DELIVERED / VEHICLE_CONFIRMED
```

### 8.3 Lane / junction

```text
Raw fixture ID (sanitized):
Lane count and source indices:
laneNumber base: 0 / 1 / unknown
Bitmap vs iconId result:
Preferred lane visible:
Lane hidden/route end clears:
Junction raw stream image visible:
Simplified geometry matches:
Evidence link:
Final level:
```

### 8.4 媒体 owner

```text
Owner: DIPLAY / OEM_BLUETOOTH / PHONE_OR_EXTERNAL
Connection: wired / wireless
Music heard from:
Navigation heard from:
Siri heard from:
Call audio heard from:
Media keys owned by:
Tunnel handoff stable:
Switch back restores GeePlay:
Evidence link:
Final level:
```

### 8.5 OneOS / Flyme input

```text
Profile:
Bind/register success:
Short press play-pause/next/previous:
Long/double behavior:
One physical action -> one command:
Call-state gate:
Service death/reconnect:
Unregister/close:
Flyme sender identity:
Siri action result:
Evidence link:
Final level:
```

### 8.6 Geely 物理 display

```text
Fingerprint/profile match:
Display name/id/owner/geometry:
OEM enter API/result:
MAP/TURN_CARD/INSTRUMENTS frames visible:
Pause/resume:
Day/night:
Reconnect:
OEM restore API/result:
App close restore:
Force-stop then recovery restore:
Evidence link:
Final level:
```

## 9. 当前实车结论

### 9.1 ECARX G636 ADB 证据（2026-10-11）

```text
Vehicle/head unit: ECARX G636
Manufacturer/brand/device: QTI / qti / ecarx
Android: 11 / API 30
Build fingerprint: qti/ecarx/ecarx:11/RQ3A.211001.001/eng.jenkin.32980801.222706:user/test-keys
CPU ABI: arm64-v8a, armeabi-v7a, armeabi
Physical display: 1440x1920 at 160 dpi
Data free space before install: about 7.9 GiB
APK: geely-debug.apk
APK SHA-256 local/device: fcb4d5f2bb2a1f9fc709390d71a0036ab935e3eeb2cf4b14394d6a802d2f660c / same
Install: pm install -r -t -> Success
Installed version: 0.2.17-geely.1-debug / versionCode 1
Cold launch: Status ok / COLD / 2067 ms
```

厂商接口探测：

| 项目 | 结果 | 结论 |
| --- | --- | --- |
| `ecarx.naviservice` | system app 存在 | `AVAILABLE` |
| `com.ecarx.sdk.openapi` | system app 存在 | `AVAILABLE` |
| `com.geely.inputservice` | system app 存在 | `AVAILABLE`，协议未确认 |
| `com.geely.service.oneosapi` | package 不存在 | 当前 OneOS adapter 不可用 |
| `com.permission.adnaviservice` | source=`ecarx.naviservice`、normal、GeePlay granted | permission `AVAILABLE`；不代表 SDK publish 成功 |
| GeePlay generic/product MapEmbed action | query-services 0 命中 | 外部 MapEmbed 未暴露 |
| AutoDisplay package | `pm path`/`pm list packages` 0 命中 | 未安装；GeePlay 无交互入口 |

ADB 恢复后补充结果：

- GeePlay PID 15541 持续存活，process exit history 无 GeePlay 退出记录；
- TOTAL PSS 约 65.9 MiB，TOTAL RSS 约 151 MiB；
- AndroidRuntime 唯一 fatal 为车机 `uiautomator` PID 23440 的 Looper NPE，不属于 GeePlay；
- 主界面、设置 Overview、Navigation 和 Diagnostics 均在 1440×1920 实车屏正确渲染；
- Navigation 默认关闭 Geely navigation、lane 和 simplified junction，未触发 OEM 写入；
- Diagnostics：SDK available、permission true、Driver IDLE、failure none、cleanupPending false、OEM method none、session inactive、`0x5201/0x5202/0x5204` 均为 0；
- media owner 为 `OEM_BLUETOOTH`，retainBluetooth=true；input profile 为 disabled；
- display 0 为 1440×1920 HDMI，display 1 为 2880×1080 HDMI；validatedProfiles=0、selectedProfile=none、surfaceCreation=not_attempted。

实车截图位于 `docs/images/geely-validation/`。这些证据证明 UI、默认门禁、SDK/permission probe 和进程稳定性，不证明导航已发布到仪表。

### 9.2 一期修复包（已覆盖安装）

```text
Version: 0.2.17-geely.2-debug / versionCode 2
First phase-one APK SHA-256: 5cc88dcaec6ec1024141a84e643b4d74f9f014c0dc673f85a12d1a2659e01a3f
Full CI: BUILD SUCCESSFUL
Tests: 2439 / failures 0 / errors 0 / skipped 1
Lint: mobile/home/maphost/vehicle-geely/geely all 0 errors
Authentication assets: exactly identity.pk8 + certificate.p7b; byte-matched to selected official 0.2.13 inputs
Repository credential files: 0
Install state: geely.2 installed with pm install -r -t; firstInstallTime and preference hashes preserved
Authentication: ready on the real head unit; no missing-identity warning
Interface size: 150 percent app-only override; system remains 160 dpi
Final branding-resource APK SHA-256: c38bf62853058e5fc94272f8aff6aeb2a97d6036a78a77cba22254011fb6f347
Final branding-resource install: superseded by the installed geely.3 build
```

该包还修复 GeePlay 品牌文案、隐藏遗留 BYD unavailable 提示，并把诊断报告目录切换为 `Downloads/GeePlay`。

### 9.3 当前结论

`GeelyClusterDisplayPolicy.VALIDATED_PROFILES` 当前故意为空。G636 已证明安装、产品隔离、厂商导航 permission grant 和 Activity cold-launch 可用，但没有导航字段、lane、音频、按键或物理仪表的持续运行证据。

因此当前只能确认软件门禁和安全降级；ECARX 导航、lane、媒体 owner、方向盘、junction 和物理仪表仍为 `VEHICLE_PENDING`。GeePlay 不使用 AutoDisplay 作为替代交付路径。

### 9.4 G636 工厂车道与 GeePlay 对照证据（2026-10-13）

工厂地图隐藏模拟导航被临时打开并在验证后恢复。到“爱力中心A座”的模拟导航产生了真实车道流：

```text
factory lanes: [0,16, 1,0, 2,4]
factory lanes: [0,0, 1,0, 2,15, 3,15, 4,0, 5,0]
factory lanes: [0,1, 1,1, 2,15, 3,15, 4,3]
factory status: 43 before visible lane panel; 44 when hidden
factory data source: 9
```

确认结论：

- `laneIndex` 为 0 基；Bitmap 在 G636 AdapterService 下不会进入最终协议，真正生效的是 `laneIconId`。
- 工厂基础 ID：`0` 直行、`1` 左转、`3` 右转、`4` 直行或右转、`8` 右掉头；推荐 ID 至少确认 `15` 直行、`16` 左转。
- GeePlay 发送 `[0,16, 1,0, 2,4]` 后，`DIMNaviInteractioncService` 收到完全一致数组；完整导航探针也发送并收到 `[0,1, 1,15, 2,3]`。用户确认仪表仍无车道，因此“Binder 调用成功/DIM 收到”不等于仪表呈现。
- 反编译车机实际 XUI/AdapterService 路径确认：`updateLaneInfo()` 会序列化 `laneNumber,laneIconId`；但 `EcarxNaviInteractionImpl.updateNaviStatus()` 是空操作。工厂地图观察到的 `43/44` 来自 EAS NaviService，不来自旧 XUI。
- 旧版 Debug-only `eas-lane` 已在真车执行：服务端忽略客户端请求的 vendor `9`，按真实包身份注册 GeePlay 为 `-100`；`43`、三条 lane `16/0/4`、`44` 均到达 NaviService，但当时 `mapForWidget=9`，仪表无车道。随后执行的 `EAS 43 → 500 ms → XUI lane` 也无显示。
- 离线反编译纠正了旧假设：`43/44` 只表示路口放大图开/关，不会建立导航所有权；`16/33` 才进入 guiding，`17/35` 才退出。lane 事件只有在自身 vendor 等于当前 `mapForWidget` 时，才会变成事件 `1009` 和协议 `3418 RspLanesInfo`。
- `MapInstallMonitor` 允许正式内置包使用固定 vendor，也允许声明 `ecarx.intent.action.MAP_SERVICE` 的第三方 provider 使用包名 hash vendor。当前没有证据允许 GeePlay 用空壳 service 取得该身份，因此生产继续 fail-closed；修正后的探针不再设置 vendor `9`，仅使用服务端实际分配值。
- 修正后的 Debug-only 探针序列为 `GUIDE_START(16) → CROSSROAD_ON(43) → lanes → empty clear → CROSSROAD_OFF(44) → GUIDE_STOP(17)`。自动化已通过；该序列尚未真车执行。
- 二次离线审计确认这组调用经过同一 one-way Binder、serialized RxBus、MapManager dispatcher 和 StateMachine Handler，顺序不会越过；`-100` 在 NaviService 内部也可进入 guiding state、成为 `mapForWidget` 并生成 `3418 RspLanesInfo`。未闭环的是 G636 仪表最终消费者是否拒绝未知 vendor，该消费者不在当前工厂 Map、NaviService 近似包或 NaviWidget 包中。探针已增加 `-100` 硬拒绝测试：未知身份时不发任何 status/lane，仍执行 unregister/release。

清理确认：工厂模拟导航系统设置恢复为未设置，Mock Location app-op 为 `deny`，测试导航 `published/cleared=true`、`cleanupPending=false`、Driver 最终 `CLOSED`。

当前等级：lane parser/映射 `SOFTWARE_VERIFIED`；旧 XUI 到 DIM 为 `DELIVERED`；仪表显示与 EAS provider 权限仍为 `VEHICLE_PENDING`。

离线静态证据来自以下候选包；其中 NaviService 是与车机日志同系列的本地近似版本，结论只采用同时被真车日志印证的控制条件：

```text
Factory Map 2.0.0 l7a1: 77732106354ca16ddc06658dda21cf29a12bf6851c5a0cfed995417d65c375c6
NaviWidget 1.0.12 l7a1: abfdd054cc266291c24bab6bd74914a9e018a339383d898b20470c2c9a77787d
XSFNaviService 26020302: 724137e1a44078433467232c886ee9d8c6d41f27f721b57b50307daabbd2d8fc
```

离线候选包门禁：

```text
Superseded debug APK: 0da5c0ef6686cdc40b0980076c0d2e251a144d1768ba3a51a37f745bfe962459
Superseded vehicle result: 43/lanes/44 reached NaviService but did not render

Corrected EAS lifecycle and unknown-vendor fail-closed tests: PASS
Base AGENTS.md CI command: BUILD SUCCESSFUL in 4s
GeePlay AGENTS.md command: BUILD SUCCESSFUL in 14s
vehicle-geely/geely lint: 0 errors
Corrected debug APK SHA-256: ab437d98372cd17ba84b290d73edb5d81e3a4e0ee5c4e7336597fb621701b989
Install/vehicle result: not attempted; user is away from the vehicle
```

暂不安排下一次真车运行。先离线解决合法 provider 身份，或拿到 G636 仪表消费者对 `RspLanesInfo.mapVendor` 的接受规则；完成后才允许一次自动化验证，同时确认 `mapForWidget` 切换、`RspLanesInfo(protocol=3418)`、仪表可见和 `17`/empty clear。该结果未确认前不得接入生产。

### 9.5 G636 状态栏与 CarPlay 圆角调查

实车复现截图：`docs/images/geely-validation/g636-statusbar-black-repro.png`。

- G636 的 `TopCarSystemBar` 高 88 px 且保持 visible；用户设置 `hide_top_bar=false`。
- CarPlayHost window 原先固定继承 dark theme 的 `statusBarColor=#0C111B`，即使 CarPlay 已切到日间浅色地图也不刷新。
- 修复后 host root、status bar、navigation bar 统一跟随 `DiPlayPalette`：日间 `#E9EEF6` 且深色图标，夜间 `#0C111B` 且浅色图标；焦点恢复和外观切换均重申该状态。
- 回归测试 `visibleSystemBarsFollowTheLiveCarPlayAppearance` 覆盖日/夜两组颜色与 icon appearance。

圆角证据：

- GeePlay video view 没有 outline/clip/corner radius；SurfaceFlinger 对 app window/task 报 `cornerRadius=0`。
- 当前 `/info` viewArea 和 safeArea 都是完整矩形，inset 全 0，`drawUIOutsideSafeArea=true`。
- Apple WWDC23 说明 CarPlay 默认自行绘制圆角；corner clipping mask 用于把圆角外黑色三角与车机背景透明混合，不是关闭圆角。
- 未发现受支持的 corner-radius=0 或 rectangular-corner 属性。按用户要求不裁切视频、不缩放画面、不写未知私有字段。
- 作为无损试验，CarPlay host root 背景与状态栏使用同一日/夜颜色；若 iPhone 输出角落可透明混合，实车将直接消除色差；若黑色已编码在 H.264 帧内，则该背景不会穿透。

geely.3 构建：

```text
Version: 0.2.17-geely.3-debug / versionCode 3
APK SHA-256: b1d2bc90c4290a459af281e904c1d3200e971c247b68b59a0fad12f9eb3a42f8
Full CI: BUILD SUCCESSFUL in 3m 41s
Tasks: 358 actionable, 344 executed, 14 up-to-date
Tests: 2440 / failures 0 / errors 0 / skipped 1
Credential entries: exactly identity.pk8 + certificate.p7b; selected bytes matched
Install: pm install -r -t succeeded on G636; app data preserved
```

G636 后续已覆盖安装该 geely.3 包；firstInstallTime、150% interface-size 偏好和应用数据均保留。其后的 host-root 背景同步与 BYD 菜单屏蔽源码改动已通过软件门禁，但尚未生成新的含认证实车包。

### 9.6 BYD 设置屏蔽与 upstream checklist

| 验证项 | 结果 |
| --- | --- |
| 默认产品策略 | `blockLegacyBydMenus=true`，GeePlay provider 不暴露 legacy BYD settings |
| 整段 section | BYD ADB/navigation、cluster map、navigation wheel、ambient lighting 统一屏蔽 |
| 混合 section | 只隐藏 BYD call popup、joystick/map zoom、Advanced vehicle data |
| 通用设置 | Siri key、external controller、CarPlay display/audio/connection 保留 |
| Search | 被屏蔽标题不进入索引；GeePlay compatibility toggle 可搜索 |
| Escape hatch | Advanced 可关闭屏蔽，立即刷新；不视为车辆支持证据 |
| Locale | values/ar/es/ru/uk/zh-CN/zh-TW 名称集合一致 |
| Merge governance | `AGENTS.md` 强制每次 upstream 操作生成独立 checklist |
| Checklist template | `docs/upstream-merge-checklists/TEMPLATE.md` |

```text
Full CI: BUILD SUCCESSFUL in 3m 3s
Tasks: 358 actionable, 64 executed, 294 up-to-date
Tests: 2444 / failures 0 / errors 0 / skipped 1
Lint: mobile/home/maphost/vehicle-geely/geely all 0 errors
```

### 9.7 G636 导航音频与 bundled Opus 解码

2026-10-12 在同一 G636、同一 iPhone 和同一音频路由下复现 GeePlay 导航静音：

- iPhone 正常建立 `type=101`、`audioType=default`、`audioFormat=0x10000000` 的 Opus 导航流；
- UDP 包持续接收并成功解密，RTP 时钟约 16 kHz；
- 导航 AudioTrack 成功建立并路由到 G636；
- 失败点为 `MediaCodec.createDecoderByType("audio/opus")`，返回 `0xfffffffe`；后续包没有产生 PCM；
- G636 的 codec registry 不提供可用的 `audio/opus` decoder。

产物差异核对：验证过的 `DiPlay_0.2.13_Packed_ADSigned_20261006 (2).apk` 额外包含
`libdiplay_opus_jni.so` 和 libopus 1.6.1 license，但这些文件不存在于 Git 历史。GeePlay 初建时只继承了源码，
没有继承发布/加固阶段注入的 native decoder，这就是同源代码在两个安装包上表现不同的原因。

修复后证据：

```text
audio decoder configured mime=audio/opus name=bundled-libopus
audio first PCM type=101 bytes=1920
audio playback started type=101
inputDroppedTotal=0 decoderUnavailablePacketsTotal=0 outputBuffersTotal=757
underruns=0 writeErrors=0
```

用户在车内确认导航声音恢复。native backend 缺失或自检失败时使用源码内置 Concentus decoder，避免再次出现
“向 iPhone 宣告 Opus、运行时却无法解码”的静音状态。导入二进制的来源与 SHA-256 记录在
`shared/src/main/jniLibs/README.md`，libopus license 随 APK 打包。

```text
Version: 0.2.18-geely.4-debug / versionCode 4
APK SHA-256: 5e82811b3cc927026b9fcbf187b15f15459c264fb8d352f93cf7360c7b802430
Full CI: BUILD SUCCESSFUL in 12s
Tests: 2665 / failures 0 / errors 0 / skipped 1
Packaged native decoder: arm64-v8a, armeabi-v7a, x86_64
Authentication assets: repository 0; temporary build directory removed
Install: pm install -r succeeded on G636; app data preserved
```
