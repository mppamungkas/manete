# NetLogger: Android Cellular / 5G NSA Drive Test Logger + Traffic Capture

**CLAUDE.md version 1.4** · last updated 2026-10-13

### Changelog
- **1.4:** Added PCAPdroid to `reference/` (study only, GPL). Phase 3a now uses PCAPdroid's app API, including UDP packet streaming to NetLogger. Capture file changed from `.pcapng` to `.pcap` (PCAPng is a paid PCAPdroid feature).
- **1.3:** Added development environment (Proxmox homelab, Ubuntu Server VM, Claude Code CLI, ADB over Wi-Fi). Added `reference/` folder with open-source projects for study and license rules for reuse. NetMonster Core evaluated as a parsing dependency in Milestone 2. Added device/ADB commands and `.gitignore` rules. Updated first prompt.

## Goal
Android app for research-grade drive tests:
1. **Phase 1–2:** log cellular radio measurements (LTE + 5G NSA, later SA), GPS, battery and data-usage context, show them live and on a map, and export clean datasets.
2. **Phase 3:** capture the phone's IP traffic (PCAPdroid-style) in sync with the radio log, so radio conditions can be correlated with application behavior (QoE, throughput, battery).

The data is used for research (LTE anchor quality vs. 5G NSA availability, ML on drive-test data, RAT vs. battery consumption). **Data quality and traceability matter more than UI polish.**

## Hard constraints
- Public Android APIs only. No root, no vendor diag/QXDM interfaces, no reflection or hidden APIs.
- Layer-3 / RRC / NAS signaling is **out of scope** (needs root or diag access).
- Never crash on missing data. Every radio field is nullable; OEMs hide or fake values.
- Treat `Int.MAX_VALUE`, `Long.MAX_VALUE` and `CellInfo.UNAVAILABLE` as `null`. Wrap all OEM-dependent parsing in try/catch and log parse failures (counted, not thrown).
- Core logging needs no network. Online dependencies: map tiles, throughput test server, optional upload.
- Battery: one location request stream, no wakelock beyond the service lifetime, no busy loops.

## Tech stack
- Kotlin, Jetpack Compose (Material 3), single-activity, Navigation Compose
- minSdk 31 (Android 12), targetSdk 35, compileSdk 35
- Room (SQLite) with exported schemas + migrations, Kotlin Coroutines + Flow
- Hilt for DI
- Google Play Services Location (`FusedLocationProviderClient`)
- osmdroid for maps (no API key)
- OkHttp for the throughput/ping module
- kotlinx.serialization for JSON fields and session metadata
- Candidate dependency: **NetMonster Core** (Apache 2.0) for cell parsing and OEM quirks. Evaluate in Milestone 2 before writing our own parser (see Reference code).
- Gradle Kotlin DSL, version catalog (`gradle/libs.versions.toml`)
- Testing: JUnit5 (or JUnit4 if simpler with Android), Turbine for Flows, Robolectric only where needed
- Phase 3b only: Android NDK (C/C++) via CMake, JNI

## Commands (run these to verify your own work)
```
./gradlew assembleDebug          # build
./gradlew testDebugUnitTest      # unit tests (must pass before every commit)
./gradlew lintDebug              # lint (no new errors)
./gradlew connectedDebugAndroidTest   # instrumented tests (needs device)
./gradlew :core:data:kspDebugKotlin   # regenerate Room schema

# device (phone connected via ADB over Wi-Fi)
adb devices                                   # confirm phone is connected
./gradlew installDebug                        # install on phone
adb logcat -s NetLogger:*                     # app logs (use tag "NetLogger")
adb shell dumpsys telephony.registry          # raw telephony state, for comparing with parser output
```

