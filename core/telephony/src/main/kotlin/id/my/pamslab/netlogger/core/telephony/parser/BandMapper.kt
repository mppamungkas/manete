package id.my.pamslab.netlogger.core.telephony.parser

import id.my.pamslab.netlogger.core.model.radio.Rat

/**
 * A band derived from an ARFCN. [candidates] lists every band containing the frequency, [band] included.
 * [countryPreferred] is true when [band] came from the country's known band list, strong enough to overrule a
 * contradicting getBands().
 */
data class BandMapping(val band: Int, val candidates: List<Int>, val countryPreferred: Boolean = false)

/**
 * Maps an ARFCN to a band when getBands() is empty (results are marked MAPPED), and checks getBands() where the ARFCN
 * allows only one band or the country's band is known.
 */
fun interface BandMapper {
    /** [mcc] is the registered network's MCC, used to choose between overlapping bands. */
    fun map(rat: Rat, arfcn: Int, mcc: String?): BandMapping?

    companion object {
        val None = BandMapper { _, _, _ -> null }

        val Standard = BandMapper { rat, arfcn, mcc ->
            when (rat) {
                Rat.LTE -> LteBandTable.map(arfcn)
                Rat.NR -> NrBandTable.map(arfcn, mcc)
                Rat.WCDMA -> WcdmaBandTable.map(arfcn)
                Rat.GSM -> GsmBandTable.map(arfcn, mcc)
            }
        }
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
     * n90. To be checked against field data from all operators before it is final (decision 001). 700 MHz follows the
     * APT plan (n28), not the US n14 inside it.
     */
    private val PREFERRED_BY_MCC = mapOf("510" to listOf(41, 28))

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

/**
 * EARFCN → E-UTRA operating band, from the downlink EARFCN ranges in 3GPP TS 36.101 Table 5.7.3-1. Each band has its
 * own EARFCN range, so the result is unique. Bands used in Indonesia (1, 3, 5, 8, 40) are checked against getBands()
 * on our phones (see LteBandTableTest); the rest is from the table only.
 */
object LteBandTable {

    private class Band(val number: Int, val first: Int, val last: Int)

    // (band, first DL EARFCN = N_Offs-DL, last DL EARFCN)
    private val BANDS = listOf(
        Band(1, 0, 599), Band(2, 600, 1199), Band(3, 1200, 1949), Band(4, 1950, 2399), Band(5, 2400, 2649),
        Band(6, 2650, 2749), Band(7, 2750, 3449), Band(8, 3450, 3799), Band(9, 3800, 4149), Band(10, 4150, 4749),
        Band(11, 4750, 4949), Band(12, 5010, 5179), Band(13, 5180, 5279), Band(14, 5280, 5379), Band(17, 5730, 5849),
        Band(18, 5850, 5999), Band(19, 6000, 6149), Band(20, 6150, 6449), Band(21, 6450, 6599), Band(22, 6600, 7399),
        Band(23, 7500, 7699), Band(24, 7700, 8039), Band(25, 8040, 8689), Band(26, 8690, 9039), Band(27, 9040, 9209),
        Band(28, 9210, 9659), Band(29, 9660, 9769), Band(30, 9770, 9869), Band(31, 9870, 9919), Band(32, 9920, 10359),
        Band(33, 36000, 36199), Band(34, 36200, 36349), Band(35, 36350, 36949), Band(36, 36950, 37549),
        Band(37, 37550, 37749), Band(38, 37750, 38249), Band(39, 38250, 38649), Band(40, 38650, 39649),
        Band(41, 39650, 41589), Band(42, 41590, 43589), Band(43, 43590, 45589), Band(44, 45590, 46589),
        Band(45, 46590, 46789), Band(46, 46790, 54539), Band(47, 54540, 55239), Band(48, 55240, 56739),
        Band(49, 56740, 58239), Band(50, 58240, 59089), Band(51, 59090, 59139), Band(52, 59140, 60139),
        Band(53, 60140, 60254), Band(65, 65536, 66435), Band(66, 66436, 67335), Band(67, 67336, 67535),
        Band(68, 67536, 67835), Band(69, 67836, 68335), Band(70, 68336, 68585), Band(71, 68586, 68935),
        Band(72, 68936, 68985), Band(73, 68986, 69035), Band(74, 69036, 69465), Band(75, 69466, 70315),
        Band(76, 70316, 70365), Band(85, 70366, 70545),
    )

    fun map(earfcn: Int): BandMapping? =
        BANDS.firstOrNull { earfcn in it.first..it.last }?.let { BandMapping(it.number, listOf(it.number)) }
}

/**
 * UARFCN → UTRA FDD band, from the downlink general UARFCN ranges in 3GPP TS 25.101 Table 5.2. Only bands whose
 * ranges do not overlap are listed (band VI and XIX, inside band V, and the additional channels are left out).
 */
object WcdmaBandTable {

    private class Band(val number: Int, val first: Int, val last: Int)

    private val BANDS = listOf(
        Band(1, 10562, 10838), Band(2, 9662, 9938), Band(3, 1162, 1513), Band(4, 1537, 1738),
        Band(5, 4357, 4458), Band(8, 2937, 3088), Band(9, 9237, 9387),
    )

    fun map(uarfcn: Int): BandMapping? =
        BANDS.firstOrNull { uarfcn in it.first..it.last }?.let { BandMapping(it.number, listOf(it.number)) }
}

/**
 * GSM ARFCN → band, named by its frequency in MHz (850, 900, 1800, 1900) because GSM bands have no common number
 * (3GPP TS 45.005 clause 2). ARFCN 512–810 is DCS 1800 or PCS 1900: PCS in the Americas (MCC 3xx and 7xx), DCS
 * elsewhere and when the MCC is unknown.
 */
object GsmBandTable {

    fun map(arfcn: Int, mcc: String?): BandMapping? = when (arfcn) {
        in 0..124, in 955..1023 -> BandMapping(900, listOf(900))
        in 128..251 -> BandMapping(850, listOf(850))
        in 512..810 -> {
            val pcs = mcc?.firstOrNull() == '3' || mcc?.firstOrNull() == '7'
            BandMapping(if (pcs) 1900 else 1800, listOf(1800, 1900))
        }
        in 811..885 -> BandMapping(1800, listOf(1800))
        else -> null
    }
}
