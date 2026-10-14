# Field test checklist

## Setup (every session)
- [ ] `adb devices` shows the phone (pilot: USB passthrough); `./gradlew installDebug`. No root commands.
- [ ] Note device, Android version, baseband (Settings → About → Software info), active data SIM and operator
- [ ] All permissions granted, location set to **Precise**
- [ ] `adb logcat -s NetLogger:*` running in a second terminal

## Milestone 2: Live screen vs. `dumpsys`
Do this on each phone, in LTE-only coverage and in 5G NSA coverage (5G icon visible).
- [ ] Open the Live tab, wait 10 s, take a screenshot
- [ ] Within the same few seconds: `adb shell dumpsys telephony.registry > docs/samples/<device>_<operator>_<situation>_<date>.txt` (gitignored)
- [ ] Compare serving LTE: PCI, EARFCN, band, TAC, CI, RSRP/RSRQ/RSSNR (`mCellInfo` against the `CELL_INFO` card, `mSignalStrength.mLte` against the `SIGNAL_STRENGTH` card; both are shown)
- [ ] Compare NR: does `dumpsys` list a `CellInfoNr`? Does the Live screen show NR as `CELL_INFO` or `SIGNAL_STRENGTH`?
      NSA NR is only added while data moves: keep a download running, and wait until the status bar shows 5G
      (`overrideNetwork=NR_NSA` in dumpsys) before capturing. Check the NR card's PCI and band against G-NetTrack.
- [ ] Compare network type with `mTelephonyDisplayInfo` (`overrideNetworkType`) and the status bar icon
- [ ] Note every parse warning shown, especially `SAMSUNG_*` and `OUT_OF_RANGE`
- [ ] Neighbors: count and PCIs roughly match `dumpsys`
- [ ] Turn the screen off for 30 s, back on: check cell ages and `Cell info: FRESH/CACHED`
- [ ] Repeat once on an unrooted phone (A56) before marking the milestone done
- [ ] Record findings in `docs/device-quirks.md`

## Milestone 3: logging service, screen off

Run on the moto g45 (pilot) and on the A56 (unrooted). **Unplug the phone**: on USB it charges and never enters
Doze, so a plugged-in test proves nothing about screen-off behavior.

- [ ] Live → **Start logging**: notification "NetLogger is logging" with Stop and Add marker appears
- [ ] `adb -s <serial> shell dumpsys power | grep NetLogger:logging` shows the partial wake lock while logging
- [ ] Add marker from the notification and from the Live screen: marker count goes up
- [ ] GPS card: status OK, accuracy and age plausible (age < 2 s while moving outdoors)
- [ ] Device card: battery %, current (note sign while charging vs. discharging), voltage, temperature, thermal, screen
- [ ] Unplug, screen off, **1 hour** (walk or drive if possible). Then plug in and read
      `adb -s <serial> logcat -d -s NetLogger:I | grep ticks` (one summary per minute: ticks recorded/expected,
      cell info origin, max serving-cell age, max GPS age, screen-off ticks, NR ticks)
- [ ] Pass: ≥ 95 % of expected ticks (Live: "Last session x / y"), no crash
- [ ] Note the max serving-cell age with screen off (moto plugged in: ~10 s) and whether NR disappears
- [ ] Stop from the notification: notification gone, wake lock released (`REL NetLogger:logging` in dumpsys power)
- [ ] Record results per phone in `docs/device-quirks.md`

## Milestone 4: persistence and crash-safe closing

- [ ] Live: pick a scenario chip, Start, Add marker (Live and notification), Stop
- [ ] Sessions: the session shows scenario, operator, device, duration, ticks and the marker count
- [ ] Detail: edit label and notes, change scenario, Save; values survive an app restart
- [ ] Kill test: start a session, `adb -s <serial> shell am force-stop id.my.pamslab.netlogger`, reopen the app.
      The session shows "closed after the app was killed (end = last tick)" and its end time equals the last tick
- [ ] Delete with confirmation removes the session (the recording session cannot be deleted)
- [ ] Pull the database (`adb -s <serial> exec-out run-as id.my.pamslab.netlogger cat databases/netlogger.db`,
      also `-wal`) into `docs/samples/`; check that each tick has at most one `servingInferred` NR cell per NR-ARFCN
      and look at `servingSource`
- [ ] Release check: `ANDROID_SERIAL=<serial> ./gradlew :core:data:connectedDebugAndroidTest` passes on both phones

## Milestone 5: export

- [ ] Sessions → detail of a stopped session: "Save ZIP…" opens the document picker with the suggested name
      `netlogger_<model>_<start>_<label or scenario>_<uuid8>.zip`; the file appears where it was saved
- [ ] "Share ZIP" opens the share sheet with the ZIP attached (cache copy: `adb -s <serial> exec-out run-as
      id.my.pamslab.netlogger cat cache/exports/<file>.zip`); both buttons are disabled while the session records
