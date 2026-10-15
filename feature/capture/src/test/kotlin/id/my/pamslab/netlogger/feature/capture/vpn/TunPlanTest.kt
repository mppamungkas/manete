package id.my.pamslab.netlogger.feature.capture.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class TunPlanTest {

    private fun ip(s: String): InetAddress = InetAddress.getByName(s)

    @Test
    fun `IPv4-only network - IPv6 off, its IPv6 DNS servers left out`() {
        val plan = TunPlan.of(
            linkAddresses = listOf(ip("10.45.12.7"), ip("fe80::1234")), // link-local IPv6 only
            dnsServers = listOf(ip("2001:4860:4860::8888"), ip("202.155.0.10")),
        )
        assertFalse(plan.ipv6)
        assertEquals(listOf(ip("202.155.0.10")), plan.dnsServers)
        assertFalse(plan.dnsFallback)
    }

    @Test
    fun `global IPv6 address - IPv6 routed with its DNS servers`() {
        val plan = TunPlan.of(
            linkAddresses = listOf(ip("10.45.12.7"), ip("2001:448a:1020::42")),
            dnsServers = listOf(ip("2001:448a:1020::53"), ip("202.155.0.10"), ip("202.155.0.10")),
        )
        assertTrue(plan.ipv6)
        assertEquals(listOf(ip("2001:448a:1020::53"), ip("202.155.0.10")), plan.dnsServers)
    }

    @Test
    fun `unique local IPv6 is not global`() {
        assertFalse(TunPlan.of(listOf(ip("fd12::1")), listOf(ip("8.8.4.4"))).ipv6)
    }

    @Test
    fun `no usable DNS server - fallback, marked`() {
        val plan = TunPlan.of(linkAddresses = listOf(ip("10.45.12.7")), dnsServers = listOf(ip("2001:db8::53")))
        assertEquals(listOf(ip(TunPlan.FALLBACK_DNS)), plan.dnsServers)
        assertTrue(plan.dnsFallback)
    }

    @Test
    fun `no network at all`() {
        val plan = TunPlan.of(emptyList(), emptyList())
        assertFalse(plan.ipv6)
        assertTrue(plan.dnsFallback)
    }
}
