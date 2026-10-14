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
| EAS lane Debug probe | 服务器分配 vendor、`-100` 显式 Debug override、`16 → 43 → lanes → clear → 44 → 17`、unregister/release | Robolectric 通过；真车到达 `3418/DIM`，与完整 route frame 组合后仪表可见；不进入生产 |
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
| ECARX lane | 0 基 typed array、工厂 icon ID、lane-before-route flush、empty clear | 合成输出 `VEHICLE_CONFIRMED`; 真实 CarPlay `VEHICLE_PENDING`（重连未重放 `0x5204`） |
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
- GeePlay 发送 `[0,16, 1,0, 2,4]` 后，`DIMNaviInteractioncService` 收到完全一致数组；完整导航探针也发送并收到 `[0,1, 1,15, 2,3]`。早期两者都在没有后续完整帧时失败，说明“Binder 调用成功/DIM 收到”不等于仪表呈现。
- 反编译精确 `DIMInteraction.apk` 确认 `updateLaneInfo()` 只更新共享 `mDimNaviInteraction` builder；真正调用 QNX `sendNaviMessgae()` 的是 `updateNaviDataChange()`。当前 XUI SDK 的 `updateNavigationInfo()` 在 ETA 字段触发该发送，而 `EcarxNaviInteractionImpl.updateNaviStatus()` 为空操作。
- 旧版 Debug-only `eas-lane` 已在真车执行：服务端忽略客户端请求的 vendor `9`，按真实包身份注册 GeePlay 为 `-100`；`43`、三条 lane `16/0/4`、`44` 均到达 NaviService，但当时 `mapForWidget=9`，仪表无车道。随后执行的 `EAS 43 → 500 ms → XUI lane` 也无显示。
- 离线反编译纠正了旧假设：`43/44` 只表示路口放大图开/关，不会建立导航所有权；`16/33` 才进入 guiding，`17/35` 才退出。lane 事件只有在自身 vendor 等于当前 `mapForWidget` 时，才会变成事件 `1009` 和协议 `3418 RspLanesInfo`。
- `MapInstallMonitor` 允许正式内置包使用固定 vendor，也允许声明 `ecarx.intent.action.MAP_SERVICE` 的第三方 provider 使用包名 hash vendor。当前没有证据允许 GeePlay 用空壳 service 取得该身份，因此生产继续 fail-closed；修正后的探针不再设置 vendor `9`，仅使用服务端实际分配值。
- 修正后的 Debug-only 探针序列为 `GUIDE_START(16) → CROSSROAD_ON(43) → lanes → empty clear → CROSSROAD_OFF(44) → GUIDE_STOP(17)`。真车确认 vendor `-100` 可进入 guiding、产生协议 `3418` 并到达 DIM；单独运行仍因没有 builder flush 而不显示。
- 在 EAS lane 可见期间，用独立 Activity 发送一次完整 XUI route frame 后，仪表立即出现固定测试车道。旧版 XUI 日志随后进一步证明 `updateNavigationInfo()` 内的 `updateETA(600)` 先发送，独立 `updateLaneInfo [0,1, 1,15, 2,3]` 后写入，而第二次相同 ETA 被 `OnceExecute` 去重。
- 因此生产不需要注册 EAS provider，也不依赖 vendor `-100`：修复为在已有 XUI driver 中先调用独立 `updateLaneInfo`，再调用 `updateNavigationInfo`，让同一帧 ETA flush 携带 lane；清理时同样先写 empty lane 再发送最终导航帧。

早期清理记录不完整：虽然 Mock Location app-op 为 `deny`，测试导航也达到 `published/cleared=true`、`cleanupPending=false`、Driver `CLOSED`，但 2026-10-14 实车仍读到 `flyme_auto_map_mock_guidance=1`。该 setting 随后已删除并确认为 `null`，同时重启 `com.flyme.auto.map` 清除其进程内缓存路线。

