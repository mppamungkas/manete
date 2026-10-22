# 001: Cell parsing: NetMonster Core dependency vs. own `CellParser`

- **Status:** Accepted (option C), 2026-10-13.
- **Date:** 2026-10-13
- **Milestone:** 2 (Telephony)
- **Studied:** `reference/netmonster-core` (commit `33063e0`, 2026-10-12), the published artifact
  `app.netmonster:core:1.3.0`, `reference/network-survey` (`SurveyRecordProcessor`),
  `reference/tower-collector` (`app/build.gradle`)

## Context

`core:telephony` must turn `CellInfo`, `SignalStrength`, `ServiceState` and `TelephonyDisplayInfo` into
`cell` / `measurement` rows. CLAUDE.md sets these requirements for it:

1. Public APIs only. No reflection, no hidden APIs.
2. Every field is nullable. Sentinels (`Int.MAX_VALUE`, `CellInfo.UNAVAILABLE`) become `null`.
   Parse failures are counted, never thrown.
3. Traceability: record `source` (CELL_INFO / SIGNAL_STRENGTH), `bandSource` (API / MAPPED),
   `cellInfoAgeMs`, and the raw NSA inputs, not only the conclusion.
4. Parsers are pure functions that return nullable fields and a list of warnings, unit-tested on the JVM
   with fake data.
5. `TelephonyManager` is called only from `AndroidTelephonySource`.

Our minSdk is **31**, so anything NetMonster does for API < 31 doesn't matter to us.

## What NetMonster Core does well

