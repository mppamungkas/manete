package id.my.pamslab.netlogger.feature.logger.plan

import id.my.pamslab.netlogger.core.model.indoor.FloorPlan
import id.my.pamslab.netlogger.core.model.indoor.Waypoint
import id.my.pamslab.netlogger.core.ui.signal.SignalLevel
import id.my.pamslab.netlogger.feature.logger.service.IndoorState
import id.my.pamslab.netlogger.feature.logger.service.PlanTick
import id.my.pamslab.netlogger.feature.logger.service.StoredWaypoint
import org.junit.Assert.assertEquals
import org.junit.Test

class PlacedTicksTest {

    private val s = 1_000_000_000L
    private val floor2 = FloorPlan(1, "a", "C", "2", 1000, 1000)
    private val floor3 = FloorPlan(2, "b", "C", "3", 1000, 1000)

    private fun tick(atS: Long, lteRsrp: Int?) = PlanTick(atS * s, "LTE", lteRsrp, null, null, null)

    @Test
    fun `only ticks between taps on the current plan are placed, coloured by the metric`() {
        val indoor = IndoorState(
            plan = floor2,
            waypoints = listOf(
                StoredWaypoint(1, Waypoint(1, 0, 10 * s, 0.0, 0.0)),
                StoredWaypoint(2, Waypoint(1, 0, 20 * s, 100.0, 0.0)),
                StoredWaypoint(3, Waypoint(2, 0, 30 * s, 50.0, 50.0)),
                StoredWaypoint(4, Waypoint(2, 0, 40 * s, 60.0, 50.0)),
            ),
            ticks = listOf(tick(5, -70), tick(15, -70), tick(18, -100), tick(25, -90), tick(35, -120)),
        )
        val placed = placedTicks(indoor, PlanMetric.LTE_RSRP)
        assertEquals("before the first tap, on the stairs and on another floor: not placed", 2, placed.size)
        assertEquals(50.0, placed[0].position.x, 1e-9)
        assertEquals(SignalLevel.GOOD.argb, placed[0].argb)
        assertEquals(SignalLevel.POOR.argb, placed[1].argb)

        val onFloor3 = placedTicks(indoor.copy(plan = floor3), PlanMetric.LTE_RSRP)
        assertEquals(listOf(55.0), onFloor3.map { it.position.x })
        assertEquals(SignalLevel.BAD.argb, onFloor3.single().argb)
        assertEquals(emptyList<PlacedTick>(), placedTicks(indoor.copy(plan = null), PlanMetric.LTE_RSRP))
    }

    @Test
    fun `elapsed time for the plan view`() {
        assertEquals("0:42", formatElapsedShort(42 * s))
        assertEquals("1:02:07", formatElapsedShort(3727 * s))
        assertEquals("0:00", formatElapsedShort(-5 * s))
    }
}