当前等级：lane parser/映射与 `lanes → info` 顺序为 `SOFTWARE_VERIFIED`，合成 lane 的 XUI 输出为 `VEHICLE_CONFIRMED`；真实 CarPlay lane 仍为 `VEHICLE_PENDING`。用户看到的可见 lane 来自 Debug `validationSnapshot()`，不能作为真实 `0x5204` 闭环证据。

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
Combined EAS + XUI flush vehicle result: lanes visible (user confirmed)
Fixed XUI-only debug APK SHA-256: 689f10c8a25d18d77d98ef27dc49af906a0dddf6bd8f7324ac088dede20265b2
Fixed XUI-only log order: updateLaneInfo [0,1, 1,15, 2,3] -> updateNavigationInfo/updateETA(600)
Fixed XUI-only cleanup: published=true, cleared=true, cleanupPending=false
Fixed XUI-only synthetic result: mock lanes visible (user confirmed; not a real CarPlay lane result)
Real CarPlay evidence: 22:17 session received eight 0x5204 frames; 22:30 reconnect received 0x5201/0x5202 but no 0x5204 replay while phone still showed lanes
```

上面的“phone still showed lanes”后续被用户纠正为可能与 mock 画面混淆，不能作为证据。当前可靠结论仅为：历史会话收到过 8 帧 `0x5204`，合成输出能在仪表显示，真实两段时序尚未同时抓到。

通勤诊断包已安装，SHA-256：`d254ce8c5ec657ab9978416e13a403e30f6a2b7cc46b0a4fefb66d4a32211a3a`。`RGDIAG` 已在 G636 私有 `files/logs/diplay.log` 实际落盘，并随 `previous.log` 至 `previous-7.log` 自动轮转。记录边界：

- `stage=RX`：5201/5202/5204 的解析结果、source owner、session 和连接 generation；
- `STORE_PUBLISH/STORE_CLEAR`：当前 maneuver/lane 选择和清除原因；
- `OEM_PLAN/OEM_LANE_WRITE/OEM_CALL`：Apple lane、G636 icon ID、empty transition 及 DIM 结果；
- 不保存道路名、目的地、坐标、lane description 正文或原始 payload。

EAS probe 仅保留为 Debug 诊断，不进入生产。下一步在真实通勤后拉取 8 个轮转日志，按 `0x5204 → STORE_PUBLISH → OEM_LANE_WRITE → updateNavigationInfo` 时间线定位缺口。

### 9.4.1 2026-10-14 通勤诊断与残留防护

2026-10-14 从 G636 拉取了诊断包的全部 8 个轮转日志；备份为 `/tmp/geeplay-lane-commute.7uFRHz/route-logs.tar`，SHA-256 `8c1ff4dd7f1e740d07305ba8998403f47e11abc9a9be1a954aa7dbcb07429d5e`。完整通勤统计：

```text
message=0x5204: 0
laneCount>0: 0
OEM_LANE_WRITE: 0
LaneGuidanceShowing=false: 2484
OEM_PLAN: 2360
```

同一时段系统 `NaviService` 独立出现 `[0,16, 1,16, 2,15]`、`[0,16, 1,16, 2,18]`、`[0,2, 1,4]` 等 lane 数组。随后确认 `flyme_auto_map_mock_guidance=1`，说明用户当时看到的方向、推荐态和提前展示来自原车隐藏模拟导航，而不是上述高德 CarPlay 会话。删除 setting 并重启原车地图后，模拟 lane 流停止；GeePlay 重连恢复普通 HUD 导航。

清理后的稳定会话仍持续收到有效 `0x5201`，但每帧 `LaneGuidanceCurrentIndex=0`、`LaneGuidanceShowing=0`，没有 `0x5204`。用户同时确认该时刻 CarPlay 页面本身也没有车道提示，因此“HUD 无车道”是正确结果，不能用于通过或否决真实 lane 闭环。下一有效验收窗口必须是 CarPlay 页面实际出现车道时，并以 `RX 0x5204 → STORE_PUBLISH laneCount>0 → OEM_LANE_WRITE → updateNavigationInfo` 全链日志与 HUD 画面同时成立为准。

该有效窗口已在同日 `18:29` 出现，且得到与预期不同但边界明确的结果。用户在驾驶中明确确认高德 CarPlay UI 正显示车道；物理 G636、GeePlay PID、`flyme_auto_map_mock_guidance=null` 均已在窗口前复核。同步统计如下：

```text
window: 18:29:15–18:31:10
RX 0x5201: 125
RX 0x5202: 2
RX 0x5204: 0
LaneGuidanceShowing=false: 125
LaneGuidanceShowing=true: 0
STORE_PUBLISH laneCount>0: 0
OEM_LANE_WRITE visible=true: 0
DIM non-empty updateLaneInfo: 0
fatal: 0
```

该窗口跨越多个 maneuver index 和约两分钟持续更新，不是单帧竞争或延迟上报。当前会话在 `18:27:21/18:27:34` 两次 identification accepted，并分别于 `18:27:22/18:27:36` 向 component 42 发送 RouteGuidance subscription。`Iap2RouteGuidanceSubscriptionTest` 锁定 display component 参数 3/5/6/7/8，其中 `7=64`、`8=8`，接收列表包含 `0x5204`；因此可排除 GeePlay 未声明 lane capability、漏订阅或接收白名单缺失。

用户后续补充：高德 lane 不只出现在高德自己的主画面，也出现在 CarPlay 主页小组件。因此不能表述为“高德只画 UI、没有推 lane”；可靠结论应先收窄为 lane 已进入 CarPlay 系统，但同一窗口没有经 GeePlay 当前订阅的 accessory RouteGuidance component 42 输出 `0x5204/Showing=true`。

安装 Apple Maps 推荐态修复包并切回高德后，建立每分钟只读 heartbeat，扩大到所有 iAP2 RX Control Session 消息。用户在 `19:16` 明确确认 CarPlay 主页小组件正在显示 lane，对应完整窗口为：

```text
window: 19:15:00–19:17:05
RX 0x5201: 221
RX 0x5202: 5
RX 0x5204: 0
LaneGuidanceShowing=false: 221
LaneGuidanceShowing=true: 0
STORE_PUBLISH laneCount>0: 0
OEM_LANE_WRITE visible=true: 0
other RX control IDs: 0x4158 only
```

`0x4158` 在当前 endpoint catalog 和 identification 中是 `CommunicationsUpdate`，不是导航数据。该窗口还覆盖多个 maneuver 更新和路口切换，期间没有新的控制消息 ID。因此可排除“只过滤错了 `0x5204`”及另一条未监听的 iAP2 Control Session lane message。与 Apple Maps 标准 `0x5204` 正向对照结合后的准确结论是：高德确实向 CarPlay/iOS 主页组件提供 lane，但 iOS 没有把这份第三方导航组件数据桥接到当前 accessory RouteGuidance display component。GeePlay 现有接收端没有可补解析的 lane payload；继续修改 parser、订阅参数或 OEM 输出都不能从该窗口恢复高德 lane。若必须覆盖，只能寻找并验证标准 Control Session 之外的私有 CarPlay 通道，不能凭主页组件画面假设存在可读消息。

用户于 `19:18:48` 再次确认高德主页 lane 仍然可见；延伸分片截至 `19:18:49` 又记录 58 帧 `0x5201`，全部 `LaneGuidanceShowing=false`，继续保持 `0x5204=0`、store/OEM 非空 lane=0，额外消息仍只有 `0x4158`。因此 UI/metadata 分离持续数分钟，可排除短暂显示残留、单帧时序竞争或 metadata 延迟到达。

网络资料复核没有推翻上述抓包结论，但给出了可复现的机制解释和替代路径：

- Apple WWDC23《Optimize CarPlay for vehicle systems》在 8:52 后明确区分：主屏显示 CarPlay video stream；地图小组件和仪表地图使用 CarPlay navigation UI stream；HUD 使用 iAP2 route-guidance metadata。
- Apple WWDC25《Turbocharge your app for CarPlay》明确第三方导航 App 只有在 `mapTemplateShouldProvideNavigationMetadata` 返回 true，并向 `CPNavigationSession` 添加 maneuvers/lane guidance 后，CarPlay 才把这些 metadata 发送给车辆。官方示例代码为 `cpNavigationSession.add(laneGuidance)`。
- Apple `CPLaneGuidance` 文档标记为 iOS 17.4+。高德 2024 年公开的 CarPlay 仪表盘导航条件同样是 iPhone 12+、iOS 17.4+、车机支持 CarPlay 仪表盘投屏；其宣传使用“投屏导航”，没有公开承诺 HUD/iAP2 lane metadata。
- 真车高德窗口中 stream 111 持续收到 20–30 fps navigation UI 视频，证明车辆侧仪表投屏能力和高德 UI 路径确实被激活；同时 `0x5204=0`，说明高德没有在该会话向 accessory 提交可用的 `CPLaneGuidance`，或 iOS 没有为该第三方 App 桥接该字段。

可行方案按目标分级：

1. **仪表直接展示高德导航/车道画面**：使用已收到的 CarPlay stream 111，按现有 `VehicleClusterDisplayPolicy` 只在 G636 找到并验证安全的仪表 Display 后开放。该方案是 Apple 官方 UI-stream 路径，不产生结构化 lane。
2. **G636 原生 HUD lane icon**：最佳方案是高德 App 完整实现/启用 `cpNavigationSession.add(laneGuidance)`；车辆端无法代替第三方 App 调用。可先确保 iPhone 12+、iOS 17.4+、最新版高德，但当前真车已证明单靠 accessory 参数 7/8 和 component 42 不足以强制高德输出。
3. **视频识别 fallback**：离屏解码 stream 111，针对固定 lane panel 做模板匹配，再映射 OEM icon。技术上可行，但依赖高德 UI 主题、分辨率、语言和版本，延迟/误判/性能风险显著，应作为独立实验能力，不能混进当前标准 RouteGuidance 修复。

参考：

- <https://developer.apple.com/videos/play/wwdc2023/10150/>
- <https://developer.apple.com/videos/play/wwdc2025/216/>
- <https://developer.apple.com/documentation/carplay/cplaneguidance>
- <https://xinwen.bjd.com.cn/content/s66bc9005e4b07fbcccaecdb2.html>

随后在同一 iPhone、G636、GeePlay session 中切换到 Apple Maps，得到正向对照：

```text
18:32:26 event=0 0x5204 lanes=4
18:32:26 event=1 0x5204 lanes=3
18:32:26 event=2 0x5204 lanes=2
18:33:32 0x5201 index=0 total=3 showing=false
18:40:19 0x5201 index=0 total=3 showing=false
18:40:20 0x5201 index=1 total=3 showing=true
18:40:20 STORE laneEvent=1 laneCount=3
18:40:20 DIM [0,15, 1,15, 2,3] -> updateNavigationInfo flush
```

Apple Maps 会在路线开始时预发后续 lane event，再由 `LaneGuidanceCurrentIndex/Showing` 控制展示；当前 store 行为正确，没有因未来 `0x5204` 提前展示。这证明 GeePlay 当前 capability/订阅能够接收标准 accessory RouteGuidance lane，但不能单独解释高德为何只进入 CarPlay 主页组件而未进入该通道。

event 1 的真实结构为：前两条 `PREFERRED, angles=[], highlight=0`；第三条 `PREFERRED, angles=[90], highlight=0`。用户明确纠正第三条视觉含义是“直行+右转都可”，而不是普通右转。由此确认 `LaneAngleHighlight` 除了选择推荐分支，也可能是 Apple Maps 从 `LaneAngles` 省略的一个允许方向。修复前 parser 将第三条归一为 `[90]`，mapper 输出 `3`；修复后归一为 `[90,0]`，对应 G636 `(straight|right, selected straight)` 图标 `32`，目标数组为 `[0,15, 1,15, 2,32]`。

回归测试先锁定真实失败，再修复：

```text
AppleRouteGuidanceStoreTest.highlight angle ...: FAIL before fix, PASS after fix
GeelyLaneIconMapperTest.maps captured Apple Maps lanes ...: PASS
CarPlayRouteGuidanceReplayTest: PASS with captured Apple Maps fifth phase
```

修复完成后完整 AGENTS.md 两组检查以 439 tasks 通过。用户停车后覆盖安装，车机与本地 APK SHA-256 均为 `303e8c4c6367d27689133f99df6ab069a9cf1ddc9d74c0e5956678a6f4bbd329`。五阶段 replay 报告 `COMPLETE session=idle phases=5/5`，新增阶段解析为 `angles=90,0, highlight=0`、预期 OEM `[15,15,32]`；用户确认 HUD 上第三条为正确的“直行/右转、推荐直行”。最终 empty lane/status 清理成功，正常 GeePlay 已恢复，无崩溃，原车 mock setting 仍为 `null`。

为避免两个已证实的跨应用生命周期问题，生产 XUI 路径增加：

- 每个新 driver 的首帧即发送一次 typed empty lane array，清除 OEM builder 跨进程残留；同一 driver 后续重复 empty 仍去重；
- 活动导航每 5 秒重申 `notifyNavigationStatus(1)`，防止原车地图的迟到 stop 事件让 HUD 长期消失。

专项 `GeelyDimNavigationAdapterTest` 和完整 AGENTS.md 两组命令均通过；完整运行共 439 tasks，`BUILD SUCCESSFUL in 1m 26s`。加固 APK 于 `2026-10-14 10:26:15` 首次覆盖安装到物理 G636。`10:29:53` 首次重连确认 bootstrap/runtime 两个新 driver 均执行 `updateLaneInfo(length=0) → updateNavigationInfo → notifyNavigationStatus(1)`；稳定 runtime driver 于 `10:29:59` 记录 `OEM_STATUS_REFRESH status=1 intervalMs=5381`，两项防护均已在真车调用链生效。

随后增加 Debug-only `route-replay`，以真实 iAP2 TLV 结构而非直接构造 snapshot，覆盖 `0x5202 maneuver → 0x5204 lane → 0x5201 route selection → AppleRouteGuidanceStore → GeelyLaneIconMapper → DIM/XUI`。入口在检测到真实 CarPlay session 为 active/retained 时拒绝执行，结束或异常时都发送 route end 并关闭 driver；不会进入 release APK。可复用命令：

```sh
adb -s 198.18.34.15:34233 shell am force-stop com.lllhys.geeplay.debug
adb -s 198.18.34.15:34233 shell am start -W \
  -n com.lllhys.geeplay.debug/com.lllhys.geeplay.GeePlayValidationActivity \
  --es mode route-replay --el durationMillis 20000
