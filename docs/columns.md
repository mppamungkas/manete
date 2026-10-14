# Export columns

One session exports as `netlogger_<model>_<start yyyyMMdd-HHmmss>_<label or scenario>_<uuid prefix>.zip`, which contains:

| File | Content |
|---|---|
| `session.json` | Session metadata (below) |
| `measurements.csv` | One row per tick |
| `cells.csv` | One row per cell per tick: serving and neighbor cells from CellInfo, plus one row per RAT from SignalStrength |
| `flat.csv` | One row per tick: the serving LTE and the serving NR cell side by side, for spreadsheets and quick plots |
| `events.csv` | Events (user markers for now; network events come with milestone 7) |
| `warnings.csv` | Parse warnings and flagged corrections, one row per (code, RAT, PCI, field) per session |
| `track.geojson` | GPS track (LineString) and events with a location (Points), RFC 7946 |

## Format rules (all CSV files)

- UTF-8 without BOM, comma separator, CRLF line ends, one header row (RFC 4180). Fields with a comma, quote, line
  break or leading/trailing space are quoted, quotes doubled.
- **Empty field = not reported.** Never `0`, `-999` or another sentinel for a missing value. Android's
  `UNAVAILABLE` / `Int.MAX_VALUE` are empty.
- Numbers use `.` as decimal separator, no thousands separator, no scientific notation, regardless of the phone's
  locale. Booleans are `true` / `false`. Enum values are their names (e.g. `NR_NSA`).
- **Time:** `time` and other ISO columns are ISO-8601 with milliseconds and UTC offset, e.g.
  `2026-10-14T05:55:32.434+07:00`, in the phone's time zone **at export** (stored in `session.json` as `timeZone`).
  Every file also has raw `wallTimeMs` (Unix epoch ms, phone clock) and `elapsedRealtimeNs` (ns since boot, immune to
  clock changes; the sync key with pcap files in Phase 3).
- `seq` is the tick number in the session, starting at 0. It joins `measurements.csv`, `cells.csv`, `flat.csv` and
  `warnings.csv` (`firstSeq`).
- Spreadsheet note: Excel may drop leading zeros of `mnc` (e.g. `01`) and show long IDs in scientific notation;
  import as text if needed.
- Sign convention: dBm / dB values are negative where the quantity is (RSRP, RSRQ, RSSI).

## measurements.csv

| Column | Unit | Description |
|---|---|---|
| `seq` | | Tick number, 0-based |
| `time` | ISO-8601 | Tick wall time |
| `wallTimeMs` | ms | Tick wall time, Unix epoch |
| `elapsedRealtimeNs` | ns | Tick time since boot |
| `lat` | ° WGS84 | Latitude of the newest fix at or before the tick; empty without a fix |
| `lon` | ° WGS84 | Longitude |
| `altitude` | m | Altitude above the WGS84 ellipsoid |
| `speedMps` | m/s | GPS speed |
| `bearing` | ° | GPS bearing, 0 = north |
| `gpsAccuracy` | m | Horizontal accuracy (68 %) |
| `locationAgeMs` | ms | Tick time minus fix time; large values mean a stale fix |
| `locationStatus` | | `OK`, `WAITING`, `NO_PERMISSION`, `DISABLED`, `UNAVAILABLE`, `ERROR` |
| `networkType` | | NetLogger's classification: `LTE`, `NR_NSA`, `NR_SA`, `WCDMA`, `GSM`, `NONE` |
| `overrideNetworkType` | | `TelephonyDisplayInfo.getOverrideNetworkType()`: 0 none, 1 LTE_CA, 2 LTE_ADVANCED_PRO, 3 NR_NSA, 4 NR_NSA_MMWAVE, 5 NR_ADVANCED |
| `dataNetworkType` | | `TelephonyManager.NETWORK_TYPE_*` of the data connection (13 = LTE, 20 = NR) |
| `nsaLteServing` | | NSA input: an LTE serving cell is present (CellInfo) |
| `nsaNrCellInfo` | | NSA input: a serving NR cell is present in CellInfo (`servingInferred`) |
| `nsaNrSignal` | | NSA input: SignalStrength has a valid, current NR value (a stale one, see `NR_SIGNAL_STRENGTH_STALE`, does not count) |
| `serviceState` | | `ServiceState.getState()`: 0 in service, 1 out of service, 2 emergency only, 3 power off |
| `dataState` | | `TelephonyManager.getDataState()`: 0 disconnected, 1 connecting, 2 connected, 3 suspended |
| `isRoaming` | | Roaming flag from ServiceState |
| `operatorNumeric` | | MCC+MNC of the registered network (ServiceState), e.g. `51089`. Use this for the operator, not the cell's PLMN (network sharing) |
| `cellInfoOrigin` | | `FRESH` (cell info request completed), `CACHED`, `UNAVAILABLE`. Judge freshness by `cellInfoAgeMs` in cells.csv |
| `cellBandwidthsJson` | kHz | `ServiceState.getCellBandwidths()` as a JSON array, e.g. `[20000,10000]`; best-effort CA hint |
| `batteryLevel` | % | Battery level |
| `batteryCurrentUa` | µA | Instantaneous current; sign convention is OEM-specific |
| `batteryVoltageMv` | mV | Battery voltage |
| `batteryTempC` | °C | Battery temperature |
| `isCharging` | | Charging |
| `rxBytesTotal` | bytes | `TrafficStats.getTotalRxBytes()`: all interfaces incl. Wi-Fi, counter since boot; use differences between ticks |
| `txBytesTotal` | bytes | `TrafficStats.getTotalTxBytes()` |
| `rxBytesApp` | bytes | NetLogger's own received bytes (`getUidRxBytes`) |
| `txBytesApp` | bytes | NetLogger's own sent bytes |
| `screenOn` | | Screen interactive |
| `thermalStatus` | | `PowerManager.getCurrentThermalStatus()`: 0 none … 6 shutdown |
| `parseErrors` | count | Telephony getter calls that threw in this tick (real parse failures) |
| `warningCount` | count | Warnings raised in this tick, including flagged OEM corrections (see warnings.csv) |

