package id.my.pamslab.netlogger.feature.indoor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FloorPlanInputTest {

    @Test
    fun `distance accepts a decimal point or comma`() {
        assertEquals(12.5, parseMetres("12.5")!!, 0.0)
        assertEquals(12.5, parseMetres(" 12,5 ")!!, 0.0)
        assertNull(parseMetres(""))
        assertNull(parseMetres("0"))
        assertNull(parseMetres("1.2.3"))
    }

    @Test
    fun `the default name is the file name without its extension`() {
        assertEquals("Building C 2F", FloorPlansViewModel.defaultName("Building C 2F.pdf"))
        assertEquals("plan.v2", FloorPlansViewModel.defaultName("plan.v2.png"))
        assertEquals("", FloorPlansViewModel.defaultName(null))
        assertEquals("", FloorPlansViewModel.defaultName(".png"))
    }
}