adb -s 198.18.34.15:34233 shell cat \
  /sdcard/Android/data/com.lllhys.geeplay.debug/files/validation/latest-route-replay.txt
adb -s 198.18.34.15:34233 shell am start -W \
  -n com.lllhys.geeplay.debug/com.shilapi.xcertplay.DiPlayActivity
```

2026-10-14 第二轮真车重放完整通过：

```text
left-and-straight-preferred: [0,1, 1,16, 2,15, 3,3]
straight-and-right-preferred: [0,0, 1,30, 2,18, 3,8]
lane-hidden-route-kept: []
first-pattern-restored: [0,1, 1,16, 2,15, 3,3]
route-ended cleanup: [] + notifyNavigationStatus(0)
report: COMPLETE phases=4/4
```

每个非空/空 lane 数组之后都有 `updateNavigationInfo`，因此真实走到了 G636 的 QNX flush 边界。`8dcf3bd6…dab6` 是加入第五阶段前的历史安装包；当前 Debug APK 为 `303e8c4c…d329`，完整 AGENTS.md 两组检查和五阶段 replay 均通过。此 replay 可以替代日常开车验证解析、推荐态映射、切换和清理；高德 CarPlay 主页组件数据未进入当前 `0x5204` 的问题仍需独立追踪。

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

### 9.8 G636 CarPlay 媒体低延迟

2026-10-14 在同一 G636、同一 iPhone 上对比原车 Bluetooth A2DP 与 GeePlay CarPlay：

- 原车 A2DP AudioTrack 为 44.1 kHz、2836 frames，容量约 64 ms；
- 旧 GeePlay AAC AudioTrack 为 48 kHz、24000 frames，容量 500 ms，首次启播约需 309 ms；
- `main_buffered_audio` 未开启，排除额外 1 秒 lookahead；主要确定性延迟来自媒体软件预缓冲；
- 将阈值直接降到 100 ms 后，真机又暴露无线音频约 400 ms 的突发到达模式：旧 underrun 恢复逻辑会反复暂停并再次预缓冲，长空档后压缩队列还可积到 47 包、约 1 秒。

最终策略：

- 保持上游/其他产品默认 300 ms；仅 GeePlay 在没有已保存选择时初始化为 100 ms；
- 设置提供 50/100/300/500/1000 ms，修改后明确要求重连；
- 50/100 ms 档使用小 PCM Track：目标容量分别约 70/120 ms，约 400 ms 的无线突发由压缩队列承接；首次按所选阈值启播，underrun 后不再主动暂停重缓冲；
- 保留约 400 ms 的正常无线突发窗口，只有压缩音频继续落后到 50 ms 档 450 ms、100 ms 档 500 ms 以上时才丢最旧包追帧；300/500/1000 ms 稳定档保留完整重缓冲和不丢包行为。

最终安装包的真机证据：

```text
100ms mode: AudioTrack buffer=120ms, steady queued PCM~=80ms
User result: much better than the old build, but delay was still noticeable
50ms preference: media_buffer_ms=50
50ms actual platform floor: AudioTrack start=64ms, buffer=74ms
50ms first AAC packet: 22:34:33.799
50ms AudioTrack play: 22:34:33.966 (first packet to play ~=167ms including decoder/track creation)
50ms steady queued PCM: 0-69ms
Normal 400-484ms arrival gaps: dropped=0, rebuffers=0
Long 1.26/1.29s gaps: stale packets trimmed to the 450ms low-latency window
APK SHA-256 local/device: 2a170d2c6ea399bf80710f567a0de2c5bdbc2f50810eb5af280c5be9e2f15c27
Base CI: BUILD SUCCESSFUL in 3m 4s (250 actionable tasks)
GeePlay CI: BUILD SUCCESSFUL in 18s (276 actionable tasks)
```

低延迟档的单测覆盖首次阈值、小 PCM Track 容量、underrun 后不暂停、正常突发不裁包和超限陈旧队列追帧。车机当前选择 50 ms 极限档；最终体感仍需驾驶者在当前安装包上完成播放/暂停与切歌确认。
