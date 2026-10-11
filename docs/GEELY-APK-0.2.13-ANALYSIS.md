# DiPlay 0.2.13 吉利适配 APK 静态分析

## 1. 结论摘要

分析对象不是上游 `v0.2.13` 的普通构建，而是一个经过二次适配、签名和百度加固的 APK。脱壳 DEX 中确实包含吉利相关实现，但“代码存在”和“运行时已接线”需要分开判断。

| 功能 | 静态结论 | 实现方式 |
| --- | --- | --- |
| 吉利原车导航提示 | 已接入运行链路，默认关闭，标注实验性 | 解析 iPhone 的 iAP2 `0x5201/0x5202`，通过反射调用车机提供的 ECARX XUI `DimInteraction` / `INaviInteraction` 接口 |
| CarPlay 车道展示 | 车机 SDK 明确支持，但当前吉利版 DiPlay 未接入 | iPhone 的 iAP2 `0x5204` 提供逐车道位置、状态和方向角；ECARX XUI 可接收 `ILaneInfo[]`，需要补充 capability、解析器、事件缓存和图标生成 |
| 仪表地图视频／桌面地图嵌入 | 上游和吉利版 APK 均已接入视频接收、解码与嵌入；吉利物理仪表投屏尚未确认 | 请求 iPhone 的独立 AirPlay stream `111`，用 MediaCodec 输出到 Surface；现有直接仪表显示选择主要针对 BYD，AutoDisplay 嵌入走独立 SurfacePackage 通路 |
| ECARX XUI / Flyme Auto / OneOS 导航 backend | 有三个设置选项，但并非三个独立协议实现 | 三者共用同一个 `GeelyDimNavigationAdapter`；XUI/Flyme 走 `connect`，OneOS 跳过连接回调并直接取接口 |
| OneOS / KX11 方向盘媒体键 | 实现类完整，但未发现生产入口 | 原始 Binder 调用 `OneOSApiService`，注册播放/下一首/上一首；`SteeringWheelRuntime` 没有被 Activity、Controller 或媒体模块 attach |
| Flyme 语音键触发 Siri | 实现类完整，但未发现生产入口 | 动态注册两个 ECARX 语音广播；发送者不可认证，文案也明确提示可伪造风险 |
| ECARX XUI / OneOS 2 方向盘输入 | 仅诊断 | 只检查 XUI 类是否存在，明确不申请进程级按键截获 |
| 原车蓝牙负责音乐与方控 | 已接入运行链路，且是此 APK 的默认选择 | 抑制 CarPlay 本地音乐流和 MediaSession，保留导航/通话音频；无线交接后保留蓝牙 bootstrap |
| GEELY 返回车机图标和标签 | 已接入运行链路 | 默认 OEM label 改为 `GEELY`，并把 `ic_car_home_geely` 编码成 192×192 PNG 提供给 CarPlay |
| 向 AutoDisplay 共享导航快照 | 已接入运行链路，但不是吉利原车接口本身 | 用显式、受权限保护的广播把同一份路线快照发送给 `com.simplerbit.autodisplay` |

最值得复用的是导航输出的分层：来源所有权管理、路线状态、厂商 Driver 接口、反射 Adapter、清理状态和诊断状态彼此分离。最需要警惕的是方向盘部分：类和文案都存在，但没有调用入口，不能据此宣称已经支持。

## 2. 样本与分析边界

### 2.1 APK

- 路径：`/Users/bytedance/Downloads/DiPlay_0.2.13_Packed_ADSigned_20261006 (2).apk`
- 大小：`19,611,228` bytes
- SHA-256：`84bef06e82d1b73bdae53edb52640f78e7680eb3523c66a3a8354c2eb53320f0`
- package：`com.shihab.diplay`
- version：`0.2.13` / versionCode `32`
- minSdk：28
- targetSdk / compileSdk：37
- Application：`com.sagittarius.v6.StubApplication`
- 加固特征：`libbaiduprotect.so`、`assets/baiduprotect*.dex/jar`
- APK 签名：仅 v3，证书 SHA-256 为 `bfaec2cbd62aa97961260bceba41083c5ba1558ef40f4512b1206a5c492e40ac`

APK 内还直接携带 `assets/offline-mfi/identity.pk8` 和 `certificate.p7b`。本报告只分析功能实现，不评价这些身份材料的授权、分发或安全性。

### 2.2 脱壳 DEX

来源目录：`/Users/bytedance/Downloads/diplay-ad-release`

| 文件 | 大小 | SHA-256 |
| --- | ---: | --- |
| `classes.dex` | 24,200 | `c93830e330540670f05e187e675a904e0c4fc1c4036392b30d6abfdf00374f44` |
| `classes2.dex` | 191,308 | `d748fc5c2403984366d558b51af080453dddb97e928e592c960f6871718731b7` |
| `classes3.dex` | 89,976 | `1623ab7c6d0bb7dbc4c9d7489984b65fb83d214a990bdf5915dd3aa5a70b2474` |
| `classes4.dex` | 2,765,976 | `82c581a2fbcd080182c9a665432a5aba8f17d6eec0f2045d0fd75f6c3efd284c` |

使用 JADX 1.5.5 反编译。JADX 对少量 Kotlin 默认参数构造器报告了 10 个 SSA/变量初始化错误，涉及 `AirPlayConfig`、`Iap2IdentificationConfig` 等合成构造器；本文引用的吉利导航、Binder、广播、媒体策略和生命周期方法均成功反编译。反编译代码不是原始源码，局部变量名和个别合成调用可能不准确，因此结论优先依赖类名、常量、调用顺序和 Manifest/资源的交叉证据。

### 2.3 吉利车机原厂 Flyme Map APK

为了确认车机端是否存在结构化车道写入接口，补充分析原厂地图 APK：

- 路径：`/Users/bytedance/Downloads/flyme_map_apk/___2.0.0.(l7a1)(26042019)(9768fdd59).apk`
- version：`2.0.0.(l7a1)(26042019)(9768fdd59)`
- minSdk / targetSdk：30
- SHA-256：`77732106354ca16ddc06658dda21cf29a12bf6851c5a0cfed995417d65c375c6`
- 签名：Autolink platform 证书
- 关键权限：`android.car.permission.CAR_DISPLAY_IN_CLUSTER`、`android.car.permission.CAR_VENDOR_EXTENSION`

该 APK 约 377 MB，JADX 报告 110 个反编译错误，主要集中在资源构造器、协程和少量复杂方法；本章引用的 ECARX XUI 接口、转发实现、AMap 车道模型和 icon 映射均成功反编译。它还携带 ECARX Navi SDK 的离线 Javadoc，可用于交叉确认 `LaneItem` 和 `LanesInfo` 的字段含义。

## 3. 与上游代码的关系

本仓库的 `v0.2.13` tag 和当前 `main` 都没有以下代码：

- `com.shilapi.xcertplay.geely/*`
- `GeelyNavigationSettings`
- `GeelyNavigationOutput`
- `OneOsWheelAdapter` / `OneOsWheelProtocol`
- `SteeringWheelRuntime` / `SteeringWheelSettings`
- `VehicleVoiceBroadcastAdapter`
- `MediaControlOwner` / `MediaControlSettings`
- `LocalMediaAudioPolicy`
- `DiPlayNavigationSharing`
- `CarPlayHomeIcon`

