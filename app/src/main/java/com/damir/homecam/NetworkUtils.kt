package com.damir.homecam

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkUtils {

    /**
     * Best-effort local IPv4 address of this device on the Wi‑Fi / LAN, e.g.
     * "192.168.1.20". Prefers a site-local (private) address. Returns null when
     * the phone has no usable network interface (e.g. Wi‑Fi off).
     */
    fun localIpv4(): String? {
        var fallback: String? = null
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr: InetAddress = addrs.nextElement()
                    if (addr.isLoopbackAddress || addr !is Inet4Address) continue
                    val host = addr.hostAddress ?: continue
                    if (addr.isSiteLocalAddress) return host
                    if (fallback == null) fallback = host
                }
            }
        } catch (_: Exception) {
            return fallback
        }
        return fallback
    }

    /**
     * This phone's Tailscale address (100.64.0.0/10 on a VPN "tun" interface),
     * or null when Tailscale is not connected. Mobile carriers also hand out
     * 100.64.0.0/10 addresses, so only VPN interfaces are considered.
     */
    fun tailscaleIpv4(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || !intf.name.startsWith("tun")) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && isCgnat(addr)) return addr.hostAddress
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun isCgnat(addr: Inet4Address): Boolean {
        val first = addr.address[0].toInt() and 0xFF
        val second = addr.address[1].toInt() and 0xFF
        return first == 100 && second in 64..127
    }
}
