package id.my.pamslab.netlogger.core.model.indoor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanInterpolatorTest {

    private val s = 1_000_000_000L
    private val t0 = 500_000 * s

    private fun wp(atS: Long, x: Double, y: Double, plan: Long = 1, kind: WaypointKind = WaypointKind.HERE, removed: Boolean = false) =
        Waypoint(plan, 1_792_400_000_000 + atS * 1000, t0 + atS * s, x, y, kind, removed)

    private fun assertPosition(expected: PlanPosition, actual: PlanPosition?) {
        requireNotNull(actual) { "expected $expected, got null" }
        assertEquals(expected.planId, actual.planId)
        assertEquals(expected.x, actual.x, 1e-9)
        assertEquals(expected.y, actual.y, 1e-9)
        assertEquals(expected.waypointGapS, actual.waypointGapS, 1e-9)
    }

    @Test
    fun `between two taps the tick is on the line, in proportion to time`() {
        val taps = listOf(wp(0, 100.0, 200.0), wp(10, 300.0, 200.0), wp(20, 300.0, 600.0))
        assertPosition(PlanPosition(1, 100.0, 200.0, 0.0), PlanInterpolator.at(t0, taps))
        assertPosition(PlanPosition(1, 160.0, 200.0, 3.0), PlanInterpolator.at(t0 + 3 * s, taps))
        assertPosition(PlanPosition(1, 300.0, 200.0, 0.0), PlanInterpolator.at(t0 + 10 * s, taps))
        assertPosition(PlanPosition(1, 300.0, 500.0, 2.5), PlanInterpolator.at(t0 + 17_500_000_000, taps))
        assertPosition(PlanPosition(1, 300.0, 600.0, 0.0), PlanInterpolator.at(t0 + 20 * s, taps))
    }

    @Test
    fun `no position before the first tap, after the last one, or without taps`() {
        val taps = listOf(wp(5, 0.0, 0.0), wp(15, 10.0, 0.0))
        assertNull(PlanInterpolator.at(t0 + 4 * s, taps))
        assertNull(PlanInterpolator.at(t0 + 16 * s, taps))
        assertNull(PlanInterpolator.at(t0, emptyList()))
        assertPosition(PlanPosition(1, 0.0, 0.0, 0.0), PlanInterpolator.at(t0 + 5 * s, listOf(wp(5, 0.0, 0.0))))
        assertNull("a single tap places only its own moment", PlanInterpolator.at(t0 + 6 * s, listOf(wp(5, 0.0, 0.0))))
    }

    @Test
    fun `a floor change leaves the ticks between the floors without a position`() {
        val taps = listOf(wp(0, 50.0, 50.0, plan = 1), wp(10, 80.0, 50.0, plan = 1), wp(40, 20.0, 20.0, plan = 2), wp(50, 40.0, 20.0, plan = 2))
        assertPosition(PlanPosition(1, 65.0, 50.0, 5.0), PlanInterpolator.at(t0 + 5 * s, taps))
        assertNull("on the stairs", PlanInterpolator.at(t0 + 25 * s, taps))
        assertPosition(PlanPosition(2, 30.0, 20.0, 5.0), PlanInterpolator.at(t0 + 45 * s, taps))
    }

    @Test
    fun `undone taps are ignored, a still-here tap keeps the spot`() {
        val taps = listOf(
            wp(0, 0.0, 0.0),
            wp(10, 999.0, 999.0, removed = true),
            wp(20, 100.0, 0.0),
            wp(50, 100.0, 0.0, kind = WaypointKind.STILL),
            wp(60, 100.0, 100.0),
        )
        assertPosition(PlanPosition(1, 50.0, 0.0, 10.0), PlanInterpolator.at(t0 + 10 * s, taps))
        assertPosition(PlanPosition(1, 100.0, 0.0, 15.0), PlanInterpolator.at(t0 + 35 * s, taps))
        assertPosition(PlanPosition(1, 100.0, 50.0, 5.0), PlanInterpolator.at(t0 + 55 * s, taps))
    }

    @Test
    fun `taps stored out of order are sorted by time, the later of two equal times wins`() {
        val taps = listOf(wp(10, 10.0, 0.0), wp(0, 0.0, 0.0), wp(10, 20.0, 0.0), wp(20, 20.0, 20.0))
        assertPosition(PlanPosition(1, 5.0, 0.0, 5.0), PlanInterpolator.at(t0 + 5 * s, taps))
        assertPosition(PlanPosition(1, 20.0, 0.0, 0.0), PlanInterpolator.at(t0 + 10 * s, taps))
        assertPosition(PlanPosition(1, 20.0, 10.0, 5.0), PlanInterpolator.at(t0 + 15 * s, taps))
    }

    @Test
    fun `positions for many ticks stay aligned with the ticks`() {
        val taps = listOf(wp(1, 0.0, 0.0), wp(3, 20.0, 0.0))
        val ticks = listOf(t0, t0 + 2 * s, t0 + 4 * s, t0 + 1 * s)
        val out = PlanInterpolator.positions(ticks, taps)
        assertNull(out[0])
        assertPosition(PlanPosition(1, 10.0, 0.0, 1.0), out[1])
        assertNull(out[2])
        assertPosition(PlanPosition(1, 0.0, 0.0, 0.0), out[3])
    }

    @Test
    fun `metres need a scale`() {
        val plan = FloorPlan(1, "u", "Building C", "2nd floor", 2000, 1000, metresPerPx = 0.05)
        assertEquals(10.0 to 5.0, PlanPosition(1, 200.0, 100.0, 0.0).metres(plan))
        assertNull(PlanPosition(1, 200.0, 100.0, 0.0).metres(plan.copy(metresPerPx = null)))
    }
}