## Development environment
- Host: Proxmox homelab. Guest: **Ubuntu Server LTS VM** (no desktop), min. 4 vCPU, 8–16 GB RAM, 60+ GB disk.
- Agent: **Claude Code CLI**, run inside `tmux` over SSH so sessions survive disconnects.
- Toolchain: JDK 17, Android SDK command-line tools (`platform-tools`, `platforms;android-35`, `build-tools`), Gradle wrapper from the repo. NDK + CMake only from Phase 3b.
- Gradle: `org.gradle.jvmargs=-Xmx4g` (raise if the VM has more RAM), configuration cache and build cache enabled.
- No emulator: it has no real radio data. All radio testing happens on a **real phone via ADB over Wi-Fi** (`adb pair` / `adb connect` using Wireless debugging). Phone and VM must be on the same network.
- Editing by the user: VS Code Remote-SSH or Android Studio on a laptop via git. Do not assume a GUI exists on the server.
- Never store signing keys, keystore passwords or API tokens in the repo. Use `local.properties` or environment variables (both gitignored).

## Module structure
Multi-module from day 1 so traffic capture can be added later without refactoring.

```
netlogger/
├── CLAUDE.md
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
├── app/                         # Application, MainActivity, NavGraph, DI wiring
├── core/
│   ├── model/                   # pure Kotlin data classes (no Android deps)
│   ├── telephony/               # radio collection behind interfaces
│   │   ├── TelephonySource.kt        # interface wrapping TelephonyManager (fakeable)
│   │   ├── AndroidTelephonySource.kt
│   │   ├── CellParser.kt             # CellInfo / SignalStrength -> CellSample (pure, unit-tested)
│   │   ├── NetworkStateListener.kt   # TelephonyCallback: DisplayInfo, ServiceState, SignalStrengths
│   │   ├── BandMapper.kt             # EARFCN / NR-ARFCN -> band (fallback when getBands() is empty)
│   │   └── EventDetector.kt          # RAT/PCI/NR-attach changes -> events
│   ├── location/                # LocationProvider: Flow<Location> ~1 Hz
│   ├── device/                  # battery, TrafficStats, thermal, screen state, device info
│   ├── data/                    # Room DB, DAOs, repositories, migrations, schemas/
│   ├── export/                  # CSV / GeoJSON / KML / ZIP bundle writers
│   └── ui/                      # theme, shared components, RsrpColor
├── feature/
│   ├── logger/                  # LoggingService (foreground), Live screen
│   ├── map/                     # Map screen
│   ├── sessions/                # Sessions list, detail, export, delete
│   ├── speedtest/               # ping + HTTP download/upload against own server (Phase 2)
│   ├── settings/
│   └── capture/                 # Phase 3: PCAPdroid bridge, later native VpnService capture
└── docs/
    ├── device-quirks.md         # update after every real-device test
    ├── field-test-checklist.md
    └── columns.md               # export column definitions and units
reference/                       # read-only open-source code for study (gitignored)
├── netmonster-core/
├── network-survey/
├── tower-collector/
└── pcapdroid/
```

Rules:
- `core:model` and parsers have **no Android dependencies** where possible, so they are unit-testable on the JVM.
- Never call `TelephonyManager` directly outside `AndroidTelephonySource`.
- Features depend on `core:*`, never on each other.

## Reference code
`reference/` holds cloned source repositories (not APKs) of open-source projects, for **study only**. It is gitignored.

Set up once:
```bash
mkdir -p reference && cd reference
git clone --depth 1 https://github.com/mroczis/netmonster-core
git clone --depth 1 https://github.com/christianrowlands/android-network-survey network-survey
git clone --depth 1 https://github.com/zamojski/TowerCollector tower-collector
git clone --depth 1 https://github.com/emanuele-f/PCAPdroid pcapdroid
# PCAPdroid native code lives in submodules; only needed if studying zdtun/nDPI:
# (cd pcapdroid && git submodule update --init --depth 1)
```

| Project | License | Use it for |
|---|---|---|
| netmonster-core | Apache 2.0 | Cell parsing, OEM workarounds, band mapping. Prefer as a Gradle dependency over reimplementing. |
| network-survey | Apache 2.0 | Foreground service architecture, logging pipeline, export formats |
| tower-collector | MPL 2.0 | Cell info throttling, background collection strategies |
| pcapdroid | GPL (see `COPYING`) | Phase 3 only: `docs/app_api.md` (our integration contract), VpnService lifecycle, capture architecture, how zdtun/nDPI are wired in |