上游 `v0.2.13` 和当前 `main` 的 `DEFAULT_OEM_LABEL` 都是 `BYD`；APK 将其改成了 `GEELY`。当前源码文档也明确记录过：上游未引入吉利 SDK、外部二进制或对应连接改写。因此这个 APK 应视为基于 DiPlay 的独立适配构建，不能把其中行为直接归到上游 `v0.2.13`。

APK 没有打包 `com.ecarx.*` 或 `com.geely.lib.oneosapi.*` SDK 实现类。导航通过字符串类名和反射使用车机系统自带 SDK；方向盘 OneOS 适配则直接手写 Binder descriptor 和 transaction code。

## 4. 吉利原车导航提示

### 4.1 总体调用链

```text
iPhone
  └─ iAP2 RouteGuidance 0x5201 / 0x5202
       └─ CarPlayController
            └─ GeelyNavigationControlSources
                 ├─ 有线：使用 wired CSM channel
                 └─ 无线：先接 Bluetooth bootstrap，后切到优先的 tunnel iAP2 channel
                      └─ GeelyNavigationOutput
                           └─ GeelyNavigationRuntime
                                └─ BydHudRouteState（复用现有 Apple RouteGuidance 解析器）
                                     └─ GeelyNavigationSnapshot
                                          └─ GeelyManeuverCodes
                                               └─ GeelyDimNavigationAdapter
                                                    └─ 车机 DimInteraction / INaviInteraction
                                                         └─ 原车仪表导航提示
```

这里没有 OCR，也不是把 CarPlay 地图画面投到仪表。输入是 iPhone 发来的结构化路线数据，输出是原车导航提示字段。

### 4.2 输入字段

`GeelyNavigationRuntime` 直接复用了原 BYD HUD 管线里的 `BydHudRouteState` 和 `BydAppleManeuver`，从 `0x5201` / `0x5202` 得到：

- Apple maneuver type
- 下一转向距离，米
- 道路名称
- 剩余路线距离，米
- 剩余时间，秒
- 路线是否仍有效

`20993` 和 `20994` 正好是十进制形式的 `0x5201` 与 `0x5202`。

### 4.3 有线、无线和 session 所有权

`GeelyNavigationControlSources` 维护唯一的 source token、当前 iAP2 channel、AirPlay session 和 `preferred` 标记：

- 有线 CSM channel 直接作为 preferred source。
- 无线启动时，Bluetooth iAP2 bootstrap 先作为非 preferred source。
- AirPlay type-130 tunnel 建立后，tunnel channel 替换为 preferred source。
- 已经有 preferred channel 时，不允许旧的非 preferred channel 抢回所有权。
- channel、source、session 结束时会分别清理，防止旧会话继续向仪表输出。

这一层解决的不是吉利协议，而是无线 CarPlay 从蓝牙控制链路切到 Wi-Fi tunnel 时，哪条链路有资格提供最新 RouteGuidance。这个设计可以直接复用于其他厂商输出。

### 4.4 Runtime 状态机

`GeelyNavigationRuntime` 使用单线程 `ScheduledThreadPoolExecutor`，线程名为 `diplay-geely-navigation`，每秒 tick 一次。只有同时满足以下条件才创建 Driver：

- 设置已启用；
- Controller owner 有效；
- RouteGuidance source 有效；
- 活动 AirPlay session 已建立。

它用 `Lease` 和 execution guard 拦截旧任务，配置变化、source 变化或 session 变化都会替换 lease。每次 publish 前都会再次检查 lease 是否仍然有效。

路线更新后构造 `GeelyNavigationSnapshot`。与上一份 snapshot 相同则不重复发送；路线消失时调用 `clear()`。关闭失败或厂商清理未确认时，会保留 `pendingCleanup` 并阻止新 Driver 启动，状态页提示用户停车后刷新重试。

### 4.5 厂商 SDK 入口

核心反射类名：

```text
com.ecarx.xui.adaptapi.diminteraction.DimInteraction
com.ecarx.xui.adaptapi.diminteraction.INaviInteraction$INavigationInfo
com.ecarx.xui.adaptapi.diminteraction.INaviInteraction$INavigationInteractionCallback
com.ecarx.xui.adaptapi.binder.IConnectable$IConnectWatcher
```

初始化步骤：

1. 验证 `INavigationInfo` 是接口，并验证 getter 的返回类型。
2. 调用静态 `DimInteraction.create(Context)`。
3. 查找 `getNaviInteraction()`。
4. 查找 `setPresentationToDimSwitch(number, string, string, boolean)`。
5. XUI/Flyme backend 注册 connect watcher 并调用 `connect()`。
6. OneOS backend 跳过 watcher/connect，直接认为连接信号已就绪。
7. 获取 `INaviInteraction` 后，验证所有写入方法签名。
8. 如果存在 navigation callback 接口，则注册动态代理；代理目前只返回默认值，不处理车机回调业务。

接口探测按钮仅检查 `DimInteraction` 及其 `create(Context)` 是否存在。它不会验证权限、service 连接，更不能证明仪表实际显示。

Manifest 中声明了：

```xml
<uses-permission android:name="com.permission.adnaviservice" />
```

代码没有自行授予或绕过该权限。如果车机按签名、白名单或激活身份控制接口，普通安装包仍可能在 connect/publish 阶段失败。

### 4.6 向原车接口写什么

Driver 首次收到有效路线时依次执行：

```text
setPresentationToDimSwitch(1, "", "", true)
updateNavigationInfo(infoProxy)
notifyNavigationStatus(1)
notifyTurnByTurnStarted()
updateDistanceToDestination(remainingMeters)
updateDistanceToNextGuidancePoint(nextDistanceMeters)
updateTurnByTurnArrow(mappedTurnId)
updateETA(remainingSeconds)
updateNextGuidancePointName(road)
```

后续更新直接刷新 `updateNavigationInfo` 和各字段，不再重复 start。

`INavigationInfo` 动态代理提供：

- `getNavigationStatus()`：0/1
- `getNavigationTurnId()`：映射后的箭头 ID
- `getDistanceToNextGuidancePoint()`
- `getDistanceToDestination()`
- `getETA()`
- `getNextGuidancePointName()`
- `getDayNightMode()` / `getDrivingDirection()`：反编译结果显示固定返回 1
- `getMuteState()`：0
- `getExtensionInfo()`：空 `Bundle`

停止时执行：

```text
updateNavigationInfo(emptyInfo)
notifyNavigationStatus(0)
notifyTurnByTurnStopped()
setPresentationToDimSwitch(1, "", "", false)
```

任何调用返回 `false` 或负数都会被当作 OEM 拒绝。失败后不再继续写，先尝试停止导航、注销 callback/watcher 并 disconnect。

### 4.7 backend 选择的真实差异

设置页有三个选项：

- ECARX XUI
- Flyme Auto
- OneOS / OneOS 2 / KX11

但 DEX 中只有一个 `GeelyDimNavigationAdapter`，三者使用相同的 XUI `DimInteraction` 类名和相同导航写入方法。

真实差异只有：

- `ONE_OS`：跳过 `registerConnectWatcher()` / `connect()`，直接尝试 `getNaviInteraction()`；可选择保留 Apple 原始 maneuver ID。
- `ECARX_XUI` 与 `FLYME_AUTO`：执行完全相同的 watcher/connect 流程。

