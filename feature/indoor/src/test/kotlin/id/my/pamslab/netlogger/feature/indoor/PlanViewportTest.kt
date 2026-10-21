package id.my.pamslab.netlogger.feature.indoor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanViewportTest {

    // A 4000 x 2000 px plan in a 1000 x 1000 px view: fitted to the width, 0.25 screen px per plan px, centred.
    private val view = PlanViewport(1000f, 1000f, 4000f, 2000f)

    private fun assertPoint(x: Float, y: Float, actual: Pair<Float, Float>) {
        assertEquals(x, actual.first, 0.01f)
        assertEquals(y, actual.second, 0.01f)
    }

    @Test
    fun `fitted and centred at zoom 1`() {
        assertEquals(0.25f, view.scale, 1e-6f)
        assertPoint(0f, 250f, view.toScreen(0f, 0f))
        assertPoint(2000f, 1000f, view.toPlan(500f, 500f))
        assertTrue(view.onPlan(4000f, 2000f))
        assertFalse(view.onPlan(2000f, -1f))
    }

    @Test
    fun `toPlan and toScreen are inverse`() {
        val v = view.transformed(300f, 400f, 25f, -40f, 3f)
        val (sx, sy) = v.toScreen(1234f, 567f)
        assertPoint(1234f, 567f, v.toPlan(sx, sy))
    }

    @Test
    fun `zooming keeps the plan point under the fingers`() {
        val before = view.toPlan(600f, 500f)
        val zoomed = view.transformed(600f, 500f, 0f, 0f, 4f)
        assertEquals(4f, zoomed.zoom, 1e-6f)
        assertPoint(before.first, before.second, zoomed.toPlan(600f, 500f))
    }

    @Test
    fun `zoom stays between 1 and the maximum`() {
        assertEquals(1f, view.transformed(500f, 500f, 0f, 0f, 0.2f).zoom, 1e-6f)
        assertEquals(PlanViewport.MAX_ZOOM, view.transformed(500f, 500f, 0f, 0f, 100f).zoom, 1e-6f)
    }

    @Test
    fun `the plan cannot be dragged out of view`() {
        assertEquals("smaller than the view: stays centred", 0f, view.transformed(500f, 500f, 300f, 300f, 1f).panX, 1e-6f)
        val zoomed = view.transformed(500f, 500f, 0f, 0f, 2f) // 2000 x 1000 on screen
        val dragged = zoomed.transformed(500f, 500f, 5000f, 0f, 1f)
        assertEquals("left edge stops at the view's left edge", 0f, dragged.left, 0.01f)
        assertEquals(1f, zoomed.reset().zoom, 0f)
    }
}
