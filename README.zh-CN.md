# GeePlay

**面向受支持吉利／ECARX 安卓车机的 CarPlay。** GeePlay 基于 [DiPlay](https://github.com/shihabal3amri/DiPlay) 开发，是独立的吉利下游产品，应用包名为 `com.lllhys.geeplay`。

> **产品范围：** 当前明确验证目标是 Android 11 的 ECARX G636 车机。其他吉利、领克及 ECARX 固件必须分别取得实车证据。DiPlay 中仅适用于比亚迪／DiLink 的设置和实现，在 GeePlay 中保持隐藏或禁用。

[发布版本](https://github.com/lllhys/GeePlay/releases) · [English README](README.md) · [能力对齐总表](CAPABILITY-ALIGNMENT.md) · [报告问题](https://github.com/lllhys/GeePlay/issues/new)

## 0.2.18-geely.4 — 开发预览版

GeePlay 安装在**车机**，不是 iPhone。当前验证车型使用 Geely/ECARX G636（`ecarx`，Android 11）。核心有线／无线 CarPlay 继承 DiPlay 传输栈；吉利专用能力通过产品接口隔离，固件 API、权限或 EVCC 属性不满足时默认关闭并 fail closed。

- 继承 DiPlay 的有线／无线连接、显示、音频和恢复链路。
- 使用独立 `:geely` 应用模块提供 GeePlay 品牌、更新源和设置边界。
- 通过 EVCC 1.2 读取电量、续航、充电、轮速和挡位。
- 吉利导航、输入、媒体和仪表适配集中在 `:vehicle-geely`，不污染通用 CarPlay 模块。
- 本地诊断报告包含从 EVCC 输入到 iAP2 输出的有界 `VEHICLE-DATA` 时序。
- 可与 DiPlay、DiAuto 分开安装，但同一时间只应运行一个投屏应用。

本项目**未经 Apple、吉利或 ECARX 认证**。仓库不保存 CarPlay 认证材料；源码构建只有在构建者显式提供外部运行时材料时才会包含它们。iOS、EVCC 或车机固件升级都可能改变兼容性。

### G636 已验证

- GeePlay 可以覆盖安装并保留设置和配对状态。
- G636 可以运行 CarPlay 会话；对于系统没有 Opus 解码器的固件，随包 Opus 路径已恢复导航提示音。
- EVCC 3.3.0 / EvccApi 1.2 已验证电池与续航 selector。
- `ADAPT_API:1048832` 车速原值已经是 m/s，`1055232` 为 fallback。
- 挡位使用 `CAPI:0x21401A01`，动态枚举为 `0=P, 1=N, 2=R, 3=D`，fallback 为 `ADAPT_API:0x200200`。
- iPhone 会请求 `$PASCD` LocationInformation component，GeePlay 会通过当前 iAP2 tunnel 发送。
- 诊断报告保存在 **Downloads/GeePlay**，不会自动上传。

### 证据门禁／实验能力

- Geely DIM 导航发布已实现，但最终仪表／HUD 渲染仍受固件和 vendor 策略限制；没有证据的生产路径保持关闭。
- 仪表 Display 选择仅用于调试，直到建立精确固件 profile；不会把上游 BYD/FCE 仪表设置伪装成吉利能力。
- 胎压、胎温、氛围灯、驻车视频门禁及高权限 EVCC 动作，在车型契约未完全确认时仍为可选或证据门禁能力。
- GeePlay 可以证明 `$PASCD` 已到达 iAP2 链路，但 iPhone 是否在特定隧道或地库使用它做航位推算，仍需实车验证。
- Siri 车辆控制目前是架构分析，不代表 iOS/CarPlay 已开放任意车辆属性控制。

## GeePlay 0.2.18-geely.4 的主要变化

- 同步 DiPlay 0.2.18 的连接、USB、VPN、设置、本地化和媒体修复，同时保留吉利产品边界。
- 建立独立 `:geely` 应用及 `:vehicle-geely` 适配层，不在共享传输代码中散布吉利条件分支。
- 增加 EVCC 1.2 read/control gateway、source generation 处理和按连接生命周期聚合的车辆数据 demand。
- 增加适用于增程车的 iAP2 状态：汽油续航和纯电续航分别上送。
- 根据 G636 实车轨迹修正车速单位及 P/R/N/D 动态映射。
- 增加有界、脱敏的车辆数据诊断，覆盖 EVCC 原始值、单位转换、`$PASCD` batch 和实际 iAP2 发送。
- 增加吉利导航、输入、蓝牙音频仲裁和 Debug 验证工具，并为每条能力设置明确证据门禁。

完整能力状态及明确不迁移的功能，以 [CAPABILITY-ALIGNMENT.md](CAPABILITY-ALIGNMENT.md) 为唯一事实来源。DiPlay 上游版本说明保存在[上游 README](docs/UPSTREAM-README.zh-CN.md)和 [0.2.18 发布说明](docs/RELEASE-NOTES-0.2.18.md)。

## 安装、连接与问题反馈

必须使用 `:geely` 模块构建的 GeePlay APK。DiPlay 的 `:mobile` APK 是另一个应用包，不包含吉利产品组合层。

1. 在车机上安装或覆盖升级 GeePlay；需要保留设置时不要先卸载。
2. 从源码构建时，使用方必须自行提供经过授权的本地 CarPlay 认证材料；仓库中不包含这些文件。
3. 只有在需要车辆遥测或明确确认的车辆动作时才安装／授权 EVCC；核心投屏不依赖 EVCC。
4. 请停车后配置车辆集成。固件相关和高权限功能默认关闭，或在证据不足时 fail closed。
5. 出现问题后立即打开“**设置 → 诊断 → 保存诊断报告**”，分享前自行检查文本内容。

反馈时请提供车型／车机、完整固件、GeePlay 与 EVCC 版本、iPhone/iOS、USB 或无线模式、相关设置、发生时间和完整复现步骤。EVCC/CarPlay 数据问题应在 15 分钟内保存报告，确保源数据和 iAP2 时间线仍能对齐。

## 架构

| 模块 | 职责 |
| --- | --- |
| `:geely` | GeePlay 应用身份和产品组合 |
| `:vehicle-geely` | Geely/ECARX 导航、输入、媒体、Display 与 EVCC 适配 |
| `:common` | 通用 Android Host UI 和生命周期 |
| `:shared` | 品牌无关的 CarPlay、iAP2、AirPlay、音频与车辆接口 |
| `:evccapi-contract` / `:evccapi-client` | 随仓库维护的 EVCC 1.2 契约及客户端 |

`main` 保持 DiPlay 上游历史，GeePlay 日常集成进入 `geely/main`。每次同步上游都必须同时维护 [CAPABILITY-ALIGNMENT.md](CAPABILITY-ALIGNMENT.md) 和 [`docs/upstream-merge-checklists/`](docs/upstream-merge-checklists/) 中独立的合并检查表。

## 文档

- [能力对齐总表——单一事实来源](CAPABILITY-ALIGNMENT.md)
- [GeePlay 实施进度](docs/GEELY-IMPLEMENTATION-PROGRESS.md)
- [GeePlay 软件／实车验证记录](docs/GEELY-VALIDATION.md)
- [吉利核心架构](docs/GEELY-CORE-FEATURES-SPEC.md)
- [BYD → Geely 能力评估](docs/BYD-TO-GEELY-FEATURE-MIGRATION-ASSESSMENT.md)
- [未对齐能力迁移 Spec](docs/BYD-TO-GEELY-UNALIGNED-CAPABILITIES-MIGRATION-SPEC.md)
- [EVCC 1.2 与 CarPlay 集成分析](docs/EVCC-1.2-CARPLAY-INTEGRATION-ANALYSIS.md)
- [EVCC → CarPlay 车辆数据同步 Spec](docs/EVCC-CARPLAY-VEHICLE-DATA-SYNC-SPEC.md)
- [仅 Siri 车辆控制分析](docs/EVCC-1.2-SIRI-VEHICLE-CONTROL.md)
- [隐私与诊断报告](docs/PRIVACY.md)
- [DiPlay 上游 README](docs/UPSTREAM-README.zh-CN.md)

## 来源与致谢

GeePlay 基于 GPL-3.0 的 [DiPlay](https://github.com/shihabal3amri/DiPlay) 和 [xcertplay](https://github.com/shilapi/xcertplay)。主页／设置界面及网站继承了从 [DiAuto](https://github.com/shihabal3amri/DiAuto) 适配的工作，其 AGPL-3.0 声明保存在 `docs/licenses`。EVCC 契约源码、来源信息及声明位于 `third_party/evccapi`。

CarPlay 及相关标志属于 Apple Inc.；吉利、领克、ECARX 及相关标志属于各自权利人。本项目不代表任何官方关联或背书。

## 构建与验证

构建 GeePlay Debug APK：

```sh
./gradlew :geely:assembleDebug
```

输出路径：

```text
geely/build/outputs/apk/debug/geely-debug.apk
```

运行 GeePlay 专项检查：

```sh
./gradlew :vehicle-geely:testDebugUnitTest :vehicle-geely:lintDebug \
  :geely:testDebugUnitTest :geely:lintDebug :geely:assembleDebug
```

仓库不包含配件身份或 Android 签名私钥。只有在获得相应授权时，才应通过 `DIPLAY_AUTH_ASSETS_DIR` 指向外部运行时材料目录。发布 APK 会使其中嵌入的运行时身份可被提取。
