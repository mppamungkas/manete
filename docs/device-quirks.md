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
  entry; the list is not reliable as a CA count on this phone. **Fixed:** for an LTE primary the SECONDARY_SERVING
  rule now counts only LTE channel bandwidths (1.4/3/5/10/15/20 MHz) as CA evidence, or the LTE_CA override.
- Operator name from ServiceState is the SIM's marketing name `3SinyalKuatHemat`; PLMN 510-89 as on the moto.

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
