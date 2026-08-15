package com.cameraviewer.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Viewer-side: watches this phone's active network transport (Wi-Fi vs.
 * cellular) via ConnectivityManager, calling [onNetworkTypeChanged] only
 * when it actually flips between the two — not on every capability tick
 * (signal strength changes etc. also fire onCapabilitiesChanged, and
 * would spam the sender with redundant quality requests otherwise).
 * Networks that are neither (ethernet, VPN-only, unknown) are ignored
 * rather than guessed at.
 */
class NetworkQualityMonitor(context: Context, private val onNetworkTypeChanged: (isCellular: Boolean) -> Unit) {

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private var lastIsCellular: Boolean? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            if (!isCellular && !isWifi) return
            if (isCellular != lastIsCellular) {
                lastIsCellular = isCellular
                onNetworkTypeChanged(isCellular)
            }
        }
    }

    fun start() {
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    fun stop() {
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        lastIsCellular = null
    }
}