因此 Flyme Auto 并不是独立的 Flyme 导航协议实现；它更像一个生命周期预设。后续若移植，应该把“平台标签”升级成真正不同的 Adapter，或者把这两个等价选项合并，避免给用户造成已验证多套协议的印象。

### 4.8 maneuver 映射

默认将 Apple maneuver type 映射为原车 turn ID：

| Apple type | 原车 turn ID |
| --- | ---: |
| 0, 51 | 0 |
| 1, 20 | 102 |
| 2, 21 | 103 |
| 3, 52, 53 | 109 |
| 4, 18, 26 | 108 |
| 5 | 120 |
| 6, 7 | 15 |
| 8 | 12 |
| 9 | 13 |
| 10, 24, 25, 27 | 115 |
| 11 | 101 |
| 12 | 22 |
| 13 | 48 |
| 14 | 49 |
| 15, 16, 17 | 18 |
| 19 | 44 |
| 22 | 47 |
| 23 | 46 |
| 28–44 | Apple type + 1 |
| 45, 46 | 45 |
| 47 | 106 |
| 48 | 107 |
| 49 | 104 |
| 50 | 105 |
| 其他 | 0 |

当 backend 为 OneOS 且用户明确打开“使用 OneOS 原始转向图标”时，不做映射，直接把 Apple maneuver type 作为 turn ID。该选项默认关闭。

### 4.9 设置与安全提示

- 功能默认关闭。
- 更换 backend 会自动关闭输出，需要重新确认开启。
- 开启前显示风险对话框，明确提示可能切换仪表的原车导航模式、覆盖其他导航提示。
- 设置页每秒刷新 Driver 状态。
- 用户可手动刷新 SDK/连接/清理状态。
- 文案明确说“接口存在或调用成功不代表仪表已显示”，与代码行为一致。

## 5. 车道展示支持

### 5.1 结论与证据边界

CarPlay 车道信息可以桥接到吉利车机的 ECARX 导航接口，但当前吉利版 DiPlay 尚未实现这条链路。两端能力已经找到：

```text
iPhone CarPlay
  └─ iAP2 0x5204 LaneGuidanceInformation
       └─ 按事件编号缓存逐车道位置、状态和方向角
            └─ 由 0x5201 的 current index / showing 选择当前事件
                 └─ 生成 ECARX ILaneInfo[]
                      └─ INaviInteraction.updateNavigationInfo / updateLaneInfo
                           └─ 吉利仪表车道展示
```

证据强度需要分三层理解：

1. **APK 内已确认的 OEM 能力**：Flyme Map 携带的 XUI SDK 定义并实现了 `ILaneInfo[]` 转发，证明平台接口支持结构化车道输入。
2. **原厂地图实际业务接线**：原厂地图自身主要把 AMap 车道数据用于本地 UI 和 EAS Map OpenAPI，没有发现它调用 XUI `updateLaneInfo()`；因此不能仅凭静态分析宣称这台仪表已实车验证该接口。
3. **Apple 输入协议**：`0x5204` 字段结构来自公开的 CarPlay RGI 逆向实现及测试，不是 Apple 官方公开协议文档，应通过 DiPlay 原始帧日志和实车再次核对。

### 5.2 ECARX XUI 的车道接口

`INaviInteraction.ILaneInfo` 暴露三个 getter：

```java
Bitmap getLaneBackIcon();
int getLaneBackIconId();
int getLaneNumber();
```

车道既可以随完整导航快照返回，也可以独立更新：

```java
ILaneInfo[] INavigationInfo.getLaneInfo();
void INaviInteraction.updateLaneInfo(ILaneInfo[] lanes);
void INaviInteraction.updateNavigationInfo(INavigationInfo info);
```

`NaviInteraction` 不是空壳。它会逐项读取 `Bitmap`、`iconId` 和 `laneNumber`，构造底层 `EcarxLaneInfo[]`，再调用 `mNaviDIMInteractionManager.updateLaneInfo(...)`。`updateNavigationInfo(...)` 也会把非空的 `getLaneInfo()` 数组复制到下层导航对象。

清理行为需要特别处理：

- `updateLaneInfo(null)` 会直接读取 `iLaneInfoArr.length`，在当前 SDK 实现中会抛出空指针异常。
- `updateNavigationInfo()` 遇到 `getLaneInfo() == null` 时只记录日志，没有向下层设置车道数组，可能保留旧车道。
- 隐藏或结束导航时应返回/发送**非空的空数组**，不能依赖 `null` 清理。

### 5.3 Apple `0x5204` 数据结构

公开实现 `luka-dev/mib2q-carplay-rgi` 的 `72d321cc9f57bc2f3a1bb76021404c96b300f326` 提交中，`0x5204` 被解析为以下顶层 TLV：

| TLV ID | 字段 | 作用 |
| ---: | --- | --- |
| `0x0000` | ComponentID | RouteGuidance display component |
| `0x0001` | LaneGuidanceIndex | 车道事件编号 |
| `0x0002` | LaneInformations | 嵌套的逐车道信息，可重复/包含多条车道 |
| `0x0003` | LaneGuidanceDescription | 车道提示描述字符串 |

`LaneInformations` 内每条车道使用：

| 子 TLV ID | 字段 | 解析方式 |
| ---: | --- | --- |
| `0x0000` | index / position | `u16`，车道从左到右的位置；部分样本可能只有 `u8` |
| `0x0001` | laneStatus | `u8`：`0=NOT_GOOD`、`1=GOOD`、`2=PREFERRED` |
| `0x0002` | laneAngles | 重复的有符号 `i16` 角度，描述该车道允许的所有方向 |
| `0x0003` | laneAngleHighlight | 有符号 `i16`，推荐高亮方向；`1000` 可作为未知哨兵 |

角度是普通度数，不是 maneuver geometry 使用的半度数。公开渲染实现将负角度视为左侧、正角度视为右侧，并把超出 `[-180, 180]` 的值视为未知。

车道是独立事件，不应绑定成“当前 maneuver 的普通字段”：

- `0x5204` 可能提前发送整条路线后续的多个车道事件。
- `0x5201` 的 `0x0010 LaneGuidanceCurrentIndex` 决定当前事件。
- `0x5201` 的 `0x0012 LaneGuidanceShowing` 决定是否应显示。
- `0x5201` 的 `0x0011 LaneGuidanceTotalCount` 可用于诊断和容量检查。
- `0x5202` 的 `0x000C LinkedLaneGuidance` 可以把 maneuver 与车道事件关联，但只要 current index/showing 完整，就不应让新到达的 `0x5204` 自行抢占当前展示。

建议第一版最多保留 8 条车道、每条 16 个角度，并对超容量、缺少 index/status、畸形嵌套 TLV 做 fail-closed 处理。这个上限来自已验证的公开实现，不代表 Apple 官方协议的硬上限。

### 5.4 当前 DiPlay 和吉利适配 APK 的缺口

当前 `main` 在 iAP2 Identify 的 `RouteGuidanceDisplayComponent` 中只声明：

- 参数 `2`：`MaxCurrentRoadNameLength`；
- 参数 `4`：`MaxAfterManeuverRoadNameLength`；
- 参数 `6`：`MaxGuidanceManeuverStorageCapacity`。

