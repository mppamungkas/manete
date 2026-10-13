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
