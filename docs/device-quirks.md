# Device quirks

Update after every real-device test. For each quirk: device, Android / baseband version, what was seen
(paste `dumpsys telephony.registry` or logcat excerpts), and how NetLogger handles it.

## Test devices

| Device | SoC / modem | Android | Notes |
|---|---|---|---|
| Samsung Galaxy A56 (SM-A566B, `a56x`) | Exynos 1580 (`ro.soc.model` = s5e8855), baseband `A566BXXSECZI2` | 16 (API 36) | Samsung corrections active. Unrooted: use for the per-milestone unrooted check. ADB over Wi-Fi. First tested 2026-10-13 |
| Motorola moto g45 5G (`fogos`, ADB `ZP22222MQL`) | Qualcomm Snapdragon 6s Gen 3 (`ro.soc.model` = SM6375), baseband `M6375_HI434_03.562.01.115R` | 15 (API 35) | **Pilot phone, rooted** (root only for the developer's validation tools; NetLogger never uses it). No OEM corrections. First tested 2026-10-13 |

## Seen on our devices

### moto g45, Tri (510-89), LTE B3 with carrier aggregation, 2026-10-13

Setup: SIM 1 Tri, slot 2 empty, static indoor, no 5G coverage (`isEndcAvailable = false`). Raw dumps in
`docs/samples/` (gitignored, local only: they contain cell IDs and TACs). Anonymized fixture (CI/TAC
replaced, IPs and logs removed): `core/telephony/src/test/resources/fixtures/moto-g45_tri_lte-ca_2026-10-13.txt`. The Live screen matched
`dumpsys telephony.registry`.
No extraction failures and no parse warnings in logcat. `requestCellInfoUpdate` returned FRESH data every tick
(age 19–986 ms) with the screen on.

| What was seen | Evidence | Handling |
|---|---|---|
| **RSSNR missing in CellInfo but present in SignalStrength** | CellInfo serving: `rssnr=2147483647`; `SignalStrength.mLte`: `rssnr=11` (and `rssnr=9` in the sample) | A `SIGNAL_STRENGTH` LTE row is always stored next to the `CELL_INFO` row (serving, no identity, age from `SignalStrength.getTimestampMillis()`), so SINR = 11 is kept. Both RSRP values are kept (-101 vs -103 here). `flat.csv` takes each serving field from CELL_INFO first, then SIGNAL_STRENGTH, with a `*Source` column (decision 001, addendum). Unit test: `moto g45 - RSSNR missing in CellInfo is kept from SignalStrength in its own row`. |
| CQI and TA always unavailable | `cqi=2147483647 ta=2147483647` in both CellInfo and SignalStrength | Null. Expected on this modem; not a parser bug. |
| CellInfo PLMN differs from registered PLMN (network sharing) | CellInfo: `mMcc=510 mMnc=01 IND INDOSAT`; ServiceState / NRI: `510-89`, `rRplmn=51089`, alpha `3` | Both recorded as reported: operator from ServiceState (Tri 510-89), cell PLMN from CellIdentity (510-01). Tri runs on Indosat's RAN (Indosat Ooredoo Hutchison). Use `session.mcc/mnc` for the subscriber's operator, `cell` PLMN for the broadcasting network. |
| Serving cell `connectionStatus = NONE` while data is connected | `mCellConnectionStatus=0`, `mDataConnectionState=2` | Recorded as NONE. Qualcomm doesn't fill it here; don't use it to pick the serving cell (we use `isRegistered`). |
| Only the PCell in CellInfo during CA | `mCellBandwidths=[10000, 10000, 20000]`, `isUsingCarrierAggregation=true`, one registered CellInfo | Secondary carriers only visible via `cellBandwidths`. The list changes between samples (`[10000, 10000, 20000]` live vs. `[20000, 10000, 10000]` in an earlier dump, with the PCell moving from EARFCN 1475 to 1625), so it is logged every tick. |
| Neighbor without identity | `mCi`, `mTac`, `mBandwidth` = MAX, `mMcc=null` | Null, no warning. Bands still reported (`[3]`). |
| `getBands()` populated | `mBands=[3]` on serving and neighbor | `bandSource = API`. |
| `overrideNetworkType = 1` (LTE_CA) with 4G icon | `TelephonyDisplayInfo` | Shown raw; not an NSA input. |
| `nrState` redacted | `nrState=****` in every `NetworkRegistrationInfo`, even in dumpsys | EN-DC state is not readable through the public API here; NSA detection relies on `TelephonyDisplayInfo` override + NR cells/signal. |
| Empty SIM slot reports its own state | Phone Id=1: `OUT_OF_SERVICE`, `mCellInfo=[]`, all-sentinel SignalStrength | We follow only the active data subscription (`createForSubscriptionId`), never "any" subscription. |

**Still to test on this phone:** 5G NSA area (does `CellInfoNr` appear, or only `SignalStrength.mNr`?), screen
off for several minutes (cached cell info), and an indoor/weak-signal case.

### Samsung A56, Telkomsel (510-10), LTE B1, 2026-10-13

Setup: SIM 1 Telkomsel, slot 2 empty, indoor static, phone on Wi-Fi (mobile data idle, `dataState = 0`), no 5G
(`isEndcAvailable = false`). Raw dump in `docs/samples/` (local only). No extraction failures, no crash.

**Samsung redacts cell identity in `dumpsys`**: `mCi`, `mPci`, `mTac`, `mEarfcn` print as `[****]`, so CI and TAC
cannot be cross-checked from the shell. PCI 140 / EARFCN 500 / band 1 / 20 MHz were confirmed through
`mPhysicalChannelConfigs` (dumpsys only, not a public API for us). Signal values matched CellInfo exactly.

| What was seen | Evidence | Handling now | Proposed |
|---|---|---|---|
| **Neighbors reported as SECONDARY_SERVING** | 3 extra cells with `mCellConnectionStatus=2`, `mRegistered=NO`, same EARFCN 500 as the PCell, while `isUsingCarrierAggregation=false`, `mCellBandwidths=[20000]` and one PhysicalChannelConfig | **Fixed:** a same-RAT SECONDARY_SERVING cell on the PCell's ARFCN, or with no CA shown (at most one LTE-sized cell bandwidth and no LTE_CA override), is stored as a neighbor (`connectionStatus = NONE`) with `SECONDARY_SERVING_WITHOUT_CA` (raw status kept). Applies to all manufacturers; NR under an LTE primary (EN-DC) is never touched. Confirmed on device after the fix. | – |
| Neighbor CI / TAC = 0 | App shows `0 / 0` for those cells (dumpsys redacted) | **Fixed:** null with `SAMSUNG_NEIGHBOR_PLACEHOLDER` (Samsung neighbors only) | – |
| Neighbor CQI 0, TA 0, RSSNR 0 | `cqi=0 ta=0 rssnr=0` on all three | **Fixed:** RSSNR 0 → `SAMSUNG_LTE_SNR_ZERO`; TA 0 and LTE CQI 0 → `SAMSUNG_NEIGHBOR_PLACEHOLDER` | – |
| Serving bandwidth unavailable in CellIdentity | `mBandwidth=2147483647` | Null; `cellBandwidths` from ServiceState has 20000 | – |
| SignalStrength differs a lot from CellInfo at the same moment | CellInfo serving: RSSI -79, RSSNR 5; SignalStrength: RSSI -103, RSSNR -4, `cqiTableIndex=1` | Both rows kept (decision 001 addendum) | Track over a drive; possibly different measurement windows |
| SignalStrength timestamp is old while values look current | SIGNAL_STRENGTH row age 39.5 s | Grayed out as stale (> 5 s) | Expected: the callback only fires on change. Keep the honest age; flag in analysis, not in the parser |
| CellInfo ages 3–7 s although origin is FRESH | Ages 3102 ms then 6977 ms | Shown as reported | Samsung may answer `requestCellInfoUpdate` with the last modem report. Check in milestone 3 with screen on/off |
| TAC byte swap | No `SAMSUNG_TAC_BYTE_SWAP` warning; TAC 11550 | Not triggered | Resolved later with the Tri SIM: no swap on this phone (see the A56 NR check below) |

### moto g45, Tri (510-89), 5G NSA (LTE B3 anchor + NR 531870), 2026-10-13

Captured twice with the status bar showing 5G (`overrideNetwork=NR_NSA`, override 3). Raw dumps in `docs/samples/`.
No parse warnings, no extraction failures. Reported vs. G-NetTrack Pro: "NR_NSA in the header, but no NR values".

**The NR data is not dropped. It is split across two sources, and neither one is labelled as the serving NR cell:**

| Source | What it holds | How NetLogger shows it now |
|---|---|---|
| `CellInfoNr` | `mRegistered=NO`, `mCellConnectionStatus=0` (NONE), PCI 479, NR-ARFCN 531870, `mBands=[]`, TAC 19350, NCI unavailable, **all signal fields unavailable** | Parsed (no cast or filter problem), but **as a neighbor** because it is neither registered nor PRIMARY/SECONDARY_SERVING: neighbor row "NR 479 531870 – –". Band null (`getBands()` empty, `BandMapper.None` until milestone 7). `nsaInputs.nrCellInfo = false`. |
| `SignalStrength.getCellSignalStrengths()` → `CellSignalStrengthNr` | SS-RSRP -101 / -103, SS-RSRQ -11 / -12, SS-SINR 17 / unavailable; CSI unavailable | Parsed: "NR serving · SIGNAL_STRENGTH" card with RSRP/RSRQ, **no PCI/ARFCN/band** (by design, decision 001 addendum). `nsaInputs.nrSignal = true`. |

Other observations:
- **NR leg comes and goes.** Within 1–2 minutes the phone switched between `NR_NSA` (5G icon) and `LTE_CA` (4G+) while
  idle on USB. When the SCG is released, `mNr` returns to all-unavailable and the NR card disappears. NSA NR is added
  with data activity; a field check with idle data will often see no NR.
- **NR TAC = LTE TAC (19350) and NR PCI = LTE PCI (479).** An NSA NR cell broadcasts no TAC, so the TAC is most likely
  copied from the LTE anchor by the modem (compare NetMonster `NrIdentityFromLtePostprocessor`, which handles the same
  pattern for the NCI on Xiaomi). The equal PCI may be real (co-sited planning); to confirm against G-NetTrack's NR PCI.
- **Band:** NR-ARFCN 531870 = 2659.35 MHz (ΔF = 5 kHz for N < 600000). That frequency lies in n41 (499200–537999),
  n90 (499200–537999) and n7 (524000–537999). `getBands()` is empty, so the band needs `BandMapper` with an operator
  preference: Indonesian 2.6 GHz NR is TDD → n41, as G-NetTrack shows.
- **SignalStrength LTE vs. CellInfo LTE** (same moments): RSRP -97/-97, -96/-97, -95/-97, -101/-103; RSRQ differs by up
  to 4 dB. No constant offset in these few pairs; recheck once sessions are stored (milestone 4).
- **SignalStrength age:** the callback fires only on change, so a stable signal has an age of tens of seconds (40.7 s seen).

**Fixed (2026-10-13), confirmed on the phone:**
- With override NR_NSA / NR_NSA_MMWAVE / NR_ADVANCED, a registered LTE cell and exactly one unregistered, non-serving
  NR cell, that cell becomes `SECONDARY_SERVING` with `NR_NSA_SECONDARY_INFERRED` (raw status kept). Several such NR
  cells → none labelled, `NR_NSA_SECONDARY_AMBIGUOUS`. `nsaInputs.nrCellInfo` is now true in this case.
- Its TAC is blanked when it equals the LTE serving TAC (`NR_TAC_FROM_LTE`, raw TAC kept). PCI is kept as reported
  (still to compare with G-NetTrack's NR PCI).
- Band from `BandMapper.Standard` (NR table from TS 38.101-1/-2): 531870 → n41, `bandSource = MAPPED`,
  `MULTIPLE_BANDS` with `[7, 41, 90]`.
- Signal stays in the separate `SIGNAL_STRENGTH` NR row; the Live screen shows both cards. SignalStrength rows show
  "Updated X s ago" and are no longer grayed out; the age stays in the data.

**Known limitation:** right after the app starts, the display override can be missing for a few seconds (seen:
`override=–` about 2 s after launch) until the first `onDisplayInfoChanged`; there is no getter to seed it. In that
window the NR cell stays a neighbor. Logging sessions are long, so this affects only the first ticks.

### Samsung A56, Tri (510-89), NR check, 2026-10-13

SIM swapped to Tri (subId 5, slot 1 / phone 0), camped on the same Indosat cell as the moto g45 (PCI 479, EARFCN
1475, B3). The phone is connected to ADB over Wi-Fi.

**No NR at all**, in every source: `overrideNetwork=NONE` (only short `LTE_CA` flips in the history, never `NR_NSA`),
no `CellInfoNr`, `mNr=Invalid`, `isNrAvailable/isEnDcAvailable=false`. The phone and SIM allow 5G
(`preferred_network_mode=26` = NR/LTE/GSM/WCDMA, carrier config `carrier_nr_availabilities_int_array=[1, 2]`), so this
is not a setting. **Why:** Wi-Fi is the default network; cellular only carries IMS (`dumpsys connectivity`:
`MOBILE[LTE] CONNECTED extra: IMS`, `mDataConnectionState=0`, `mDataActivity=4` dormant). With no cellular data
traffic the network never adds the NR leg. The moto on the same cell gets NSA because its mobile data is in use.
To test NR on the A56: Wi-Fi off and traffic on mobile data, which needs ADB over USB (Samsung vendor `04e8`
USB passthrough) or checks done by hand.

Other findings:
- **TAC byte swap: not present on this A56 (Android 16).** NetLogger reads CI 12866878 / TAC 19350 for the cell,
  identical to the moto g45 on the same cell (19350 = 0x4B96; a swap would read 38475), and no
  `SAMSUNG_TAC_BYTE_SWAP` warning. The correction stays in code for other Samsung models/firmware.
- **Cell bandwidths include 60000 kHz** (`[10000, 10000, 60000]`) while `isUsingCarrierAggregation=false` and one
  PhysicalChannelConfig (10 MHz). 60 MHz is not a valid LTE bandwidth, so this is either an NR carrier or a stale
  entry; the list is not reliable as a CA count on this phone. *Update (NSA capture below): the 60000 kHz entry is the
  NR n41 carrier (PhysicalChannelConfig: NR, 60 MHz), i.e. Samsung puts the NR SCG bandwidth into this list.* **Fixed:** for an LTE primary the SECONDARY_SERVING
  rule now counts only LTE channel bandwidths (1.4/3/5/10/15/20 MHz) as CA evidence, or the LTE_CA override.
- Operator name from ServiceState is the SIM's marketing name `3SinyalKuatHemat`; PLMN 510-89 as on the moto.

### Both phones in 5G NSA on the same site, Tri (510-89), 2026-10-13

Both on USB, mobile data in use, both `overrideNetwork=NR_NSA`, LTE anchors on the same site (PCI 479; moto on EARFCN
1475 / 10 MHz, A56 on 1625 / 20 MHz). Dumps: `docs/samples/*_tri_nsa-both_2026-10-13.txt`. No extraction failures,
no crash. The A56's `mPhysicalChannelConfigs` (dumpsys only) gives ground truth for the NR leg: **PCI 440, n41,
60 MHz, centre NR-ARFCN 532002** (2660.01 MHz).

| | moto g45 (Qualcomm) | A56 (Samsung) |
|---|---|---|
| `CellInfoNr` present | yes | yes |
| registered / status | NO / **0 (NONE)** | NO / **2 (SECONDARY_SERVING)** |
| NR-ARFCN (SSB) | 531870 | 531870 (via NetLogger; dumpsys redacts) |
| `getBands()` | `[]` | **`[7]`** (wrong: network config says n41; 60 MHz is not a valid n7 bandwidth) |
| PCI | **479 = LTE anchor PCI** (wrong: real NR PCI is 440) | 440 (matches PhysicalChannelConfig) |
| TAC | 19350 = LTE TAC (copied) | **0** (NSA NR has no TAC; Samsung placeholder) |
| NCI | unavailable | unavailable |
| SS-RSRP / RSRQ / SINR in CellInfoNr | all unavailable | -98…-100 / -10…-11 / 12–13 |
| CSI-RSRP / RSRQ / SINR in CellInfoNr | unavailable | **-140 / -20 / -23**: -20 and -23 are the floors of Android's CSI-RSRQ / CSI-SINR ranges, -140 is ~40 dB below SS-RSRP (the LTE RSRP floor): a "not measured" placeholder, not a reading |
| `CellSignalStrengthNr` in SignalStrength | yes: -100 / -11 / 18 | yes: -100…-104 / -10…-11 / 9–14, CSI unavailable, CSI CQI report `[10]` |
| `CellConfigLte.isEndcAvailable` | false (even in NSA) | false (even in NSA) |
| NetLogger today | NR CELL_INFO card: SECONDARY_SERVING (inferred), PCI 479, n41 MAPPED, TAC blank, no signal; NR SIGNAL_STRENGTH card with signal | NR CELL_INFO card: SECONDARY_SERVING (reported), PCI 440, **band 7 (API)**, **TAC 0**, SS signal, **CSI -140/-20/-23**; NR SIGNAL_STRENGTH card |

**Problems found, fixed 2026-10-13 and confirmed on both phones:**
1. moto: NR **PCI copied from the LTE anchor** together with the TAC (the A56 and its PhysicalChannelConfig show 440
   on the same NR carrier). → When an inferred NSA NR cell has both the LTE PCI and the LTE TAC, both are blanked
   (`NR_PCI_FROM_LTE`, `NR_TAC_FROM_LTE`). An equal PCI alone is kept.
2. A56: `getBands()` reports **n7 for an n41 carrier**. → When the reported band contradicts the country's known band
   for the ARFCN (510 → n41), the known band is used with `bandSource = MAPPED` and `API_BAND_CONFLICT` keeps `[7]`.
   Outside such countries the API band is kept.
3. A56: NR **TAC 0** on the NSA NR secondary. → Null with `NR_NSA_TAC_ZERO` (all manufacturers).
4. A56: **CSI placeholder** -140 / -20 / -23. → On Samsung, CSI-RSRQ -20 and CSI-SINR -23 with CSI-RSRP ≥ 20 dB
   below SS-RSRP: all three null with `SAMSUNG_NR_CSI_PLACEHOLDER`.

### NR availability over 3 minutes, both phones, moto SIM now with a data plan, 2026-10-13

Both phones on USB, mobile data as default network, same Tri site, idle (no download running). `dumpsys
telephony.registry` sampled every ~4.5 s for 3 minutes (36 samples each; log in `docs/samples/`, local only).

| | moto g45 | A56 |
|---|---|---|
| `overrideNetwork=NR_NSA` | 27 / 36 (75 %); one 36 s gap in `LTE_CA` | 36 / 36 (100 %) |
| During the gap | `mDataActivity=4` (dormant), `mNr` unavailable, no `CellInfoNr`: NR leg released | – |
| `CellSignalStrengthNr` SS-RSRP | -99 … -102 dBm | -104 … -107 dBm (same NR carrier, ~5 dB lower) |
| `CellInfoNr` | unregistered, status 0, no signal (unchanged) | unregistered, status 2, with signal (unchanged) |
| NR PhysicalChannelConfig (dumpsys only) | entry present but empty (no channel, PCI 0, band 0) | ch 532002, PCI 440, n41, 60 MHz |
| `getCellBandwidths()` | `[10000, 10000, 20000]` (LTE only) | `[10000, 10000, 60000]` (includes the NR carrier) |

- The data plan changes **how often** the moto has NR, not **how** it reports it.
- The moto releases the NR leg after ~30 s without data activity; the A56 keeps it while idle. NR availability in
  drive tests therefore depends on the phone's inactivity handling, not only on coverage: log `mDataActivity`-like
  context (TrafficStats deltas, milestone 3) next to the network type.
- **Screen off (moto):** with the screen off and NetLogger in the background, the registry's cell list lost
  `CellInfoNr` for ~45 s while `NR_NSA` and NR signal were still present; it came back as soon as NetLogger was in
  front and requested an update. For milestone 3: check whether `requestCellInfoUpdate()` from the foreground service
  returns NR with the screen off, or cached data without it (`cellInfoAgeMs`, `cellInfoOrigin`).
- The 5 dB SS-RSRP gap between phones on the same carrier is device-level (antenna, position, calibration): compare
  NR levels across phones only with the device recorded per session.

### Logging service, first screen-off run, moto g45, 2026-10-13 (milestone 3)

Plugged in via USB (charging, so **no Doze**: `deviceidle mState=ACTIVE`), screen off ~10 min, 1 s interval.
- 688 / 688 ticks (100 %), partial wake lock held for the whole session and released on Stop.
- Cell info: every request returned (`FRESH`), but with the screen off the serving cell's own timestamp lagged by up
  to **~10 s** (max per minute 9.0–10.0 s; with the screen on < 2 s). The modem refreshes measurements about every
  10 s when the screen is off. `cellInfoOrigin = FRESH` only means the request completed: use `cellInfoAgeMs` to judge
  staleness.
- GPS age with screen off ≤ 1 s (fused, high accuracy, held by the foreground service).
- NR ticks dropped to 0 in the screen-off minutes with idle data (NR leg released, as seen before).
- Battery current is positive while charging on this phone (+57678 µA); sign convention to confirm while discharging.
- App TrafficStats rx/tx = 0 (NetLogger itself uses no network yet).
- **Still to do:** the unplugged 1-hour test on both phones (Doze), see `docs/field-test-checklist.md`.

A56: service starts and runs (foreground, type location), but its lock screen could not be dismissed over adb, so
the screen-off run is pending.

### First unplugged screen-off run, both phones, 2026-10-13 (milestone 3)

Sessions started 23:02 (phone time), 1 s interval, screens off, unplugged until ~23:21 (**~20 min, not the
required hour**), static indoors.

| | moto g45 | A56 |
|---|---|---|
| Ticks at replug | 1200 / 1200 (100 %) | 1200 / 1200 (100 %) |
| Doze reached | unknown (batterystats history reset on replug) | **light Doze only** (23:05–23:11, 23:12–23:21); deep Doze never entered |
| Max serving-cell age per minute | ~10 s | ~10 s every minute |
| Max GPS age per minute | ≤ 1.1 s (5.6 s once) | 0.6–1.3 s; **7.5–7.7 s** in three minutes during light Doze |
| NR ticks | 52–60 per minute in the minutes kept | 60 → 0 after ~4 min screen off (NR leg released), back when replugged |
| Service, wake lock, crash | running, held, none | running, held, none |

- Light Doze did not cost ticks; the wake lock keeps the 1 s loop running. GPS fixes slow down to ~7 s in some
  minutes under light Doze on the A56 (fused provider batching), which `locationAgeMs` records.
- The moto's log buffer kept only the last 2 summaries (busy system log); the Live counter is the reliable total.
- Deep Doze (stationary, ~30+ min after screen off) is still untested: the full 1-hour run is needed.

### Second unplugged screen-off run, both phones, 2026-10-13/14 (milestone 3)

Sessions started 23:26 (phone time), 1 s interval, screens off, phones lying still indoors. Unplugged 23:26:30 →
00:12:20 = **~46 min** (short of the required hour).

| | moto g45 | A56 |
|---|---|---|
| Ticks (Live counter at replug) | **2820 / 2820 (100 %)** at 00:12:57 | **2829 / 2829 (100 %)** at 00:13:11 |
| Doze | **deep Doze (IDLE) 23:38:37 → 00:12:18 (~34 min)**, one maintenance window at 23:53 (`device_idle` events: SENSING → LOCATING → IDLE) | **light Doze only** (23:28–23:34, 23:36–23:49, 23:49–00:09, 00:10–00:12); never deep Doze |
| Max serving-cell age per minute | ~10 s (only the last summary survived in the log) | 9.98–9.99 s every minute |
| Max GPS age per minute | 1.3 s (last summary) | 0.55–0.80 s for 40 min, then 1.3–3.5 s in the last 8 min |
| NR ticks | 39 / 60 in the replug minute | 0 for the whole idle period |
| Service, wake lock, crash | running, held, none | running, held, none |

- Final totals after Stop (00:15): moto 2982 / 2982, A56 2982 / 2982 (100 %). Accepted as the milestone 3
  screen-off test (46 min unplugged instead of 60, with ~34 min of deep Doze on the moto); a full hour on a real
  drive is still to be checked.
- **Deep Doze did not cost a single tick on the moto**: the partial wake lock plus the location foreground service
  keep the 1 s loop running through IDLE.
- The A56 (Samsung, unrooted) never left light Doze while logging. Likely the active high-accuracy location request
  of the foreground service keeps it out of deep Doze; not a problem for logging, but battery use is higher.
- Cell data age stays at the ~10 s modem refresh with the screen off on both phones; `cellInfoAgeMs` records it.
- The moto's system log rolled over, so per-minute summaries were lost; persistence in milestone 4 removes the
  dependency on logcat.

### Samsung A56, Tri (510-89), field walk with the persistence build, 2026-10-14 (milestone 4)

Three walk sessions (148, 15 and 931 ticks, the long one 15.5 min with 836 ticks screen off), checked from the
pulled database and logcat. Raw database backed up in `docs/samples/` (gitignored, contains locations).

- **Recording:** 100 % of expected ticks in every session, max write 51 ms, all 6 markers (Live button and
  notification) stored with a location. The long session was force-stopped from Settings → App info; on the next
  app start it was closed as `RECOVERED_AFTER_KILL` with `endedAt` = last tick (05:15:51). Milestone 4 checks pass on
  the unrooted phone.
- **NR neighbors labelled as serving.** The A56 reports *every* NR cell on the NSA carrier (NR-ARFCN 531870) as
  unregistered `SECONDARY_SERVING`, same-channel neighbors included: PCIs 438, 161, 169 and 439 next to the real
  NSA secondary 440 (PhysicalChannelConfig). 115 ticks had two or more such cells. The SignalStrength NR values
  matched exactly one candidate within 1 dB (RSRP and RSRQ) in 42 of them; in the rest the two samples were too far
  apart in time. Fixed in the parser: the phone's label stays in `reportedServing`, the derived `servingInferred` and
  `servingSource` (`SIGNAL_STRENGTH_MATCH` / `STRONGEST` / `AMBIGUOUS`) pick one per channel, losers get a
  `NR_SERVING_*` warning. Replayed on this log: 42 by match, 73 by strongest, 0 ambiguous, and PCI 440 every time.
- **First tick of a session has no network MCC.** ServiceState arrives after the first CellInfo, so the country
  band rule could not replace the reported n7 and 3 rows were stored as band 7 (`API`). The registered LTE cell
  carries its MCC (510) already, so it now stands in; such bands get `bandSource = MAPPED_MCC_FALLBACK`.
- **LTE cell PLMN 510-01 while the operator is 510-89.** The registered LTE cell reports MNC 01 (Indosat) under the
  Tri SIM, consistent with IOH network sharing. Use `operatorNumeric` (measurement) for the operator, not the cell's
  MCC/MNC.
- **Warnings per tick:** about 8 on average, all flagged Samsung corrections (neighbor placeholders, LTE SNR 0, NR
  TAC 0, CSI floor, band conflict). These were counted in `parseErrors`, which made the data look broken;
  `parseErrors` now counts only getter failures (0 here) and `warningCount` holds the warnings.
- **NR with the screen off:** NR in 71 of 836 screen-off ticks vs. 85 of 95 screen-on ticks; screen-off traffic was
  small (~190 KiB), and the override agreed (`0` = LTE). Same pattern as the milestone 3 runs: the network releases
  the NR leg on low traffic.
- Cell data age ≤ 2 s with the screen on, up to ~10 s with it off (as before). GPS age > 5 s in 6 ticks (max 7.3 s).
- logcat kept only the last ~20 minutes; the database is the record.
- **Fix confirmed on the A56** (build `bc7afbb`, static session, 105 ticks, NSA): exactly one `servingInferred` NR
  cell per tick, always PCI 440 (82 `REPORTED`, 19 `SIGNAL_STRENGTH_MATCH`, 4 `STRONGEST`); neighbor 438 kept
  `reportedServing = true` with `NR_SERVING_*` warnings; first tick n41 `MAPPED_MCC_FALLBACK`; `parseErrors` 0.

### Samsung A56, dual SIM: Tri (slot 0, data) + Telkomsel (slot 1, voice/SMS), 2026-10-14

Two static sessions with both SIMs active (subId 5 = Tri, data; subId 1 = Telkomsel, default voice/SMS).

- **Only the data SIM is logged, nothing leaks from the other SIM.** NetLogger reads CellInfo, ServiceState and
  SignalStrength through `createForSubscriptionId(defaultDataSubId)`. Every tick had exactly one registered cell
  (Tri LTE PCI 479, EARFCN 1625, B3); Telkomsel was camped on EARFCN 1850 (registry, phone 1), which never appeared
  in our cells. Session metadata: subId 5, slot 0, operator 3SinyalKuatHemat, MNC 89.
- **Wi-Fi on = no NR.** With Wi-Fi as the default network, the Tri data connection was down (`dataState` 0, only
  the IMS PDN up), override `0`, LTE CA only (`[20000,10000,10000]`, SCell on EARFCN 1475 kept as serving): the network
  adds the NR leg only for mobile data traffic. NetLogger and the registry agreed. For NR tests, turn Wi-Fi off.
- **Wi-Fi off:** 81/81 ticks NR_NSA (override 3, `dataState` 2), bandwidths `[20000,10000,60000]` as in the
  registry. NR serving selection: PCI 440 in every tick (67 `REPORTED`, 13 `STRONGEST` against neighbor 438 on the
  same NR-ARFCN, first tick `MAPPED_MCC_FALLBACK`); never more than one serving NR cell per NR-ARFCN. `parseErrors` 0;
  warnings are the known Samsung corrections (11.5 per tick on average).
- **The data SIM is chosen once per session.** `RadioSampler` resolves the default data subscription when the
  session starts. If the user switches the data SIM during a session, NetLogger keeps logging the first SIM and
  nothing marks the switch. Handled since 2026-10-14 (approved): the session stays on its SIM, every tick after a
  switch has a `DATA_SIM_CHANGED` warning, a `DATA_SIM_CHANGED` event marks each switch, and Live shows a notice.
- **Data-SIM switch confirmed (2026-10-14, 08:35–08:38, 176 ticks, 100 %):** session on Tri (subId 5); data switched
  to Telkomsel at 08:38:01 and back at 08:38:22. Two `DATA_SIM_CHANGED` events (5 → 1, 1 → 5) with location at the
  exact ticks; one `DATA_SIM_CHANGED` warning row with 21 occurrences (the 21 ticks on Telkomsel data). All 176
  ticks stayed on Tri (operator 51089, no cell from Telkomsel's EARFCN 1850). While Telkomsel carried data, Tri's
  `dataState` was 0 and NR dropped to LTE within the 21 ticks; back on Tri, data and NR_NSA returned. `parseErrors` 0.
  A switch takes effect only after confirming it in Settings → SIM manager → Mobile data; the first attempt in
  this test did not change the default data SIM (confirmed in `dumpsys isub`).
- Not tested yet: a whole session with data on the Telkomsel SIM.

### Map (milestone 6), both phones, 2026-10-14

- moto: live track, tap card (NR cell `NSA_INFERRED`, PCI blank as copied from LTE, NR RSRP from SignalStrength
  marked "(SS)", NR SS-SINR gray: the moto reports none), Show on map.
- A56: live growth (44 → 58 → 63 → 98 ticks), Follow on/off (with Follow off the view stays where it was panned),
  Network metric (NR_NSA purple), tap card (NR PCI 440 n41 `REPORTED`), marker, Show on map for the stopped session
  (126/126 ticks). OSM tiles load over mobile data on both.
- Not seen yet: a moving track framed over a longer route, and the map in airplane mode (no tiles).

### Samsung A56, Tri, first field drive, 2026-10-14 (session 6, 09:00–09:15)

893/893 ticks (100 %), longest gap 1.02 s, max write 55 ms, 0 parse errors, screen off 790 ticks, battery 85 → 84 %,
35 °C. GPS: fix in every tick, 8.9 m average accuracy; the first 6 ticks (status WAITING) carried a cold-start fix up to
317 m. Route 1.78 km, but ~9.5 min standing still and ~3.5 min driving at 25–29 km/h: not yet the 1-hour drive.

- LTE: 14 cells, 15 handovers, bands 1, 3, 8. NR serving selection: at most one serving NR per NR-ARFCN in every
  tick; 223 `SIGNAL_STRENGTH_MATCH`, 112 `STRONGEST` picks, none ambiguous.
- NR only in minutes 0–2 and at the end (standing still); released ~1 min after screen off with little traffic, no NR
  while driving (same pattern as the screen-off runs).
- **Stale NR in SignalStrength.** After the release, SignalStrength kept its last NR value (-104/-11/9) without
  updates, aging up to 110 s (56 s average), while the override showed no 5G and CellInfoNr was gone. Our NSA rule
  counted it: 110 ticks were NR_NSA without the 5G icon (103 override 0, 6 LTE_CA, 1 unknown), and flat.csv / map
  showed a 10–110 s old NR RSRP in 100 ticks. Fixed (decision 001, stale NR addendum): such an entry is
  `NR_SIGNAL_STRENGTH_STALE` (kept, not serving, not NR evidence, not a flat.csv fallback). Replayed on this drive:
  100 of the 110 ticks become LTE; 10 stay NR_NSA (7 within 10 s of the release, 3 with an NR cell in CellInfo).
- LTE SignalStrength also aged up to 110 s with the screen off (168 ticks > 10 s), but flat.csv used it only 25 times
  on the A56, never older than 10 s (A56 CellInfo has LTE RSSNR). Later handled as `LTE_SIGNAL_STRENGTH_STALE` (see
  the moto screen-off drive below).

### Both phones, screen-off drive, Tri (510-89), 2026-10-14 (10:01–10:24; moto session 3, A56 session 7)

Both phones unplugged, screen off for most of the drive (moto 1218 / 1411 ticks, A56 1329 / 1389), 9.5 km, moving
(> 2 m/s) in ~1160 ticks. Analyzed from the databases (backup `docs/samples/db-backup-2026-10-14-evening/`).

- **Ticks:** 100 % on both (moto 1411 / 1411, A56 1389 / 1389), max spacing 1.005 / 1.014 s, cell info always
  FRESH, GPS fix in every tick (age ≤ 2 s). No markers. Battery moto 99 → 97 %, A56 85 → 84 %.
- **CellInfo with the screen off:** serving LTE age median 5 s (moto) / 4 s (A56), never over 10 s.
- **moto: LTE SignalStrength goes stale with the screen off.** Age median 34 s, p90 108 s, max 212 s; 1010 of 1218
  screen-off ticks over 10 s (screen on: median 5 s, max 56 s). The moto's CellInfo has no LTE RSSNR, so **flat.csv
  `lteSinr` on the moto is mostly a 10–200 s old value with the screen off** (age in `lteSignalStrengthAgeMs`).
  RSRP/RSRQ come from CellInfo and stay fresh. **Fixed** (decision 001, stale LTE addendum): an LTE SignalStrength entry
  older than 10 s is `LTE_SIGNAL_STRENGTH_STALE` (kept, not serving, no flat.csv / map fallback). Replayed: 1063 of
  1410 ticks lose `lteSinr` on this drive, 314 of 1622 on the screen-on drive at 16:02.
- **A56: LTE SignalStrength stayed fresh** this time (median 1 s, max 15.5 s with the screen off), unlike the mostly
  stationary drive at 09:00 (up to 110 s). LTE RSSNR is in CellInfo anyway.
- **5G:** A56 none (LTE, override LTE_CA in 233 ticks). moto 134 NR_NSA ticks, 40 with the 5G icon. The other 94 had
  a fresh (< 10 s) NR SignalStrength entry and the unregistered CellInfoNr, but override 0: kept as NR_NSA by the
  rule; 111 older NR entries were marked `NR_SIGNAL_STRENGTH_STALE` and did not count.
- **CQI:** moto none (CellInfo or SignalStrength), A56 present.
- **moto: wrong `getBands()` right after a handover.** In 197 rows of the registered LTE cell (all moto sessions of the day), getBands() still held the
  previous cell's band (EARFCN 75 / 200 with band 3 or 8, 1475 / 1625 with band 1, 3652 with band 3), always the
  registered cell, never a neighbor. Fixed in milestone 7: the EARFCN settles the band (`API_BAND_CONFLICT`, MAPPED).
- **Map:** the moving track draws on the moto (1411 / 1411 points with GPS). Zoomed out, the dense points' dark
  outline makes the line look almost black; colors are clear when zoomed in.

### Both phones, NetLogger vs. G-NetTrack Pro on the same phone, Tri (510-89), drive, 2026-10-14 (16:02–16:29)

Each phone ran NetLogger (1 s) and G-NetTrack Pro at the same time, **screen on, both apps in the foreground**,
unplugged. Not the screen-off test. G-NetTrack rows were matched to the NetLogger tick of the same second
(moto 712 pairs, A56 680).

- **Ticks:** NetLogger moto 1623 / A56 1596 ticks, 100 % of expected, max spacing 1.007 s, cell info always FRESH.
  G-NetTrack wrote a row every ~2–3 s, with 21 / 22 gaps of 5 s or more (longest 36–38 s at the start).
- **5G share:** moto NetLogger 93.1 % NR_NSA vs. G-NetTrack 91.9 % 5G; A56 98.2 % vs. 96.8 %. Network type agrees in
  all but 6 / 6 matched rows (transitions).
- **Values agree; both apps read the same API values.** moto: LTE PCI/EARFCN/RSRP/RSRQ/SINR/RSSI/TA, NR SS-RSRP,
  SS-RSRQ and SS-SINR identical in 91–99 % of pairs, median difference 0. A56: identical in 66–96 % (NR SS-RSRP 66 %,
  p90 |Δ| 5 dB): the A56's values change about every 2 s, so a one-second offset between the two apps shows up.
- **G-NetTrack hides the same staleness.** moto LTE SINR comes only from SignalStrength (age median 3.8 s, max 51.6 s
  even with the screen on). NR SS-RSRP on the moto changes every 6 s (median), longest 47 s, in **both** apps. G-NetTrack
  has no age column, so its values only look steadier.
- **CQI:** moto reports none (both apps). A56: NetLogger has a CQI from CellInfo in every tick, changing every ~2 s
  (values 4–14). G-NetTrack shows 8 in 510 of 682 rows and changed only 13 times: it holds a stale CQI.
- **NR band: G-NetTrack says N7, NetLogger n41 (`MAPPED`).** NR-ARFCN 531870 lies in n7, n41 and n90; the A56's
  physical channel config (2026-10-13) said n41, 60 MHz, and 60 MHz is not a valid n7 bandwidth. G-NetTrack is wrong
  on both phones (on the A56 it apparently takes `getBands()` = [7], which NetLogger flags `API_BAND_CONFLICT`).
- **NR PCI:** moto's CellInfoNr has no usable NR PCI (NetLogger: `NR_PCI_FROM_LTE`, nrPci empty). G-NetTrack shows the
  LTE anchor's PCI as the NR PCI, so its 40 moto "5G→5G handovers" are LTE anchor changes. A56: NR PCI agrees in 89 %
  of pairs (NetLogger's serving NR choice, `SIGNAL_STRENGTH_MATCH` / `STRONGEST`).
- **NR PLMN on the A56:** G-NetTrack labels the NR cells 510-10 (Telkomsel, the voice SIM in slot 1) with LAC 0;
  NetLogger's NR cells have no MCC/MNC from the API. Probably G-NetTrack filling in from the other SIM; not verified.
- **Invalid values:** G-NetTrack wrote LAC 65535 and empty cell IDs (moto 16:15:38); NetLogger stores null.
- **Events:** G-NetTrack logged 76 (moto) / 50 (A56) events. NetLogger's flat.csv has 52 / 87 LTE PCI changes, 0 / 106
  NR PCI changes and 33 / 6 network-type changes, not yet written as events (milestone 7). A56 NR PCI changes include
  NetLogger's serving pick moving between NR cells on the same ARFCN; G-NetTrack logs fewer (39 5G→5G).
- Battery: moto 96 → 93 %, A56 51 → 47 % over 27 min with both apps and the screen on.

### moto g45, PCAPdroid capture: UDP vs. TCP stream, 2026-10-14 (milestone 10)

PCAPdroid 2.0.2, VPN mode (records ≤ 10000 bytes = PCAPdroid's VPN MTU; LINKTYPE_RAW, snaplen 32768). The "root"
prompt the user saw was PCAPdroid's own consent dialog; NetLogger's request now also says `root_capture=false`.

- **UDP exporter loses packets under load.** A 10 MB download (session 8): PCAPdroid counted 10,779 packets / 11.2 MB
  dumped, NetLogger received 5,813 / 5.9 MB (~46 % lost). The socket kept Android's 224 KB default receive buffer
  (rmem_max allows 16 MB on the moto, 32 MB on the A56). A small capture (331 packets) arrived complete. UDP's arrival
  delay also varied by up to 1.5 s (PCAPdroid queues and sends in batches), so arrival time is no packet clock.
- **TCP exporter: nothing lost.** Session 10, 3 × 10 MB download (curl from the shell, Cloudflare): NetLogger
  5,483 packets / 30,388,956 bytes = PCAPdroid 5,483 / 30,388,956 bytes dumped; file valid to the last byte,
  timestamps monotonic. Wall-clock offset START vs. STOP: 0.6 ms apart.
- **Capture overhead on throughput: none measurable.** Same spot, 5G NSA, LTE CA 10+10+20 MHz, LTE RSRP -97/-98,
  NR -96, logging on in both sets:

  | 10 MB download | run 1 | run 2 | run 3 | mean |
  |---|---|---|---|---|
  | capture off | 0.762 s, 13.1 MB/s | 0.731 s, 13.7 MB/s | 0.819 s, 12.2 MB/s | 0.771 s, 13.0 MB/s (104 Mbit/s) |
  | TCP capture on | 0.741 s, 13.5 MB/s | 0.880 s, 11.4 MB/s | 0.719 s, 13.9 MB/s | 0.780 s, 12.9 MB/s (103 Mbit/s) |

  Difference ~1 %, inside the run-to-run spread (0.72–0.88 s). Short downloads only; a long capture under sustained
  load is still to check.
- A second capture in the same session overwrote the first one's capture_sync.csv: one capture per session now.

### Samsung A56, PCAPdroid TCP capture, 2026-10-14 (milestone 10, session 13)

Unrooted; PCAPdroid 2.0.2 asked for consent per start ("Once"), no root prompt. No curl in the A56 shell: the
10 MB download ran over plain HTTP with toybox `nc` (Cloudflare `__down`), timed from request to the last byte on
the device. Same spot, 5G NSA, LTE 20+10 MHz + NR 60 MHz, LTE RSRP -91/-92, NR -96…-102, logging on:

| 10 MB download (HTTP) | run 1 | run 2 | run 3 | mean |
|---|---|---|---|---|
| capture off (session 12, 21:12) | 0.782 s | 0.788 s | 0.747 s | 0.772 s, 13.0 MB/s |
| capture off (session 13, 21:18) | 0.837 s | 1.097 s | 0.852 s | 0.929 s, 10.8 MB/s |
| TCP capture on (session 13, 21:20) | 0.751 s | 0.733 s | 0.830 s | 0.771 s, 13.0 MB/s |

No slowdown from the capture; the spread between runs (0.73–1.10 s) is larger than any difference.
Packets: NetLogger 6,053 / 30,418,158 bytes, PCAPdroid 6,052 / 30,418,058 bytes dumped (`missing=-1`); session 12's
short capture: 2,449 vs. 2,446. NetLogger gets slightly *more*: PCAPdroid reads its counters when the capture stops,
just before its last packets are dumped. A positive `missing` would be loss. File valid to the last byte, max record
10,000 bytes (VPN MTU), timestamps monotonic; wall-clock offset START vs. STOP 0.14 ms apart.

### moto g45, schema v1 → v2 migration and capture card, 2026-10-14 (milestones 8, 10)

Backup first (`docs/samples/db-backup-2026-10-14-pre-v2/`), then the milestone 10 build: `user_version` 2, integrity
ok, all rows kept (5 sessions, 3246 measurements, 21087 cells, 6 events, 95 warnings). PCAPdroid 2.0.2 is installed on
both phones. The capture card shows while logging (test session #6, 2026-10-14 20:37, moto in landscape); a real
capture still needs the user to answer PCAPdroid's consent and VPN dialogs.

### Samsung A56, schema v1 → v2 migration on the phone, 2026-10-14 (milestone 8)

Installed the v2 build over the field database (backup first: `docs/samples/db-backup-2026-10-14-pre-v2/`). Room logged
"DB version upgrading from 1 to 2"; afterwards `user_version` 2, integrity ok, all rows kept (8 sessions, 4515
measurements, 21970 cells, 6 events, 850 warnings), `throughput_test` present and empty. moto g45: not yet (it dropped
off USB before the install).

### moto g45, native capture (VpnService + zdtun), first run, Tri (510-89), 2026-10-15 (milestone 11, step 3)

Started from the debug-only `NativeCaptureDebugActivity` (no logging session), all apps, MTU 1500. The system VPN
consent dialog ("Connection request") appeared once; the user tapped OK.

- **Network:** mobile data (network 120, `3gprs`) is IPv4 only (`10.129.85.28/29`, DNS `10.0.37.36`, `114.5.9.87`);
  the IPv6 address belongs to the IMS network. So the tun routed IPv4 only and used the operator's DNS servers.
- **VPN network as apps see it:** `Transports: CELLULAR|VPN`, underlying network 120, metered (`setMetered(false)`
  = like the network underneath). The speed test's mobile-data guard (`TRANSPORT_CELLULAR`) still passes.
- **Process:** `id.my.pamslab.netlogger:vpn` (separate PID from the main process).
- **Traffic:** `curl` 3 × 10 MB from speed.cloudflare.com: 1.062 / 0.777 / 0.826 s; `generate_204` and
  `example.com` fine. zdtun logs `TCP send error[32]: Broken pipe` at the end of each download (curl closed first;
  harmless).
- **Counters at stop:** tun rx 13,466 packets / 548,902 B, tx 20,668 / 30,904,739 B; pcap 34,134 = rx + tx, dropped
  0; connections TCP 8, UDP 10; protect failures 0; tun write errors 0; 3 TCP segments of connections older than the
  capture (not forwarded); 8 unparsed.
- **pcap file** (31,999,809 B = 24 + 34,134 × 16 + packet bytes, exact): independent Python check: nanosecond
  magic, link type 101, 34,134 records, no timestamp going backwards, 0 bad IPv4 header checksums, 0 bad TCP
  checksums (5,000 checked), all records ≤ 1,500 B.
- **The 8 unparsed packets** are IPv6 control packets the kernel sends on the new interface (4 ICMPv6, 4 MLD with a
  hop-by-hop header) although IPv6 isn't routed: captured, not forwarded.
- **Clock:** wall − boot offset measured to ±104 ns; first packet at elapsedRealtimeNs 143,981,237,061,300 =
  0.23 ms after the capture start.
- **Stop:** the loop ended 49 ms after the stop request; the VPN network disappeared.

### moto g45, native capture: app per connection, Tri (510-89), 2026-10-15 (milestone 11, step 4)

`getConnectionOwnerUid()` resolved the app of all 14 TCP/UDP connections (`uidUnknown 0`): shell `curl`
(uid 2000), Google Play services DNS, QUIC (UDP 443) and port 5228 (uid 10196, shared uid listed as
`com.google.android.gms|com.google.android.gsf`), Play Store. `ping` appears as one ICMP row without uid (the
API has no ICMP). uid 1051 (the system DNS resolver, no package) opens TCP 853 (DNS over TLS, "automatic" Private
DNS) to the operator's DNS servers: 4 packets out, none back, so Tri's resolvers don't offer DoT and plain DNS
(UDP 53) is used. curl downloads end with `SOCKET_ERROR` / errno 32 (EPIPE, curl closed first).

### moto g45, native capture from the capture card, Tri (510-89), 2026-10-15 (milestone 11, step 5)

Static sessions 11–14, started and stopped through NetLogger's own UI (adb taps). The VPN consent was given in step
3, so no dialog appeared.

- **Start:** the main process recorded CAPTURE_STARTED 50 ms after the `:vpn` process reported its first counters.
  `wallMinusElapsedNs` was identical to the nanosecond in step 3 (1,791,872,176,837,475,483): Android keeps the
  realtime − boottime offset as one kernel value, and the best of 5 reads finds it exactly. It changes only when the
  wall clock is set or slewed (later captures: …509, then …414,735 at STOP).
- **CHECK rows** (every 60 s) read the wall clock in milliseconds, so they differ from START by up to 1 ms without any
  clock change (seen: −0.55 ms).
- **Stop from the card** (session 11): VPN stopped in 53 ms; pcap 34,325 = expected, dropped 0; 14 connections, all
  with an app. **Session end** (session 14): CAPTURE_STOPPED SESSION_END 0.2 s after Stop, VPN gone.
- **Capture process killed** (`am crash` of `:vpn`, sessions 12 and 13): logging went on (session 12: 130/130
  ticks), CAPTURE_STOPPED PROCESS_DIED after 5.6 s, VPN gone, pcap valid to the end (6,317 records, the last flush
  came before the crash). The first try left connections.csv empty (buffered rows died with the process): fixed,
  header written at once and rows flushed at most 1 s after a connection ends. Rows of connections still open at a
  crash are lost. Android keeps a dead `NativeCaptureService` record (`app=null`, `stopIfKilled=true`): not restarted.

### moto g45, native capture throughput, Tri (510-89), NR NSA, static, 2026-10-15 (milestone 11, step 6)

`curl` on the phone (shell uid), capture off and native capture alternating per round; every native run's pcap
held all packets (dropped 0, pcap = expected).

| Test | Capture off | Native, MTU 1500 | Native, MTU 10 000 |
|---|---|---|---|
| 10 MB download, speed.cloudflare.com, 3 rounds | 14.3 MB/s | 11.4 MB/s (−20 %) | – |
| 5 MB upload (POST), speed.cloudflare.com, 3 rounds | 1.68 MB/s | 1.55 MB/s (−8 %) | – |
| 20 MB download, speed.cloudflare.com, 3 rounds | 19.4 MB/s | 14.5 MB/s (−25 %) | 17.9 MB/s (−8 %) |
| 20 MB HTTP range download, kartolo.sby.datautama.net.id (Surabaya), 4 rounds, batched tun reads | 17.3 MB/s | 13.0 MB/s (−25 %) | 16.8 MB/s (−3 %) |

- **Why MTU matters:** zdtun reads one MSS-sized segment per readable socket per loop round, so every downstream
  packet costs a `select()` + `recv()` + tun write. MTU 10 000 means ~6.8× fewer, larger packets (pcap: ~3,950
  instead of ~21,700 records for 20 MB). Reading up to 64 waiting tun packets per round (the app's ACKs) did not
  change the MTU 1500 result. With MTU 1500 the forwarder tops out around 13–15 MB/s (105–120 Mbit/s) on this phone;
  slower links should lose little. Hence the capture card setting: "Realistic packets (MTU 1500)" (default, D4) or
  "Fast (MTU 10 000)"; the MTU is stored in CAPTURE_STARTED and capture_sync.csv.
- **Cloudflare rate limit:** after ~25 large downloads within minutes, `__down?bytes=…` answered HTTP 429 (1 byte)
  for 10 and 20 MB. Check the HTTP status in scripted tests; the Surabaya mirror (plain HTTP, `Range`) worked.
- **Debug captures from adb** (`NativeCaptureDebugActivity`) are marked standalone: without that, the main process
  stopped them within 10 ms as "capture without a session" (the safety rule for a restarted main process).

### Samsung A56, native capture, Tri (510-89), 2026-10-15 (milestone 11, step 6)

First native capture on the unrooted phone (Android 16), started by the user from the capture card: the system VPN
consent dialog appeared, Cancel was tapped first (the card showed the error, no files left), then OK. Browser and
YouTube for ~3 min; the session was then stopped (CAPTURE_STOPPED SESSION_END).

- pcap 11,735 = expected, dropped 0; independent check: valid, timestamps in order, 0 bad checksums; 9,729 UDP
  packets (YouTube's QUIC), 1,630 TCP, 366 ICMP.
- 138 connections, every TCP/UDP uid resolved (`uidUnknown 0`); clock offset ±117 ns; CHECK rows every 60 s.
- **Package names were missing** for most uids (YouTube = uid 10279 showed no `app`): Android 11+ package
  visibility hides other apps' names. On the moto this went unnoticed because the apps seen there (shell, Google Play
  services, Play Store) are always visible. Fixed with a `<queries>` entry for launcher apps (no permission):
  `dumpsys package queries` then lists 132 packages visible to NetLogger, YouTube, WhatsApp, Instagram, ChatGPT,
  Gmail and OneDrive among them. Packages without a launcher icon (e.g. `com.facebook.services`, uid 10075) keep only
  their uid; only `QUERY_ALL_PACKAGES` (restricted on Google Play) would name them.
- The capture card still had "PCAPdroid → NetLogger" selected from milestone 10, so the first start was a PCAPdroid
  capture: NetLogger 9,434 vs. PCAPdroid 9,433 packets (missing −1, as expected).
- The A56's mobile data (`3gprs`) is IPv4 only, like the moto's: the IPv6 path is untested on both phones.
- **Another VPN takes over** (user started a capture in the PCAPdroid app during a native capture): Android called
  `onRevoke`, the loop ended `TUN_CLOSED` 0.1 s later, CAPTURE_STOPPED VPN_REVOKED; pcap 2,056 = expected, 1 tun write
  error (a packet on its way when the tun closed); logging went on.
- **Native start while PCAPdroid captures** (started in PCAPdroid itself): the first build showed the system VPN
  dialog again, and OK would have ended PCAPdroid's capture silently. Now refused with "Another VPN is active (e.g.
  PCAPdroid): stop it first" (any network with `TRANSPORT_VPN`; Android doesn't say whose); checked on the A56: no
  dialog, no files.

### moto g45, native capture with the screen off, Tri (510-89), 2026-10-15 (milestone 11, step 6)

Drive session 16 (static on the desk, USB connected), native capture MTU 1500, all apps; the user turned the screen
off; a 5 MB HTTP range download every 2 min (17 in total) from the Surabaya mirror.

- Logging: 2,146 of 2,146 expected ticks (100.0 %) over 2,145 s, longest gap 1.00 s; 1,840 ticks with the screen off
  (30.7 min). `dumpsys power` showed `Awake` for ~5 min in the middle (23:46–23:51 UTC); NetLogger's screen state says
  the display stayed off for most of that.
- Capture: all 17 downloads complete (HTTP 206, 5,000,000 B, 3.9–6.6 MB/s with the screen off), VPN up the whole
  time; pcap 109,780 = expected, dropped 0; 178 connections, every uid resolved.
- USB power keeps the phone out of deep Doze, so this is not the unplugged case (milestone 3 covered that for logging
  alone).

### Speed test server (LibreSpeed) and Tri's data cutoff, moto g45, 2026-10-15 (milestone 8)

**Server probe** (from the VM, `curl`): the user's LibreSpeed (Docker, standalone mode, Apache 2.4 / PHP) serves
everything under `/backend/`; the root paths answer 404.

| Endpoint | Result |
|---|---|
| `GET /backend/garbage.php?ckSize=N` | 200, `application/octet-stream`, N MiB (ckSize=1 → 1,048,576 B; 100 → 104,857,600 B); `Cache-Control: no-store` |
| `POST /backend/empty.php` (upload) | 200, empty body; 10 MB accepted |
| `GET /backend/empty.php` (ping) | 200, empty; keep-alive (later requests reuse the connection) |
| `GET /backend/getIP.php` | 200 JSON `{processedString, rawIspInfo}`; `?isp=true` adds ISP details (ASN, country). Not used by NetLogger |

So `SpeedTestConfig.libreSpeed(base)` (ping/upload `empty.php`, download `garbage.php?ckSize=100`) fits unchanged. The
server is plain HTTP: allowed only for its host via the generated network security config
(`netlogger.cleartextHosts` in local.properties; nothing VPS-specific in the repo). From the VM the server delivered
only ~1.2–2.2 MB/s down and ~0.9 MB/s up, and the phone's first test (09:40) 4.3 Mbit/s down, 12.6 Mbit/s up
(ping 118 ms) — far below the ~140 Mbit/s the same phone gets from the Surabaya mirror, so the server's bandwidth or
route limits the result. That first phone result is doubtful anyway, see below.

**Tri cut the moto's data off** during these tests (most likely the quota, after ~1 GB of capture and throughput
tests this day): every plain-HTTP request — the LibreSpeed server, the mirror — got `302 Location:
https://bimatri.ioh.co.id/` (IOH's bima+ portal) within 0.1 s, and every TLS handshake (speed.cloudflare.com,
cloudflare.com, google.com) was reset right after the TCP connect. The same with and without the native capture; from
the VM all of these answered normally. NetLogger then recorded ping/download "timeout" or "Connection reset" and an
upload of 35 Mbit/s into the portal.

**Fixed:** the speed test followed redirects (OkHttp's default), so a captive portal could be measured as the server.
Redirects are now never followed; a 3xx is the phase's error, `HTTP 302 (redirect to bimatri.ioh.co.id)`. Results of
this phone from before the fix on 2026-10-15 09:40–09:42 (session of that time) should be treated as invalid.

### PCAPdroid bridge removed, 2026-10-15 (CLAUDE.md 1.7.0)

At the user's request NetLogger now captures only with its own VPN. The PCAPdroid findings above (milestone 10) stay
for the record and for reading old sessions. PCAPdroid can still run as a separate app, but not at the same time as
a NetLogger capture (one VPN at a time; NetLogger refuses to start while another VPN is active).

### moto g45: NetLogger data lost and partly restored, 2026-10-15 11:00

Running `:app:connectedDebugAndroidTest` (launcher icon previews) uninstalled NetLogger afterwards, as AGP does for
the app module, and deleted its data. Restored from a database copy taken at 07:02 for the screen-off check:
sessions 1–16 (integrity ok, schema 2). **Lost:** session 16 after 07:02 (it ends there now) and its
CAPTURE_STOPPED event; session 17 (09:40, speed test, invalid anyway); all capture files of sessions 7–16 (copies
exist only of session 12's pcap and two debug captures, `docs/samples/m11/`); the speed test and capture settings.
App-module device tests are no longer used for this (the previews come from a debug-only activity).

### Samsung A56, speed test with working data, Tri (510-89), static, 2026-10-15 17:02 (milestone 8, session 23)

First complete in-app speed test. Wi-Fi off, data on Tri (subId 5, IPv4 `rmnet0`, MTU 1500); build `f973dc1`,
LibreSpeed URLs `…/backend/empty.php` and `garbage.php?ckSize=25`. Three runs in one session, 4 streams each:

| Row | Target | Capture | Radio during the phase | Ping avg / jitter | Download | Upload |
|---|---|---|---|---|---|---|
| 7–9 | LibreSpeed (own server) | off | NR_NSA | 159 / 120 ms | 196.6 Mbit/s (209.7 MB in 8.5 s) | 20.4 Mbit/s |
| 10–12 | Cloudflare (public, secondary) | off | LTE | 80 / 9 ms | 28.8 Mbit/s | 21.8 Mbit/s |
| 13–15 | LibreSpeed (own server) | native, MTU 1500 | LTE | 165 / 147 ms | 49.5 Mbit/s | 26.7 Mbit/s |

- The download of the first run stopped at the 200 MiB cap after 8.5 s, as intended. The three runs used ~390 MB.
- `captureActive` is 0 for rows 7–12 and 1 for rows 13–15; `serverUrl` is stored per row. The capture's pcap has
  93,374 packets / 101.3 MB = expected, 0 dropped; its 95.8 MB on the card match the test's 61.9 + 33.4 MB.
- The phone fell from NR NSA to LTE between the first and the second run (no NR leg in CellInfo or SignalStrength), so
  the drop to 28.8 / 49.5 Mbit/s is the radio, not Cloudflare or the capture. Compare speed tests only together with
  the network type logged during each phase (`measurement` rows between `startedElapsedNs` and + `durationMs`).
- Ping to the own server has a high jitter (120–147 ms) on both radios; Cloudflare's 9 ms. The server or its route,
  not the phone (the moto's first test also saw 118 ms).
- The earlier VPN consent was still granted: the capture started without a dialog.

### Both phones, Settings pages and Phone capabilities, 2026-10-16 05:25–05:33 (decision 003, step 6)

New SIMs: the A56 has Telkomsel (510-10, LTE only here, subId 1); the moto has two Tri SIMs (3 + 3), data on
slot 1 (subId 3). Wi-Fi off on both. Branch `feat/settings-ux`.

- **Mobile data vs. IMS:** the moto has two cellular networks up: IMS (`rmnet_data1`, IPv6 only, capability IMS) and
  internet (`rmnet_data2`, IPv4 only, `10.129.x.x/30`, MTU 1430). Phone capabilities reads only the network with
  NET_CAPABILITY_INTERNET and shows "IPv4" (an earlier version took the IMS one and showed IPv6). The A56 on
  Telkomsel has IPv4 + IPv6 on one interface (`rmnet0`, MTU 1500).
- **Phone capabilities, moto (NR_NSA, LTE B3 + NR 531870 n41):** NR from both CellInfo and SignalStrength; NR PCI
  Missing (0 % of 4309 recorded NR rows: the copied LTE PCI is dropped, `NR_PCI_FROM_LTE`); NR band mapped; LTE CQI
  never reported (0 % of 6739); LTE RSSNR in 66 %, TA in 78 %, NR SS-SINR in 54 % of recorded rows.
- **Phone capabilities, A56 (Telkomsel, LTE B1 with CA 20 + 10 MHz, icon LTE_CA):** all LTE fields available,
  including CQI (100 %); neighbour cells in 59 % of recorded rows.
- Battery optimisation is on for NetLogger on both phones (Android's default). The earlier screen-off runs reached
  their tick targets anyway; the How to use page still advises Unrestricted.
- **500 ms interval, moto, screen on, static, NR_NSA (session 17):** 400 ticks in 199.5 s, tick spacing median
  500.0 ms (max 506). The serving LTE cell's `cellInfoAgeMs`: median 986 ms, max 1996 ms; 57.5 % of rows older than
  500 ms and 34.8 % older than 1000 ms. The moto's modem refreshes cell info only about every 1–2 s, so at 500 ms
  more than half the rows repeat cell values (identifiable by their age); 1000 ms is the useful minimum here.
- **Capture with Specific apps, A56 (Telkomsel, session 26):** only NetLogger selected, Map tiles loaded during the
  capture: all 15 connections in `connections.csv` have NetLogger's uid; 357 packets, 0 dropped. Tiles went over
  IPv4 even with IPv6 available; the 9 IPv6 packets in the pcap are the ones counted as unparsed (not app
  connections), so the IPv6 capture path is still untested. The VPN consent from the earlier tests still held.

### moto g45, Map crash when tapping the open Map tab, 2026-10-16 08:57
- The crash log had a NetLogger crash at 05:51 (process 5 s old, foreground). Reproduced: with the Map open,
  tapping the Map tab again crashed the app 4 of 4 times; switching Map → Live 0 of 4. Each crash: a native
  SIGSEGV in MapLibre's `MapRenderer::render` (null pointer, render thread), then on the main thread
  `IllegalStateException: Calling getSourceAs when a newer style is loading/has loaded` from `TrackMap`.
- Cause: the bottom bar navigated to the already open Map tab (`popUpTo` start + `restoreState`), which pops the
  Map entry and builds a new one; destroying the old `MapView` that way crashes MapLibre 13.6.1. The logging
  service shares the process, so this would also have ended a recording.
- Fixed: tapping the open tab of a screen without sub-pages (Live, Map) does nothing; `TrackMap` skips a style
  that MapLibre has invalidated. After the fix: 5 of 5 taps on the open Map tab and 2 switches without a crash.

### moto g45, traffic counters and VPN consent, 2026-10-16 (decision 004)
- `TrafficStats` (total and mobile) stays flat for ~3 s and then jumps, also during a steady 57 kbps ping. Rates
  between consecutive 1 s samples swung between 0 and 1.25 Mbps; Live averages over at least 5 s (57 kbps shown).
  The per-tick `rxBytesTotal`/`txBytesTotal` in the database have the same steps (session 17: 10 changes in 400
  ticks), so per-tick throughput from them needs a window of several seconds.
- The VPN consent dialog appeared again at Start capture, although consent had been given on 2026-10-15 (reason
  unknown: Android drops it e.g. when another app's VPN was used). Cancelled: the capture row showed "VPN consent
  denied", logging continued.

### moto g45, problem reports (decision 005, step 1), 2026-10-16 12:11–12:18
- `ApplicationExitInfo` keeps the last 16 exits of the package. On first read: this morning's Map crash as one
  CRASH_NATIVE with a 466 KB tombstone (protobuf, libmaplibre.so frames named) and three CRASH (the Java exception
  ended those processes first), plus 12 normal exits (PACKAGE_UPDATED, USER_REQUESTED), correctly skipped.
- Debug crash tests (`CrashTestReceiver`): Java crash in the main process (stack trace + 55 log lines), native
  SIGSEGV (tombstone + 35 log lines), Java and native crash in `:vpn` (report appears when the app comes to the front,
  without restarting the main process).
- The ended process' own log lines are still readable with `logcat -d --pid` after the restart. They include every
  tag of the process, also MapLibre's tile requests (`tile.openstreetmap.org/17/x/y.png` gives away the area viewed),
  so the filter drops tile URLs as well as coordinate pairs.
- The tombstone contains memory dumps around the registers and the process' log buffer: it is not filtered, so it is
  summarised in the report, and the raw file must not be attached without the user seeing that (step 2).
- A broadcast ANR in the background (75 s blocked) was logged as "ANR in id.my.pamslab.netlogger" but the process
  was not killed and recovered: no exit record, so no report. Only ANRs that end the process are reported.
- A second Java crash within a short time: Android keeps the crashing process alive while its "keeps stopping"
  dialog is up (the crashing thread waits in the crash handler); the logging loop kept recording session 22 for 75 s
  after the crash, and the exit record's time is when the process finally ended. The report takes the crash time from
  the Java stack trace (step 2). `setProcessStateSummary` worked: the report has the session id and the open screen.

### Samsung A56, problem reports (decision 005, step 4), 2026-10-16 14:16–14:25
- The A56 had only normal exits stored (PACKAGE_UPDATED, USER_REQUESTED), so no report was imported.
- Java and native crashes in the main process: reports with stack trace / tombstone and log lines, as on the moto.
- **Android 16: a running process does not get exit records added after it started.** A Java crash of `:vpn` while the
  main process kept running was in `dumpsys activity exit-info` but not returned to the app; it appeared at the next
  start of the main process. The moto (Android 15) returned it at once. The capture row shows the ended capture
  meanwhile.
- ANR (main thread blocked, app in the foreground): Samsung's "NetLogger isn't responding" dialog; "Close app" ends
  the process with REASON_ANR and a 78 KB thread dump; description "user request after error: Input dispatching timed
  out (… Waited 10000ms …)", shortened in the title. Same on the moto.
- Wi-Fi on (2026-10-16): the Live data row shows the SSID with the existing permissions (fine location, location on);
  mobile data shows "Data disconnected".

### moto g45, 1.5-hour screen-off drive, Tri (510-89), 2026-10-16 17:29–19:03 (MVP definition of done)
Mostly parked, ~3.3 km driven (up to 45 km/h); unplugged (98 → 91 %, 4.5 %/h); screen off 98 % of the time. Release
0.2.0 recorded with a native capture (all apps, MTU 1500) while a ping to google.com ran once per second; the debug
build (`.debug`) recorded the same time in parallel.
- **Ticks:** 5603 / 5603 (release) and 5584 / 5584 (debug), 100 %; max tick spacing 1.01 s; no crash. The two apps
  agree on the serving LTE cell in 99.9 % of the seconds both recorded, on RSRP in 95.9 % (each requests cell info at
  its own moment; up to 18 dB apart around cell changes).
- **Cell info with the screen off:** the serving cell's age rises like a saw-tooth to just under 10 s (median 5.0 s,
  46 % of ticks older than 5 s, never 10 s); with the screen on median 1.0 s, never 5 s. The values are stored with
  their age, so stale rows can be filtered.
- **LTE SINR is missing with the screen off:** it only comes from SignalStrength, which goes stale with the screen off
  (`LTE_SIGNAL_STRENGTH_STALE` on 98 % of the ticks), so `lteSinr` is set in 2 % of the ticks. RSRP / RSRQ come from
  CellInfo and are always there. The same for NR SS-SINR.
- **Band from the API is wrong for B1:** `getBands()` says band 3 for the B1 cells (EARFCN 75 and 200);
  `API_BAND_CONFLICT`, mapped to B1.
- **Thermal status 3 (SEVERE)** from 18:42 to the end at a battery temperature of only 39–40.5 °C: the status is the
  device's own (skin / SoC), not the battery's; logging was not affected.
- **Capture:** 19 249 packets, none dropped; the wall-clock offset moved 1.0 ms in 93 min (93 CHECK rows). The ping
  (ICMP) has no app in `connections.csv`: `getConnectionOwnerUid()` covers TCP and UDP only. IPv4 only. From the pcap:
  5550 of 5556 echo requests answered (0.1 % lost), RTT median 32.7 ms, p90 38 ms, p99 46 ms; no ping lost around the
  19 LTE cell changes (one 172 ms spike within 5 s after one of them).
- **Export cut off (bug):** the debug build's "Save ZIP" left an incomplete ZIP in Download (measurements.csv and part
  of cells.csv, no central directory) without an error: the export runs in the session screen's ViewModel scope and is
  cancelled when the screen is left before it finishes. The release ZIP (the user waited) was complete. **Fixed** the
  same day (`ExportRunner`): exports run outside the screen, "Save ZIP" writes to a cache file and copies it to the
  chosen document only when complete (deleting the document on failure); checked on the moto by leaving the screen at
  31 %: an 801 kB ZIP that tests clean, and Share opening its chooser on return.

### Samsung A56, Live data rate during a capture, Telkomsel, 2026-10-19
- The user saw a Data-row rate of "2xxxx Mbps" on Live after starting a capture (session 6, during a Cloudflare speed
  test); the stored speed test (↓ 93.3 Mbps, SIN) and the capture (0 dropped) were fine. Not reproduced: with a
  logging build, a capture and a full Cloudflare test showed plausible rates ("Cellular (Telkomsel) · via VPN",
  ↓ up to 64 / ↑ 35 Mbps). The A56's counters: mobile ~16.8 GB, total ~23 GB above it, so a single reading where the
  route is not plain cellular while the mobile counter drops makes "total − mobile" jump by gigabytes (12 GB in 5 s
  ≈ 19 Gbit/s). Handled since: readings carry their route, the window restarts on a route change or any counter going
  back, and rates above 10 Gbit/s show "–".

### Xiaomi 24094RAD4G (MediaTek modem), Android 16, Telkomsel (510-10), student drive, 2026-10-19 16:49–17:15
First session from a student's phone (release 0.3.0, schema v3), 26 min, screen on, mostly walking speed.
- **Ticks:** 1578 / 1578 (100 %), max spacing 1.02 s; no crash.
- **CellInfo alternates between LTE-only and NR-only lists.** 28 % of the ticks (156 short stretches of 1–5 s)
  contain only NR cells, the serving one labelled PRIMARY_SERVING and registered, and no LTE cell at all; the other
  ticks contain only LTE cells. Never both, although the phone was 5G NSA (override NR_NSA) the whole time. In the
  NR-only ticks the serving LTE row comes from SignalStrength (RSRP, RSRQ, SINR marked SS; no PCI / EARFCN). Unlike
  the moto, the NR cell's own PCI is reported (n40, NR-ARFCN 466850; 11 NR cell changes).
- **LTE values:** RSRP median -90 dBm, RSRQ median -15 dB, SINR median 1 dB (from CellInfo in 72 % of ticks; LTE
  CQI and SINR are reported, unlike the moto). Carrier aggregation `[20000,20000,30000]` most of the time.
  `getBands()` says B1 for three B3 cells (EARFCN 1850): `API_BAND_CONFLICT`, mapped to B3.
- **Speed tests (Cloudflare, site CGK = Jakarta; the moto on Tri gets SIN):** 68.3 ↓ / 59.4 ↑ Mbps, ping 68 ms
  (standing, NR SS-RSRP -75 dBm, SS-SINR 12 dB; the upload reached the 50 MB cap after 7 s) and 26.2 ↓ / 25.9 ↑ Mbps,
  ping 70 ms (moving up to 20 km/h, NR -82 dBm / 7 dB, LTE SINR -1 dB).
- Battery 90 → 84 % in 26 min (13.7 %/h, screen on, two speed tests); thermal status 2 (MODERATE) throughout.

## Handled in code (from NetMonster Core, not yet confirmed on our devices)

| Quirk | Devices | Handling | Warning code | Confirmed on |
|---|---|---|---|---|
| Serving TAC/LAC bytes swapped in CellInfo | Samsung, Android 12+ | Compared with `getCellLocation()`; swapped back, latched for the session | `SAMSUNG_TAC_BYTE_SWAP` | **Not seen** on A56 / Android 16 (TAC matches moto g45 on the same cell) |
| Neighbor placeholders RSSI -51, TA 0, Ec/No 0; plus cell ID 0, TAC 0, LTE CQI 0 (ours, from A56 data) | Samsung | Set to null | `SAMSUNG_NEIGHBOR_PLACEHOLDER` | A56 (CI/TAC/CQI/TA 0), 2026-10-13 |
| LTE RSSNR 0 meaning "not available" | Samsung | Set to null | `SAMSUNG_LTE_SNR_ZERO` | A56 (neighbors), 2026-10-13 |

## Watch for (not handled yet)

- **GSM neighbors reported as LTE** (NetMonster `PixelTensorPostprocessor`, Samsung modems in Pixel Tensor):
  LTE neighbor with a PCI, an RSRP and no band, RSSI or RSRQ. Check on the A56.
- **No `CellInfoNr` in NSA**: expected on many phones; handled by the `SIGNAL_STRENGTH` NR row. moto g45 does list
  `CellInfoNr` in NSA, but unregistered with status NONE and no signal (see above). A56: to check in 5G coverage.
- **RSSNR scaling**: some OEMs report 0.1 dB units (values > 30 → `OUT_OF_RANGE`). moto g45: dB units (9, 11) in SignalStrength. A56: to check.
- **`getBands()` empty**: until `BandMapper` (milestone 7), band stays null. Record how often each phone does this.
- **Cached cell info with screen off**: check `cellInfoOrigin` and `cellInfoAgeMs` in milestone 3 tests.

### Samsung Galaxy A56, Telkomsel (510-10), periodic speed test with a capture, 2026-10-20

Debug build `4efe672` (schema v4), session 9: 5 min 21 s, mostly still and indoors (average 0.5 km/h), screen off for
half the ticks, native capture all apps (MTU 1500). Analysis in `~/netlogger-analysis/a56-periodic-20261020/`.

- Ticks 322 of 322 expected. GPS: 127 ticks with a fix older than 2 s and 21 GPS_LOST/GPS_RESTORED pairs (STALE_FIX)
  indoors, as expected.
- Every tick NR_NSA (`overrideNetworkType` 3, the 5G icon), but **no NR cell at all**, neither from CellInfo nor from
  SignalStrength; the earlier Telkomsel sessions on this phone were LTE. Cell bandwidths `[20000,20000,20000,20000]`
  (4 × 20 MHz LTE CA) in 298 ticks. So the icon alone does not show that NR carried data; the 63.8 Mbps download fits
  LTE CA. Same pattern as G-NetTrack's "NR_NSA in the header, but no NR values" (moto, above).
- Warnings as known for Samsung: SAMSUNG_LTE_SNR_ZERO 656, SAMSUNG_NEIGHBOR_PLACEHOLDER 379 per field,
  SECONDARY_SERVING_WITHOUT_CA 379, LTE_SIGNAL_STRENGTH_STALE 44.
- Capture: 150 783 packets = expected, 0 dropped, 82 connections, `uid_unknown` 0.
- Periodic speed test (every 5 min, 5 s per part, caps 25 MB down / 10 MB up): ran exactly 5:00 after Start, stored
  as PERIODIC. Download stopped at the cap after 4.5 s (26.2 MB, 46.1 Mbps). **Upload: 10.55 MB in 520 ms =
  162.3 Mbps**, against 30.2 Mbps for the manual 10 s upload in the same session. The upload counts bytes handed to
  the socket; the phone's TCP send buffer is 2 MB by default per connection (`tcp_wmem` 512000 2097152 8388608), so
  4 connections accept about 8 MB at memory speed, and during a capture the VPN forwarder buffers more. A small upload
  cap measures the buffers, not the link; every upload rate is overstated by the data still in the buffers when the
  phase ends (up to ~8 MB, i.e. up to ~25 % of a 10 s test at 30 Mbps). **Fixed** (CLAUDE.md 1.7.9): the upload now
  counts only pieces the server confirmed, timed to the last confirmation; upload rows before the fix overstate.

## Not available by design

- Physical channel config (needs privileged `READ_PRECISE_PHONE_STATE`).
- NR timing advance: `CellSignalStrengthNr.getTimingAdvanceMicros()` exists only from API 34, in µs rather
  than the LTE index unit; not recorded yet.