要让 iPhone 发送车道，还需要声明：

- 参数 `7`：`MaxLaneGuidanceDescriptionLength`；
- 参数 `8`：`MaxLaneGuidanceStorageCapacity`。

同时还存在以下缺口：

1. `MESSAGES_RECEIVED_FROM_PHONE` 只允许 `0x5201/0x5202`，没有 `0x5204`。
2. `Iap2Endpoint` 只登记了 `0x5204` 名称，没有顶层和嵌套 payload schema。
3. `BydNavigationOutputs` 在入口处过滤掉除 `0x5201/0x5202` 以外的路线帧。
4. `BydHudRouteState` 不解析 lane current index、showing、逐车道角度或独立 lane event cache。
5. 吉利 APK 的 `GeelyNavigationRuntime.onFrame()` 只接收十进制 `20993/20994`，即 `0x5201/0x5202`。
6. `GeelyNavigationSnapshot` 不包含车道，车道单独变化无法突破 snapshot 去重。
7. `GeelyDimNavigationAdapter` 的动态代理没有处理 `getLaneInfo()`；对象类型的默认返回值是 `null`，所以现有实现一定不会向仪表写车道。

这说明当前缺失的是一条完整的数据链，而不是只补一个 `updateLaneInfo()` 调用。

### 5.5 icon ID 与 Bitmap 的选择

Flyme Map 中存在两套容易混淆的整数图标：

1. `global_image_landback_*` / `global_image_landfront_*` 是原厂地图包自己的 Android drawable resource ID，只能由原厂地图的 `Resources` 正确解析。
2. `LaneInfoResUtil` 把 AMap `backLane` / `frontLane` action 映射为 `LaneItem.laneIconId`，基础值包含 `0..12`，组合推荐车道包含 `15..95` 等值。这套编号属于 `com.ecarx.eas.mapsdk` Map OpenAPI。

目前没有证据证明 EAS 的 `LaneItem.laneIconId` 与 XUI 的 `ILaneInfo.getLaneBackIconId()` 使用同一套编号。原厂地图 APK 的 `R.drawable` 数值也不能复制到 DiPlay：Android 资源 ID 是 package-local，换一个 APK 就可能指向完全不同的资源。

因此推荐按以下顺序实车验证：

1. 第一版由 DiPlay 根据 Apple 的 angle vector 和 status 动态绘制每条车道的最终 `Bitmap`。
2. `getLaneBackIcon()` 返回非空 Bitmap，`getLaneBackIconId()` 暂时返回 `0`。
3. `status == 2` 时只高亮 `laneAngleHighlight` 对应箭头；其他允许方向保持普通颜色。
4. `getLaneNumber()` 先传 Apple 的物理 position；接口未说明 0-based/1-based，需要用两车道测试样本实车确认。
5. 如果 Bitmap 路径不生效，再测试 DiPlay **自己包内**的 drawable ID；只有确认编号语义相同后，才尝试 EAS semantic icon ID。

XUI wrapper 会同时向下转发 Bitmap 和 icon ID，但 APK 中没有体现底层优先级。因此“非空 Bitmap + iconId 0”是风险最低的首次实验方案，不是已完成的实车结论。

### 5.6 最小实现方案

建议把车道解析做成品牌无关模块，而不是继续塞进 `BydHudRouteState`：

1. **iAP2 capability**
   - Identify group 30 增加参数 `7/8`；
   - `MESSAGES_RECEIVED_FROM_PHONE` 增加 `0x5204`；
   - `Iap2Endpoint` 增加 `0x5201` lane 状态字段和 `0x5204` 嵌套 schema。
2. **品牌无关状态模型**
   - `AppleLaneGuidanceEvent(index, description, lanes)`；
   - `AppleLane(position, status, angles, highlightAngle)`；
   - 按 event index 缓存，按 `0x5201` current index/showing 选择当前快照；
   - 路线 generation、source/session handoff、路线结束时清空。
3. **吉利 runtime**
   - 接受 `0x5204`；
   - 把当前 lane snapshot 纳入 `GeelyNavigationSnapshot` 或提供独立的 lane revision；
   - 车道变化必须能单独触发 publish，不能依赖路线距离或道路名同时变化。
4. **XUI 反射 Adapter**
   - 加载 `com.ecarx.xui.adaptapi.diminteraction.INaviInteraction$ILaneInfo`；
   - 用 `Array.newInstance(laneType, count)` 创建运行时类型正确的数组；
   - 每个元素使用动态代理提供 Bitmap/iconId/laneNumber；
   - `INavigationInfo.getLaneInfo()` 始终返回非空数组；隐藏时返回长度为 0 的数组。
5. **资源和线程**
   - 缓存相同 angle/status 组合生成的 Bitmap，避免每秒 tick 重画；
   - 日夜模式变化时失效缓存；
   - 所有 Bitmap 的创建、替换和释放保持在吉利导航单线程 worker 内。

调用上可以继续复用现有的 `updateNavigationInfo(infoProxy)`。如实车发现 lane-only 更新不会刷新，再增加 `updateLaneInfo(laneArray)`，但两条路径必须共用同一个 typed array 和清理语义。

### 5.7 验证矩阵

在宣称支持之前至少需要验证：

- Identify 后 iPhone 是否开始发送 `0x5204`；
- Apple Maps、Google Maps、开启 CarPlay 导航提示的 AMap 是否都有 lane event；
- 两车道、三车道、直行+左右组合、掉头和无推荐车道；
- 多个未来 `0x5204` 到达时，只展示 `0x5201 current index` 指向的事件；
- `LaneGuidanceShowing=0`、路线结束、重新规划、source/session handoff 会发送空数组并清除旧车道；
- 畸形或超容量 TLV 不发布部分状态，不保留上一条错误车道；
- Bitmap 尺寸、透明背景、日夜颜色、左右顺序和 0/1-based laneNumber；
- XUI `updateNavigationInfo()` 与 `updateLaneInfo()` 哪条能在目标仪表可靠刷新；
- 普通签名安装包是否仍具备 `adnaviservice`/厂商白名单权限。

如果当前吉利版 APK 的道路名、距离和转向箭头已经能在目标车上显示，说明 XUI 连接和基础权限大概率已经通过；车道实现的主要剩余风险将集中在 Bitmap/iconId 约定和仪表刷新行为，而不是整体服务可达性。

## 6. HUD／仪表地图投屏与路口图

### 6.1 三条不同的显示链路

本节补充分析当前 `main`（HEAD `6ebb58218f90b0c0853b78013cb8431765ca77bb`）、吉利适配 APK 的脱壳 DEX，以及原厂 Flyme Map。日常所说的“HUD 地图投屏”在代码中实际对应三条通路：

| 通路 | 输入 | 输出及实际内容 |
| --- | --- | --- |
| CarPlay 仪表地图视频 | iPhone 独立 AirPlay stream `111` | iPhone 渲染好的地图／官方转向卡像素，解码到仪表或桌面 Surface |
| DiPlay 自绘转向卡 | iAP2 `0x5201/0x5202` | 本地矢量转向图标、下次转向距离、道路名、环岛出口，以及可用的 ETA／剩余时间／剩余里程 |
| 原车 HUD／仪表导航提示 | 同一份 iAP2 路线状态 | BYD SOME/IP、BYD 原车广播或吉利 ECARX 字段调用，由 OEM 决定最终样式；这一通路没有完整地图视频 |

