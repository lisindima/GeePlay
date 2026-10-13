# GeePlay

**CarPlay for supported Geely/ECARX Android head units.** GeePlay is a Geely-focused downstream product built on [DiPlay](https://github.com/shihabal3amri/DiPlay), with an independent application package: `com.lllhys.geeplay`.

> **Product scope:** GeePlay currently targets the ECARX G636 head unit on Android 11. Other Geely, Lynk & Co and ECARX systems require their own firmware evidence. BYD/DiLink-only controls from DiPlay stay hidden or disabled in GeePlay.

[Releases](https://github.com/lllhys/GeePlay/releases) · [Chinese README](README.zh-CN.md) · [Capability alignment](CAPABILITY-ALIGNMENT.md) · [Report a problem](https://github.com/lllhys/GeePlay/issues/new)

## 0.2.18-geely.4 — development preview

Install GeePlay on the **car head unit**, not the iPhone. The validated target is the Geely/ECARX G636 (`ecarx`, Android 11). Core wired and wireless CarPlay comes from the DiPlay transport stack. Geely-specific integrations live behind product interfaces and fail closed when the required firmware API, permission or EVCC property is unavailable.

- Wired and wireless CarPlay with the DiPlay connection, display, audio and recovery stack.
- Geely branding, update channel and settings boundaries in a separate `:geely` application.
- EVCC 1.2 vehicle telemetry for battery, range, charging state, wheel speed and gear.
- Geely navigation, input, media and cluster adapters isolated in `:vehicle-geely`.
- Local diagnostic reports and a bounded `VEHICLE-DATA` trace from EVCC input through iAP2 output.
- Separate installation from DiPlay and DiAuto. Run only one projection app at a time.

This is **not an Apple-, Geely- or ECARX-certified product**. CarPlay authentication assets are not stored in this repository. Source builds omit runtime identity assets unless the builder supplies them explicitly. Compatibility can change with iOS, EVCC or head-unit firmware updates.

### Verified on the G636

- GeePlay can be installed and upgraded without clearing its settings or pairing state.
- CarPlay sessions run on the G636, and the bundled Opus path restores navigation prompts on firmware without a platform Opus decoder.
- EVCC 3.3.0 / EvccApi 1.2 exposes the validated battery and range selectors.
- `ADAPT_API:1048832` vehicle speed is already in metres per second; `1055232` is the fallback.
- Gear uses `CAPI:0x21401A01` with `0=P, 1=N, 2=R, 3=D`, with `ADAPT_API:0x200200` as fallback.
- The iPhone requests the `$PASCD` LocationInformation component and GeePlay sends it over the active iAP2 tunnel.
- Diagnostic reports save locally under **Downloads/GeePlay** and are never uploaded automatically.

### Evidence-gated or experimental

- Geely DIM navigation publishing is implemented, but final cluster/HUD rendering remains firmware- and vendor-policy-dependent. Unsupported production paths stay off.
- Cluster display selection is debug-only until a firmware profile is validated. GeePlay does not expose inherited BYD/FCE cluster controls as Geely features.
- Tyre telemetry, ambient lighting, parked-video gating and privileged EVCC actions remain opt-in or evidence-gated where the exact vehicle contract is not confirmed.
- GeePlay can prove that `$PASCD` reaches the iAP2 link; whether an iPhone uses it for dead reckoning in a particular tunnel or parking structure still requires physical validation.
- Vehicle-specific Siri commands are an architecture proposal, not a promise that iOS exposes arbitrary vehicle controls to CarPlay.

## What’s new in GeePlay 0.2.18-geely.4

- Synced the product with DiPlay 0.2.18 connection, USB, VPN, settings, localization and media fixes while preserving the Geely product boundary.
- Added a dedicated `:geely` app and `:vehicle-geely` adapter layer instead of adding Geely conditionals throughout the shared transport.
- Added EVCC 1.2 read/control gateways, source-generation handling and connection-scoped vehicle-data demand.
- Added REEV-aware iAP2 status reporting with separate gasoline and electric ranges.
- Corrected G636 wheel speed units and dynamic P/R/N/D mapping after real-car traces.
- Added bounded, redacted vehicle-data diagnostics covering EVCC raw values, normalization, `$PASCD` batches and actual iAP2 sends.
- Added Geely navigation, input, Bluetooth-audio arbitration and debug validation surfaces, all with explicit evidence gates.

The complete capability status, including features intentionally excluded from GeePlay, is maintained in [CAPABILITY-ALIGNMENT.md](CAPABILITY-ALIGNMENT.md). Upstream DiPlay release details remain available in [the preserved upstream README](docs/UPSTREAM-README.md) and [0.2.18 release notes](docs/RELEASE-NOTES-0.2.18.md).

## Install, connect and report a problem

Use a GeePlay APK built from the `:geely` module. Installing a DiPlay `:mobile` APK produces a different package and does not contain the Geely composition layer.

1. Install or upgrade GeePlay on the head unit. Do not uninstall first if you want to retain settings.
2. Supply the required local CarPlay authentication assets when building from source; the repository intentionally contains none.
3. Install EVCC only when you want EVCC-backed vehicle telemetry or explicitly confirmed vehicle actions. Core projection does not depend on EVCC.
4. Configure optional vehicle integrations while parked. Firmware-specific and privileged features default to disabled or fail closed.
5. When reporting a problem, reproduce it and immediately use **Settings → Diagnostics → Save diagnostic report**. Review the text file before sharing it.

Include the vehicle and head-unit model, full firmware, GeePlay and EVCC versions, iPhone/iOS version, USB or wireless mode, relevant settings, the failure time and exact reproduction steps. For EVCC/CarPlay data problems, capture the report within 15 minutes so the source and iAP2 timelines overlap.

## Architecture

| Module | Responsibility |
| --- | --- |
| `:geely` | GeePlay application identity and product composition |
| `:vehicle-geely` | Geely/ECARX navigation, input, media, display and EVCC adapters |
| `:common` | Shared Android host UI and lifecycle |
| `:shared` | Brand-neutral CarPlay, iAP2, AirPlay, audio and vehicle interfaces |
| `:evccapi-contract` / `:evccapi-client` | Vendored EVCC 1.2 contract and client |

`main` follows the DiPlay upstream history. Active GeePlay integration lands on `geely/main`. Every upstream merge must keep [CAPABILITY-ALIGNMENT.md](CAPABILITY-ALIGNMENT.md) and a standalone checklist under [`docs/upstream-merge-checklists/`](docs/upstream-merge-checklists/) consistent with the resulting product.

## Documentation

- [Capability alignment — single source of truth](CAPABILITY-ALIGNMENT.md)
- [GeePlay implementation progress](docs/GEELY-IMPLEMENTATION-PROGRESS.md)
- [GeePlay validation and real-car evidence](docs/GEELY-VALIDATION.md)
- [Geely core architecture](docs/GEELY-CORE-FEATURES-SPEC.md)
- [BYD-to-Geely capability assessment](docs/BYD-TO-GEELY-FEATURE-MIGRATION-ASSESSMENT.md)
- [Unaligned-capability migration spec](docs/BYD-TO-GEELY-UNALIGNED-CAPABILITIES-MIGRATION-SPEC.md)
- [EVCC 1.2 and CarPlay integration analysis](docs/EVCC-1.2-CARPLAY-INTEGRATION-ANALYSIS.md)
- [EVCC → CarPlay vehicle-data sync spec](docs/EVCC-CARPLAY-VEHICLE-DATA-SYNC-SPEC.md)
- [Siri-only vehicle-control analysis](docs/EVCC-1.2-SIRI-VEHICLE-CONTROL.md)
- [Privacy and diagnostic reports](docs/PRIVACY.md)
- [Upstream DiPlay README](docs/UPSTREAM-README.md)

## Source and credits

GeePlay is derived from [DiPlay](https://github.com/shihabal3amri/DiPlay) and [xcertplay](https://github.com/shilapi/xcertplay) under GPL-3.0. The home/settings UI and website inherit work adapted from [DiAuto](https://github.com/shihabal3amri/DiAuto), whose AGPL-3.0 notice is retained under `docs/licenses`. EVCC contract sources are vendored with their source information and notices under `third_party/evccapi`.

CarPlay and its marks belong to Apple Inc. Geely, Lynk & Co and ECARX marks belong to their respective owners. No affiliation or endorsement is implied.

## Build and verification

Build the GeePlay debug APK:

```sh
./gradlew :geely:assembleDebug
```

Output:

```text
geely/build/outputs/apk/debug/geely-debug.apk
```

Run the GeePlay-specific checks:

```sh
./gradlew :vehicle-geely:testDebugUnitTest :vehicle-geely:lintDebug \
  :geely:testDebugUnitTest :geely:lintDebug :geely:assembleDebug
```

The repository contains no accessory or Android signing private keys. Set `DIPLAY_AUTH_ASSETS_DIR` to an external runtime-asset directory only for a build that is authorized to use those assets. Publishing an APK makes any embedded runtime identity extractable.
