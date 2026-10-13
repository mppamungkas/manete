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
| **Neighbors reported as SECONDARY_SERVING** | 3 extra cells with `mCellConnectionStatus=2`, `mRegistered=NO`, same EARFCN 500 as the PCell, while `isUsingCarrierAggregation=false`, `mCellBandwidths=[20000]` and one PhysicalChannelConfig | **Fixed:** a same-RAT SECONDARY_SERVING cell on the PCell's ARFCN, or with no CA shown (one cell bandwidth and no LTE_CA override), is stored as a neighbor (`connectionStatus = NONE`) with `SECONDARY_SERVING_WITHOUT_CA` (raw status kept). Applies to all manufacturers; NR under an LTE primary (EN-DC) is never touched. Confirmed on device after the fix. | – |
| Neighbor CI / TAC = 0 | App shows `0 / 0` for those cells (dumpsys redacted) | **Fixed:** null with `SAMSUNG_NEIGHBOR_PLACEHOLDER` (Samsung neighbors only) | – |
| Neighbor CQI 0, TA 0, RSSNR 0 | `cqi=0 ta=0 rssnr=0` on all three | **Fixed:** RSSNR 0 → `SAMSUNG_LTE_SNR_ZERO`; TA 0 and LTE CQI 0 → `SAMSUNG_NEIGHBOR_PLACEHOLDER` | – |
| Serving bandwidth unavailable in CellIdentity | `mBandwidth=2147483647` | Null; `cellBandwidths` from ServiceState has 20000 | – |
| SignalStrength differs a lot from CellInfo at the same moment | CellInfo serving: RSSI -79, RSSNR 5; SignalStrength: RSSI -103, RSSNR -4, `cqiTableIndex=1` | Both rows kept (decision 001 addendum) | Track over a drive; possibly different measurement windows |
| SignalStrength timestamp is old while values look current | SIGNAL_STRENGTH row age 39.5 s | Grayed out as stale (> 5 s) | Expected: the callback only fires on change. Keep the honest age; flag in analysis, not in the parser |
| CellInfo ages 3–7 s although origin is FRESH | Ages 3102 ms then 6977 ms | Shown as reported | Samsung may answer `requestCellInfoUpdate` with the last modem report. Check in milestone 3 with screen on/off |
| TAC byte swap | No `SAMSUNG_TAC_BYTE_SWAP` warning; TAC 11550 | Not triggered | Can't verify without CI/TAC in dumpsys. Compare with the cell in a crowd-sourced map or Network Survey on the same phone |

## Handled in code (from NetMonster Core, not yet confirmed on our devices)

| Quirk | Devices | Handling | Warning code | Confirmed on |
|---|---|---|---|---|
| Serving TAC/LAC bytes swapped in CellInfo | Samsung, Android 12+ | Compared with `getCellLocation()`; swapped back, latched for the session | `SAMSUNG_TAC_BYTE_SWAP` | – |
| Neighbor placeholders RSSI -51, TA 0, Ec/No 0; plus cell ID 0, TAC 0, LTE CQI 0 (ours, from A56 data) | Samsung | Set to null | `SAMSUNG_NEIGHBOR_PLACEHOLDER` | A56 (CI/TAC/CQI/TA 0), 2026-10-13 |
| LTE RSSNR 0 meaning "not available" | Samsung | Set to null | `SAMSUNG_LTE_SNR_ZERO` | A56 (neighbors), 2026-10-13 |

## Watch for (not handled yet)

- **GSM neighbors reported as LTE** (NetMonster `PixelTensorPostprocessor`, Samsung modems in Pixel Tensor):
  LTE neighbor with a PCI, an RSRP and no band, RSSI or RSRQ. Check on the A56.
- **No `CellInfoNr` in NSA**: expected on many phones; handled by the `SIGNAL_STRENGTH` NR row. Record which
  phone does what.
- **RSSNR scaling**: some OEMs report 0.1 dB units (values > 30 → `OUT_OF_RANGE`). moto g45: dB units (9, 11) in SignalStrength. A56: to check.
- **`getBands()` empty**: until `BandMapper` (milestone 7), band stays null. Record how often each phone does this.
- **Cached cell info with screen off**: check `cellInfoOrigin` and `cellInfoAgeMs` in milestone 3 tests.

## Not available by design

- Physical channel config (needs privileged `READ_PRECISE_PHONE_STATE`).
- NR timing advance: `CellSignalStrengthNr.getTimingAdvanceMicros()` exists only from API 34, in µs rather
  than the LTE index unit; not recorded yet.