第一条传像素，后两条传语义。视频中显示车道箭头，不等于 DiPlay 已经解析并向原车写入 `0x5204`；反过来，只能写原车转向字段，也不等于能把完整 CarPlay 地图投到物理仪表。

### 6.2 地图视频从哪里来

地图不由 DiPlay 下载地图瓦片、规划路线或识别主屏截图生成。`CarPlayHostActivity.clusterDisplayConfig()` 在地图功能启用后，给 `AirPlayConfig.cluster` 提供第二屏配置；`AirPlayInfoPlist.build()` 将它写入 `/info` 的 `displays`：

- 主 CarPlay 屏：stream type `110`；
- 仪表 alt screen：stream type `111`，独立 UUID；
- 告知 iPhone 分辨率、帧率、safe area、view area 和 `initialURL`；
- 第二屏没有普通触摸/HID 输入；地图缩放可以通过发往 alt UUID 的 `changeMapZoomLevel` 命令实现。

`CarPlayClusterDisplay.Content` 的四种选择为：

| 模式 | initialURL | 谁绘制内容 |
| --- | --- | --- |
| `MAP` | `maps:/car/instrumentcluster/map` | iPhone 地图视频 |
| `TURN_CARD` | `maps:/car/instrumentcluster/instructioncard` | iPhone 官方转向卡视频 |
| `INSTRUMENTS` | `maps:/car/instrumentcluster` | iPhone 提供的组合仪表导航界面；具体画面由 iOS／导航 App 决定 |
| `MAP_WITH_CUSTOM_CARD` | 与 `MAP` 相同 | iPhone 地图 + DiPlay 本地叠加转向卡 |

代码注释记录 iPhone 会在 `/info` 请求中报告 `altScreenURLs`，但当前处理只将请求内容记入日志，没有把它解析成可动态选择的能力列表。内容枚举仍是固定 URL；这些模式是否都可用，需要按 iOS／导航 App 版本验证。不能因为枚举存在就认为任意手机都有对应画面。

接收与显示链路是：

```text
CarPlayHostActivity.clusterDisplayConfig()
  → /info: displays[type=111, initialURL, safeArea, size]
  → iPhone SETUP streams[type=111]
  → AirPlaySession.handleStreams()
  → CarPlayMediaEngine.onScreen() 返回独立 TCP dataPort
  → ScreenStream 接收配置与加密视频帧
  → ChaCha20-Poly1305 解密，NAL 转 Annex B
  → AndroidMediaSink.onVideoConfig/onVideoFrame(type=111)
  → MediaCodec H.264／H.265 解码
  → SurfaceView／TextureView／桌面嵌入 Surface
```

切换地图／官方卡片时，`AirPlaySession.setClusterUrl()` 发送针对 alt UUID 的 `showUI(url)`，再请求 `forceKeyFrame`，无需为单纯内容切换重连。暂停时发送 `stopUI`；恢复时重放 URL 并请求关键帧，底层视频通道保持已建立状态。

因此地图内道路、路线、车标及地图 App 选择绘制的标注都作为视频像素呈现，DiPlay 不从视频反解析路线坐标、交通数据或路口图片。`INSTRUMENTS` 这个命名也不能理解为手机向车机同步实际车速、ADAS 或动力仪表数据。

### 6.3 视频投到哪些屏

**BYD 物理仪表**：`ClusterMapPresentation` 使用 Android `Presentation` 在已识别的外接屏上放置 Surface。选择规则包括：

- DiLink 5：`fission_bg_XDJAScreenProjection` 及相应共享层；
- 特定 DiLink 5.1 firmware：`shared_fission_bg_XDJAScreenProjection_0` 全层、`_1` 侧层，按 fingerprint 和主题选择；
- DiLink 4：`fission_bg_xdjaVirtualSurface`，已测量的 1920×720 屏；当前 `main` 还认可 1280×480 的已观察投影形态，吉利样本的对应选择代码仍只匹配较早的规则；
- 无公开 Presentation display 的特定 BYD 模式：经本地 ADB 查询匹配名称、尺寸和 `com.xdja.containerservice` owner 的屏，再 `am start-activity --display ...` 启动 `AdbClusterActivity`。

默认 1920×720 面板使用 83% 请求约 1600×600 视频，30 FPS；DiLink 4／特定 5.1 profile 请求原生 1920×720。safe area 用来让 iPhone 的车标处于未被原车读数覆盖的区域，`safeAreaDrawOutside=true` 允许地图背景继续铺满。DiLink 5.1 另有本地侧图裁剪和原车读数对比度遮罩。改变 safe area／分辨率是重新协商画布，不能理解成 DiPlay 自己改变地图路线内容。

**中控悬浮卡片**：`CenterMapOverlay` 用 `SYSTEM_ALERT_WINDOW` 和 TextureView 在桌面上显示同一条 `111` 流；点击打开 CarPlay，拖动／捏合只改变本地窗口位置／大小。

**Launcher／AutoDisplay 内嵌地图**：Android 11+ 的 `MapEmbedService` 通过 Messenger 接收 host token、displayId 和视图尺寸，创建 `SurfaceControlViewHost`，返回 `SurfacePackage` 供另一 App 的 SurfaceView 嵌入。

`MapMirrors` 将每个输出 Surface 登记到 `AndroidMediaSink.setMirrorSurface(111, key, surface)`。媒体后端把同一份 codec config 和压缩帧交给各个镜像解码器；这是多 Surface 各自解码，增加镜像会增加解码开销，没有为每个视图请求独立地图视频。

没有匹配到物理仪表时，当前 `main` 仍可请求 1280×720、16:9 的虚拟仪表视频供桌面使用。这证明“能收到仪表视频”和“已经投到原车物理仪表”是两个不同状态。

### 6.4 吉利版 APK 的实际接线与 AutoDisplay 扩展

脱壳 DEX 同样有 `CarPlayClusterDisplay`、`AirPlaySession`、`AndroidMediaSink`、`ClusterMapPresentation`、`CenterMapOverlay` 和 `MapEmbedService`；`CarPlayHostActivity` 明确把镜像 Surface 接到 `setMirrorSurface(111, ...)`，视频通路已经接线。

相比当前 `main`，这个样本额外支持 `MapEmbedRenderSize`／`MapEmbedRenderTargets`：

- 客户端通过 `renderTarget=cluster`、`renderWidth`、`renderHeight` 请求 iPhone 地图画布尺寸；
- 仅允许 UID 所属 package 包含 `com.simplerbit.autodisplay` 且与 DiPlay 签名一致的客户端提出自动尺寸请求；
- 请求尺寸边界为宽 `160..4096`、高 `120..2160`，还会校验按 8 对齐后的协商尺寸；这只是参数校验，不能保证任意尺寸都有硬件解码支持；
- Host 无物理仪表配置时，优先使用手工渲染尺寸，其次使用 AD 请求，再退回 1280×720；
- 输出视图尺寸与请求 iPhone 重画的画布尺寸是两个概念，不能只调整 SurfaceView 大小就认为手机重新渲染了对应地图。

