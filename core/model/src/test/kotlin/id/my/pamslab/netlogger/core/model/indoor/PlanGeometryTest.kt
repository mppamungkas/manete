package id.my.pamslab.netlogger.core.model.indoor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanGeometryTest {

    @Test
    fun `stored size fits 4096 px on the long side and never enlarges`() {
        assertEquals(4096 to 2304, PlanGeometry.storedSize(8000, 4500))
        assertEquals(2304 to 4096, PlanGeometry.storedSize(4500, 8000))
        assertEquals(1200 to 800, PlanGeometry.storedSize(1200, 800))
        assertEquals("a thin strip keeps 1 px", 4096 to 1, PlanGeometry.storedSize(100_000, 2))
    }

    @Test
    fun `PDF pages render at 300 dpi within the limit`() {
        assertEquals("A4 portrait (595 x 842 pt)", 2479 to 3508, PlanGeometry.pdfRenderSize(595, 842))
        assertEquals("A1 landscape (9933 x 7017 px at 300 dpi) is capped", 4096 to 2894, PlanGeometry.pdfRenderSize(2384, 1684))
    }

    @Test
    fun `scale from two points and their distance`() {
        assertEquals(0.05, PlanGeometry.metresPerPx(100.0, 100.0, 400.0, 500.0, 25.0)!!, 1e-12) // 500 px = 25 m
        assertNull("points too close", PlanGeometry.metresPerPx(100.0, 100.0, 105.0, 105.0, 1.0))
        assertNull("no distance", PlanGeometry.metresPerPx(0.0, 0.0, 300.0, 0.0, 0.0))
        assertNull("not a number", PlanGeometry.metresPerPx(0.0, 0.0, 300.0, 0.0, Double.NaN))
    }
}
