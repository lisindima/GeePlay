# GeePlay 吉利车机验证记录

本文件是 [GEELY-CORE-FEATURES-SPEC.md](GEELY-CORE-FEATURES-SPEC.md) 的证据台账。类存在、调用成功和车辆实际可见/可听是三个不同结论；没有实车记录的能力不得对外标记为已支持。

## 1. 当前产品身份

- application ID：`com.lllhys.geeplay`
- debug application ID：`com.lllhys.geeplay.debug`
- version：`0.2.17-geely.1` / versionCode `1`
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
| lane / junction renderer | Bitmap、icon ID 0、lane base、empty array、junction angle seam | fake SDK / Robolectric 通过 |
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
| ECARX lane | typed array、Bitmap、empty clear、profile seam | `SOFTWARE_VERIFIED`; `VEHICLE_PENDING` |
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

`GeelyClusterDisplayPolicy.VALIDATED_PROFILES` 当前故意为空。现有设备为 Google `sdk_gphone64_arm64` 模拟器，仅有内置 display 0，不具备 ECARX/OneOS 服务，也不能授予厂商 signature permission。

因此当前只能确认软件门禁和安全降级；ECARX 导航、lane、媒体 owner、方向盘、junction 和物理仪表仍为 `VEHICLE_PENDING`。GeePlay 不使用 AutoDisplay 作为替代交付路径。
