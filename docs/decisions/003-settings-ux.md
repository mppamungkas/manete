# 003: Settings, help and phone capabilities (UI/UX touch-up): plan

- **Status:** Accepted (D1–D5 as recommended), 2026-10-15.
- **Date:** 2026-10-15
- **Users:** teachers and students (not only the developer), so every option explains itself.
- **Language:** English only. All texts go to `strings.xml` (a translation stays possible later).

## Already decided with the user

1. The Settings tab becomes a list of sections. Each row shows the current value and a one-line description;
   every choice shows a short description per option.
2. **Capture start/stop stays on the Live card.** What is captured (all apps or specific apps) and the MTU move to
   Settings.
3. **Phone capabilities is a live check** on the phone, not a static list.
4. New info pages: About, How to use, Phone capabilities.

## Layout

```
Settings
├─ Measurement
│   ├─ Logging          interval · SIM · default scenario
│   ├─ Speed test       server URLs · public secondary target
│   └─ Traffic capture  all apps / specific apps · MTU
├─ Data
│   └─ Export           (existing ZIP content explained; options only if we add any)
└─ Info
    ├─ Phone capabilities
    ├─ How to use
    └─ About
```

Navigation: nested destinations inside the Settings tab (Navigation Compose, already a dependency); back returns to
the list. No new dependency.

### Choices with descriptions (examples)

| Setting | Options and descriptions |
|---|---|
| Logging interval | 500 ms: finest detail, more battery; the OS may return cached cell data · **1000 ms (recommended)**: drive tests · 2000 ms: walks, long static tests · 5000 ms: very long sessions, lowest battery |
| Default scenario | Drive / Walk / Static / Indoor, with one line each; still changeable on Live before Start |
| SIM | Follows the active data SIM (as now); shows which SIM that is. A fixed-slot choice only if the user wants it later |
| Capture: which apps | **All apps** (default): everything the phone sends · **Specific apps**: pick from a list of installed apps (icon + name), instead of typing package names |
| Capture: MTU | **1500 realistic** (default): real packet sizes; fast links lose up to a quarter of their download speed · 10 000 fast: −3 %, but packets are bigger than on the air |
| Speed test public target | Off by default; "Cloudflare: a public server, different route; only for comparison" |

The Live capture card keeps Start/Stop, the counters and one summary line ("All apps · MTU 1500 · change in
Settings").

## Phone capabilities (live check)

One page, refreshed on open and with a Refresh button. Three parts:

1. **Device:** manufacturer, model, Android version, baseband, NetLogger version; SIMs (operator, MCC-MNC, slot,
   which is the data SIM).
2. **Right now:** network type and 5G icon state, IPv4/IPv6 on mobile data, Wi-Fi on/off (blocks speed tests and
   mobile NR), another VPN active (blocks capture), GPS fix, battery optimisation exempt or not.
3. **What this phone reports** (from one fresh cell update, through `TelephonySource` and the parser, no new API
   calls): per field Available / Missing / Limited, with a one-line meaning:
   LTE RSRP/RSRQ/SINR/CQI/TA, NR SS-RSRP/SINR, NR in CellInfo or only in SignalStrength, band from the API or mapped,
   carrier aggregation hint (cell bandwidths), neighbour cells, cell identity redacted (Samsung).
   Fields that need 5G coverage say "not seen now (needs 5G coverage)" instead of "Missing".
   Optionally a second column "seen in recorded sessions" from the database (share of ticks with a value), which
   is more reliable than one snapshot.

"Copy report" puts the page as text on the clipboard (for `docs/device-quirks.md` or a teacher).

Rules: public APIs only, nothing new needs a permission; missing permissions are shown as such, never crash.

## How to use (for teachers and students)

Short pages, plain text with headings, no images at first:
1. Before you start: permissions, Wi-Fi off, battery optimisation, mount the phone.
2. A drive or walk test: scenario, Start, markers, Stop; what the gray (stale) values mean.
3. Speed test: own server vs. public target, data use (~100–250 MB per run), why results depend on the radio.
4. Traffic capture: VPN consent, all vs. specific apps, file size (a speed test during a capture ≈ 100 MB),
   MTU, the limits (no radio signalling).
5. Map, charts and export: what is in the ZIP, opening CSVs in Excel, the pcap in Wireshark.
6. Glossary: RSRP, RSRQ, SINR, PCI, EARFCN, band, NSA/SA, CA.

## About

App name and logo, version and build commit, GPL v3, source repository, third-party licences (zdtun LGPL v3+,
MapLibre BSD-2, OkHttp Apache 2.0, AndroidX/Hilt/Room Apache 2.0, kotlinx Apache 2.0) written by hand from
`docs/third-party.md` and `gradle/libs.versions.toml` (no licence plugin; each licence checked when written), the OpenStreetMap attribution, and a privacy note: sessions and exports
contain precise location and, with a capture, network traffic; share them carefully.

## Code changes

- `CaptureSettings` moves from `feature:capture` to `core:data` (beside `SpeedTestSettings`), so `feature:settings`
  can use it (features never depend on each other). Same SharedPreferences file and keys: existing choices survive.
  **This touches Phase 3 code** (only where the settings are read; nothing in the capture itself).
- New `LoggingSettings` in `core:data` (interval, default scenario); `LoggingService.start` uses them. No schema
  change: `session.intervalMs` already stores the interval.
- Installed-app list for the picker: `PackageManager` with the existing launcher-apps `<queries>` entry; no
  `QUERY_ALL_PACKAGES`.
- Capability check in `feature:settings`, reading `core:telephony` / `core:device` (no new APIs outside
  `AndroidTelephonySource`).

## Steps (one commit each; build, unit tests and lint green before each)

1. Settings list + Logging page (interval, default scenario, SIM info) + `LoggingSettings`.
2. Speed test page moved into the new layout, with descriptions.
3. Traffic capture page (app picker, MTU) + `CaptureSettings` to `core:data`; Live card shows the summary.
4. Phone capabilities page + Copy report.
5. How to use + About.
6. Check on both phones (screenshots), update `docs/field-test-checklist.md`.

## Decisions needed

- **D1** Steps and layout above. *Recommendation: yes.*
- **D2** Move `CaptureSettings` to `core:data` (Phase 3 code). *Recommendation: yes.*
- **D3** Capabilities: add "seen in recorded sessions" next to the live check. *Recommendation: yes, in step 4.*
- **D4** Logging interval selectable (500 / 1000 / 2000 / 5000 ms). *Recommendation: yes, default 1000.*
- **D5** SIM: show the data SIM only (no fixed-slot choice for now). *Recommendation: yes.*

## Out of scope

Snaplen (discussed, not now), translations, a design overhaul of Live/Map/Sessions, tutorial overlays.
