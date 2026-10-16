package id.my.pamslab.netlogger.feature.logger

import id.my.pamslab.netlogger.core.model.radio.CellSample
import id.my.pamslab.netlogger.core.model.radio.CellSource
import id.my.pamslab.netlogger.core.model.radio.ConnectionStatus
import id.my.pamslab.netlogger.core.model.radio.NetworkType
import id.my.pamslab.netlogger.core.model.radio.Rat
import id.my.pamslab.netlogger.core.model.session.Scenario
import java.util.Locale

// Pure formatting for the Live dashboard (decision 004), unit-tested.

/** "h:mm:ss" from nanoseconds; negative counts as 0. */
internal fun formatElapsed(ns: Long): String {
    val s = (ns / 1_000_000_000).coerceAtLeast(0)
    return "%d:%02d:%02d".format(Locale.ROOT, s / 3600, s / 60 % 60, s % 60)
}

internal fun Scenario.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }

/** Network type in words, plus the carrier count when the phone reports more than one cell bandwidth. */
internal fun networkLabel(type: NetworkType, cellBandwidthsKhz: List<Int>): String {
    val base = when (type) {
        NetworkType.NR_SA -> "5G SA"
        NetworkType.NR_NSA -> "5G NSA"
        NetworkType.LTE -> "LTE"
        NetworkType.WCDMA -> "3G"
        NetworkType.GSM -> "2G"
        NetworkType.NONE -> return "No service"
    }
    return if (cellBandwidthsKhz.size > 1) "$base · ${cellBandwidthsKhz.size} carriers" else base
}

/** TelephonyManager data states other than connected, in words; null when connected or unknown. */
internal fun dataStateLabel(dataState: Int?): String? = when (dataState) {
    DATA_DISCONNECTED -> "Data disconnected"
    DATA_CONNECTING -> "Data connecting"
    DATA_SUSPENDED -> "Data suspended"
    else -> null
}

private const val DATA_DISCONNECTED = 0
private const val DATA_CONNECTING = 1
private const val DATA_SUSPENDED = 3

/** A value on the Live serving table and whether it came from SignalStrength rather than CellInfo. */
internal data class Shown<T>(val value: T?, val fromSignalStrength: Boolean = false)

/**
 * One serving RAT as a column of the Live table: the serving CellInfo cell (the primary one under carrier aggregation)
 * with every missing value filled from the SignalStrength row of the same RAT (decision 004).
 */
internal data class ServingColumn(
    val rat: Rat,
    val band: Int?,
    val pci: Int?,
    val arfcn: Int?,
    /** RSRP for LTE/NR, RSCP for WCDMA, RSSI for GSM. */
    val level: Shown<Int>,
    val rsrq: Shown<Int>,
    val sinr: Shown<Int>,
    val cellId: Long?,
    val tac: Int?,
    /** The CellInfo values are older than [STALE_AGE_MS]. */
    val stale: Boolean,
)

internal fun servingColumns(cells: List<CellSample>): List<ServingColumn> = Rat.entries.mapNotNull { rat ->
    val serving = cells.filter { it.rat == rat && it.servingInferred }
    val infos = serving.filter { it.source == CellSource.CELL_INFO }
    val info = infos.firstOrNull { it.connectionStatus == ConnectionStatus.PRIMARY_SERVING || it.isRegistered == true }
        ?: infos.firstOrNull()
    val ss = serving.firstOrNull { it.source == CellSource.SIGNAL_STRENGTH }
    if (info == null && ss == null) return@mapNotNull null
    fun <T> pick(get: (CellSample) -> T?): Shown<T> =
        info?.let(get)?.let { Shown(it) } ?: ss?.let(get)?.let { Shown(it, fromSignalStrength = true) } ?: Shown(null)
    ServingColumn(
        rat = rat,
        band = info?.band ?: ss?.band,
        pci = info?.pci ?: ss?.pci,
        arfcn = info?.arfcn ?: ss?.arfcn,
        level = pick { it.rsrpDbm ?: it.rscpDbm ?: it.rssiDbm },
        rsrq = pick { it.rsrqDb },
        sinr = pick { it.sinrDb },
        cellId = info?.cellId,
        tac = info?.tac,
        stale = info != null && (info.cellInfoAgeMs ?: 0) > STALE_AGE_MS,
    )
}

/** "LTE B3", "NR n41", "3G B1", "2G 900"; without a band only the RAT. */
internal fun ServingColumn.title(): String {
    val name = when (rat) {
        Rat.LTE -> "LTE"
        Rat.NR -> "NR"
        Rat.WCDMA -> "3G"
        Rat.GSM -> "2G"
    }
    val b = band ?: return name
    return when (rat) {
        Rat.LTE, Rat.WCDMA -> "$name B$b"
        Rat.NR -> "$name n$b"
        Rat.GSM -> "$name $b"
    }
}

/** Values older than this are grayed out. */
internal const val STALE_AGE_MS = 5_000L