- **OEM quirks.** It encodes years of field experience (the README cites 50,000+ active users). The fixes
  that matter on API 31+ are `SamsungEndiannessPostprocessor` (byte-swapped serving-cell TAC on Samsung
  with Android 12+), `SamsungInvalidValuesPostprocessor` (Samsung's own "N/A" constants, LTE SNR `0.0`),
  `PixelTensorPostprocessor` (GSM neighbors marked as LTE), `InvalidCellsPostprocessor` (MediaTek NR
  ghost cell with -44/-3), `NrIdentityFromLtePostprocessor` (Xiaomi copies the LTE ECI into the NR cell,
  which makes NSA look like SA), and `SubDuplicitiesPostprocessor`.
- **Band tables.** `BandTableLte` / `BandTableNr` resolve overlapping bands by MCC, e.g. n77/n78.
- **NSA NR from `SignalStrength`.** `SignalStrength.toCells()` builds an NR secondary cell when both LTE
  and NR signal are present. This is the same fallback CLAUDE.md asks for.
- **License.** Apache 2.0. It is compatible with this repo's GPL-3.0 `LICENSE`, and we may port code
  with attribution.

## Problems for Manete

| # | Issue | Evidence | Impact |
|---|---|---|---|
| 1 | **Contains reflection on hidden fields and methods** | `util/Reflection.kt` (`getDeclaredField` + `isAccessible` on `mRsrp`, `mRssnr`, …), `PhoneStateListenerPort` (sets hidden `mSubId`), `NetworkOperatorGetter` / `SimOperatorGetter` | On API 31+, these paths appear to be SDK-gated off (`SDK_INT < O/P/Q`, or replaced by `TelephonyCallback`). The code still ships in our APK, though, and proving that it is unreachable on every path and in every future version is up to us. This conflicts with the hard constraint. |
| 2 | **Published artifact is stale** | Maven Central's latest release is `1.3.0` (2024-03-08). The source is active (last commit 2026-10-12). `NrIdentityFromLtePostprocessor` and the refined NR band definitions are not in 1.3.0. | Taking it as a Gradle dependency gives us 2.5-year-old quirk handling. Getting current code means JitPack or vendoring the source. |
| 3 | **Silent corrections, no provenance** | Postprocessors swap bytes, drop values ("can eventually also discard valid values"), merge `SignalStrength` into `CellInfo` cells, and derive the LTE band from EARFCN while treating `getBands()` only as hints. `ICell` doesn't record which source a value came from or which fix was applied. | We couldn't fill `source`, `bandSource` or the parse warnings truthfully. For research data, a hidden correction is worse than a flagged one. |
| 4 | **Not testable on the JVM** | The mappers are `internal` extension functions on `android.telephony.*` classes. | Testing with fake `CellInfo` would need Robolectric or a device. Requirement 4 asks for pure JVM tests. |
| 5 | **Blocking access model** | `requestPhoneStateUpdate` blocks on a `CountDownLatch` with a 1 s timeout. `getCells()` is synchronous. | This doesn't fit a 1 Hz tick loop built on `TelephonyCallback` → `StateFlow`. We would call only its mappers, not its sources. |
| 6 | **Model mismatch** | Visitor-based `ICell` with sealed band and connection types | We would need an adapter to our flat `cell` row anyway. |

Network Survey writes its own parsing (`SurveyRecordProcessor`). Tower Collector depends on
NetMonster `1.3.0`. Its goal is crowd-sourcing tower locations, where silent corrections are acceptable.
Ours is research data, where they are not.

## Options

**A. NetMonster Core 1.3.0 as a Gradle dependency, plus an adapter.** This is the least code, but it
takes on problems 1–6. Because of 3, we would also have to re-derive `source` and `bandSource`
ourselves, which removes most of the saving.

**B. Our own parser, written from scratch.** This meets every requirement, but we would rediscover OEM
quirks in the field, one drive test at a time.

**C. Our own parser, with selected NetMonster workarounds ported as flagged corrections (recommended).**
- `AndroidTelephonySource` copies framework objects into plain Kotlin DTOs (`RawCellInfo`,
  `RawSignalStrength`, …) in `core:model`. This is the only Android-touching step. It extracts each
  getter in its own try/catch and counts failures.
- `CellParser` is a pure function from DTOs to `CellSample` plus `List<ParseWarning>`, and is unit-tested
  on the JVM. It converts sentinels to `null`, applies range checks, records `source`, `bandSource` and
  `cellInfoAgeMs`, and handles the NSA fallback from `SignalStrength`.
- Each ported quirk fix is a small named `Correction`. It never overwrites silently: the corrected value
  goes into the column, the reason goes into the warnings (for example `SAMSUNG_ENDIANNESS_SWAP`,
  `MTK_GHOST_NR_DROPPED`), and the raw value is kept in the warnings for audit.
- Port only quirks relevant to API 31+, one at a time, each with a unit test that reproduces the bad input.
  Start with the five listed above. Add others only when `docs/device-quirks.md` shows we hit them.
- Write `BandMapper` (milestone 7) from 3GPP TS 36.101 / 38.101. Port NetMonster's MCC-based overlap
  resolution, with attribution, only if Indonesian operators need it (to check: n40 vs. n41, n77 vs. n78).
- Porting rules (CLAUDE.md): before porting each piece, I tell you the source file and license, keep the
  original copyright notice in the file header, and add NetMonster Core to `docs/third-party.md`.

**Optional, debug builds only:** add `debugImplementation("app.netmonster:core:1.3.0")` to a hidden
"cross-check" screen that shows NetMonster's view of the same moment next to ours, for validation drives.
It never ships in release builds. I'll propose it separately; it is not part of this decision.

## Recommendation

**Option C.** It keeps NetMonster's main value, its OEM knowledge, without the reflection, stale
artifact, silent corrections or Robolectric-only tests. The extra cost compared with A is roughly the
DTO layer and the per-quirk tests, which we would need for requirements 3 and 4 anyway.

## What happens after approval

1. `core:model`: `RawCellInfo` / `RawSignalStrength` / `RawServiceState` DTOs, `CellSample`, `ParseWarning`.
2. `core:telephony`: `TelephonySource` interface plus `AndroidTelephonySource` (the only `TelephonyManager`
   user), `CellParser`, `NetworkStateListener` (`TelephonyCallback`: DisplayInfo, ServiceState, SignalStrengths),
   and NR-from-`SignalStrength` fallback.
3. Unit tests with fake DTOs: sentinels, NSA without `CellInfoNr`, stale cells, empty `getBands()`, and each ported quirk.
4. Live screen showing raw values.
5. On-device check: I'll ask you to compare the Live screen with `adb shell dumpsys telephony.registry`.

## Answers and what follows from them

1. **Option:** C approved.
2. **Test phones:** Samsung Galaxy A56 (Exynos 1580, Samsung modem, Android 16) and Motorola moto g45
   (Qualcomm Snapdragon 6s Gen 3, `ro.soc.model` = SM6375, Android 15). Quirks to port first:
   - Samsung A56: `SamsungEndiannessPostprocessor` (LTE TAC byte swap, Samsung on Android 12+) and
     `SamsungInvalidValuesPostprocessor` (Samsung N/A constants, LTE SNR `0.0`).
   - Not ported up front: `PixelTensorPostprocessor` only runs on a hard-coded list of Pixel products.
     Its pattern (GSM neighbors reported as LTE) comes from the Samsung modem in Tensor, so we watch for
     it on the A56 and port it only if we see it. MediaTek and Xiaomi fixes don't apply to these phones.
   - moto g45 (Qualcomm): no specific NetMonster fix. It serves as the cleaner reference device.
   - Both phones report Android version and modem to `session` metadata. Findings go into `docs/device-quirks.md`.
3. **Operators:** all Indonesian operators. `BandMapper` therefore uses the full 3GPP EARFCN / NR-ARFCN
   tables, not an operator subset. Tests cover every band that Indonesian operators are confirmed to use.
   The band list is checked against field data from both phones before it is final.

## Addendum (2026-10-13): two sources for serving-cell signal

Found on the moto g45 (see `docs/device-quirks.md`): CellInfo reports LTE RSSNR as unavailable while
`SignalStrength` reports it (`rssnr=11`). Decided with the user:

1. `CellParser` always emits a `SIGNAL_STRENGTH` row for each RAT entry in `SignalStrength` that has any valid
   value, next to the `CELL_INFO` rows. Previously it did so only when no CELL_INFO serving cell of that RAT had
   signal.
2. That row has `isServing = true`, its `rat`, and null identity (PCI, ARFCN, cell ID, TAC, band, PLMN).
   Identity is never copied from the CELL_INFO row.
3. Its `cellInfoAgeMs` is measured from `SignalStrength.getTimestampMillis()` (elapsedRealtime base), because it
   arrives from a callback at a different time than CellInfo.
4. No deduplication: when both sources have RSRP, both are stored. Differences are kept for validation.
5. **`flat.csv` rule (milestone 5):** for each serving-cell field (RSRP, RSRQ, SINR, RSSI, CQI, TA, CSI-*), take
   the CELL_INFO value; if it is null, take the SIGNAL_STRENGTH value. Each such field gets a source column
   (`sinrSource`, `rsrpSource`, …) with `CELL_INFO`, `SIGNAL_STRENGTH` or empty. Document in `docs/columns.md`.

## Addendum (2026-10-13): NSA NR cell labelling and NR band mapping

From the moto g45 NSA capture (see `docs/device-quirks.md`), decided with the user:

1. In NSA (override NR_NSA / NR_NSA_MMWAVE / NR_ADVANCED, registered LTE cell), exactly one unregistered,
   non-serving NR cell is labelled `SECONDARY_SERVING` with a `NR_NSA_SECONDARY_INFERRED` warning. With several
   candidates nothing is guessed (`NR_NSA_SECONDARY_AMBIGUOUS`).
2. Its TAC is blanked when equal to the LTE serving TAC (`NR_TAC_FROM_LTE`, raw value kept).
3. No signal merging: the NR signal stays in its `SIGNAL_STRENGTH` row (first addendum).
4. The NR part of `BandMapper` is pulled forward from milestone 7: full NR-ARFCN table from TS 38.101-1/-2; on
   overlap an MCC preference (510 → n41), then TDD first for 510, then the narrowest band, then the lowest number.
   All candidates go into a `MULTIPLE_BANDS` warning. LTE mapping and the per-operator band tests stay in milestone 7.
5. From the two-phone NSA comparison: the inferred NR cell's PCI is blanked too when both PCI and TAC equal the LTE
   anchor's; TAC 0 on an NSA NR secondary is null; a country-preferred band overrides a contradicting `getBands()`
   (`API_BAND_CONFLICT`, `bandSource = MAPPED`); Samsung's NR CSI floor values are null.

## Addendum (2026-10-14): reported vs. inferred serving label, MCC fallback, parse error count

From the A56 field walk (see `docs/device-quirks.md`), decided with the user:

1. Every cell keeps the phone's own label in `reportedServing` (registered, or status PRIMARY/SECONDARY_SERVING),
   never corrected. The derived flag is `servingInferred` (was `isServing`); consumers, including `flat.csv`, use it.
2. NR CELL_INFO cells get `servingSource`. Among unregistered NR cells reported SECONDARY_SERVING on the same
   NR-ARFCN: one candidate → `REPORTED`; several → the only one whose SS-RSRP and SS-RSRQ match a SignalStrength NR
   entry within 1 dB (`SIGNAL_STRENGTH_MATCH`), else the strictly strongest SS-RSRP (`STRONGEST`), else none is
   serving (`AMBIGUOUS`). Each candidate that loses its label gets `NR_SERVING_SIGNAL_STRENGTH_MATCH`,
   `NR_SERVING_STRONGEST` or `NR_SERVING_AMBIGUOUS`. Candidates on different channels are judged separately (NR CA).
   The NSA inference of the second addendum sets `NSA_INFERRED` (or `AMBIGUOUS` with several candidates), a value
   added to the four agreed ones because that label was not reported by the phone. `flat.csv` exports
   `nrServingSource` (milestone 5).
3. Without a network MCC (first tick, before ServiceState), the registered cell's MCC is used for the country band
   preference; a band chosen that way has `bandSource = MAPPED_MCC_FALLBACK`.
4. `measurement.parseErrors` counts getter failures only; `measurement.warningCount` counts all warnings of the tick.

## Addendum (2026-10-14): stale NR in SignalStrength

From the A56 drive (see `docs/device-quirks.md`), approved: after the NR leg is released, SignalStrength may keep its
last NR value without updates (up to 110 s with the screen off). A SIGNAL_STRENGTH NR row is **stale** when the display
override is known and not NSA, no NR CELL_INFO cell is serving, and the row is older than 10 s. It is kept (raw values),
gets `servingInferred = false` and an `NR_SIGNAL_STRENGTH_STALE` warning with its age, does not count as NR evidence for
NR_NSA, and is never used as the flat.csv / map fallback.

## Addendum (2026-10-14): stale LTE in SignalStrength

From the moto g45 screen-off drive (see `docs/device-quirks.md`), approved: the LTE entry in SignalStrength is not
refreshed for up to 212 s with the screen off (median 34 s), while CellInfo stays under 10 s. The moto's CellInfo has
no RSSNR, so its flat.csv `lteSinr` was mostly an old value. A SIGNAL_STRENGTH LTE row older than 10 s is **stale**: kept
(raw values), `servingInferred = false`, an `LTE_SIGNAL_STRENGTH_STALE` warning with its age, never used as the
flat.csv / map fallback. Unlike NR there is no further condition (LTE stays the anchor). A steady signal can also leave
the entry unrefreshed while still true; that value is dropped from flat.csv too, but stays in cells.csv.

## Addendum (2026-10-14): bands for LTE, WCDMA and GSM (milestone 7)

`BandMapper.Standard` now covers LTE (EARFCN ranges, TS 36.101 Table 5.7.3-1), WCDMA (non-overlapping UARFCN ranges,
TS 25.101 Table 5.2) and GSM (TS 45.005; band named by MHz, 1800 vs. 1900 by MCC). An EARFCN lies in exactly one band,
so the mapped band is definitive. The moto g45 sometimes keeps the previous cell's `getBands()` for the newly
registered cell right after a handover (197 serving-cell rows on 2026-10-14, e.g. EARFCN 1625 with band 1). Rule: when
`getBands()` contradicts a band the ARFCN settles (a single candidate, or the country's known band), the mapped band is
stored with `bandSource = MAPPED` and an `API_BAND_CONFLICT` warning keeps the reported bands. In Indonesia, 700 MHz NR is
taken as n28 (APT plan), not n14.
