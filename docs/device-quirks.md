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

## Not available by design

- Physical channel config (needs privileged `READ_PRECISE_PHONE_STATE`).
- NR timing advance: `CellSignalStrengthNr.getTimingAdvanceMicros()` exists only from API 34, in µs rather
  than the LTE index unit; not recorded yet.