这些类可以解释该样本为何能够为 AD 提供地图。它们与第 10 节的 `NAVIGATION_SNAPSHOT` 广播分别传递视频 Surface 和路线语义；广播字段没有地图瓦片、路线坐标或路口图片。

另一方面，`ClusterMapPresentation.findDisplay()` 和 `AdbClusterRouter` 的原车屏选择仍主要针对 BYD，没有发现专门选择 Flyme `displayId=11` 并向其送入视频的 Adapter。`GeelyDimNavigationAdapter.setPresentationToDimSwitch()` 只调用 OEM 开关与导航字段，没有把 `111` Surface 注册到吉利地图屏。

所以该样本可以确认接收地图、桌面嵌入和吉利导航字段写入；仅分析这个 APK，还不能确认它独立完成了吉利物理仪表完整地图投屏。AutoDisplay 是否进一步把嵌入的视频送到仪表，需要 AutoDisplay 或车机显示服务的实现证据。

### 6.5 HUD／转向卡实际投了哪些信息

| 输出 | 实际投递 | 当前没有投递的内容 |
| --- | --- | --- |
| BYD SOME/IP HUD | 本地 PNG 图标、原车箭头编号、下次转向距离、道路名、开始／清除状态；每 300 ms 重发 | 完整地图、真实路口放大图、逐车道数组；虽然 payload 有 ETA 字符串槽位，当前调用没有传 ETA，实际为空 |
| BYD standalone HUD | 转向 code、距离、道路名，经 `byd.hud.NAVIGATION` 的 `normal` 字符串发给 `com.byd.clusterdebug.BroadcastReceiverCAN` | 地图视频、路口图片、独立 ETA；导航无活动时可按 HUD song 设置在共享文本行显示歌曲内容 |
| BYD 仪表导航广播 | `NEW_ICON`、`ROUNG_ABOUT_NUM`、`SEG_REMAIN_DIS`、`NEXT_ROAD_NAME`、`ROUTE_REMAIN_DIS`、`ROUTE_REMAIN_TIME` | 地图／路口 Bitmap；原车自己依据编号绘图 |
| DiPlay 自绘转向卡 | 本地矢量 icon、环岛出口徽标、距离、道路名；存在有效字段时加 ETA、剩余小时／分钟、剩余公里数 | lane array、真实路口背景、道路连接几何、地图缩略图 |
| 吉利 XUI adapter | navigation status、turnId、下一转向／剩余距离、道路名、剩余秒数作为 ETA 参数 | 当前 `getLaneInfo()` 和 `getNavigationTurnSVG()` 均没有实现，返回 null；没有地图 Surface 或 cross image |
| iPhone 官方卡／地图 | `111` 视频的所有已渲染像素 | DiPlay 没有把卡片拆成图片或字段；其具体布局和车道／路口内容取决于 iPhone 实际输出 |

歌曲、电话卡等另有 iAP2／OEM 通路，不能将这些额外功能理解成地图视频携带的结构化信息。原车自己的车速、动力和 ADAS 图层也由原车绘制。

### 6.6 “路口图”是怎么做的

这里需要区分转向符号与真实路口放大图。

**DiPlay 自绘卡片的转向符号**：`0x5202` 给出 maneuver type，`BydHudRouteState` 目前读取 index、type、AfterRoadName、DrivingSide。`BydClusterFrame` 把 Apple type 映射为 AMap NEW_ICON 词汇，`ClusterTurnCardView.glyphRes()` 再选择本地 Material Symbols 矢量资源，例如左转、右转、直行、掉头、环岛。View 用 Canvas 绘制卡片、图标、距离、道路和信息条；环岛附 1..9 出口徽标。这是固定符号选图，没有按路口的多条支路动态重建几何。

**BYD SOME/IP HUD 图形**：`BydHudBridge.iconFor()` 从 `shared/src/main/assets/byd-hud-icons/0x<code>.png` 读取对应转向图。PNG 放进 `BydHudPayload` 的 field 8，箭头 code 放 field 28，距离 field 9，道路 field 10。环岛／目的地等缺少可用原车箭头的动作将箭头置为空样式，依赖 PNG 呈现。图片来自仓库内 BYDMate 图标资源，而不是导航途中由 iPhone 返回的路口图片。

**iPhone 官方卡／地图上的路口画面**：若 iPhone 在 `111` 输出中已经绘制某种路口图或车道提示，DiPlay 只是解码显示；源码没有独立的“从主屏抽取路口图”模块，也没有保证第三方地图主屏上出现的路口放大图会同步到 `111`。需要实测对应 iOS／地图 App 在 `MAP`、`TURN_CARD` 和 `INSTRUMENTS` 模式下的输出。

**Flyme 原厂地图的真实路口图**：这是另一条 AMap SDK 渲染链。`NaviObserverProxy.onShowCrossImage(CrossImageInfo)` 接收 `dataBuf`、`arrowDataBuf`、type 和 distance：

- type `3/4`：`GuidanceMapViewManager.updateCross(dataBuf, type)` → `BizRoadCrossControl.updateCross(...)`，渲染矢量路口；
- type `1`：分别把 `arrowDataBuf` 和 `dataBuf` 包装成箭头纹理／道路背景纹理，经 `setRasterImageData(...)` 渲染栅格路口；
- `SyncMapViewManager` 把矢量 cross 数据或栅格纹理同步给其他已注册地图显示视图。

原厂 SDK 的 `CrossImageInfo` 确实携带路口专用数据，DiPlay 的 `0x5201/0x5202` 状态模型和 AD 快照没有这些字段。能找到原厂路口渲染链，不等于可以用它直接画 iPhone 正在导航的路口：两端还缺对应的道路几何／背景数据。

### 6.7 如果后续要做真实路口展示

可以分两个目标推进：

1. **原样展示手机输出**：先抓 `111` 视频，确认 iPhone 本身是否把所需路口画面送到 alt screen；如果有，复用现有视频／Surface 链路即可。吉利还需补齐物理仪表 display、模式控制和权限适配。
2. **DiPlay 自绘简化路口**：公开 RGI 解析证据表明 `0x5202` 有 `0x0009 JunctionType`、`0x000A JunctionElementAngle`、`0x000B JunctionElementExitAngle`。当前解析器忽略这些字段；可在拿到真实帧核对后补充几何解析，根据各支路角度画简化路口并高亮出口。它只能重建方向示意，不能从这些语义生成高德的真实道路纹理／三维实景背景。车道仍使用第 5 节的独立 `0x5204` 生命周期。

当前代码已支持的是地图视频接收与固定转向符号。真实 cross image 注入、自绘支路几何，以及独立吉利仪表视频路由都应分别列为后续能力，不能合称已经实现“路口图支持”。

### 6.8 关键源码与复核入口

