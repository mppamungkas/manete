package id.my.pamslab.netlogger.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class CleartextTest {

    private val allowed = setOf("10.0.0.5")

    @Test
    fun `plain HTTP hosts not allowed are listed once, HTTPS and allowed hosts are not`() {
        val urls = listOf(
            "http://203.0.113.7:8080/backend/empty.php",
            " http://203.0.113.7:8080/backend/garbage.php?ckSize=100 ",
            "https://speed.example.org/backend/empty.php",
            "http://10.0.0.5/backend/empty.php",
        )
        assertEquals(listOf("203.0.113.7"), cleartextBlockedHosts(urls) { it in allowed })
    }

    @Test
    fun `empty and broken URLs are ignored`() {
        assertEquals(emptyList<String>(), cleartextBlockedHosts(listOf("", "http://exa mple", "not a url")) { false })
    }
}