Rules:
- Read-only: never edit, build, or add `reference/` to the Gradle build.
- Always check the project's own `LICENSE` file before reuse; the table above is a starting point, not a guarantee.
- Default: **write our own implementation** after studying the approach.
- Copying or adapting code is allowed only from permissive licenses (Apache 2.0, MIT, BSD), and only after telling the user the source file and license. Keep the original copyright notice and add the project to `docs/third-party.md`.
- MPL 2.0 code: do not copy into our files; study only.
- PCAPdroid is GPL: **study only, never copy or adapt its code** into NetLogger (that would require NetLogger itself to be GPL). Interact with PCAPdroid only through its public app API. For Phase 3b, write our own implementation; if a GPL library such as zdtun is needed, stop and ask the user first, since it changes NetLogger's license.
- No decompiled code from any closed-source app (e.g. G-NetTrack Pro). Compare with it only as a black box using its exported logs.
- When a design choice is based on a reference project, mention it in the commit message (e.g. `inspired by network-survey: CellularController`).

## Radio collection: what works and what doesn't
Implement with these realities in mind:

| Data | Source | Notes |
|---|---|---|
| Serving + neighbor cells | `requestCellInfoUpdate()` → `List<CellInfo>` | Throttled by the OS and may return cached data, especially with screen off. Store each cell's age via `CellInfo.getTimestampMillis()` (elapsedRealtime base). |
| LTE signal | `CellSignalStrengthLte` | RSRP, RSRQ, RSSNR (SINR proxy; scaling varies by OEM), RSSI, CQI, TA |
| NR signal (NSA) | `CellInfoNr` **and** `SignalStrength.getCellSignalStrengths()` | Many phones don't report `CellInfoNr` in NSA. Use `CellSignalStrengthNr` from `SignalStrength` as a second source and record which source was used. |
| Bands | `CellIdentityLte/Nr.getBands()` | Often empty → fall back to `BandMapper`. Record `bandSource` (`API` / `MAPPED`). |
| 5G icon state | `TelephonyDisplayInfo.overrideNetworkType` | NR_NSA, NR_ADVANCED |
| Registration / NR state | `ServiceState`, `NetworkRegistrationInfo` | Cell bandwidths via `ServiceState.getCellBandwidths()` (best-effort CA hint) |
| Physical channel config | — | **Not available**: needs `READ_PRECISE_PHONE_STATE` (privileged). Do not implement. |
| Dual SIM | `SubscriptionManager`, `createForSubscriptionId()` | Default: active data SIM. Log `subscriptionId` and `simSlot`. |

NSA detection: LTE serving cell registered **and** (NR cell or NR signal present **or** `overrideNetworkType` is NR_NSA / NR_ADVANCED). Store the raw inputs too, not just the conclusion.

## Data model (Room)
Normalized, long format. Keep a schema version and export the schema JSON for migrations.

**`session`**
`id, uuid, startedAt, endedAt, label, scenario (drive/walk/static/indoor), notes, intervalMs,`
`deviceManufacturer, deviceModel, androidVersion, basebandVersion, appVersion, schemaVersion,`
`subscriptionId, operatorName, mcc, mnc, captureFile (nullable, Phase 3)`

**`measurement`** (one row per tick)
`id, sessionId, seq, wallTimeMs, elapsedRealtimeNs,`
`lat, lon, altitude, speedMps, bearing, gpsAccuracy, locationAgeMs,`
`networkType (LTE/NR_NSA/NR_SA/WCDMA/GSM/NONE), overrideNetworkType, dataState, isRoaming,`
`cellBandwidthsJson,`
`batteryLevel, batteryCurrentUa, batteryVoltageMv, batteryTempC, isCharging,`
`rxBytesTotal, txBytesTotal, rxBytesApp, txBytesApp,`
`screenOn, thermalStatus, parseErrors`

**`cell`** (many rows per measurement: serving + neighbors)
`id, measurementId, rat (LTE/NR/WCDMA/GSM), isServing, isRegistered, connectionStatus,`
`ci_or_nci, tac, pci, arfcn, band, bandSource, bandwidthKhz,`
`rsrp, rsrq, sinr, rssi, cqi, ta,`
`csiRsrp, csiRsrq, csiSinr,`
`source (CELL_INFO/SIGNAL_STRENGTH), cellInfoAgeMs`