- [ ] Unzip: session.json, measurements.csv, cells.csv, flat.csv, events.csv, warnings.csv, track.geojson
- [ ] Row counts match the database (`counts` in session.json, and the pulled `netlogger.db`); every CSV row has the
      header's width; empty fields for missing values, no `NaN`/`-999`/`2147483647`
- [ ] Times are ISO-8601 with the phone's offset; `timeZone` in session.json
- [ ] flat.csv: one row per tick, at most one NR cell (`nrServingCount` > 1 only with NR CA), every value has its
      `*Source`
- [ ] track.geojson opens in a GeoJSON viewer (e.g. geojson.io) at the right place; coordinates are [lon, lat]
- [ ] Open flat.csv in a spreadsheet with a comma-decimal locale (e.g. German): numbers still parse with "." when
      imported as CSV with "." decimal separator

## Milestone 6: map

- [ ] Map tab with no sessions: hint, no crash. With sessions: the newest session is selected and framed
- [ ] Each metric chip recolors the dots: LTE RSRP, NR SS-RSRP, LTE SINR, NR SS-SINR, Network; legend matches;
      ticks without a value are gray; ticks without a GPS fix are not drawn (count shown top right)
- [ ] Tap a dot: card with tick, time, LTE/NR identity, RSRP/SINR with "(SS)" when from SignalStrength, ages, GPS
- [ ] Markers appear as black dots with a white ring; detected events as small rings (gray handover, purple
      network type / NR, red service / GPS / SIM)
- [ ] Start logging, open Map: the session is marked "recording" and grows every tick; Follow keeps the newest
      point in view; turning Follow off lets you pan freely
- [ ] Session detail → "Show on map" opens that session on the Map tab
- [ ] "© OpenStreetMap contributors" is visible; tiles load on mobile data; no crash in airplane mode (gray map,
      track still drawn)
- [ ] Map open for 10+ minutes while logging: no slowdown or memory growth visible in Live's tick counter

## Milestone 7: events and bands

- [ ] Drive with handovers and 5G coming and going: events.csv has PCI_CHANGE_LTE, RAT_CHANGE, NR_ADDED / NR_REMOVED
      at plausible places on the map; A56 PCI_CHANGE_NR only for cells held 2+ ticks
- [ ] Compare the event list with G-NetTrack's events file (same phone, same drive)
- [ ] warnings.csv: `API_BAND_CONFLICT` on the moto right after handovers; bands in flat.csv match the EARFCN

## Milestone 8: speed test

- [ ] Server reachable from mobile data (LibreSpeed backend or own web server); URLs saved in Settings
- [ ] **Wi-Fi off.** With Wi-Fi on, the test is refused ("default network is not mobile data")
- [ ] While logging: "Run speed test" on Live (and once from the notification with the screen off): ping, then
      download and upload for 10 s each; Live shows progress and results
- [ ] throughput.csv: 3 rows per test, plausible Mbps vs. G-NetTrack / another speed test app at the same spot;
      `startedElapsedNs` falls inside the session's ticks
- [ ] Data used per test stays under 250 MB (200 MB down, 50 MB up caps)
- [ ] Server down / wrong URL: the phases have `error` set, no crash, logging continues

## Milestone 9: session charts

- [ ] Session detail of a drive: RSRP and SINR charts (LTE blue, NR purple) over the map's color bands; timeline
      rows for network type, screen off and speed tests; times at start / middle / end match the session
- [ ] Gaps: no line across missing values or missed ticks; moto LTE SINR mostly empty with the screen off (stale rule)
- [ ] Tap and drag on any chart: cursor on all charts, readout with time, tick, network type, values, "(SS)" marks
- [ ] A 1-hour session: charts load in a few seconds, scrolling stays smooth
- [ ] A recording session: "Reload" shows newer ticks

## Milestone 10: PCAPdroid bridge (Phase 3a)

- [ ] PCAPdroid installed (F-Droid / Play); no other VPN active
- [ ] Without PCAPdroid: the capture card says so, no crash
- [ ] Logging → capture card → "Into NetLogger" → Start capture: PCAPdroid asks for consent (first time) and for the
      VPN; deny once: the card shows the error, no CAPTURE_STARTED event, no file left behind
- [ ] Allow: packets and MB count up on the card; browse / run a speed test (flagged `captureActive`)
- [ ] Stop capture: CAPTURE_STOPPED with counts; export → capture.pcap opens in Wireshark (raw IP), capture_sync.csv
      has about one row per second of traffic
- [ ] Stop from PCAPdroid itself: NetLogger notices (CAPTURE_STOPPED reason PCAPDROID)
- [ ] Stop the session from the notification while capturing: CAPTURE_STOPPED SESSION_END; Live offers "Stop PCAPdroid capture"
- [ ] Screen off for 10 min while capturing: packets keep counting, no gap in capture_sync.csv while traffic flows
- [ ] "PCAPdroid file" mode: file in Download/PCAPdroid/netlogger_<uuid>.pcap, session.json captureFile names it
- [ ] App filter with one package: only that app's traffic in the PCAP
