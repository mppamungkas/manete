package id.my.pamslab.netlogger.core.model.indoor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoTransformTest {

    // A plan placed near Padang (-0.906, 100.407): 0.05 m per pixel, its up direction turned 30° clockwise from north.
    private val lat0 = -0.906
    private val lon0 = 100.407
    private val mPerDeg = 6_371_008.8 * Math.PI / 180
    private val scale = 0.05
    private val rot = Math.toRadians(30.0)

    /** Where plan pixel x/y truly lies. */
    private fun truth(x: Double, y: Double): Pair<Double, Double> {
        // Plan right = (cos, -sin) rotated; plan up (-y) = (sin, cos) in east/north for a clockwise turn.
        val east = scale * (x * Math.cos(rot) + -y * Math.sin(rot))
        val north = scale * (-x * Math.sin(rot) + -y * Math.cos(rot))
        return lat0 + north / mPerDeg to lon0 + east / (mPerDeg * Math.cos(Math.toRadians(lat0)))
    }

    private fun point(x: Double, y: Double) = truth(x, y).let { (lat, lon) -> GeoPoint(x, y, lat, lon) }

    private fun distanceM(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val north = (a.first - b.first) * mPerDeg
        val east = (a.second - b.second) * mPerDeg * Math.cos(Math.toRadians(lat0))
        return Math.hypot(north, east)
    }

    @Test
    fun `two points place the whole plan - scale, rotation and any pixel`() {
        val t = Georeference(listOf(point(100.0, 200.0), point(2900.0, 1800.0))).transform
        requireNotNull(t)
        assertEquals(scale, t.metresPerPx, 1e-6)
        assertEquals(30.0, t.rotationDeg, 1e-4)
        assertEquals(0.0, t.rmsM, 1e-4)
        listOf(0.0 to 0.0, 1500.0 to 1000.0, 3000.0 to 2000.0, 400.0 to 1900.0).forEach { (x, y) ->
            assertEquals("pixel $x/$y", 0.0, distanceM(t.toLatLon(x, y), truth(x, y)), 0.01)
        }
    }

    @Test
    fun `a third point is fitted by least squares and its error shows`() {
        val exact = Georeference(listOf(point(0.0, 0.0), point(3000.0, 0.0), point(1500.0, 2000.0))).transform!!
        assertEquals("micrometres from the local projection only", 0.0, exact.rmsM, 1e-4)
        // The third point placed 2 m too far north on the map.
        val (lat, lon) = truth(1500.0, 2000.0)
        val off = Georeference(listOf(point(0.0, 0.0), point(3000.0, 0.0), GeoPoint(1500.0, 2000.0, lat + 2.0 / mPerDeg, lon))).transform!!
        assertEquals(3, off.residualsM.size)
        assertEquals("the error is shared by the three points", true, off.rmsM in 0.5..2.0)
        assertEquals("the plan centre moves less than the misplaced point", true, distanceM(off.toLatLon(1500.0, 1000.0), truth(1500.0, 1000.0)) < 1.0)
    }

    @Test
    fun `too few or too close points give no placement`() {
        assertNull(Georeference(emptyList()).transform)
        assertNull(Georeference(listOf(point(10.0, 10.0))).transform)
        assertNull("plan points 4 px apart", Georeference(listOf(point(10.0, 10.0), point(14.0, 10.0))).transform)
        assertNull(
            "map points on top of each other",
            Georeference(listOf(GeoPoint(0.0, 0.0, lat0, lon0), GeoPoint(1000.0, 0.0, lat0, lon0))).transform,
        )
    }

    @Test
    fun `a plan with north up and no rotation`() {
        val north = Georeference(listOf(GeoPoint(0.0, 0.0, 1.0, 100.0), GeoPoint(0.0, 1000.0, 1.0 - 100.0 / mPerDeg, 100.0))).transform!!
        assertEquals(0.0, north.rotationDeg, 1e-6)
        assertEquals(0.1, north.metresPerPx, 1e-9)
    }
}
