# Field test checklist

## Setup (every session)
- [ ] `adb devices` shows the phone; `./gradlew installDebug`
- [ ] Note device, Android version, baseband (Settings → About → Software info), active data SIM and operator
- [ ] All permissions granted, location set to **Precise**
- [ ] `adb logcat -s NetLogger:*` running in a second terminal

## Milestone 2: Live screen vs. `dumpsys`
Do this on each phone, in LTE-only coverage and in 5G NSA coverage (5G icon visible).
- [ ] Open the Live tab, wait 10 s, take a screenshot
- [ ] Within the same few seconds: `adb shell dumpsys telephony.registry > dumpsys-$(date +%s).txt`
- [ ] Compare serving LTE: PCI, EARFCN, band, TAC, CI, RSRP/RSRQ/RSSNR (`mCellInfo` and `mSignalStrength`)
- [ ] Compare NR: does `dumpsys` list a `CellInfoNr`? Does the Live screen show NR as `CELL_INFO` or `SIGNAL_STRENGTH`?
- [ ] Compare network type with `mTelephonyDisplayInfo` (`overrideNetworkType`) and the status bar icon
- [ ] Note every parse warning shown, especially `SAMSUNG_*` and `OUT_OF_RANGE`
- [ ] Neighbors: count and PCIs roughly match `dumpsys`
- [ ] Turn the screen off for 30 s, back on: check cell ages and `Cell info: FRESH/CACHED`
- [ ] Record findings in `docs/device-quirks.md`
