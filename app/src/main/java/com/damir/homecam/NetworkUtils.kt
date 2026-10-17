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
}