**`event`**
`id, sessionId, wallTimeMs, elapsedRealtimeNs, type (RAT_CHANGE, PCI_CHANGE_LTE, PCI_CHANGE_NR, NR_ADDED, NR_REMOVED, NO_SERVICE, SERVICE_RESTORED, GPS_LOST, GPS_RESTORED, USER_MARKER), fromValue, toValue, lat, lon`

**`throughput_test`** (Phase 2)
`id, sessionId, startedAtMs, type (PING/DOWNLOAD/UPLOAD), serverUrl, durationMs, bytes, mbps, rttMinMs, rttAvgMs, rttMaxMs, jitterMs, lossPct, captureActive`

Timing rules:
- Every row stores **both** `wallTimeMs` and `elapsedRealtimeNs`. `elapsedRealtimeNs` is the sync key with pcap files and is immune to clock changes.
- One tick = one coroutine loop driven by `elapsedRealtime`, not `Thread.sleep` drift.

## Logging service
1. `LoggingService` is a foreground service, type `location`, with an ongoing notification (Stop, Add Marker actions).
2. Every N ms (default 1000; options 500/1000/2000/5000): request a cell info update, take latest `NetworkState`, latest GPS fix, device context → build measurement + cells → insert in **one Room transaction**.
3. `EventDetector` compares with the previous tick and writes events.
4. Writes are batched if inserts become slow (measure first; don't optimize early).
5. Survive screen off for 1+ hour. If the service is killed, the session is closed cleanly on next app start (`endedAt` = last measurement time).

## Permissions
`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `READ_PHONE_STATE`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `WAKE_LOCK`, `INTERNET` (tiles, speed test).
- `ACCESS_BACKGROUND_LOCATION` is **not** needed: the service is started from the foreground.
- Rationale screen before requesting; app stays usable (read-only sessions, export) if denied.
- Phase 3b adds the `VpnService` declaration and its foreground service type. Check current Android 14+ rules for VPN foreground services before implementing.

## Export
Per session, export a **ZIP bundle** via Storage Access Framework or `ACTION_SEND`:
- `session.json` (metadata)
- `measurements.csv`, `cells.csv`, `events.csv`, `throughput.csv`
- `flat.csv`: one row per tick with serving LTE + strongest NR columns (easy for Excel and quick plots)
- `track.geojson` (and optional `track.kml`)
- `capture.pcap` if available (Phase 3)

CSV rules: UTF-8, comma-separated, header row, ISO-8601 timestamps with offset, empty field for null (never `0` or `-999` for missing values), `.` as decimal separator regardless of device locale.

Optional: a "G-NetTrack-compatible" column layout for side-by-side validation. Implement only after the native format is stable.

## UI
- **Live:** network type, operator, serving LTE + NR values, neighbor table, GPS status, sample age warnings (gray out values older than 5 s).
- **Map:** session track colored by selectable metric (LTE RSRP, NR SS-RSRP, SINR, network type); event markers; tap point → values.
  RSRP colors: > -80 green, -80 to -95 yellow, -95 to -110 orange, < -110 red, null gray.
- **Sessions:** list, rename/label, notes, export, delete (with confirmation).
- **Settings:** interval, SIM selection, speed test server URL, export options.

## Phase 3: Traffic capture
**3a — PCAPdroid bridge (do first).**
PCAPdroid (installed separately on the phone) exposes an app API to control capture from another app. Read `reference/pcapdroid/docs/app_api.md` and implement exactly what it documents; do not guess intent extras or permissions. Two modes, user-selectable in Settings:
- **File mode:** PCAPdroid writes a `.pcap`; NetLogger links the file to the session by session UUID.
- **UDP stream mode:** PCAPdroid sends captured packets via UDP to a local port opened by NetLogger, which writes `capture.pcap` itself and timestamps arrival with `elapsedRealtimeNs`. Preferred, because NetLogger then owns the file and timing.
Store capture start/stop `elapsedRealtimeNs` for alignment in both modes. Handle "PCAPdroid not installed" and "user denied capture" gracefully. Do not depend on PCAPdroid paid features (PCAPng, firewall).

**3b — Native capture (later, separate milestone).**
`VpnService` + userspace TCP/UDP forwarding in C (NDK/JNI), our own PCAP/PCAPNG writer, per-app attribution via `ConnectivityManager.getConnectionOwnerUid()`. Document limits in the UI:
- only IP traffic of apps, no radio signaling;
- cannot run together with another VPN;
- adds overhead, so throughput tests during capture are flagged (`captureActive = true`).

## Coding conventions
- Kotlin official style; no `!!` in production code.
- Parsers are pure functions returning nullable fields + a list of parse warnings.
- Domain units in names: `rsrpDbm`, `speedMps`, `bandwidthKhz` in code; CSV headers may drop suffixes but must be documented in `docs/columns.md`.
- Every Room schema change needs a migration and a migration test.
- No new dependency without saying why in the commit message.
- Small commits, one per milestone step, message format: `feat(telephony): ...`, `fix(export): ...`, `test(...)`.

## Milestones (build in order; build + tests + lint green before each commit)
**Phase 1 — MVP**
1. **Scaffold:** multi-module Gradle, Hilt, Compose, navigation, permission flow, CI (GitHub Actions: build, unit tests, lint).
2. **Telephony:** first, study `reference/` and write a short decision note in `docs/decisions/001-cell-parsing.md`: use NetMonster Core as a dependency, or write our own `CellParser`. Wait for user approval. Then implement `TelephonySource`, the parser (or NetMonster adapter), `NetworkStateListener`, NR-from-SignalStrength fallback, and a Live screen with raw values. Unit tests with fake CellInfo data.
3. **Location + Service:** `LocationProvider`, `LoggingService`, notification, device context (battery, TrafficStats, thermal).
4. **Persistence:** Room schema above, repositories, sessions list, crash-safe session closing.
5. **Export:** ZIP bundle, CSV writers, GeoJSON. Tests for escaping, nulls, locale.
6. **Map:** colored track, metric selector, event markers.

**Phase 2 — Research extras**
7. `EventDetector` + events table; `BandMapper` fallback with tests for all bands used by Indonesian operators.
8. Speed test module: ping, HTTP download/upload against configurable own server.
9. Session detail with charts (RSRP/SINR vs time, network type timeline).

**Phase 3 — Capture**
10. PCAPdroid bridge (3a).
11. Native `VpnService` capture (3b) — plan first, get approval before coding.

**Always**
12. Keep `docs/device-quirks.md` and `docs/field-test-checklist.md` updated.

## Definition of done (MVP = milestones 1–6)
- Start a session, drive/walk, stop; the ZIP contains session metadata, measurements, cells (serving + neighbors), GPS, LTE and NR RSRP/RSRQ/SINR, PCI, ARFCN, band (+ source), network type, both timestamps.
- 1 hour of logging with screen off: no crash, ≥ 95% of expected ticks recorded, stale samples identifiable.
- Works on at least one Android 12+ phone with 5G NSA; values cross-checked against G-NetTrack Pro on the same phone and differences documented.
- Unit tests pass for parsers, `BandMapper`, `EventDetector`, CSV writers.

## When working on this project, Claude Code should
- Implement only the milestone asked for; propose, don't silently expand scope.
- Run build, unit tests and lint before reporting a milestone as done.
- Report device-specific limitations and add them to `docs/device-quirks.md`.
- Ask before adding dependencies, changing the schema, or touching Phase 3 code.
- Follow the Reference code rules; never copy from `reference/` silently.
- Assume a headless server: no GUI tools, no emulator. Ask the user to run on-device checks and paste logcat output when needed.

## .gitignore (must include)
```
reference/
local.properties
*.jks
*.keystore
.gradle/
build/
captures/
*.pcap
*.pcapng
exports/
```

## First prompt to give Claude Code
> Read CLAUDE.md. First check the environment: JDK, Android SDK, `adb devices`, and that `reference/` contains the four repositories; tell me what is missing. Also confirm `reference/pcapdroid/docs/app_api.md` exists. Then implement milestone 1: multi-module Gradle project, Hilt, Compose navigation, permission flow, `.gitignore` and the CI workflow. Run build, tests and lint. Then start milestone 2 only up to the decision note `docs/decisions/001-cell-parsing.md` comparing NetMonster Core with our own parser, and stop for my approval.
