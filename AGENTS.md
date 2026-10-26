# AGENTS.md

Instructions for coding agents that work on the DiPlay upstream code and the GeePlay downstream product.

## Checks

Run the CI command from `.github/workflows/android.yml` before you report a change as done:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
```

Set `ANDROID_HOME` or `local.properties` if Gradle cannot find the SDK.

GeePlay changes MUST also run:

```sh
./gradlew :vehicle-geely:testDebugUnitTest :vehicle-geely:lintDebug \
  :geely:testDebugUnitTest :geely:lintDebug :geely:assembleDebug
```

## Capability alignment tracking

`CAPABILITY-ALIGNMENT.md` in the repository root is the single source of truth for every project
capability's GeePlay alignment status and implementation path. Keep it current:

- Every upstream merge/rebase/cherry-pick into a GeePlay branch MUST update the affected rows in
  `CAPABILITY-ALIGNMENT.md` (status, Geely implementation path, evidence) as part of the same change.
  The merge is not done until this table and the standalone checklist agree.
- When a capability's status changes outside a merge (new Geely implementation, EVCC property verified,
  real-car confirmation, feature removed), update the row and append a line to its "变更记录" section.
- Keep the table's status/implementation legends and the per-capability numbering consistent with
  `docs/BYD-TO-GEELY-FEATURE-MIGRATION-ASSESSMENT.md` and
  `docs/BYD-TO-GEELY-UNALIGNED-CAPABILITIES-MIGRATION-SPEC.md`. If you add a new capability, add it to the
  assessment matrix, the alignment table, and (if not yet implemented) the migration Spec.
- EVCC stays the preferred capability source (vehicle properties, Shell, actions). Only fall back to a
  Geely OEM API or a brand-neutral host command when the EVCC contract does not support the capability,
  and record that reason in the table.

## Upstream merge review for GeePlay

Every merge, rebase or cherry-pick from the DiPlay upstream into a GeePlay branch MUST include a new,
standalone checklist copied from `docs/upstream-merge-checklists/TEMPLATE.md`.

Name it `docs/upstream-merge-checklists/YYYY-MM-DD-<upstream-short-sha>.md`. Do not reuse or overwrite an
older checklist. Create one even when the conclusion is that no GeePlay migration is needed.

### Before changing merge results

1. Record the GeePlay branch, pre-merge `HEAD`, upstream ref/SHA and merge base in the checklist.
2. Record the dirty worktree and preserve unrelated user changes.
3. Inventory upstream changed files and settings before accepting `ours` or `theirs` resolutions.
4. Search the upstream diff for `BYD`, `DiLink`, settings sections, permissions, services, iAP2 messages,
   CarPlay display/audio changes and newly added resources.

### Classify every product-relevant upstream change

Use exactly one primary disposition for every reviewed item:

| Disposition | Meaning |
| --- | --- |
| `INHERIT` | Product-neutral behavior GeePlay should receive unchanged |
| `MIGRATE_SHARED` | Useful capability that first needs a brand-neutral interface/model in `:shared` or `:common` |
| `ADAPT_GEELY` | Behavior that needs a separate Geely/ECARX implementation, permission, profile or lifecycle |
| `EXCLUDE_GEEPLAY` | BYD/DiLink-only behavior that must stay hidden or absent in GeePlay |
| `EVIDENCE_PENDING` | Potential GeePlay capability that remains disabled until protocol or real-car evidence exists |

For `MIGRATE_SHARED` and `ADAPT_GEELY`, list the target files, tests and cleanup/rollback behavior. For
`EXCLUDE_GEEPLAY`, identify the product gate or manifest removal that enforces the exclusion. A file may
contain multiple reviewed items with different dispositions; list them separately.

### Required merge hotspots

Review at least these areas on every upstream merge, marking each as changed or not changed:

- `SettingsCategory`, `SettingsSection`, `SettingsInformationArchitecture` and Settings search indexing;
- `LegacyBydSettingsPolicy` and the GeePlay `GeelyProductSettings` BYD-menu switch;
- application/library manifests, exported components, queries and permissions;
- `CarPlayController`, `CarPlayHostActivity`, transport/source ownership and teardown;
- `shared/.../hud/Byd*`, vehicle hooks and any new vendor singleton;
- iAP2 identification, endpoint catalogs, RouteGuidance/lane messages and AirPlay display properties;
- media ownership, audio focus, Bluetooth bootstrap and MediaSession behavior;
- strings/locales, settings screenshots, credentials, release/update identity and build configuration;
- tests added or changed upstream, including tests that imply a new product decision.

When upstream adds a BYD/DiLink-only setting, add its section to the centralized BYD boundary or gate the
mixed control with `legacyBydSettingsAvailable()`. Do not delete the upstream implementation merely to hide
it from GeePlay, and do not expose it in GeePlay until the checklist explicitly classifies it.

### Merge completion

The merge is not done until the standalone checklist has no unclassified item, all required tests/evidence
are linked, the GeePlay exclusion gates are rechecked, `CAPABILITY-ALIGNMENT.md` is updated for every
affected capability, and both the base CI command and the GeePlay command above pass. `EVIDENCE_PENDING`
items may remain open only when the runtime setting stays disabled and the checklist states the missing
evidence and next validation action.

## Adding or moving a setting

The Settings screen is grouped by driver goal, not by implementation.
`SettingsCategory`, `SettingsSection` and `SettingsInformationArchitecture` at the top of
`common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt` define the groups.
`SettingsLayoutPolicyTest` fails when a section has no category or has more than one.

### Choose the category

Ask which goal the driver has when they look for the setting. Use the first row that fits.

| Category | Put a setting here when it controls… | Examples |
| --- | --- | --- |
| Connection | how the iPhone connects and how DiPlay starts | connection setup, connect on open, start with the car, USB permissions, car hotspot automation, iPhone choice, Android permissions |
| Display | how CarPlay looks on the head-unit screen | day/night mode, picture, size, resolution, frame rate, dock, system bars, multi-window resolution |
| Audio | what the driver hears | media and navigation streams, music buffer |
| Navigation | location and turn-by-turn guidance | location to iPhone, BYD HUD and cluster guidance |
| Vehicle | how CarPlay fits this car and its driver | driving side, wheel keys (Siri, BYD joystick and map zoom), car button, gestures that conflict with the head unit |
| Diagnostics | troubleshooting evidence | diagnostic reports |
| Advanced | experimental, firmware-specific or risky behavior | dashboard map, split screen, screen rotation, side panel, HEVC video, audio focus, audio channel mapping, buffered music, vehicle data |
| Overview | nothing new | see "Overview" below |

A setting goes to Advanced when it is experimental, depends on specific firmware, or is an opt-in that can break sound, video or the connection on some head units.
A control that fixes a common problem and is safe at its default (for example the audio stream choice) stays in its category.
Exception: a gated control that only its own audience sees, and that completes an everyday goal, stays in that goal's category.
Example: auto car hotspot needs ADB but appears only for car-hotspot users, so it is in Connection.

Mark an experimental setting or card with the title suffix ` (experimental)`, never `· experimental`, a dash, or an `Experimental …` prefix.
Each locale MUST use its existing form: `(تجريبي)`, `(experimental)`, `(sperimentale)`, `(экспериментально)`, `(експериментально)`, `（实验性）`, `（實驗性）`, `（実験的機能）`.

### Add the code

1. To extend an existing card, add the control inside the `filteredSection(...)` block of a card in the correct category.
2. For a new card, add a `SettingsSection` entry and map it to exactly one category in `SettingsInformationArchitecture.sectionsByCategory`.
3. Build the new card with `filteredSection(content, SettingsSection.X, …)` inside `allSettingsSections()`.
   A plain `section(...)` call there MUST NOT be used: it renders on every category page.
4. A setting MUST NOT appear in two categories.
   Overview quick settings are the only duplicates. They MUST use the same persistence as the full control. Overview SHOULD NOT have more than four quick settings.
   A header shortcut MAY duplicate a Display setting when it uses the same persistence.
5. If the change reconnects CarPlay or applies at the next connection, the description MUST say so.
   A setting that only takes effect at the next connection MUST call `markReconnectNeeded()` after it saves.
   This shows the "Reconnect now" bar. It MUST NOT drop a running session without the driver's consent.
   Reconnect at once (`reconnectIfRunning()`) only after a dialog button that says "Apply and reconnect", or when the description says that the change reconnects CarPlay.
6. Add each new string to `common/src/main/res/values/` and to every `values-xx` locale folder.
   New Settings copy SHOULD use the `settings_` prefix. `SettingsTranslationsTest` fails when a `settings_*` string has no translation.
7. A control that opens a choice uses `button()` with the text `"Title · Value"`.
   That form renders as a setting row with the value and a chevron, and Search indexes the title.
8. When you move a control between categories, update `AdaptiveSettingsUiTest.settingsLiveWhereDriversLookForThem`.

### Overview

Overview holds the connection status, links to the categories, quick settings, About and Language.
Do not add a new setting to Overview. Add it to its category. Then promote it to quick settings only if drivers change it often.

## Settings layout

Spacing, size and shape in the Settings screen MUST come from one place.

1. A gap between blocks MUST use a named dp constant, such as `SETTINGS_BLOCK_GAP_DP`.
   A new literal gap value MUST NOT be added.
2. A size MUST NOT be a pixel constant (`*_PX`). Use dp, and clamp to the available window width.
   Test a menu or panel on a narrow window and on a high-density screen.
3. Controls in one row MUST have the same height.
   Corner radius MUST follow the control height. A new button MUST NOT set its own radius.
4. Every `SettingsCategory` except Overview MUST have a link on Overview.
   `AdaptiveSettingsUiTest.overviewLinksToEveryOtherCategory` checks this.
5. A layout change MUST include compact and full screenshots with a large font scale.
   Add an Arabic (right-to-left) screenshot when the change touches row alignment.
