package id.my.pamslab.netlogger.feature.capture.vpn

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * What the tun routes, decided from the network the phone uses before the VPN starts (pure, unit-tested).
 *
 * - IPv4 always (0.0.0.0/1 + 128.0.0.0/1).
 * - IPv6 (2000::/3) only when that network has a global IPv6 address. Otherwise apps use IPv4, as without the VPN.
 * - DNS: that network's own servers, of the routed families, so DNS goes through the tun (and into the pcap) to the
 *   operator's resolvers as before. [FALLBACK_DNS] only if it has none.
 */
data class TunPlan(val ipv6: Boolean, val dnsServers: List<InetAddress>, val dnsFallback: Boolean) {

    companion object {
        const val IPV4_ADDRESS = "10.111.222.1"
        const val IPV4_PREFIX = 30
        /** Unique local address (fd00::/8); "6e6c:6f67" is "nlog". */
        const val IPV6_ADDRESS = "fd00:6e6c:6f67::1"
        const val IPV6_PREFIX = 128
        val IPV4_ROUTES = listOf("0.0.0.0" to 1, "128.0.0.0" to 1)
        val IPV6_ROUTES = listOf("2000::" to 3)
        const val FALLBACK_DNS = "1.1.1.1"

        fun of(linkAddresses: List<InetAddress>, dnsServers: List<InetAddress>): TunPlan {
            val ipv6 = linkAddresses.any { it is Inet6Address && it.isGlobalUnicast() }
            val dns = dnsServers.filter { it is Inet4Address || (ipv6 && it is Inet6Address) }.distinct()
            return if (dns.isEmpty()) {
                TunPlan(ipv6, listOf(InetAddress.getByName(FALLBACK_DNS)), dnsFallback = true)
            } else {
                TunPlan(ipv6, dns, dnsFallback = false)
            }
        }

        /** 2000::/3, the global unicast range (not link-local, not unique local). */
        private fun Inet6Address.isGlobalUnicast() = (address[0].toInt() and 0xE0) == 0x20
    }
}