- 当前仓库：[CarPlayClusterDisplay.kt](../shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayClusterDisplay.kt)、[AirPlayInfoPlist.kt](../shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlayInfoPlist.kt)、[AirPlaySession.kt](../shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlaySession.kt)、[CarPlayMediaEngine.kt](../shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayMediaEngine.kt)、[ScreenStream.kt](../shared/src/main/java/com/shilapi/xcertplay/airplay/ScreenStream.kt)。
- 显示／镜像：[CarPlayHostActivity.kt](../common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt)、[ClusterMapPresentation.kt](../common/src/main/java/com/shilapi/xcertplay/ClusterMapPresentation.kt)、[MapEmbedService.kt](../common/src/main/java/com/shilapi/xcertplay/MapEmbedService.kt)、[AndroidMediaSink.kt](../shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt)。
- HUD／转向图：[BydHudBridge.kt](../shared/src/main/java/com/shilapi/xcertplay/hud/BydHudBridge.kt)、[BydHudPayload.kt](../shared/src/main/java/com/shilapi/xcertplay/hud/BydHudPayload.kt)、[BydHudRouteState.kt](../shared/src/main/java/com/shilapi/xcertplay/hud/BydHudRouteState.kt)、[ClusterTurnCardView.kt](../common/src/main/java/com/shilapi/xcertplay/ClusterTurnCardView.kt)、[图标来源说明](../shared/src/main/assets/byd-hud-icons/NOTICE.txt)。
- 吉利 DEX 重点：`CarPlayHostActivity.clusterDisplayConfig()`／`mirrorSink$lambda$11()`、`MapEmbedService.canRequestRenderTarget()`、`MapEmbedRenderAuthorization.permits()`、`ClusterMapPresentation.findDisplay()`、`GeelyDimNavigationAdapter.start$lambda$3()`。
- Flyme DEX 重点：`com.flyme.auto.map.amap.adapter.guidance.proxy.NaviObserverProxy.showCrossImage()`、`GuidanceMapViewManager.updateCross()`、`com.flyme.auto.map.amap.manager.multidisplay.SyncMapViewManager`、`com.autonavi.gbl.guide.model.CrossImageInfo`。
- 支路几何外部依据：[RGI 0x5202 TLV 定义，固定提交](https://github.com/luka-dev/mib2q-carplay-rgi/blob/72d321cc9f57bc2f3a1bb76021404c96b300f326/hook/routeguidance/rgd_tlv.h#L105-L120)。

## 7. 方向盘适配：实现存在，但静态证据显示未接线

### 7.1 profile 与实际能力

| Profile | DEX 中实现 | 静态可达性结论 |
| --- | --- | --- |
| Generic | 保留标准 Android MediaSession / 无障碍输入 | 上游原有路径存在 |
| ECARX XUI | 只检测 `Input` / `IKeyCallback` 类；不注册 | 诊断功能代码存在，但未接入设置/会话入口 |
| Flyme Auto | 监听 ECARX voice-assist 广播，映射为 Siri | Adapter 存在，但 runtime 未被 attach |
| Geely OneOS | 绑定 OneOS service，注册媒体键 | Adapter 存在，但 runtime 未被 attach |
| Geely OneOS 2 | 与 XUI 一样，仅诊断 | 没有按键截获实现 |
| KX11 legacy | 复用 OneOS Binder Adapter | Adapter 存在，但 runtime 未被 attach |

资源里有完整设置文案，`SteeringWheelRuntime` 也有 profile、启停、通话保护、去重、状态轮询等逻辑。但对所有已反编译生产类做引用检查后：

- `SteeringWheelRuntime` 只被它自己的两个 lambda 类引用；
- `SteeringWheelSettings` 只被 `SteeringWheelRuntime` 引用；
- `DiPlayActivity` 没有引用 steering-wheel platform 的资源 ID；
- `CarPlayHostActivity`、`CarPlayController`、`CarPlayMediaKeys` 没有调用 `SteeringWheelRuntime.attach()` / `detach()`。

因此在这个样本中，OneOS/Flyme 方向盘功能更像“已编译但未挂载”的开发中代码。除非存在脱壳时遗漏的动态反射入口——当前 DEX 字符串和成功反编译的方法没有显示这种入口——不能把它算作可用功能。

### 7.2 OneOS Binder 协议

尽管未接线，OneOS Adapter 的实现较完整，可作为后续适配参考。

绑定组件：

```text
package:   com.geely.service.oneosapi
service:   com.geely.service.oneosapi.OneOSApiService
```

Manifest 用 `<queries>` 声明了该 package。

Binder descriptor 和 transaction：

| 对象 | Descriptor / transaction |
| --- | --- |
| service manager | `com.geely.lib.oneosapi.IServiceManager` |
| 获取 input service | transaction `2`，写入 service type `8` |
| input manager | `com.geely.lib.oneosapi.input.IInputManager` |
| 注册 listener | transaction `3` |
| 注销 listener | transaction `4` |
| listener | `com.geely.lib.oneosapi.input.IInputListener` |

注册的 raw key code：

| raw code | 行为 |
| ---: | --- |
| 200085 | play/pause |
| 200087 | next |
| 200088 | previous |

listener transaction code `2`、`5`、`6` 分别映射 short、long、double gesture，但当前 `VehicleWheelMapping` 只消费 short gesture。

实现包含这些保护：

- 8 秒 bind timeout；
- Binder death recipient；
- service 断开后 2 秒重连；
- session token、配置 revision、通话状态和 epoch 校验；
- 通话中不接管；
- 只在活动 CarPlay session 中接管；
- close 时注销 listener；
- 注销失败时把该 Binder 放入 quarantine，防止在远端仍可能持有旧 listener 时重复注册。

### 7.3 Flyme 语音广播

监听：

```text
ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT
ecarx.intent.action.ECARX_KEY_VOICEASSIST_EVENT
extra: ecarx.extra.ECARX_KEY_ACTION_TYPE == 0
```

满足条件后生成 raw code `231`，映射成 Siri。两个 alias 在 250 ms 内到达时会去重。

Android 13+ 使用 exported 动态 receiver。没有 sender permission 或签名校验，任意本机应用理论上都可伪造事件触发 Siri；设置文案明确承认这一点，并说明不会阻止原车语音助手，因此可能双重响应。

## 8. 原车蓝牙音乐与 CarPlay 协同

这部分是实际接线的，而且默认选择原车蓝牙。

### 8.1 音乐所有权

设置有两个选择：

- 原车蓝牙：音乐与媒体方控交给 OEM；
- DiPlay：CarPlay 音频与媒体方控走本地 Android。

`loadOemBluetoothMusicEnabled()` 默认返回 `true`。选择原车蓝牙时：

1. `AndroidMediaSink.localMediaAudioEnabled = false`。
2. `LocalMediaAudioPolicy` 识别 `audioType=media` 或 type `102` 的 default stream 为音乐。
3. 音乐 RTP 仍由 AirPlay 接收，但不创建/启动本地 `AudioRenderer`。
4. 导航、Siri、通话等非 music stream 继续本地渲染，原有导航声道选择保持不变。
5. `CarPlayMediaKeys` 不创建本地 MediaSession，不抢媒体按键和音频焦点。
6. `CarPlayController.preserveBluetoothBootstrap = true`，无线 CarPlay 切换到 Wi-Fi tunnel 后不主动关闭最初的蓝牙控制链路。

该实现只是“本地不播放并尽量保留蓝牙连接”，不能强制 iPhone 把音乐切到原车蓝牙。手机或车机仍可能主动断开蓝牙，资源文案对此有明确说明。

## 9. GEELY 标签与返回车机图标

该 APK 将：

```text
DEFAULT_OEM_LABEL = "GEELY"
```

并新增 `CarPlayHomeIcon`：读取 `ic_car_home_geely`，绘制为 192×192 ARGB bitmap，再编码成 PNG 作为默认 AirPlay/CarPlay 返回车机图标。

另外，`loadOemLabel()` 对旧设置做了特定迁移：如果保存值仍是 `BYD` 且没有用户自定义图标，则返回 `GEELY`。这说明构建者有意识地把 BYD 默认身份替换成吉利外观，而不是仅添加一个可选图标。

## 10. 向 AutoDisplay 共享导航快照

这不是吉利原车 `DimInteraction` 的一部分，但与本构建的 AD 仪表集成直接相关。

目标 package：

```text
com.simplerbit.autodisplay
```

权限：

```text
com.simplerbit.autodisplay.permission.DIPLAY_NAVIGATION
```

动作：

```text
请求重放：com.shihab.diplay.REQUEST_NAVIGATION_SNAPSHOT
快照输出：com.shihab.diplay.NAVIGATION_SNAPSHOT
```

输出是显式 package 广播，并附带权限。字段包括：

- `version=1`
- `producerSessionId`
- `sequence`
- `connected`
- `active`
- `drivingSide`
- `road`，最多 512 字符
- `nextDistanceMeters`
- `maneuverType`
- `remainingMeters`
- `remainingSeconds`

RouteGuidance 变化时立即发送；活动 session 中每 3 秒强制 heartbeat；AutoDisplay 发请求广播时重放当前状态。它和吉利原车输出共享同一个 source/session 所有权模型，但数据通过广播给另一个应用，而非调用车机 OEM SDK。

## 11. Manifest 和运行条件

与吉利/AD 适配直接相关的声明：

```xml
<queries>
    <package android:name="com.geely.service.oneosapi" />
</queries>

<uses-permission android:name="com.simplerbit.autodisplay.permission.DIPLAY_NAVIGATION" />
<uses-permission android:name="com.permission.adnaviservice" />
```

实际可用至少依赖：

- 车机 classpath 中存在匹配签名的 ECARX `DimInteraction` / `INaviInteraction`；
- 应用拥有调用厂商导航服务所需权限或白名单；
- OneOS 方向盘若未来接线，需要 `OneOSApiService` 可绑定且接受当前应用包名注册；
- CarPlay session 必须真正收到 `0x5201/0x5202`；
- 仪表必须接受对应 turn ID 和字段单位；
- 原车蓝牙方案需要用户在 iPhone 和车机中主动选择蓝牙音频路径。

该 APK 的状态文案多次使用“待实车验证”。静态分析只能确认调用存在、参数如何组织，不能证明任意吉利车型已经在仪表上正确显示。

## 12. 对当前 `main` 的移植建议

当前源码已经从 `0.2.13` 演进到 `0.2.16`，Activity、Controller、无线 tunnel、设置 IA、音视频和生命周期均有较大变化，不适合直接复制反编译 Java。

建议拆成独立增量：

### 12.1 第一阶段：只移植吉利导航输出

可复用设计：

- `GeelyNavigationDriver`
- `GeelyNavigationRuntime`
- `GeelyNavigationControlSources`
- `GeelyDimNavigationAdapter`
- `GeelyManeuverCodes`

需要调整：

1. 把 `BydHudRouteState` / `BydAppleManeuver` 提升为品牌中立的 Apple RouteGuidance state，避免吉利模块反向依赖 `hud/Byd*` 命名。
2. 在当前 `CarPlayController` 的 wired、wireless bootstrap、wireless tunnel 三条 `onIncoming` 路径中接入一个通用 route-output source owner。
3. 把 factory output 放在 `Navigation` 分类，而不是旧版平铺设置页；标题必须带当前 locale 的 experimental 后缀。
4. backend 切换必须保持默认关闭和二次确认。
5. 所有新 `settings_*` 字符串补齐全部 locale。
6. 保留 fail-closed、cleanup-pending、lease/epoch 和 source handoff 逻辑。

不建议把 `ECARX_XUI`、`FLYME_AUTO` 当成两个不同实现，除非能补充不同的类名、方法或生命周期证据。

### 12.2 第二阶段：原车蓝牙音乐协同

单独移植 `LocalMediaAudioPolicy` 和明确的 media owner 设置，不与吉利导航耦合。验证重点：

- 只抑制 music stream，不影响导航/Siri/电话；
- MediaSession 和 audio focus 是否真正释放；
- 无线 handoff 保留 bootstrap 是否会阻碍 tunnel、重连或 OEM 蓝牙音频；
- 用户切换后描述和 reconnect 行为一致；
- 原车蓝牙关闭或手机未选中蓝牙输出时的可理解降级。

### 12.3 第三阶段：方向盘

不要直接把 DEX 中的方向盘类视为完成实现。先补齐明确入口：

- Controller/Host attach 与 detach；
- Settings 可见性和确认流程；
- session / call / media-owner 切换；
- Binder 注销和 service replacement；
- 对 XUI/OneOS 2 要么实现可靠的注册/注销协议，要么保留纯诊断。

Flyme 语音广播存在 sender spoofing 风险。若没有 signature permission、明确 package 或 OEM sender 身份校验，不建议默认启用。

### 12.4 第四阶段：AutoDisplay 广播协议

若当前项目仍需要与 `com.simplerbit.autodisplay` 联动，应先确认双方权限定义、签名关系、协议版本和许可证，再独立移植。它不是吉利 OEM SDK 的必要组成部分。

## 13. 建议测试矩阵

### 导航

- wired 与 wireless 各自收到 `0x5201/0x5202`；
- wireless bootstrap → tunnel handoff 后只有 tunnel 生效；
- 路线开始、更新、重新规划空列表、结束、30 秒 stale；
- service 未连接、permission denied、方法签名不匹配；
- callback/watcher 注册和注销失败；
- backend 切换会停止旧导航并保持 disabled；
- turn ID 在目标仪表上逐项实车验证；
- clear 后原车导航模式恢复，不残留箭头或道路名。

### 原车蓝牙音乐

- 音乐被抑制但导航/Siri/电话仍播放；
- MediaSession、audio focus、媒体按键不被 DiPlay 占用；
- 无线 tunnel 建立后 OEM 蓝牙音乐持续；
- 断开/重连、来电、Siri、切回 DiPlay 音乐；
- 手机未选择原车蓝牙输出时的提示和恢复。

### 方向盘

- OneOS register/unregister 对称；
- service death、bind timeout、重连、close 与重复 attach；
- play/pause、next、previous 只触发一次；
- long/double 当前应被忽略；
- 通话时不接管；
- OEM Bluetooth owner 时完全不注册；
- Flyme 广播伪造测试和双语音助手响应。

## 14. 最终判断

这个 APK 的吉利适配不是简单改品牌名，真正有技术内容的部分包括：

1. 把 Apple RouteGuidance 转成 ECARX/OneOS 风格的原车导航接口调用；
2. 正确处理 wired / wireless bootstrap / tunnel 的路线来源所有权；
3. 把原车蓝牙定义为音乐与方控 owner，同时保留 CarPlay 导航/通话音频；
4. 增加 GEELY 返回车机图标和 AD 导航快照广播。

其中“吉利原车导航提示”和“原车蓝牙音乐协同”是确实接入运行链路的；“OneOS/Flyme 方向盘”在该样本中只有实现类，没有生产入口，当前应标记为未接线，而不是已支持。
