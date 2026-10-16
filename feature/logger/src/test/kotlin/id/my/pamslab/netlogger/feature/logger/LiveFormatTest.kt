package id.my.pamslab.netlogger.feature.logger

import id.my.pamslab.netlogger.core.model.radio.CellSample
import id.my.pamslab.netlogger.core.model.radio.CellSource
import id.my.pamslab.netlogger.core.model.radio.ConnectionStatus
import id.my.pamslab.netlogger.core.model.radio.NetworkType
import id.my.pamslab.netlogger.core.model.radio.Rat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveFormatTest {

    @Test
    fun `elapsed time as h mm ss`() {
        assertEquals("0:00:00", formatElapsed(0))
        assertEquals("0:00:00", formatElapsed(-5_000_000_000))
        assertEquals("0:01:05", formatElapsed(65_400_000_000))
        assertEquals("2:03:04", formatElapsed((2 * 3600 + 3 * 60 + 4) * 1_000_000_000L))
    }

    @Test
    fun `network type in words with carrier count`() {
        assertEquals("5G NSA · 3 carriers", networkLabel(NetworkType.NR_NSA, listOf(20_000, 10_000, 10_000)))
        assertEquals("LTE", networkLabel(NetworkType.LTE, listOf(20_000)))
        assertEquals("LTE", networkLabel(NetworkType.LTE, emptyList()))
        assertEquals("No service", networkLabel(NetworkType.NONE, listOf(20_000, 10_000)))
        assertEquals("3G", networkLabel(NetworkType.WCDMA, emptyList()))
    }

    @Test
    fun `data state in words only when not connected`() {
        assertNull(dataStateLabel(2))
        assertNull(dataStateLabel(null))
        assertEquals("Data disconnected", dataStateLabel(0))
    }

    @Test
    fun `NR column fills CellInfo gaps from SignalStrength and marks them`() {
        // moto g45 NSA: the NR CellInfo cell has an ARFCN and band but no PCI and no signal values.
        val nrInfo = cell(Rat.NR, CellSource.CELL_INFO, arfcn = 531_870, band = 41, status = ConnectionStatus.SECONDARY_SERVING)
        val nrSs = cell(Rat.NR, CellSource.SIGNAL_STRENGTH, rsrp = -98, rsrq = -11, sinr = 7)
        val lte = cell(Rat.LTE, CellSource.CELL_INFO, pci = 123, arfcn = 1625, band = 3, rsrp = -91, rsrq = -8, status = ConnectionStatus.PRIMARY_SERVING)
        val columns = servingColumns(listOf(lte, nrInfo, nrSs))

        assertEquals(listOf(Rat.LTE, Rat.NR), columns.map { it.rat })
        val nr = columns[1]
        assertEquals("NR n41", nr.title())
        assertEquals(531_870, nr.arfcn)
        assertEquals(Shown(-98, fromSignalStrength = true), nr.level)
        assertEquals(Shown(7, fromSignalStrength = true), nr.sinr)
        assertEquals("LTE B3", columns[0].title())
        assertEquals(Shown(-91), columns[0].level)
        assertEquals(Shown<Int>(null), columns[0].sinr)
    }

    @Test
    fun `CellInfo values win and the primary cell is used under carrier aggregation`() {
        val secondary = cell(Rat.LTE, CellSource.CELL_INFO, pci = 7, rsrp = -100, status = ConnectionStatus.SECONDARY_SERVING)
        val primary = cell(Rat.LTE, CellSource.CELL_INFO, pci = 123, rsrp = -91, status = ConnectionStatus.PRIMARY_SERVING)
        val ss = cell(Rat.LTE, CellSource.SIGNAL_STRENGTH, rsrp = -95, sinr = 12)
        val column = servingColumns(listOf(secondary, primary, ss)).single()

        assertEquals(123, column.pci)
        assertEquals(Shown(-91), column.level)
        assertEquals(Shown(12, fromSignalStrength = true), column.sinr)
    }

    @Test
    fun `stale only for old CellInfo values, neighbours are not columns`() {
        val old = cell(Rat.LTE, CellSource.CELL_INFO, pci = 1, ageMs = 6_000, status = ConnectionStatus.PRIMARY_SERVING)
        val neighbour = cell(Rat.LTE, CellSource.CELL_INFO, pci = 2, serving = false)
        val column = servingColumns(listOf(old, neighbour)).single()
        assertTrue(column.stale)
        assertFalse(servingColumns(listOf(cell(Rat.NR, CellSource.SIGNAL_STRENGTH, rsrp = -90, ageMs = 60_000))).single().stale)
        assertTrue(servingColumns(listOf(neighbour)).isEmpty())
    }

    private fun cell(
        rat: Rat,
        source: CellSource,
        pci: Int? = null,
        arfcn: Int? = null,
        band: Int? = null,
        rsrp: Int? = null,
        rsrq: Int? = null,
        sinr: Int? = null,
        status: ConnectionStatus? = null,
        ageMs: Long? = 500,
        serving: Boolean = true,
    ) = CellSample(
        rat = rat, servingInferred = serving, isRegistered = null, connectionStatus = status, cellId = null, tac = null,
        pci = pci, arfcn = arfcn, band = band, bandSource = null, bandwidthKhz = null, mcc = null, mnc = null,
        rsrpDbm = rsrp, rsrqDb = rsrq, sinrDb = sinr, rssiDbm = null, cqi = null, timingAdvance = null,
        csiRsrpDbm = null, csiRsrqDb = null, csiSinrDb = null, rscpDbm = null, ecNoDb = null, source = source, cellInfoAgeMs = ageMs,
    )
}
