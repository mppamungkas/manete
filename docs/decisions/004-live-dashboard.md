# 004: Live tab as a dashboard (UI/UX): plan

- **Status:** Accepted (D1–D7 as recommended; D3 = no hint), 2026-10-16.
- **Date:** 2026-10-16
- **Users:** teachers and students during a walk or drive, and researchers who still need the raw values.
- **Language:** English only. Live's texts move to `strings.xml`, as Settings' did in 003.

## Problem

Today the Live tab is a stack of up to ten cards: logging, speed test, capture, network, GPS, device, one card per
serving cell (two for NR: CellInfo and SignalStrength), neighbours and parse warnings. The values that matter while
moving (RSRP, SINR) are below the fold, many labels are parser terms ("NSA inputs lteServing=true …",
"dataNetworkType=13 override=3", "Service / data 0 / 2", "LTE serving · CELL_INFO · NONE"), and the speed test and
capture cards carry long explanations.

## Already decided with the user

1. **One dashboard screen**, read from top to bottom, modelled on G-NetTrack's dashboard: operator, serving cell
   metrics, GPS status, network status (data route and rates), events. Layout below accepted.
2. **Show the Wi-Fi SSID** in the data route.
3. **"Cell change"** instead of "Handover" / "Cell reselection": telling them apart needs the RRC state (connected
   vs. idle), which no public API gives. NetLogger does not label what it cannot know.

## Layout

```
┌ ● REC 00:12:34 · 754 ticks · Drive              [Marker] [Stop] ┐   status bar, always visible
├─────────────────────────────────────────────────────────────────┤
│ Operator (510-89)                         5G NSA · LTE-A 3CC    │   network
│                                                                 │
│ Serving        LTE B3            NR n41                         │   serving cells, one table
│ PCI / ARFCN    123 / 1625        – / 531870                     │
│ RSRP           -91 dBm ●         -98 dBm ● SS                   │   map colours
│ RSRQ / SINR    -8 / 12 dB        -11 / 7 dB SS                  │
│ Cell / TAC     1234567 / 4321                                   │
│ ▸ Neighbours (3)                                                │   collapsed
├─────────────────────────────────────────────────────────────────┤
│ GPS   Fixed · ±10 m · 0.3 m/s · 30° · alt 28 m · 2 s ago        │
│       -6.200000, 106.800000                                     │
├─────────────────────────────────────────────────────────────────┤
│ Data  Cellular (Operator)      ↓ 1.24 Mbps   ↑ 85 kbps          │   or: Wi-Fi "SSID"
│ Speed test   last: ↓ 42.1 ↑ 11.3 Mbps · 31 ms        [Run]      │   while logging
│ Capture      Off · All apps · MTU 1500                [Start]   │   while logging
├─────────────────────────────────────────────────────────────────┤
│ Events                                                          │
│ 08:57:12  LTE cell change  PCI 123 → 211   -104 → -93 dBm       │
│ 08:55:40  5G added         n41                                  │
│ 08:51:03  GPS lost                                              │
├─────────────────────────────────────────────────────────────────┤
│ ▸ Details   raw radio values · battery and thermal · warnings   │   collapsed
└─────────────────────────────────────────────────────────────────┘
```

Not logging, the status bar is `Not logging · [Drive ▾] [Start]` (scenario as a dropdown; the interval is set in
Settings and shown there, not here). A data-SIM change during a session keeps its red message under the status bar.

### Sections

| Section | Content | Notes |
|---|---|---|
| Status bar | Recording: elapsed time, ticks recorded (and % of expected when below 95 %), scenario, Marker, Stop. Idle: scenario dropdown, Start, last session's tick share | Stays at the top while scrolling. Write errors show here in red |
| Network | Operator name and MCC-MNC; network type in words: "5G NSA", "5G SA", "LTE", "LTE-A 3CC" (carriers from `cellBandwidths`), "3G", "2G", "No service"; "Roaming" when roaming; "Data off" when the data state is not connected | Replaces `NetworkCard`. The raw inputs move to Details |
| Serving cells | One table, a column per serving RAT (LTE, NR; WCDMA/GSM when that is the serving RAT). Rows: band, PCI / ARFCN, RSRP, RSRQ / SINR, Cell ID / TAC. RSRP and SINR coloured with the Map's thresholds | **NR: one column from both sources**: the CellInfo value where present, else the SignalStrength value, marked "SS" (as on the Map). Values older than 5 s greyed, as now |
| Neighbours | Collapsed row "Neighbours (n)"; opened, today's table (RAT, PCI, ARFCN, RSRP, RSRQ) sorted by RSRP | |
| GPS | Two lines: status, accuracy, speed, bearing, altitude, age; coordinates | No satellite count: the fused location API does not give it (would need a GNSS-status listener; out of scope) |
| Data | Route: "Cellular (operator)" or "Wi-Fi \"SSID\"" or "No connection"; download and upload rate | See D2, D4 |
| Speed test | One row: the last result (or the running phase and value) and Run; the Cloudflare button only when enabled in Settings, as a small text button | Only while logging, as now. The explanation moves to How to use |
| Capture | One row: Off / Capturing · packets · MB, the summary ("All apps · MTU 1500") and Start/Stop | Only while logging, as now. See D5 |
| Events | The last 5 events, newest first: time, type in words, from → to, and for cell changes the serving RSRP before and after | See D6 |
| Details | Collapsed: the raw network fields (service/data state, NSA inputs, override type, data network type, bandwidths, cell info origin and counts, SIM subId/slot), every serving cell's full field list (RSSI, CQI, TA, CSI-RS, band source, cell info age), battery (level, current, voltage, temperature), thermal, screen, traffic counters, parse warnings | Researchers keep every value; open/closed is remembered per section |

