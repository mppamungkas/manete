package id.my.pamslab.netlogger.feature.capture

import id.my.pamslab.netlogger.core.model.capture.CaptureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Extras exactly as PCAPdroid's docs/app_api.md documents them. */
class PcapdroidTest {

    @Test
    fun `UDP exporter to NetLogger on loopback, free features only`() {
        val extras = Pcapdroid.startExtras(CaptureMode.UDP, 40_123, null, listOf(" org.mozilla.firefox", "", "com.whatsapp "), "a.b.Receiver")
        assertEquals("start", extras["action"])
        assertEquals("udp_exporter", extras["pcap_dump_mode"])
        assertEquals("127.0.0.1", extras["collector_host"])
        assertEquals("127.0.0.1", extras["collector_ip_address"])
        assertEquals("40123", extras["collector_port"])
        assertEquals("org.mozilla.firefox,com.whatsapp", extras["app_filter"])
        assertEquals("a.b.Receiver", extras["broadcast_receiver"])
        assertEquals("false", extras["pcapng_format"]) // paid feature
        assertEquals("false", extras["dump_extensions"]) // keeps plain LINKTYPE_RAW records
        assertFalse(extras.containsKey("pcap_name"))
    }

    @Test
    fun `PCAPdroid file mode with a predictable name, all apps`() {
        val extras = Pcapdroid.startExtras(CaptureMode.FILE, null, "netlogger_u.pcap", emptyList(), "a.b.Receiver")
        assertEquals("pcap_file", extras["pcap_dump_mode"])
        assertEquals("netlogger_u.pcap", extras["pcap_name"])
        assertFalse(extras.containsKey("app_filter"))
        assertFalse(extras.containsKey("collector_port"))
    }

    @Test
    fun `stop and status`() {
        assertEquals(mapOf("action" to "stop"), Pcapdroid.stopExtras)
        assertEquals(mapOf("action" to "get_status"), Pcapdroid.statusExtras)
    }
}