## cells.csv

| Column | Unit | Description |
|---|---|---|
| `seq` | | Tick number |
| `time` | ISO-8601 | Tick wall time |
| `wallTimeMs` | ms | Tick wall time, Unix epoch |
| `elapsedRealtimeNs` | ns | Tick time since boot |
| `rat` | | `LTE`, `NR`, `WCDMA`, `GSM` |
| `source` | | `CELL_INFO` (`getAllCellInfo`, has identity) or `SIGNAL_STRENGTH` (`SignalStrength.getCellSignalStrengths()`, serving signal only, no identity) |
| `reportedServing` | | The phone's own label, never corrected: registered, or connection status PRIMARY/SECONDARY_SERVING. Empty for SIGNAL_STRENGTH rows |
| `servingInferred` | | NetLogger's serving flag after flagged corrections; use this one. SIGNAL_STRENGTH rows are `true`, except a stale NR entry (`NR_SIGNAL_STRENGTH_STALE`: no 5G indicator, no serving NR cell, older than 10 s) |
| `servingSource` | | NR CELL_INFO rows only: how `servingInferred` was decided. `REPORTED` (phone's label kept), `NSA_INFERRED` (phone gave no label; one NR cell under an NSA override), `SIGNAL_STRENGTH_MATCH` / `STRONGEST` (several candidates on one NR-ARFCN; the one matching SignalStrength within 1 dB, else the strongest), `AMBIGUOUS` (no basis; none serving). See `docs/decisions/001-cell-parsing.md` |
| `isRegistered` | | `CellInfo.isRegistered()` |
| `connectionStatus` | | `NONE`, `PRIMARY_SERVING`, `SECONDARY_SERVING` after corrections; the original is in `reportedServing` and warnings.csv |
| `ciOrNci` | | LTE ECI (28 bit), NR NCI (36 bit), WCDMA/GSM CID |
| `tac` | | TAC (LTE/NR) or LAC (WCDMA/GSM) |
| `pci` | | PCI (LTE 0–503, NR 0–1007), PSC (WCDMA) or BSIC (GSM) |
| `arfcn` | | EARFCN, NR-ARFCN, UARFCN or ARFCN |
| `band` | | 3GPP band number (NR without the `n`) |
| `bandSource` | | `API` (`getBands()`), `MAPPED` (from the ARFCN, TS 36.101 / 38.101), `MAPPED_MCC_FALLBACK` (mapped with the registered cell's MCC because the network MCC was not known yet) |
| `bandwidthKhz` | kHz | LTE cell bandwidth (`CellIdentityLte.getBandwidth()`) |
| `mcc` | | Cell's MCC |
| `mnc` | | Cell's MNC (may differ from the SIM operator under network sharing) |
| `rsrp` | dBm | LTE RSRP or NR SS-RSRP |
| `rsrq` | dB | LTE RSRQ or NR SS-RSRQ |
| `sinr` | dB | LTE RSSNR (scaling varies by OEM) or NR SS-SINR |
| `rssi` | dBm | LTE RSSI or GSM RSSI |
| `cqi` | | LTE CQI 0–15 |
| `ta` | | Timing advance as reported (LTE index 0–1282, GSM 0–219) |
| `csiRsrp` | dBm | NR CSI-RSRP |
| `csiRsrq` | dB | NR CSI-RSRQ |
| `csiSinr` | dB | NR CSI-SINR |
| `rscp` | dBm | WCDMA RSCP |
| `ecNo` | dB | WCDMA Ec/No |
| `cellInfoAgeMs` | ms | Tick time minus the cell's own timestamp (`CellInfo.getTimestampMillis()`; for SIGNAL_STRENGTH rows `SignalStrength.getTimestampMillis()`). ~10 s with the screen off is the modem refresh, not a NetLogger delay |

## flat.csv

One row per tick. Identity comes only from the CELL_INFO serving cell. Each signal value takes the CELL_INFO value,
else the SIGNAL_STRENGTH value (never a stale NR entry); its `*Source` column says which (`CELL_INFO`, `SIGNAL_STRENGTH`, or empty when neither
had it). The two sources are sampled at different times; compare `*CellInfoAgeMs` and `*SignalStrengthAgeMs`.

- **LTE:** the registered `servingInferred` LTE CELL_INFO cell (else PRIMARY_SERVING, else the first serving one).
- **NR:** the `servingInferred` NR CELL_INFO cell; with several (NR carrier aggregation) the strongest SS-RSRP.

| Column | Unit | Description |
|---|---|---|
| `seq` | | Tick number |
| `time` | ISO-8601 | Tick wall time |
| `wallTimeMs` | ms | Tick wall time, Unix epoch |
| `elapsedRealtimeNs` | ns | Tick time since boot |
| `lat` | ° | As in measurements.csv |
| `lon` | ° | As in measurements.csv |
| `altitude` | m | As in measurements.csv |
| `speedMps` | m/s | As in measurements.csv |
| `gpsAccuracy` | m | As in measurements.csv |
| `locationAgeMs` | ms | As in measurements.csv |
| `networkType` | | As in measurements.csv |
| `overrideNetworkType` | | As in measurements.csv |
| `operatorNumeric` | | As in measurements.csv |
| `cellBandwidthsJson` | kHz | As in measurements.csv |
| `lteCi` | | Serving LTE ECI |
| `lteTac` | | Serving LTE TAC |
| `ltePci` | | Serving LTE PCI |
| `lteEarfcn` | | Serving LTE EARFCN |
| `lteBand` | | Serving LTE band |
| `lteBandSource` | | As `bandSource` in cells.csv |
| `lteBandwidthKhz` | kHz | Serving LTE bandwidth |
| `lteCellInfoAgeMs` | ms | Age of the CELL_INFO serving LTE cell |
| `lteRsrp` | dBm | LTE RSRP |
| `lteRsrpSource` | | Source of `lteRsrp` |
| `lteRsrq` | dB | LTE RSRQ |
| `lteRsrqSource` | | Source of `lteRsrq` |
| `lteSinr` | dB | LTE RSSNR |
| `lteSinrSource` | | Source of `lteSinr` |
| `lteRssi` | dBm | LTE RSSI |
| `lteRssiSource` | | Source of `lteRssi` |
| `lteCqi` | | LTE CQI |
| `lteCqiSource` | | Source of `lteCqi` |
| `lteTa` | | LTE timing advance |
| `lteTaSource` | | Source of `lteTa` |
| `lteSignalStrengthAgeMs` | ms | Age of the SIGNAL_STRENGTH LTE row |
| `nrServingCount` | count | Number of `servingInferred` NR CELL_INFO cells in the tick (> 1 = NR CA) |
| `nrServingSource` | | `servingSource` of the chosen NR cell |
| `nrNci` | | Serving NR NCI |
| `nrTac` | | Serving NR TAC (empty in NSA: NSA NR cells broadcast none) |
| `nrPci` | | Serving NR PCI |
| `nrArfcn` | | Serving NR-ARFCN |
| `nrBand` | | Serving NR band |
| `nrBandSource` | | As `bandSource` in cells.csv |
| `nrCellInfoAgeMs` | ms | Age of the CELL_INFO serving NR cell |
| `nrRsrp` | dBm | NR SS-RSRP |
| `nrRsrpSource` | | Source of `nrRsrp` |
| `nrRsrq` | dB | NR SS-RSRQ |
| `nrRsrqSource` | | Source of `nrRsrq` |
| `nrSinr` | dB | NR SS-SINR |
| `nrSinrSource` | | Source of `nrSinr` |
| `nrCsiRsrp` | dBm | NR CSI-RSRP |
| `nrCsiRsrpSource` | | Source of `nrCsiRsrp` |
| `nrCsiRsrq` | dB | NR CSI-RSRQ |
| `nrCsiRsrqSource` | | Source of `nrCsiRsrq` |
| `nrCsiSinr` | dB | NR CSI-SINR |
| `nrCsiSinrSource` | | Source of `nrCsiSinr` |
| `nrSignalStrengthAgeMs` | ms | Age of the SIGNAL_STRENGTH NR row |
| `batteryLevel` | % | As in measurements.csv |
| `batteryCurrentUa` | µA | As in measurements.csv |
| `isCharging` | | As in measurements.csv |
| `screenOn` | | As in measurements.csv |
| `thermalStatus` | | As in measurements.csv |
| `rxBytesTotal` | bytes | As in measurements.csv |
| `txBytesTotal` | bytes | As in measurements.csv |
| `parseErrors` | count | As in measurements.csv |
| `warningCount` | count | As in measurements.csv |

## events.csv

| Column | Unit | Description |
|---|---|---|
| `time` | ISO-8601 | Event wall time |
| `wallTimeMs` | ms | Event wall time, Unix epoch |
| `elapsedRealtimeNs` | ns | Event time since boot |
| `type` | | `USER_MARKER` (Live button or notification); `DATA_SIM_CHANGED` (default data SIM changed during the session; `fromValue`/`toValue` are subscription IDs; the session keeps logging its own SIM); RAT/PCI/NR/GPS events from milestone 7 |
| `fromValue` | | Previous value (network events) |
| `toValue` | | New value (network events) |
| `lat` | ° | Location at the event; empty without a fix |
| `lon` | ° | Location at the event |

## warnings.csv

Only anomalies and flagged corrections; sentinel values ("not reported") never raise a warning. Codes are documented
in `core/model/.../radio/Radio.kt` (`WarningCode`) and `docs/decisions/001-cell-parsing.md`.

| Column | Unit | Description |
|---|---|---|
| `code` | | Warning code, e.g. `SAMSUNG_NEIGHBOR_PLACEHOLDER`, `NR_SERVING_STRONGEST` |
| `rat` | | RAT of the affected cell |
| `pci` | | PCI of the affected cell |
| `field` | | Affected field, e.g. `tac`, `connectionStatus` |
| `rawValue` | | Original value at the first occurrence |
| `lastRawValue` | | Original value at the last occurrence |
| `occurrences` | count | Ticks in which it occurred (one per tick and key) |
| `firstSeen` | ISO-8601 | First occurrence |
| `lastSeen` | ISO-8601 | Last occurrence |
| `firstSeenMs` | ms | First occurrence, Unix epoch |
| `lastSeenMs` | ms | Last occurrence, Unix epoch |
| `firstSeq` | | Tick of the first occurrence |
| `firstElapsedRealtimeNs` | ns | First occurrence, time since boot |

## track.geojson

A FeatureCollection. Coordinates are `[lon, lat]` (RFC 7946 order), WGS84, 2D.

- Track: one `LineString` of all ticks with a fix, in order (a `Point` if there is only one fix; nothing if none).
  Properties: `kind` = `track`, `sessionUuid`, `points`, `start`, `end` (ISO-8601).
- Events: one `Point` per event with a location. Properties: `kind` = `event`, `type`, `time`, `wallTimeMs`,
  `elapsedRealtimeNs`, `fromValue`, `toValue`.

## session.json

| Field | Description |
|---|---|
| `format`, `formatVersion` | `netlogger-session`, 1 |
| `uuid` | Session UUID (also in the file name) |
| `label`, `scenario`, `notes` | As entered (`DRIVE`, `WALK`, `STATIC`, `INDOOR`) |
| `startedAt`, `startedAtMs`, `startedElapsedRealtimeNs` | Start, ISO / epoch ms / ns since boot |
| `endedAt`, `endedAtMs`, `endedElapsedRealtimeNs` | End; for `RECOVERED_AFTER_KILL` the last tick |
| `endReason` | `USER_STOP`, `RECOVERED_AFTER_KILL`, `ERROR` |
| `durationMs`, `intervalMs` | End minus start; tick interval |
| `timeZone`, `utcOffset` | Zone used for every ISO time in the bundle (phone zone at export) and its offset at session start |
| `exportedAt` | Export time |
| `appVersion`, `schemaVersion` | NetLogger version and database schema version |
| `device` | `manufacturer`, `model`, `androidVersion`, `basebandVersion` |
| `network` | `subscriptionId`, `simSlot`, `operatorName`, `mcc`, `mnc` of the logged SIM |
| `counts` | Rows per file: `measurements`, `cells`, `events`, `warnings`, and `trackPoints` (ticks with a fix) |
| `files` | Files in the bundle |
