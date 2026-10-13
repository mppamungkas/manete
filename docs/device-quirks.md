# Device quirks

Update after every real-device test. For each quirk: device, Android / baseband version, what was seen
(paste `dumpsys telephony.registry` or logcat excerpts), and how NetLogger handles it.

## Test devices

| Device | SoC / modem | Android | Notes |
|---|---|---|---|
| Samsung Galaxy A56 | Exynos 1580 (Samsung modem) | 16 | Samsung corrections active. Unrooted: use for the per-milestone unrooted check |
| Motorola moto g45 5G (`fogos`, ADB `ZP22222MQL`) | Qualcomm Snapdragon 6s Gen 3 (`ro.soc.model` = SM6375), baseband `M6375_HI434_03.562.01.115R` | 15 (API 35) | **Pilot phone, rooted** (root only for the developer's validation tools; NetLogger never uses it). No OEM corrections. First tested 2026-10-13 |

## Seen on our devices

### moto g45, Tri (510-89), LTE B3 with carrier aggregation, 2026-10-13

Setup: SIM 1 Tri, slot 2 empty, static indoor, no 5G coverage (`isEndcAvailable = false`). Raw dumps in
`docs/samples/` (gitignored, local only: they contain cell IDs and TACs). The Live screen matched
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

## Handled in code (from NetMonster Core, not yet confirmed on our devices)

| Quirk | Devices | Handling | Warning code | Confirmed on |
|---|---|---|---|---|
| Serving TAC/LAC bytes swapped in CellInfo | Samsung, Android 12+ | Compared with `getCellLocation()`; swapped back, latched for the session | `SAMSUNG_TAC_BYTE_SWAP` | – |
| Neighbor placeholders RSSI -51, TA 0, Ec/No 0 | Samsung | Set to null | `SAMSUNG_NEIGHBOR_PLACEHOLDER` | – |
| LTE RSSNR 0 meaning "not available" | Samsung | Set to null | `SAMSUNG_LTE_SNR_ZERO` | – |

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
