package id.my.pamslab.netlogger.feature.indoor

import org.junit.Assert.assertEquals
import org.junit.Test

class GeorefTapsTest {

    @Test
    fun `plan and map taps fill one point at a time, in either order`() {
        var p = withPlanTap(emptyList(), 10.0, 20.0)
        p = withPlanTap(p, 11.0, 21.0)
        assertEquals("a second plan tap corrects point A", listOf(PointPair(11.0, 21.0)), p)
        p = withMapTap(p, -0.9, 100.4)
        assertEquals(PointPair(11.0, 21.0, -0.9, 100.4), p.single())
        p = withMapTap(p, -0.8, 100.5) // B starts on the map
        p = withPlanTap(p, 900.0, 20.0)
        assertEquals(PointPair(900.0, 20.0, -0.8, 100.5), p[1])
        p = withPlanTap(withMapTap(p, -0.7, 100.6), 500.0, 800.0)
        assertEquals(3, p.size)
        assertEquals("no fourth point", p, withPlanTap(p, 1.0, 1.0))
        assertEquals(true, p.all { it.complete })
    }
}
