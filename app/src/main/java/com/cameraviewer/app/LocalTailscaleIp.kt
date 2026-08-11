package com.cameraviewer.app

import java.net.NetworkInterface
import java.util.Collections

/**
 * Finds this device's own Tailscale-assigned IPv4 address by reading it
 * directly off the local network interfaces (the tailscale0 VPN tunnel),
 * rather than asking the Tailscale API — which has no "who am I" lookup
 * usable for this anyway. Works from a normal installed Android app: the
 * "/proc/net is permission-denied" constraint documented in IPCameraDash's
 * ARCHITECTURE.md is specific to Termux's unprivileged sandbox, not a
 * general Android app restriction — NetworkInterface enumeration via the
 * standard java.net API isn't gated the same way and needs no extra
 * permission beyond what's already declared (INTERNET/ACCESS_NETWORK_STATE).
 */
object LocalTailscaleIp {
    /** Returns this device's 100.x.x.x tailnet address, or null if not found/reachable. */
    fun find(): String? = try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .flatMap { Collections.list(it.inetAddresses) }
            .mapNotNull { it.hostAddress }
            .firstOrNull { it.startsWith("100.") }
    } catch (e: Exception) {
        null
    }
}
