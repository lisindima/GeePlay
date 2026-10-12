# GeePlay upstream merge checklist

Copy this file to `YYYY-MM-DD-<upstream-short-sha>.md` for every upstream merge, rebase or cherry-pick.
Do not edit this template in place.

## Merge identity

- [ ] Date:
- [ ] Agent/owner:
- [ ] GeePlay branch:
- [ ] Pre-merge GeePlay HEAD:
- [ ] Upstream ref and SHA:
- [ ] Merge base:
- [ ] Worktree state recorded and unrelated changes preserved:
- [ ] Merge strategy: merge / rebase / cherry-pick

## Upstream inventory

- [ ] `git diff --name-status <merge-base>..<upstream-sha>` reviewed
- [ ] New/deleted modules and build plugins reviewed
- [ ] Settings and search-index changes reviewed
- [ ] Manifests, permissions, queries and exported components reviewed
- [ ] Controller/session/transport/teardown changes reviewed
- [ ] BYD/DiLink/vendor integrations reviewed
- [ ] iAP2, RouteGuidance, lane and AirPlay display changes reviewed
- [ ] Media/audio/Bluetooth/MediaSession changes reviewed
- [ ] Locale, assets, credentials, update and release identity changes reviewed
- [ ] Upstream test additions/removals reviewed

## Decision table

Add one row per product-relevant behavior, not merely one row per file.

| Upstream item | Source files/symbols | GeePlay impact | Disposition | Target/gate | Tests or real-car evidence | Status |
| --- | --- | --- | --- | --- | --- | --- |
| Example: new BYD dashboard toggle | `path`, `SettingsSection.X` | Must not appear in GeePlay | `EXCLUDE_GEEPLAY` | `LegacyBydSettingsPolicy` | GeePlay settings UI test | pending |

Allowed dispositions: `INHERIT`, `MIGRATE_SHARED`, `ADAPT_GEELY`, `EXCLUDE_GEEPLAY`, `EVIDENCE_PENDING`.

## BYD/DiLink settings boundary

- [ ] Every new Settings section was classified as product-neutral, mixed or BYD/DiLink-only
- [ ] `LegacyBydSettingsPolicy.sections` was updated when a whole section is BYD/DiLink-only
- [ ] Mixed cards gate only their BYD/DiLink controls with `legacyBydSettingsAvailable()`
- [ ] Settings search does not reveal blocked BYD/DiLink controls
- [ ] GeePlay default keeps `GeelyProductSettings.blockLegacyBydMenus=true`
- [ ] Disabling the block is treated as a developer/compatibility escape hatch, not GeePlay support evidence

## GeePlay adaptation and evidence

- [ ] Shared behavior was kept brand-neutral
- [ ] Geely-specific behavior lives in `:vehicle-geely` or `:geely`
- [ ] New experimental/risky behavior defaults off and has recovery semantics
- [ ] Missing real-car or protocol evidence is recorded as `EVIDENCE_PENDING`
- [ ] AutoDisplay and external MapEmbed exclusions remain intact
- [ ] Credentials/signing material remain outside Git

## Validation

- [ ] Focused tests for changed behavior:
- [ ] Settings IA/search/locale tests:
- [ ] Compact/full/Arabic screenshots when layout changed:
- [ ] Base CI passed:
- [ ] `:vehicle-geely` tests/lint passed:
- [ ] `:geely` tests/lint/assemble passed:
- [ ] Merged GeePlay manifest checked:
- [ ] APK credential and forbidden-component scan checked:
- [ ] Real-car result or explicit reason it is pending:

## Open actions

Every remaining action needs an owner and a condition for completion.

| Action | Owner | Blocking evidence/input | Runtime remains disabled? | Due/status |
| --- | --- | --- | --- | --- |

## Final decision

- [ ] No product-relevant upstream item remains unclassified
- [ ] GeePlay migration/adaptation commits are listed
- [ ] Explicit exclusions are enforced by code or manifest gates
- [ ] Follow-up evidence work is documented
- [ ] Checklist reviewed together with the upstream merge
