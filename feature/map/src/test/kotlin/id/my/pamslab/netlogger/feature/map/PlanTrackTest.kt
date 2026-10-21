package id.my.pamslab.netlogger.feature.map

import id.my.pamslab.netlogger.core.data.FloorPlanSummary
import id.my.pamslab.netlogger.core.data.SessionIndoor
import id.my.pamslab.netlogger.core.model.indoor.FloorPlan
import id.my.pamslab.netlogger.core.model.indoor.Waypoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class PlanTrackTest {

    private val s = 1_000_000_000L
    private fun plan(id: Long, name: String) = FloorPlanSummary(FloorPlan(id, "u$id", name, null, 1000, 800), File("$id.png"), 0, 1, 2)
    private val c2 = plan(1, "C 2")
    private val c3 = plan(2, "C 3")

    /** Ticks 0..20 at one per second, every 5th without a GPS fix (indoors). */
    private suspend fun track(): Track {
        val dao = LiveDao((0L..20L).map { tick(it, lat = if (it % 5 == 0L) null else -0.9) }) { listOf(cell("LTE", pci = 1, rsrp = -85)) }
        return TrackLoader(dao).track(1).first()
    }

    private fun tap(atS: Long, x: Double, planId: Long = 1, removed: Boolean = false) = Waypoint(planId, 0, atS * s, x, 100.0, removed = removed)

    @Test
    fun `every tick between taps is placed, also those without a GPS fix`() = runTest {
        val t = track()
        assertEquals(21, t.values.size)
        assertEquals(16, t.points.size)
        val plan = planTrack(t, SessionIndoor(listOf(c2), listOf(tap(2, 0.0), tap(12, 1000.0))), null) ?: error("plan expected")
        assertEquals((2L..12L).toList(), plan.points.map { it.values.seq })
        assertEquals(500.0, plan.points.first { it.values.seq == 7L }.position.x, 1e-9)
        assertEquals(21, plan.ticks)
        assertEquals(2, plan.path.size)
    }

    @Test
    fun `the default floor has the most taps, undone taps do not count, another floor can be chosen`() = runTest {
        val t = track()
        val indoor = SessionIndoor(
            listOf(c2, c3),
            listOf(tap(1, 0.0), tap(3, 10.0), tap(5, 20.0, removed = true), tap(8, 0.0, 2), tap(10, 20.0, 2), tap(11, 30.0, 2)),
        )
        assertEquals(c3, planTrack(t, indoor, null)?.plan)
        assertEquals(listOf(c3, c2), planTrack(t, indoor, null)?.plans)
        val onC2 = planTrack(t, indoor, c2.plan.id) ?: error("plan expected")
        assertEquals((1L..3L).toList(), onC2.points.map { it.values.seq })
        assertEquals("the undone tap is not on the path", 2, onC2.path.size)
        assertNull("no taps: no plan view", planTrack(t, SessionIndoor(listOf(c2), listOf(tap(1, 0.0, removed = true))), null))
    }

    @Test
    fun `a tap selects the nearest placed tick within reach`() = runTest {
        val plan = planTrack(track(), SessionIndoor(listOf(c2), listOf(tap(0, 0.0), tap(10, 1000.0))), null) ?: error("plan expected")
        assertEquals(3L, nearestPoint(plan.points, 310f, 105f, 60f)?.values?.seq)
        assertNull(nearestPoint(plan.points, 310f, 300f, 60f))
    }
}
