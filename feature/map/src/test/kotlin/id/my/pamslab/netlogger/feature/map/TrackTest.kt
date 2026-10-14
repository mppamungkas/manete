package id.my.pamslab.netlogger.feature.map

import id.my.pamslab.netlogger.core.data.db.EventEntity
import id.my.pamslab.netlogger.core.export.FlatRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackTest {

    private val a56Tick = listOf(
        cell("LTE", pci = 479, rsrp = -92),
        cell("NR", pci = 440, rsrp = -101, sinr = 7, servingSource = "SIGNAL_STRENGTH_MATCH"),
        cell("NR", serving = false, pci = 438, rsrp = -85, sinr = 20, servingSource = "SIGNAL_STRENGTH_MATCH"),
        cell("LTE", source = "SIGNAL_STRENGTH", rsrp = -93, sinr = 15),
        cell("NR", source = "SIGNAL_STRENGTH", rsrp = -101, sinr = 9),
    )

    @Test
    fun `point values follow flat csv - serving cells, CELL_INFO first, SignalStrength for gaps`() {
        val p = TrackPoint.of(FlatRow.of(tick(0), a56Tick)) ?: error("point expected")
        assertEquals(479, p.ltePci)
        assertEquals(-92, p.lteRsrp); assertEquals("CELL_INFO", p.lteRsrpSource)
        assertEquals(15, p.lteSinr); assertEquals("SIGNAL_STRENGTH", p.lteSinrSource)
        assertEquals("the demoted, stronger neighbor 438 is not shown", 440, p.nrPci)
        assertEquals(-101, p.nrRsrp)
        assertEquals("SIGNAL_STRENGTH_MATCH", p.nrServingSource)
    }

    @Test
    fun `ticks without a fix are not placed`() {
        assertNull(TrackPoint.of(FlatRow.of(tick(0, lat = null, lon = null), a56Tick)))
        assertNull(TrackPoint.of(FlatRow.of(tick(0, lat = Double.NaN), a56Tick)))
    }

    @Test
    fun `colors per metric use the agreed thresholds`() {
        val p = TrackPoint.of(FlatRow.of(tick(0), a56Tick)) ?: error("point expected")
        assertEquals("-92 dBm is yellow", "#FDD835", Metric.LTE_RSRP.hex(p))
        assertEquals("-101 dBm is orange", "#FB8C00", Metric.NR_RSRP.hex(p))
        assertEquals("15 dB is yellow", "#FDD835", Metric.LTE_SINR.hex(p))
        assertEquals("7 dB is orange", "#FB8C00", Metric.NR_SINR.hex(p))
        assertEquals("#8E24AA", Metric.NETWORK_TYPE.hex(p))
        assertEquals("#9E9E9E", NetworkColors.hex("SOMETHING_NEW"))
        val empty = TrackPoint.of(FlatRow.of(tick(0), emptyList())) ?: error("point expected")
        Metric.entries.filter { it != Metric.NETWORK_TYPE }.forEach { assertEquals("no value is gray", "#9E9E9E", it.hex(empty)) }
    }

    @Test
    fun `stopped session loads in pages and emits once`() = runTest {
        val dao = LiveDao((0L until 1_201).map { tick(it, lat = if (it % 100 == 0L) null else -0.9) }) { a56Tick }
        val track = TrackLoader(dao).track(1).first()
        assertEquals(1_201L, track.ticks)
        assertEquals("13 ticks without a fix are skipped", 1_201 - 13, track.points.size)
        assertEquals((0L until 1_201).filter { it % 100 != 0L }, track.points.map { it.seq })
        assertEquals(4, dao.measurementQueries) // 500 + 500 + 201 + empty page
    }

    @Test
    fun `recording session grows with each new tick and only reads the new ones`() = runTest {
        val dao = LiveDao(listOf(tick(0), tick(1))) { a56Tick }
        val emissions = mutableListOf<Track>()
        val job = launch { TrackLoader(dao).track(1).take(3).toList(emissions) }
        yield()
        dao.events += EventEntity(1, 1, T0 + 2_000, 2, "USER_MARKER", lat = -0.9, lon = 100.4)
        dao.add(tick(2))
        yield()
        dao.add(tick(3, lat = null, lon = null))
        job.join()
        assertEquals(listOf(2, 3, 3), emissions.map { it.points.size })
        assertEquals(listOf(2L, 3L, 4L), emissions.map { it.ticks })
        assertEquals(listOf(0, 1, 1), emissions.map { it.events.size })
        assertEquals(6, dao.measurementQueries) // each load: one page + one empty page
    }
}
