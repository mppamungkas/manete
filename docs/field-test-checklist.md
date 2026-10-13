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
