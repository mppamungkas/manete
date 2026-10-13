package id.ac.pnp.netlogger.core.telephony.parser

import id.ac.pnp.netlogger.core.model.radio.Rat

/**
 * A band derived from an ARFCN. [candidates] lists every band containing the frequency, [band] included.
 * [countryPreferred] is true when [band] came from the country's known band list, strong enough to overrule a
 * contradicting getBands().
 */
data class BandMapping(val band: Int, val candidates: List<Int>, val countryPreferred: Boolean = false)

/** Maps an ARFCN to a band when getBands() is empty. Used only as a fallback; results are marked MAPPED. */
fun interface BandMapper {
    /** [mcc] is the registered network's MCC, used to choose between overlapping bands. */
    fun map(rat: Rat, arfcn: Int, mcc: String?): BandMapping?

    companion object {
        val None = BandMapper { _, _, _ -> null }

        /** NR from [NrBandTable]. LTE, WCDMA and GSM follow in milestone 7. */
        val Standard = BandMapper { rat, arfcn, mcc -> if (rat == Rat.NR) NrBandTable.map(arfcn, mcc) else null }
    }
}

/**
 * NR-ARFCN → operating band, from the downlink ranges in 3GPP TS 38.101-1 Table 5.2-1 (FR1) and TS 38.101-2
 * Table 5.2-1 (FR2). Uplink-only (SUL) bands are left out because CellIdentityNr reports the downlink ARFCN.
 * A frequency is matched against the full band range (edges inclusive); the per-band channel raster is not
 * checked, so an ARFCN off the band's raster is still mapped.
 */
object NrBandTable {

    private enum class Duplex { FDD, TDD, SDL }

    private class Band(val number: Int, val duplex: Duplex, val dlLowKhz: Long, val dlHighKhz: Long)

    private fun fdd(n: Int, lowMhz: Double, highMhz: Double) = Band(n, Duplex.FDD, khz(lowMhz), khz(highMhz))
    private fun tdd(n: Int, lowMhz: Double, highMhz: Double) = Band(n, Duplex.TDD, khz(lowMhz), khz(highMhz))
    private fun sdl(n: Int, lowMhz: Double, highMhz: Double) = Band(n, Duplex.SDL, khz(lowMhz), khz(highMhz))
    private fun khz(mhz: Double) = Math.round(mhz * 1000)

    private val BANDS = listOf(
        fdd(1, 2110.0, 2170.0), fdd(2, 1930.0, 1990.0), fdd(3, 1805.0, 1880.0), fdd(5, 869.0, 894.0),
        fdd(7, 2620.0, 2690.0), fdd(8, 925.0, 960.0), fdd(12, 729.0, 746.0), fdd(13, 746.0, 756.0),
        fdd(14, 758.0, 768.0), fdd(18, 860.0, 875.0), fdd(20, 791.0, 821.0), fdd(24, 1525.0, 1559.0),
        fdd(25, 1930.0, 1995.0), fdd(26, 859.0, 894.0), fdd(28, 758.0, 803.0), sdl(29, 717.0, 728.0),
        fdd(30, 2350.0, 2360.0), tdd(34, 2010.0, 2025.0), tdd(38, 2570.0, 2620.0), tdd(39, 1880.0, 1920.0),
        tdd(40, 2300.0, 2400.0), tdd(41, 2496.0, 2690.0), tdd(46, 5150.0, 5925.0), tdd(48, 3550.0, 3700.0),
        tdd(50, 1432.0, 1517.0), tdd(51, 1427.0, 1432.0), tdd(53, 2483.5, 2495.0), fdd(65, 2110.0, 2200.0),
        fdd(66, 2110.0, 2200.0), sdl(67, 738.0, 758.0), fdd(70, 1995.0, 2020.0), fdd(71, 617.0, 652.0),
        fdd(74, 1475.0, 1518.0), sdl(75, 1432.0, 1517.0), sdl(76, 1427.0, 1432.0), tdd(77, 3300.0, 4200.0),
        tdd(78, 3300.0, 3800.0), tdd(79, 4400.0, 5000.0), tdd(90, 2496.0, 2690.0), fdd(91, 1427.0, 1432.0),
        fdd(92, 1432.0, 1517.0), fdd(93, 1427.0, 1432.0), fdd(94, 1432.0, 1517.0), tdd(96, 5925.0, 7125.0),
        fdd(100, 919.4, 925.0), tdd(101, 1900.0, 1910.0), tdd(102, 5925.0, 6425.0), tdd(104, 6425.0, 7125.0),
        fdd(105, 612.0, 652.0),
        tdd(257, 26500.0, 29500.0), tdd(258, 24250.0, 27500.0), tdd(259, 39500.0, 43500.0),
        tdd(260, 37000.0, 40000.0), tdd(261, 27500.0, 28350.0), tdd(262, 47200.0, 48200.0),
        tdd(263, 57000.0, 71000.0),
    )

    /**
     * Bands an operator in this country is known to use, tried first when a frequency falls in several bands.
     * Indonesia (510): NR at 2.6 GHz is TDD n41 (seen on Tri/IOH with G-NetTrack Pro, 2026-10-13), not n7 or
     * n90. To be checked against field data from all operators before it is final (decision 001).
     */
    private val PREFERRED_BY_MCC = mapOf("510" to listOf(41))

    /** Countries where an ambiguous frequency is resolved to a TDD band before an FDD/SDL one. */
    private val TDD_FIRST_MCC = setOf("510")

    /** Global frequency raster, TS 38.104 Table 5.4.2.1-1. Returns null outside 0..3279165. */
    fun frequencyKhz(arfcn: Int): Long? = when (arfcn) {
        in 0 until 600_000 -> 5L * arfcn
        in 600_000 until 2_016_667 -> 3_000_000L + 15L * (arfcn - 600_000)
        in 2_016_667..3_279_165 -> 24_250_080L + 60L * (arfcn - 2_016_667)
        else -> null
    }

    fun candidates(arfcn: Int): List<Int> {
        val f = frequencyKhz(arfcn) ?: return emptyList()
        return BANDS.filter { f in it.dlLowKhz..it.dlHighKhz }.map { it.number }
    }

    /**
     * Picks one band: an MCC-preferred band if it is a candidate; otherwise, among the TDD candidates in
     * TDD-first countries (or among all candidates elsewhere), the narrowest band, then the lowest number.
     * Narrowest first means a band wins over the wider bands defined around it (n78 over n77, n41 over n90,
     * n1 over n65/n66).
     */
    fun map(arfcn: Int, mcc: String?): BandMapping? {
        val f = frequencyKhz(arfcn) ?: return null
        val matches = BANDS.filter { f in it.dlLowKhz..it.dlHighKhz }
        if (matches.isEmpty()) return null
        val numbers = matches.map { it.number }
        val byWidth = compareBy<Band>({ it.dlHighKhz - it.dlLowKhz }, { it.number })
        PREFERRED_BY_MCC[mcc]?.firstOrNull { it in numbers }?.let { return BandMapping(it, numbers, countryPreferred = true) }
        val band = matches.filter { mcc in TDD_FIRST_MCC && it.duplex == Duplex.TDD }.minWithOrNull(byWidth)?.number
            ?: matches.minWith(byWidth).number
        return BandMapping(band, numbers)
    }
}
