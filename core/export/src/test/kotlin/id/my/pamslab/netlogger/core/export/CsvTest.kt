package id.my.pamslab.netlogger.core.export

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class CsvTest {

    private val defaultLocale = Locale.getDefault()

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    private fun line(vararg values: Any?) = StringBuilder().also { CsvWriter(it).row(values.toList()) }.toString()

    @Test
    fun `fields with comma, quote, line breaks or edge spaces are quoted, quotes doubled`() {
        assertEquals("plain,\"a,b\",\"say \"\"hi\"\"\",\"two\nlines\",\"cr\rhere\",\" lead\",\"trail \"\r\n",
            line("plain", "a,b", "say \"hi\"", "two\nlines", "cr\rhere", " lead", "trail "))
    }

    @Test
    fun `null is an empty field, never 0 or a sentinel, and an empty string stays unquoted`() {
        assertEquals("1,,3,,\r\n", line(1, null, 3, "", null))
        assertEquals(",\r\n", line(null, null))
    }

    @Test
    fun `numbers use a dot and no grouping in any locale`() {
        listOf(Locale.GERMANY, Locale.FRANCE, Locale.forLanguageTag("id-ID"), Locale.forLanguageTag("ar-EG")).forEach { locale ->
            Locale.setDefault(locale)
            assertEquals("$locale", "-0.906108,100.407124,1.25,4.7,1234567,-350000\r\n", line(-0.906108, 100.407124, 1.25f, 4.7f, 1_234_567, -350_000L))
        }
    }

    @Test
    fun `no scientific notation, no trailing zeros, float printed as written, NaN and infinity empty`() {
        assertEquals("0.00001,12345678901,28,0,0.3,,,\r\n", line(1e-5, 1.2345678901e10, 28.0, -0.0, 0.3f, Double.NaN, Float.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `booleans and enums as words`() {
        assertEquals("true,false\r\n", line(true, false))
        assertEquals("NANOS\r\n", line(java.time.temporal.ChronoUnit.NANOS))
    }

    @Test
    fun `ISO-8601 with milliseconds and offset in the export zone`() {
        assertEquals("2026-10-14T05:40:00.000+07:00", IsoTime(ZoneId.of("Asia/Jakarta")).format(START_MS))
        assertEquals("2026-10-13T22:40:00.123Z", IsoTime(ZoneOffset.UTC).format(START_MS + 123))
        assertEquals("2026-10-14T03:10:00.000+04:30", IsoTime(ZoneId.of("Asia/Kabul")).format(START_MS))
        assertEquals(null, IsoTime(ZoneOffset.UTC).format(null))
        assertEquals("+07:00", IsoTime(ZoneId.of("Asia/Jakarta")).offset(START_MS))
        assertEquals("+00:00", IsoTime(ZoneOffset.UTC).offset(START_MS))
    }

    @Test
    fun `ISO time ignores the default locale`() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG")) // would use Arabic-Indic digits if the locale leaked in
        assertEquals("2026-10-14T05:40:00.000+07:00", IsoTime(ZoneId.of("Asia/Jakarta")).format(START_MS))
    }
}