Event names on Live: "LTE cell change", "5G cell change", "5G added", "5G removed", "Network type change", "No
service", "Service back", "GPS lost", "GPS back", "Data SIM changed", "Marker", "Capture started/stopped". The export
keeps the existing event type names (`docs/columns.md`); only the display changes.

## Code changes

- `feature:logger`: `LiveScreen.kt` split into section composables (status bar, network, serving table,
  GPS, data, events, details); texts in `strings.xml`. Pure formatting helpers (network type in words, carrier
  count, NR merge, rates, event text) in a separate file with unit tests.
- **Data route** (new, read-only): a `NetworkRouteSource` in `core:device` with a `ConnectivityManager` network
  callback on the default network: transport (Wi-Fi / cellular / none) and, for Wi-Fi, the SSID from `WifiInfo`
  (`FLAG_INCLUDE_LOCATION_INFO`, Android 12+; needs the fine location permission NetLogger already has, and location
  on). If the phone also requires `ACCESS_WIFI_STATE`, it is added (install-time, no prompt); see D2. An unknown or
  hidden SSID shows as "Wi-Fi". Listened to only while Live is visible or a session records.
- **Rates:** from the device traffic counters NetLogger already reads every tick (`rxBytesTotal`, `txBytesTotal`):
  bytes between two ticks / time between them. Shown as kbps / Mbps.
- **Events while not logging:** an `EventDetector` instance on the Live preview ticks, in memory only (like the Map's
  live preview), cleared when a session starts; while logging, Live shows the session's own events. The RSRP
  before/after is taken from the previous and current tick when the event is detected.
- `feature:capture`: `CaptureCard` becomes a compact row (D5). The long explanation moves to How to use, which
  already has a Traffic capture page.
- No schema change, no new library.

## Steps (one commit each; build, unit tests and lint green before each)

1. Status bar (recording and idle, scenario dropdown) and the Details section with today's raw cards moved into it.
2. Network line and the serving-cell table with the merged NR column, colours, collapsible neighbours.
3. GPS lines; Data row with route (Wi-Fi SSID / cellular) and rates (`NetworkRouteSource`).
4. Speed test row and capture row (compact), texts moved to How to use.
5. Events section (session events while logging, in-memory detector while not).
6. Check on both phones (screenshots: idle, recording, with Wi-Fi on for the SSID, during a speed test and a
   capture), update `docs/field-test-checklist.md`, How to use, CLAUDE.md (UI section).

## Decisions needed

- **D1** Layout, sections and steps above. *Recommendation: yes.*
- **D2** Wi-Fi SSID via the default-network callback; add `ACCESS_WIFI_STATE` only if a phone needs it.
  *Recommendation: yes.* (SSID approved by the user; the permission is the open part.)
- **D3** "Cell change" events also say "during data transfer" when the traffic counters moved in that tick (a hint
  that it was probably a handover, without claiming it). *Recommendation: no for now: the counters include Wi-Fi and
  background traffic, so the hint would often be wrong. Revisit with the capture.*
- **D4** Data route and rates are shown on Live only, not stored. Storing the route per tick is a schema change
  (version 3) for a later decision; the rates can already be computed from the stored counters in an export.
  *Recommendation: Live only.* Note for research: with Wi-Fi on, the traffic counters (and any speed test) measure
  Wi-Fi, not the cellular link; Live will show this, the stored data does not yet.
- **D5** Change `CaptureCard` (Phase 3 code, UI only; nothing in the capture itself) into the compact row.
  *Recommendation: yes.*
- **D6** Events on Live while not logging, from an in-memory detector (never stored). *Recommendation: yes.*
- **D7** Details collapsed by default; open/closed remembered across app starts. *Recommendation: yes.*

## Out of scope

Crash and bug reports (next decision, 005), GNSS satellite count, a "Handover" / "Reselection" label, storing the data
route (schema v3), Map and Sessions redesigns, translations, a release APK (open decisions parked 2026-10-16).
