package id.my.pamslab.netlogger.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class PickerRowsTest {
    private val installed = listOf(
        InstalledApp("com.google.android.youtube", "YouTube", null),
        InstalledApp("com.android.chrome", "Chrome", null),
        InstalledApp("id.my.pamslab.netlogger", "NetLogger", null),
        InstalledApp("com.whatsapp", "whatsApp", null),
    )
    private val opened = listOf("com.google.android.youtube", "id.my.pamslab.netlogger")

    @Test
    fun `apps selected when the page opened come first, then the rest, each sorted by name ignoring case`() {
        val rows = pickerRows(installed, opened, opened, "")
        assertEquals(listOf("NetLogger", "YouTube", "Chrome", "whatsApp"), rows.map { it.label })
        assertEquals(listOf(true, true, false, false), rows.map { it.selected })
    }

    @Test
    fun `ticking or unticking an app does not move it`() {
        val rows = pickerRows(installed, listOf("id.my.pamslab.netlogger", "com.whatsapp"), opened, "")
        assertEquals(listOf("NetLogger", "YouTube", "Chrome", "whatsApp"), rows.map { it.label })
        assertEquals(listOf(true, false, false, true), rows.map { it.selected })
    }

    @Test
    fun `search matches name or package, and selected apps stay visible`() {
        val rows = pickerRows(installed, listOf("com.whatsapp"), emptyList(), " CHROME ")
        assertEquals(listOf("com.android.chrome", "com.whatsapp"), rows.map { it.packageName })
        assertEquals(listOf("YouTube"), pickerRows(installed, emptyList(), emptyList(), "google").map { it.label })
    }

    @Test
    fun `a selected package that is not installed is listed without a name`() {
        val rows = pickerRows(installed, listOf("org.example.gone"), listOf("org.example.gone"), "nothing matches")
        assertEquals(listOf(AppRow("org.example.gone", null, null, selected = true)), rows)
    }
}
